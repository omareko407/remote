package com.remotesupport.app

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

class WebRtcManager private constructor() {

    companion object {
        private const val TAG = "WebRtcManager"
        val instance = WebRtcManager()
    }

    private var socket: Socket? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoTrack: VideoTrack? = null
    private var videoSource: VideoSource? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var eglBase: EglBase? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun initialize(
        context: Context,
        screenCaptureIntent: Intent,
        serverUrl: String,
        deviceId: String
    ) {
        mainHandler.post {
            try {
                Log.i(TAG, "Initializing WebRTC & Audio connecting to $serverUrl")

                // 1. Initialize PeerConnectionFactory
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )

                eglBase = EglBase.create()
                val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
                val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

                // Setup Audio Device Module for Voice from PC to Mobile Speaker
                audioDeviceModule = JavaAudioDeviceModule.builder(context)
                    .setUseHardwareAcousticEchoCanceler(true)
                    .setUseHardwareNoiseSuppressor(true)
                    .createAudioDeviceModule()

                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setAudioDeviceModule(audioDeviceModule)
                    .setVideoEncoderFactory(defaultVideoEncoderFactory)
                    .setVideoDecoderFactory(defaultVideoDecoderFactory)
                    .createPeerConnectionFactory()

                // 2. Setup Screen Capturer
                val surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase!!.eglBaseContext)
                videoCapturer = ScreenCapturerAndroid(screenCaptureIntent, object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.w(TAG, "MediaProjection Stopped by system.")
                    }
                })

                videoSource = peerConnectionFactory!!.createVideoSource(videoCapturer!!.isScreencast)
                videoCapturer!!.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)

                // Calculate screen dimensions (even numbers)
                val displayMetrics = context.resources.displayMetrics
                var targetWidth = (displayMetrics.widthPixels / 2)
                var targetHeight = (displayMetrics.heightPixels / 2)
                if (targetWidth % 2 != 0) targetWidth -= 1
                if (targetHeight % 2 != 0) targetHeight -= 1
                targetWidth = targetWidth.coerceIn(360, 1080)
                targetHeight = targetHeight.coerceIn(640, 2400)

                videoCapturer!!.startCapture(targetWidth, targetHeight, 30)

                videoTrack = peerConnectionFactory!!.createVideoTrack("ARDAMSv0", videoSource)

                // 3. Connect to Signaling Server via Socket.IO
                connectSignaling(serverUrl, deviceId)
            } catch (t: Throwable) {
                Log.e(TAG, "Error in WebRTC initialization", t)
            }
        }
    }

    private fun connectSignaling(serverUrl: String, deviceId: String) {
        try {
            val options = IO.Options().apply {
                reconnection = true
                timeout = 10000
            }
            socket = IO.socket(serverUrl, options)
            setupSocketListeners(deviceId)
            socket?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Socket connection failed", e)
        }
    }

    private fun setupSocketListeners(deviceId: String) {
        socket?.on(Socket.EVENT_CONNECT) {
            Log.i(TAG, "Connected to Signaling Server! Registering device...")
            try {
                val regData = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("deviceName", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    put("osVersion", "Android ${android.os.Build.VERSION.RELEASE}")
                }
                socket?.emit("register-device", regData)
            } catch (e: Exception) {
                Log.e(TAG, "Error registering device", e)
            }
        }

        // Controller requested connection via WebRTC offer
        socket?.on("signal-offer") { args ->
            mainHandler.post {
                try {
                    if (args.isNotEmpty()) {
                        val data = args[0] as JSONObject
                        val controllerId = data.getString("controllerId")
                        val offerObj = data.getJSONObject("offer")
                        val sdp = offerObj.getString("sdp")

                        handleIncomingOffer(controllerId, sdp)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error handling signal-offer", t)
                }
            }
        }

        // ICE Candidates exchange
        socket?.on("signal-ice") { args ->
            mainHandler.post {
                try {
                    if (args.isNotEmpty()) {
                        val data = args[0] as JSONObject
                        val candidateObj = data.getJSONObject("candidate")
                        val sdpMid = candidateObj.getString("sdpMid")
                        val sdpMLineIndex = candidateObj.getInt("sdpMLineIndex")
                        val sdpCandidate = candidateObj.getString("candidate")

                        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, sdpCandidate)
                        peerConnection?.addIceCandidate(iceCandidate)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error adding ICE candidate", t)
                }
            }
        }

        // Receive Remote Control Actions (Tap, Swipe, Keys, Text)
        socket?.on("remote-control-action") { args ->
            mainHandler.post {
                try {
                    if (args.isNotEmpty()) {
                        val action = args[0] as JSONObject
                        handleControlAction(action)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error parsing control action", t)
                }
            }
        }

        socket?.on("session-ended") {
            mainHandler.post {
                Log.i(TAG, "Session ended by controller.")
                closePeerConnection()
            }
        }
    }

    private fun handleIncomingOffer(controllerId: String, sdp: String) {
        try {
            closePeerConnection()

            // Free Google STUN Servers
            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }

            peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (candidate != null) {
                        try {
                            val iceData = JSONObject().apply {
                                put("targetId", controllerId)
                                put("candidate", JSONObject().apply {
                                    put("sdpMid", candidate.sdpMid)
                                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                                    put("candidate", candidate.sdp)
                                })
                            }
                            socket?.emit("signal-ice", iceData)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error emitting ICE candidate", e)
                        }
                    }
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "IceConnectionState: $state")
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(dataChannel: DataChannel?) {
                    dataChannel?.registerObserver(object : DataChannel.Observer {
                        override fun onBufferedAmountChange(amount: Long) {}
                        override fun onStateChange() {}
                        override fun onMessage(buffer: DataChannel.Buffer?) {
                            buffer?.let {
                                val bytes = ByteArray(it.data.remaining())
                                it.data.get(bytes)
                                val message = String(bytes)
                                try {
                                    handleControlAction(JSONObject(message))
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parsing DataChannel action", e)
                                }
                            }
                        }
                    })
                }
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            })

            // Add video track using Unified Plan
            if (videoTrack != null && peerConnection != null) {
                peerConnection?.addTrack(videoTrack, listOf("ARDAMS"))
            }

            // Set Remote Offer Description
            val offerDesc = SessionDescription(SessionDescription.Type.OFFER, sdp)
            peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    mainHandler.post {
                        try {
                            // Enable incoming audio from PC so the controller's voice plays on the phone
                            val constraints = MediaConstraints().apply {
                                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                            }
                            peerConnection?.createAnswer(object : SdpObserverAdapter() {
                                override fun onCreateSuccess(answerDesc: SessionDescription?) {
                                    if (answerDesc != null) {
                                        mainHandler.post {
                                            peerConnection?.setLocalDescription(object : SdpObserverAdapter() {
                                                override fun onSetSuccess() {
                                                    try {
                                                        val answerData = JSONObject().apply {
                                                            put("controllerId", controllerId)
                                                            put("answer", JSONObject().apply {
                                                                put("type", "answer")
                                                                put("sdp", answerDesc.description)
                                                            })
                                                        }
                                                        socket?.emit("signal-answer", answerData)
                                                        Log.i(TAG, "Answer sent successfully to controller!")
                                                    } catch (e: Exception) {
                                                        Log.e(TAG, "Error sending answer", e)
                                                    }
                                                }
                                            }, answerDesc)
                                        }
                                    }
                                }
                            }, constraints)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Error in createAnswer", t)
                        }
                    }
                }
            }, offerDesc)
        } catch (t: Throwable) {
            Log.e(TAG, "Error in handleIncomingOffer", t)
        }
    }

    /**
     * Executes Touch, Navigation, and Text gestures on device UI
     */
    private fun handleControlAction(action: JSONObject) {
        val service = RemoteAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "Cannot execute action: Accessibility Service is not enabled by user!")
            return
        }

        mainHandler.post {
            try {
                when (action.optString("type")) {
                    "tap" -> {
                        val x = action.optDouble("x", 0.5).toFloat()
                        val y = action.optDouble("y", 0.5).toFloat()
                        service.performTap(x, y)
                    }
                    "swipe" -> {
                        val startX = action.optDouble("startX", 0.5).toFloat()
                        val startY = action.optDouble("startY", 0.5).toFloat()
                        val endX = action.optDouble("endX", 0.5).toFloat()
                        val endY = action.optDouble("endY", 0.5).toFloat()
                        val duration = action.optLong("duration", 300)
                        service.performSwipe(startX, startY, endX, endY, duration)
                    }
                    "key" -> {
                        val key = action.optString("key", "")
                        service.executeNavigationKey(key)
                    }
                    "text" -> {
                        val text = action.optString("text", "")
                        if (text.isNotEmpty()) {
                            service.insertOrCopyText(text)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error handling control action", t)
            }
        }
    }

    private fun closePeerConnection() {
        try {
            peerConnection?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing peer connection", e)
        }
        peerConnection = null
    }

    fun disconnect() {
        closePeerConnection()
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing capturer", e)
        }
        try {
            videoSource?.dispose()
            peerConnectionFactory?.dispose()
            audioDeviceModule?.release()
            eglBase?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WebRTC resources", e)
        }
        socket?.disconnect()
        socket = null
    }

    open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(err: String?) { Log.e(TAG, "SDP Create Failure: $err") }
        override fun onSetFailure(err: String?) { Log.e(TAG, "SDP Set Failure: $err") }
    }
}

package com.remotesupport.app

import android.content.Context
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import org.webrtc.*

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
    private var localMediaStream: MediaStream? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun initialize(
        context: Context,
        mediaProjection: MediaProjection,
        serverUrl: String,
        deviceId: String
    ) {
        Log.i(TAG, "Initializing WebRTC & Socket.IO connecting to $serverUrl")

        // 1. Initialize PeerConnectionFactory
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val eglBase = EglBase.create()
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .createPeerConnectionFactory()

        // 2. Setup Screen Capturer
        val metrics = context.resources.displayMetrics
        val surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.eglBaseContext)
        videoCapturer = ScreenCapturerAndroid(mediaProjection, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection Stopped by system.")
            }
        })

        val videoSource = peerConnectionFactory!!.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer!!.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
        // 720p at 30fps for smooth, low-bandwidth streaming
        videoCapturer!!.startCapture(720, 1280, 30)

        videoTrack = peerConnectionFactory!!.createVideoTrack("ARDAMSv0", videoSource)
        localMediaStream = peerConnectionFactory!!.createLocalMediaStream("ARDAMS")
        localMediaStream!!.addTrack(videoTrack)

        // 3. Connect to Signaling Server via Socket.IO
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
            val regData = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceName", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                put("osVersion", "Android ${android.os.Build.VERSION.RELEASE}")
            }
            socket?.emit("register-device", regData)
        }

        // Controller requested connection
        socket?.on("signal-offer") { args ->
            if (args.isNotEmpty()) {
                val data = args[0] as JSONObject
                val controllerId = data.getString("controllerId")
                val offerObj = data.getJSONObject("offer")
                val sdp = offerObj.getString("sdp")

                handleIncomingOffer(controllerId, sdp)
            }
        }

        // ICE Candidates exchange
        socket?.on("signal-ice") { args ->
            if (args.isNotEmpty()) {
                val data = args[0] as JSONObject
                val candidateObj = data.getJSONObject("candidate")
                val sdpMid = candidateObj.getString("sdpMid")
                val sdpMLineIndex = candidateObj.getInt("sdpMLineIndex")
                val sdpCandidate = candidateObj.getString("candidate")

                val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, sdpCandidate)
                peerConnection?.addIceCandidate(iceCandidate)
            }
        }

        // Receive Remote Control Actions (Tap, Swipe, Keys)
        socket?.on("remote-control-action") { args ->
            if (args.isNotEmpty()) {
                val action = args[0] as JSONObject
                handleControlAction(action)
            }
        }

        socket?.on("session-ended") {
            Log.i(TAG, "Session ended by controller.")
            closePeerConnection()
        }
    }

    private fun handleIncomingOffer(controllerId: String, sdp: String) {
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
                    val iceData = JSONObject().apply {
                        put("targetId", controllerId)
                        put("candidate", JSONObject().apply {
                            put("sdpMid", candidate.sdpMid)
                            put("sdpMLineIndex", candidate.sdpMLineIndex)
                            put("candidate", candidate.sdp)
                        })
                    }
                    socket?.emit("signal-ice", iceData)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
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

        // Add Local Media Stream with Screen Video Track
        if (localMediaStream != null) {
            peerConnection?.addStream(localMediaStream)
        }

        // Set Remote Description (Offer)
        val offerDesc = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                // Create Answer
                peerConnection?.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(answerDesc: SessionDescription?) {
                        peerConnection?.setLocalDescription(object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                // Send Answer to Controller
                                val answerData = JSONObject().apply {
                                    put("controllerId", controllerId)
                                    put("answer", JSONObject().apply {
                                        put("type", "answer")
                                        put("sdp", answerDesc?.description)
                                    })
                                }
                                socket?.emit("signal-answer", answerData)
                            }
                        }, answerDesc)
                    }
                }, MediaConstraints())
            }
        }, offerDesc)
    }

    /**
     * Executes Touch and Navigation gestures on device UI
     */
    private fun handleControlAction(action: JSONObject) {
        val service = RemoteAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "Cannot execute action: Accessibility Service is not enabled by user!")
            return
        }

        mainHandler.post {
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
            }
        }
    }

    private fun closePeerConnection() {
        peerConnection?.close()
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

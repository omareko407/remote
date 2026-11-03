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
    private var audioDeviceModule: org.webrtc.audio.AudioDeviceModule? = null
    private var eglBase: EglBase? = null
    private var appContext: Context? = null
    private var directAudioTrack: android.media.AudioTrack? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @Synchronized
    private fun initDirectAudioTrack() {
        try {
            val sampleRate = 16000
            val minBuf = android.media.AudioTrack.getMinBufferSize(
                sampleRate,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            directAudioTrack = android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                .build()
            directAudioTrack?.play()
            Log.i(TAG, "Loudspeaker 16kHz PCM AudioTrack initialized and playing!")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing direct Loudspeaker AudioTrack", e)
        }
    }

    fun initialize(
        context: Context,
        screenCaptureIntent: Intent,
        serverUrl: String,
        deviceId: String
    ) {
        appContext = context.applicationContext
        mainHandler.post {
            try {
                Log.i(TAG, "Initializing WebRTC & Audio connecting to $serverUrl")

                // 1. Connect to Signaling Server immediately so device is visible in Dashboard
                connectSignaling(serverUrl, deviceId)

                // Configure Speakerphone Routing for Voice
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                audioManager?.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                audioManager?.isSpeakerphoneOn = true

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

                // Calculate screen dimensions (even numbers, supporting both portrait and landscape)
                val displayMetrics = context.resources.displayMetrics
                var targetWidth = (displayMetrics.widthPixels / 2)
                var targetHeight = (displayMetrics.heightPixels / 2)
                if (targetWidth % 2 != 0) targetWidth -= 1
                if (targetHeight % 2 != 0) targetHeight -= 1
                targetWidth = targetWidth.coerceIn(360, 1920)
                targetHeight = targetHeight.coerceIn(360, 1920)

                videoCapturer!!.startCapture(targetWidth, targetHeight, 30)

                // Dynamically adapt screen capture resolution on orientation change (portrait <-> landscape)
                try {
                    val orientationListener = object : android.view.OrientationEventListener(context) {
                        private var lastIsLandscape = (displayMetrics.widthPixels > displayMetrics.heightPixels)
                        override fun onOrientationChanged(orientation: Int) {
                            if (orientation == ORIENTATION_UNKNOWN) return
                            val dm = context.resources.displayMetrics
                            val isLandscape = dm.widthPixels > dm.heightPixels
                            if (isLandscape != lastIsLandscape) {
                                lastIsLandscape = isLandscape
                                var w = dm.widthPixels / 2
                                var h = dm.heightPixels / 2
                                if (w % 2 != 0) w -= 1
                                if (h % 2 != 0) h -= 1
                                w = w.coerceIn(360, 1920)
                                h = h.coerceIn(360, 1920)
                                Log.i(TAG, "Adapting video capture format to ${w}x${h} (Landscape: $isLandscape)")
                                videoCapturer?.changeCaptureFormat(w, h, 30)
                            }
                        }
                    }
                    if (orientationListener.canDetectOrientation()) {
                        orientationListener.enable()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not enable OrientationEventListener", e)
                }

                videoTrack = peerConnectionFactory!!.createVideoTrack("ARDAMSv0", videoSource)
            } catch (t: Throwable) {
                Log.e(TAG, "Error in WebRTC initialization", t)
            }
        }
    }

    private fun formatServerUrl(input: String?): String {
        var url = (input ?: "").trim()
        if (url.isEmpty()) return "http://192.168.0.107:3000"
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        val parts = url.substringAfter("://").split("/")
        val hostPort = parts[0]
        if (!hostPort.contains(":")) {
            val remainingPath = if (parts.size > 1) "/" + parts.drop(1).joinToString("/") else ""
            val scheme = if (url.startsWith("https://")) "https" else "http"
            url = "$scheme://$hostPort:3000$remainingPath"
        }
        return url
    }

    private fun connectSignaling(serverUrl: String, deviceId: String) {
        val cleanUrl = formatServerUrl(serverUrl)
        try {
            Log.i(TAG, "Connecting to Socket.IO signaling at $cleanUrl...")
            val options = IO.Options().apply {
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = 2000
                timeout = 10000
            }
            socket = IO.socket(cleanUrl, options)
            setupSocketListeners(deviceId)
            socket?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Socket connection failed to $cleanUrl", e)
        }
    }

    private fun setupSocketListeners(deviceId: String) {
        socket?.on(Socket.EVENT_CONNECT) {
            Log.i(TAG, "Connected to Signaling Server! Registering device: $deviceId")
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

        socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
            Log.e(TAG, "Socket.IO Connect Error: ${args.joinToString()}")
        }

        socket?.on(Socket.EVENT_DISCONNECT) { args ->
            Log.w(TAG, "Socket.IO Disconnected: ${args.joinToString()}")
        }

        // Controller requested connection via WebRTC offer
        socket?.on("signal-offer") { args ->
            mainHandler.post {
                try {
                    if (args.isNotEmpty()) {
                        val data = args[0] as JSONObject
                        val controllerId = data.getString("controllerId")
                        val sdp = when {
                            data.has("offer") -> {
                                val obj = data.get("offer")
                                if (obj is JSONObject) obj.optString("sdp", "") else obj.toString()
                            }
                            data.has("sdp") -> {
                                val obj = data.get("sdp")
                                if (obj is JSONObject) obj.optString("sdp", "") else obj.toString()
                            }
                            else -> ""
                        }

                        if (sdp.isNotEmpty()) {
                            handleIncomingOffer(controllerId, sdp)
                        } else {
                            Log.w(TAG, "Received offer with empty SDP: $data")
                        }
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

        // Fullscreen Client Notice Display
        socket?.on("client-notice-show") { args ->
            mainHandler.post {
                try {
                    if (args.isNotEmpty()) {
                        val data = args[0] as JSONObject
                        val title = data.optString("title", "تنبيه الدعم الفني")
                        val message = data.optString("message", "")
                        val durationSec = data.optInt("durationSec", 0)
                        appContext?.let { ctx ->
                            FullScreenNoticeActivity.showNotice(ctx, title, message, durationSec)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error showing client notice", t)
                }
            }
        }

        // Fullscreen Client Notice Dismiss (early cancellation by operator)
        socket?.on("client-notice-dismiss") {
            mainHandler.post {
                Log.i(TAG, "Dismiss notice requested by controller")
                FullScreenNoticeActivity.dismissNotice()
            }
        }

        socket?.on("session-ended") {
            mainHandler.post {
                Log.i(TAG, "Session ended by controller. Shutting down capture to preserve battery.")
                closePeerConnection()
                try {
                    val stopIntent = Intent(appContext, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_STOP
                    }
                    appContext?.startService(stopIntent)
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping ScreenCaptureService on session-ended", e)
                }
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
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    val track = receiver?.track()
                    Log.i(TAG, "WebRTC onAddTrack received: ${track?.kind()}, id=${track?.id()}")
                    if (track is AudioTrack) {
                        track.setEnabled(true)
                        track.setVolume(1.0)
                        Log.i(TAG, "AudioTrack enabled and volume set to 1.0 on speakerphone!")
                    }
                }
            })

            // Ensure Audio Transceiver is present to receive audio from controller
            try {
                peerConnection?.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not add audio transceiver", e)
            }

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
     * Executes Touch, Navigation, Audio, and Text gestures on device UI
     */
    private fun handleControlAction(action: JSONObject) {
        val type = action.optString("type")

        // 1. Direct Loudspeaker Audio Intercom (Plays raw 16kHz PCM chunks directly)
        if (type == "audio_chunk") {
            val base64Data = action.optString("data")
            if (base64Data.isNotEmpty()) {
                try {
                    if (directAudioTrack == null || directAudioTrack?.state != android.media.AudioTrack.STATE_INITIALIZED) {
                        initDirectAudioTrack()
                    }
                    val pcmBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                    directAudioTrack?.write(pcmBytes, 0, pcmBytes.size)
                } catch (e: Exception) {
                    Log.e(TAG, "Error playing direct audio chunk on speaker", e)
                }
            }
            return
        }

        // 2. Accessibility Actions (Tap, Swipe, Navigation Keys, Text)
        val service = RemoteAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "Cannot execute action: Accessibility Service is not enabled by user!")
            return
        }

        mainHandler.post {
            try {
                when (type) {
                    "tap", "click", "POINTER_DOWN" -> {
                        val x = action.optDouble("x", 0.5).toFloat()
                        val y = action.optDouble("y", 0.5).toFloat()
                        service.performTap(x, y)
                    }
                    "swipe", "drag" -> {
                        val startX = action.optDouble("startX", 0.5).toFloat()
                        val startY = action.optDouble("startY", 0.5).toFloat()
                        val endX = action.optDouble("endX", 0.5).toFloat()
                        val endY = action.optDouble("endY", 0.5).toFloat()
                        val duration = action.optLong("duration", 300)
                        service.performSwipe(startX, startY, endX, endY, duration)
                    }
                    "key", "HARDWARE_KEY" -> {
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
            directAudioTrack?.stop()
            directAudioTrack?.release()
            directAudioTrack = null
        } catch (e: Exception) {}
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

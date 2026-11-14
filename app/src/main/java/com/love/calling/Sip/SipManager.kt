package com.love.calling.sip

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.telephony.SmsManager
import android.view.TextureView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.linphone.core.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

data class CallHistoryItem(
    val number: String,
    val type: String,
    val time: String,
    val duration: String = "00:00"
)

data class SipAccountItem(
    val id: String,
    val label: String,
    val server: String,
    val username: String,
    val password: String,
    val transport: String
)

data class ChatMessageItem(
    val id: String,
    val peer: String,
    val body: String,
    val isOutgoing: Boolean,
    val channel: String,
    val time: String
)

data class CodecToggleItem(
    val mime: String,
    val clockRate: Int,
    val enabled: Boolean
)

object SipManager {
    private var core: Core? = null
    private var appContext: Context? = null
    private var networkCallbackRegistered = false
    private var portRetryCount = 0

    private var callWakeLock: PowerManager.WakeLock? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var wifiHighPerfLock: WifiManager.WifiLock? = null

    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var timerJob: Job? = null
    private var callStartTimeMs: Long = 0L

    private val _callDurationSec = MutableStateFlow(0L)
    val callDurationSec: StateFlow<Long> = _callDurationSec.asStateFlow()

    private val _regState = MutableStateFlow("Disconnected")
    val regState: StateFlow<String> = _regState.asStateFlow()

    private val _callState = MutableStateFlow("Idle")
    val callState: StateFlow<String> = _callState.asStateFlow()

    private val _isVideoCall = MutableStateFlow(false)
    val isVideoCall: StateFlow<Boolean> = _isVideoCall.asStateFlow()

    private val _remoteNumber = MutableStateFlow("")
    val remoteNumber: StateFlow<String> = _remoteNumber.asStateFlow()

    private val _activeCallsCount = MutableStateFlow(0)
    val activeCallsCount: StateFlow<Int> = _activeCallsCount.asStateFlow()

    private val _isInConference = MutableStateFlow(false)
    val isInConference: StateFlow<Boolean> = _isInConference.asStateFlow()

    private val _isAddingCall = MutableStateFlow(false)
    val isAddingCall: StateFlow<Boolean> = _isAddingCall.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeaker = MutableStateFlow(false)
    val isSpeaker: StateFlow<Boolean> = _isSpeaker.asStateFlow()

    private val _isBluetoothAvailable = MutableStateFlow(false)
    val isBluetoothAvailable: StateFlow<Boolean> = _isBluetoothAvailable.asStateFlow()

    private val _isBluetoothActive = MutableStateFlow(false)
    val isBluetoothActive: StateFlow<Boolean> = _isBluetoothActive.asStateFlow()

    private val _isOnHold = MutableStateFlow(false)
    val isOnHold: StateFlow<Boolean> = _isOnHold.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recentCalls = MutableStateFlow<List<CallHistoryItem>>(emptyList())
    val recentCalls: StateFlow<List<CallHistoryItem>> = _recentCalls.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessageItem>>(emptyList())
    val messages: StateFlow<List<ChatMessageItem>> = _messages.asStateFlow()

    private val _audioCodecs = MutableStateFlow<List<CodecToggleItem>>(emptyList())
    val audioCodecs: StateFlow<List<CodecToggleItem>> = _audioCodecs.asStateFlow()

    private val _accounts = MutableStateFlow<List<SipAccountItem>>(emptyList())
    val accounts: StateFlow<List<SipAccountItem>> = _accounts.asStateFlow()

    private val _activeAccountId = MutableStateFlow("")
    val activeAccountId: StateFlow<String> = _activeAccountId.asStateFlow()

    private val _useWifiOnly = MutableStateFlow(false)
    val useWifiOnly: StateFlow<Boolean> = _useWifiOnly.asStateFlow()

    private val _allowIpv6 = MutableStateFlow(true)
    val allowIpv6: StateFlow<Boolean> = _allowIpv6.asStateFlow()

    private val _useRandomPort = MutableStateFlow(true)
    val useRandomPort: StateFlow<Boolean> = _useRandomPort.asStateFlow()

    private val _enableStunIce = MutableStateFlow(false)
    val enableStunIce: StateFlow<Boolean> = _enableStunIce.asStateFlow()

    private val _enableRportSymmetric = MutableStateFlow(true)
    val enableRportSymmetric: StateFlow<Boolean> = _enableRportSymmetric.asStateFlow()

    private val _dndEnabled = MutableStateFlow(false)
    val dndEnabled: StateFlow<Boolean> = _dndEnabled.asStateFlow()

    private val _autoAnswerEnabled = MutableStateFlow(false)
    val autoAnswerEnabled: StateFlow<Boolean> = _autoAnswerEnabled.asStateFlow()

    private val _stunServer = MutableStateFlow("")
    val stunServer: StateFlow<String> = _stunServer.asStateFlow()

    private val _keepAliveSec = MutableStateFlow(10)
    val keepAliveSec: StateFlow<Int> = _keepAliveSec.asStateFlow()

    private var currentSessionNumber: String = ""
    private var currentSessionDir: String = "Outgoing"
    private var wasAnswered = false

    private fun acquireLongCallLocks() {
        try {
            val ctx = appContext ?: return
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (callWakeLock == null) {
                callWakeLock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "LoveSIP::LongCallCpuWakeLock"
                )?.apply { setReferenceCounted(false) }
            }
            if (callWakeLock?.isHeld == false) {
                callWakeLock?.acquire(3 * 60 * 60 * 1000L)
            }

            if (wifiHighPerfLock == null) {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiHighPerfLock = wm?.createWifiLock(lockMode, "LoveSIP::LowLatencyCallLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiHighPerfLock?.isHeld == false) {
                wifiHighPerfLock?.acquire()
            }

            updateProximitySensorLock()
        } catch (_: Exception) {}
    }

    private fun updateProximitySensorLock() {
        try {
            val ctx = appContext ?: return
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return

            if (proximityWakeLock == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximityWakeLock = pm.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "LoveSIP::ProximityScreenOffLock"
                ).apply { setReferenceCounted(false) }
            }

            val inActiveVoiceCall = (core?.callsNb ?: 0) > 0 && !_isVideoCall.value && !_isSpeaker.value && !_isBluetoothActive.value
            if (inActiveVoiceCall) {
                if (proximityWakeLock?.isHeld == false) {
                    proximityWakeLock?.acquire(3 * 60 * 60 * 1000L)
                }
            } else {
                if (proximityWakeLock?.isHeld == true) {
                    proximityWakeLock?.release()
                }
            }
        } catch (_: Exception) {}
    }

    private fun releaseLongCallLocks() {
        try {
            if (proximityWakeLock?.isHeld == true) proximityWakeLock?.release()
            if (callWakeLock?.isHeld == true) callWakeLock?.release()
            if (wifiHighPerfLock?.isHeld == true) wifiHighPerfLock?.release()
            stopSystemBluetoothSco()
        } catch (_: Exception) {}
    }

    private fun findBluetoothOutputDevice(c: Core): AudioDevice? {
        return try {
            c.audioDevices.firstOrNull {
                (it.type == AudioDevice.Type.Bluetooth || it.type == AudioDevice.Type.HearingAid) &&
                        (it.capabilities == AudioDevice.Capabilities.CapabilityPlay || it.capabilities == AudioDevice.Capabilities.CapabilityAll)
            } ?: c.audioDevices.firstOrNull {
                it.type == AudioDevice.Type.Bluetooth || it.type == AudioDevice.Type.HearingAid
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findBluetoothInputDevice(c: Core): AudioDevice? {
        return try {
            c.audioDevices.firstOrNull {
                (it.type == AudioDevice.Type.Bluetooth || it.type == AudioDevice.Type.HearingAid) &&
                        (it.capabilities == AudioDevice.Capabilities.CapabilityRecord || it.capabilities == AudioDevice.Capabilities.CapabilityAll)
            } ?: c.audioDevices.firstOrNull {
                it.type == AudioDevice.Type.Bluetooth
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findWiredHeadsetDevice(c: Core): AudioDevice? {
        return try {
            c.audioDevices.firstOrNull {
                it.type == AudioDevice.Type.Headset || it.type == AudioDevice.Type.Headphones
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun startSystemBluetoothSco() {
        try {
            val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val btScoDev = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (btScoDev != null) {
                    am.setCommunicationDevice(btScoDev)
                }
            } else {
                @Suppress("DEPRECATION")
                if (!am.isBluetoothScoOn) {
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                }
            }
        } catch (_: Exception) {}
    }

    private fun stopSystemBluetoothSco() {
        try {
            val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                if (am.isBluetoothScoOn) {
                    am.isBluetoothScoOn = false
                    am.stopBluetoothSco()
                }
            }
        } catch (_: Exception) {}
    }

    fun autoRouteBestAudioDevice() {
        try {
            val c = core ?: return
            c.reloadSoundDevices()

            val btOut = findBluetoothOutputDevice(c)
            val btIn = findBluetoothInputDevice(c)
            val wiredDev = findWiredHeadsetDevice(c)

            _isBluetoothAvailable.value = (btOut != null)

            when {
                btOut != null -> {
                    startSystemBluetoothSco()
                    c.outputAudioDevice = btOut
                    c.defaultOutputAudioDevice = btOut
                    if (btIn != null) {
                        c.inputAudioDevice = btIn
                        c.defaultInputAudioDevice = btIn
                    }
                    _isBluetoothActive.value = true
                    _isSpeaker.value = false
                }
                wiredDev != null -> {
                    stopSystemBluetoothSco()
                    c.outputAudioDevice = wiredDev
                    c.defaultOutputAudioDevice = wiredDev
                    val wiredMic = c.audioDevices.firstOrNull {
                        it.type == AudioDevice.Type.Headset &&
                                (it.capabilities == AudioDevice.Capabilities.CapabilityRecord || it.capabilities == AudioDevice.Capabilities.CapabilityAll)
                    }
                    if (wiredMic != null) {
                        c.inputAudioDevice = wiredMic
                    }
                    _isBluetoothActive.value = false
                    _isSpeaker.value = false
                }
                _isSpeaker.value || _isVideoCall.value -> {
                    stopSystemBluetoothSco()
                    val spk = c.audioDevices.firstOrNull { it.type == AudioDevice.Type.Speaker }
                    if (spk != null) {
                        c.outputAudioDevice = spk
                    }
                    _isBluetoothActive.value = false
                    _isSpeaker.value = true
                }
                else -> {
                    stopSystemBluetoothSco()
                    val ear = c.audioDevices.firstOrNull { it.type == AudioDevice.Type.Earpiece }
                    if (ear != null) {
                        c.outputAudioDevice = ear
                    }
                    _isBluetoothActive.value = false
                    _isSpeaker.value = false
                }
            }
            updateProximitySensorLock()
        } catch (_: Exception) {}
    }

    fun toggleBluetoothAudio() {
        try {
            val c = core ?: return
            c.reloadSoundDevices()
            val btOut = findBluetoothOutputDevice(c)
            val btIn = findBluetoothInputDevice(c)

            if (btOut == null) {
                _isBluetoothAvailable.value = false
                _isBluetoothActive.value = false
                return
            }

            if (!_isBluetoothActive.value) {
                startSystemBluetoothSco()
                c.outputAudioDevice = btOut
                if (btIn != null) c.inputAudioDevice = btIn
                _isBluetoothActive.value = true
                _isSpeaker.value = false
            } else {
                stopSystemBluetoothSco()
                val ear = c.audioDevices.firstOrNull { it.type == AudioDevice.Type.Earpiece }
                if (ear != null) c.outputAudioDevice = ear
                val phoneMic = c.audioDevices.firstOrNull { it.type == AudioDevice.Type.Microphone }
                if (phoneMic != null) c.inputAudioDevice = phoneMic
                _isBluetoothActive.value = false
            }
            updateProximitySensorLock()
        } catch (_: Exception) {}
    }

    fun formatDuration(totalSec: Long): String {
        val hrs = totalSec / 3600
        val mins = (totalSec % 3600) / 60
        val secs = totalSec % 60
        return if (hrs > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    private fun startLiveTimerIfNotRunning() {
        if (callStartTimeMs == 0L) {
            callStartTimeMs = System.currentTimeMillis()
            _callDurationSec.value = 1L
        }
        if (timerJob?.isActive == true) return
        timerJob = managerScope.launch {
            while (isActive) {
                val elapsed = ((System.currentTimeMillis() - callStartTimeMs) / 1000L).coerceAtLeast(1L)
                _callDurationSec.value = elapsed
                if (elapsed % 25L == 0L) {
                    core?.refreshRegisters()
                }
                delay(1000L)
            }
        }
    }

    private fun stopLiveTimerAndGetFormatted(): String {
        val finalSec = if (callStartTimeMs > 0L) {
            ((System.currentTimeMillis() - callStartTimeMs) / 1000L).coerceAtLeast(1L)
        } else {
            0L
        }
        timerJob?.cancel()
        timerJob = null
        callStartTimeMs = 0L
        _callDurationSec.value = 0L
        return formatDuration(finalSec)
    }

    private val coreListener = object : CoreListenerStub() {
        override fun onAudioDevicesListUpdated(core: Core) {
            try {
                val btOut = findBluetoothOutputDevice(core)
                _isBluetoothAvailable.value = (btOut != null)
                if (core.callsNb > 0) {
                    autoRouteBestAudioDevice()
                } else if (btOut == null) {
                    _isBluetoothActive.value = false
                }
            } catch (_: Exception) {}
        }

        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState,
            message: String
        ) {
            when (state) {
                RegistrationState.Ok -> {
                    portRetryCount = 0
                    _regState.value = "Online"
                }
                RegistrationState.Progress -> {
                    _regState.value = "Connecting..."
                }
                RegistrationState.Failed -> {
                    _regState.value = "Failed: $message"
                    if (_useRandomPort.value && portRetryCount < 4) {
                        portRetryCount++
                        managerScope.launch {
                            delay(1000L)
                            rotateDynamicUdpPortAndRefresh()
                        }
                    }
                }
                RegistrationState.Cleared -> _regState.value = "Disconnected"
                else -> _regState.value = "Offline"
            }
        }

        override fun onMessageReceived(core: Core, room: ChatRoom, message: ChatMessage) {
            val sender = message.fromAddress.username ?: message.fromAddress.asStringUriOnly()
            val text = message.utf8Text ?: return
            if (text.isNotEmpty()) {
                saveMessage(sender, text, isOutgoing = false, channel = "SIP")
            }
        }

        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State,
            message: String
        ) {
            _activeCallsCount.value = core.callsNb
            val inConf = core.conference != null && (core.conference?.participantCount ?: 0) >= 2
            _isInConference.value = inConf

            val hasVideo = call.currentParams?.isVideoEnabled == true || call.remoteParams?.isVideoEnabled == true
            _isVideoCall.value = hasVideo

            val number = call.remoteAddress.username ?: call.remoteAddress.asStringUriOnly()
            if (currentSessionNumber.isEmpty()) {
                currentSessionNumber = number
            }
            _remoteNumber.value = if (inConf || core.callsNb > 1) {
                "Conference (${core.callsNb} Calls)"
            } else {
                number
            }

            when (state) {
                Call.State.OutgoingInit, Call.State.OutgoingProgress -> {
                    acquireLongCallLocks()
                    autoRouteBestAudioDevice()
                    if (core.callsNb <= 1) {
                        wasAnswered = false
                        timerJob?.cancel()
                        timerJob = null
                        callStartTimeMs = 0L
                        _callDurationSec.value = 0L
                        currentSessionNumber = number
                        currentSessionDir = if (hasVideo) "Video Outgoing" else "Outgoing"
                    }
                    _callState.value = "Calling..."
                    _isAddingCall.value = false
                }
                Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> {
                    acquireLongCallLocks()
                    autoRouteBestAudioDevice()
                    if (core.callsNb <= 1 && !wasAnswered) {
                        _callDurationSec.value = 0L
                    }
                    _callState.value = "Ringing..."
                    _isAddingCall.value = false
                }
                Call.State.IncomingReceived, Call.State.IncomingEarlyMedia -> {
                    if (_dndEnabled.value) {
                        call.decline(Reason.Busy)
                        addHistory(number, "Missed (DND)", "00:00")
                        return
                    }
                    acquireLongCallLocks()
                    autoRouteBestAudioDevice()
                    if (core.callsNb <= 1) {
                        wasAnswered = false
                        timerJob?.cancel()
                        timerJob = null
                        callStartTimeMs = 0L
                        _callDurationSec.value = 0L
                        currentSessionNumber = number
                        currentSessionDir = if (hasVideo) "Video Incoming" else "Incoming"
                    }
                    _callState.value = "Incoming"
                    _isAddingCall.value = false
                    if (_autoAnswerEnabled.value) {
                        answerCall(withVideo = hasVideo)
                    }
                }
                Call.State.Connected, Call.State.StreamsRunning -> {
                    wasAnswered = true
                    _callState.value = "Active"
                    _isOnHold.value = false
                    _isAddingCall.value = false
                    acquireLongCallLocks()
                    autoRouteBestAudioDevice()
                    startLiveTimerIfNotRunning()
                }
                Call.State.Paused, Call.State.PausedByRemote -> {
                    if (core.callsNb == 1 && !inConf) {
                        _callState.value = "On Hold"
                        _isOnHold.value = true
                    }
                }
                Call.State.End, Call.State.Released, Call.State.Error -> {
                    _activeCallsCount.value = core.callsNb
                    if (core.callsNb == 0) {
                        releaseLongCallLocks()
                        val totalDurationStr = if (wasAnswered) stopLiveTimerAndGetFormatted() else {
                            timerJob?.cancel()
                            timerJob = null
                            callStartTimeMs = 0L
                            _callDurationSec.value = 0L
                            "00:00"
                        }
                        val logType = if (!wasAnswered && currentSessionDir.contains("Incoming")) {
                            "Missed"
                        } else if (inConf) {
                            "Conference"
                        } else {
                            currentSessionDir
                        }
                        addHistory(currentSessionNumber.ifEmpty { number }, logType, totalDurationStr)

                        currentSessionNumber = ""
                        wasAnswered = false
                        _callState.value = "Idle"
                        _isVideoCall.value = false
                        _isMuted.value = false
                        _isSpeaker.value = false
                        _isBluetoothActive.value = false
                        _isOnHold.value = false
                        _isRecording.value = false
                        _isInConference.value = false
                        _isAddingCall.value = false
                    } else {
                        _callState.value = "Active"
                        core.currentCall?.resume()
                        autoRouteBestAudioDevice()
                    }
                }
                else -> {}
            }
        }
    }

    fun init(context: Context) {
        if (core != null) return
        appContext = context.applicationContext
        loadSettings()
        loadHistory()
        loadMessages()
        loadAccounts()

        try {
            val factory = Factory.instance()
            factory.setDebugMode(false, "LoveSIP")
            val c = factory.createCore(null, null, appContext!!)

            applyNetworkPolicies(c)

            c.isEchoCancellationEnabled = true
            c.micGainDb = 2.0f
            c.playbackGainDb = 2.5f
            c.config.setBool("sound", "ec_state", true)
            c.config.setBool("sound", "noisegate", true)
            c.config.setBool("sound", "agc", true)

            c.isAdaptiveRateControlEnabled = true
            c.config.setInt("rtp", "audio_jitt_comp", 40)
            c.config.setInt("rtp", "video_jitt_comp", 60)
            c.config.setBool("rtp", "audio_adaptive_jitt_comp_enabled", true)
            c.config.setInt("rtp", "nortp_timeout", 3600)
            c.config.setInt("sip", "ptime", 20)
            c.config.setInt("sip", "inc_timeout", 60)
            c.config.setInt("sip", "in_call_timeout", 0)

            c.audioDscp = 0x2E
            c.videoDscp = 0x28
            c.sipDscp = 0x1A

            val coreCodecs = setOf("opus", "G722", "PCMU", "PCMA", "GSM", "telephone-event")
            c.audioPayloadTypes.forEach { pt ->
                val shouldEnable = coreCodecs.any { it.equals(pt.mimeType, ignoreCase = true) }
                pt.enable(shouldEnable)
                if (pt.mimeType.equals("opus", ignoreCase = true)) {
                    pt.recvFmtp = "useinbandfec=1; usedtx=0; maxaveragebitrate=40000"
                }
            }
            refreshCodecList(c)

            c.isVideoCaptureEnabled = true
            c.isVideoDisplayEnabled = true
            val videoPolicy = c.videoActivationPolicy
            videoPolicy.automaticallyAccept = true
            videoPolicy.automaticallyInitiate = false
            c.videoActivationPolicy = videoPolicy
            c.setPreferredVideoDefinitionByName("720p")
            c.videoPayloadTypes.forEach { pt ->
                pt.enable(true)
            }

            val frontCam = c.videoDevicesList.firstOrNull { it.contains("Front", ignoreCase = true) }
                ?: c.videoDevicesList.firstOrNull()
            if (frontCam != null) {
                c.videoDevice = frontCam
            }

            c.addListener(coreListener)
            c.start()
            core = c

            _isBluetoothAvailable.value = (findBluetoothOutputDevice(c) != null)

            registerNetworkWatcher()
        } catch (_: Exception) {}
    }

    private fun rotateDynamicUdpPortAndRefresh() {
        val c = core ?: return
        if (!_useRandomPort.value || c.callsNb > 0) return
        val transports = c.transports
        transports.udpPort = if (portRetryCount % 2 == 1) Random.nextInt(10240, 65000) else -1
        transports.tcpPort = -1
        transports.tlsPort = -1
        c.transports = transports
        c.refreshRegisters()
    }

    private fun registerNetworkWatcher() {
        if (networkCallbackRegistered) return
        val ctx = appContext ?: return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                managerScope.launch {
                    delay(500L)
                    if (core?.callsNb == 0) {
                        forceNetworkRebind()
                    } else {
                        core?.isNetworkReachable = true
                        core?.refreshRegisters()
                    }
                }
            }

            override fun onLost(network: Network) {
                managerScope.launch {
                    delay(600L)
                    if (core?.callsNb == 0) {
                        forceNetworkRebind()
                    }
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                if (_useWifiOnly.value && !isWifi) {
                    core?.isNetworkReachable = false
                    _regState.value = "WiFi Only Mode"
                } else if (core?.isNetworkReachable == false) {
                    forceNetworkRebind()
                }
            }
        })
        networkCallbackRegistered = true
    }

    fun forceNetworkRebind() {
        try {
            val c = core ?: return
            val ctx = appContext ?: return
            if (c.callsNb > 0) return

            if (_useWifiOnly.value) {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val activeNet = cm?.activeNetwork
                val caps = cm?.getNetworkCapabilities(activeNet)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                    c.isNetworkReachable = false
                    _regState.value = "WiFi Only Mode"
                    return
                }
            }

            applyNetworkPolicies(c)
            c.isNetworkReachable = true

            if (c.accountList.isNotEmpty()) {
                c.refreshRegisters()
            } else {
                val prefs = ctx.getSharedPreferences("sip_prefs", Context.MODE_PRIVATE)
                val s = prefs.getString("server", "") ?: ""
                val u = prefs.getString("user", "") ?: ""
                val p = prefs.getString("pass", "") ?: ""
                val tr = prefs.getString("transport", "UDP") ?: "UDP"
                if (s.isNotEmpty() && u.isNotEmpty()) {
                    register(s, u, p, tr)
                }
            }
        } catch (_: Exception) {}
    }

    fun refreshCodecList(targetCore: Core? = core) {
        val c = targetCore ?: return
        _audioCodecs.value = c.audioPayloadTypes.map {
            CodecToggleItem(it.mimeType, it.clockRate, it.enabled())
        }
    }

    fun toggleAudioCodec(mime: String, clockRate: Int, enable: Boolean) {
        val c = core ?: return
        c.audioPayloadTypes.firstOrNull { it.mimeType == mime && it.clockRate == clockRate }?.enable(enable)
        refreshCodecList(c)
    }

    fun setVideoViews(remoteView: TextureView?, previewView: TextureView?) {
        val c = core ?: return
        c.nativeVideoWindowId = remoteView
        c.nativePreviewWindowId = previewView
    }

    fun switchCamera() {
        val c = core ?: return
        val devices = c.videoDevicesList
        if (devices.size < 2) return
        val current = c.videoDevice
        val next = devices.firstOrNull { it != current && !it.contains("StaticImage", true) } ?: return
        c.videoDevice = next
    }

    fun toggleVideoDuringCall() {
        val c = core ?: return
        val call = c.currentCall ?: return
        val params = c.createCallParams(call) ?: return
        val nextState = !(call.currentParams?.isVideoEnabled ?: false)
        params.isVideoEnabled = nextState
        call.update(params)
        _isVideoCall.value = nextState
        autoRouteBestAudioDevice()
    }

    fun applyNetworkPolicies(targetCore: Core? = core) {
        val c = targetCore ?: return

        c.isIpv6Enabled = _allowIpv6.value
        c.config.setBool("net", "allow_ipv6", _allowIpv6.value)

        val transports = c.transports
        if (_useRandomPort.value) {
            transports.udpPort = -1
            transports.tcpPort = -1
            transports.tlsPort = -1
            c.config.setInt("rtp", "audio_rtp_port", -1)
            c.config.setInt("rtp", "video_rtp_port", -1)
        } else {
            transports.udpPort = 5060
            transports.tcpPort = 5060
            transports.tlsPort = 5061
            c.config.setInt("rtp", "audio_rtp_port", 7078)
            c.config.setInt("rtp", "video_rtp_port", 9078)
        }
        c.transports = transports

        c.config.setBool("rtp", "symmetric", _enableRportSymmetric.value)
        c.config.setBool("rtp", "rtp_keepalive", true)
        c.config.setBool("sip", "rport", true)
        c.config.setBool("sip", "use_rport", true)
        c.config.setInt("sip", "contact_rewrite", 1)
        c.config.setInt("net", "mtu", 1300)

        c.isKeepAliveEnabled = true
        c.config.setInt("sip", "keep_alive_period", _keepAliveSec.value * 1000)

        val natPolicy = c.createNatPolicy()
        if (_enableStunIce.value && _stunServer.value.isNotBlank()) {
            natPolicy.stunServer = _stunServer.value.trim()
            natPolicy.isStunEnabled = true
            natPolicy.isIceEnabled = true
        } else {
            natPolicy.isStunEnabled = false
            natPolicy.isIceEnabled = false
            natPolicy.isTurnEnabled = false
        }
        c.natPolicy = natPolicy
    }

    fun setUseRandomPortQuick(enabled: Boolean) {
        _useRandomPort.value = enabled
        appContext?.getSharedPreferences("sip_adv_prefs", Context.MODE_PRIVATE)
            ?.edit()?.putBoolean("random_port", enabled)?.apply()
        applyNetworkPolicies()
        val activeAcc = _accounts.value.firstOrNull { it.id == _activeAccountId.value }
        if (activeAcc != null) {
            activateAccount(activeAcc)
        }
    }

    fun updateAdvancedSettings(
        wifiOnly: Boolean,
        ipv6: Boolean,
        randomPort: Boolean,
        stunIce: Boolean,
        rportSym: Boolean,
        dnd: Boolean,
        autoAns: Boolean,
        stunHost: String,
        keepAlive: Int
    ) {
        _useWifiOnly.value = wifiOnly
        _allowIpv6.value = ipv6
        _useRandomPort.value = randomPort
        _enableStunIce.value = stunIce
        _enableRportSymmetric.value = rportSym
        _dndEnabled.value = dnd
        _autoAnswerEnabled.value = autoAns
        _stunServer.value = stunHost.trim()
        _keepAliveSec.value = keepAlive.coerceIn(5, 120)

        appContext?.getSharedPreferences("sip_adv_prefs", Context.MODE_PRIVATE)?.edit()
            ?.putBoolean("wifi_only", wifiOnly)
            ?.putBoolean("allow_ipv6", ipv6)
            ?.putBoolean("random_port", randomPort)
            ?.putBoolean("stun_ice_v2", stunIce)
            ?.putBoolean("rport_sym", rportSym)
            ?.putBoolean("dnd", dnd)
            ?.putBoolean("auto_ans", autoAns)
            ?.putString("stun_server", _stunServer.value)
            ?.putInt("keep_alive", _keepAliveSec.value)
            ?.apply()

        applyNetworkPolicies()

        val activeAcc = _accounts.value.firstOrNull { it.id == _activeAccountId.value }
        if (activeAcc != null) {
            activateAccount(activeAcc)
        } else {
            forceNetworkRebind()
        }
    }

    private fun loadSettings() {
        val p = appContext?.getSharedPreferences("sip_adv_prefs", Context.MODE_PRIVATE) ?: return
        _useWifiOnly.value = p.getBoolean("wifi_only", false)
        _allowIpv6.value = p.getBoolean("allow_ipv6", true)
        _useRandomPort.value = p.getBoolean("random_port", true)
        _enableStunIce.value = p.getBoolean("stun_ice_v2", false)
        _enableRportSymmetric.value = p.getBoolean("rport_sym", true)
        _dndEnabled.value = p.getBoolean("dnd", false)
        _autoAnswerEnabled.value = p.getBoolean("auto_ans", false)
        _stunServer.value = p.getString("stun_server", "stun.l.google.com:19302") ?: "stun.l.google.com:19302"
        _keepAliveSec.value = p.getInt("keep_alive", 10)
    }

    fun iterate() {
        core?.iterate()
    }

    fun saveAndActivateAccount(
        id: String?,
        label: String,
        server: String,
        user: String,
        pass: String,
        transport: String
    ) {
        val cleanServer = server.trim()
        val cleanUser = user.trim()
        if (cleanServer.isEmpty() || cleanUser.isEmpty()) return

        val finalId = if (id.isNullOrEmpty()) UUID.randomUUID().toString() else id
        val finalLabel = label.trim().ifEmpty { "$cleanUser@$cleanServer" }
        val newItem = SipAccountItem(finalId, finalLabel, cleanServer, cleanUser, pass, transport)

        val current = _accounts.value.toMutableList()
        val idx = current.indexOfFirst { it.id == finalId }
        if (idx >= 0) current[idx] = newItem else current.add(0, newItem)
        _accounts.value = current
        _activeAccountId.value = finalId
        persistAccounts()

        activateAccount(newItem)
    }

    fun activateAccount(item: SipAccountItem) {
        _activeAccountId.value = item.id
        appContext?.getSharedPreferences("sip_prefs", Context.MODE_PRIVATE)?.edit()
            ?.putString("active_id", item.id)
            ?.putString("server", item.server)
            ?.putString("user", item.username)
            ?.putString("pass", item.password)
            ?.putString("transport", item.transport)
            ?.remove("port")
            ?.apply()

        register(item.server, item.username, item.password, item.transport)
    }

    fun deleteAccount(id: String) {
        val current = _accounts.value.toMutableList()
        current.removeAll { it.id == id }
        _accounts.value = current
        persistAccounts()
        if (_activeAccountId.value == id) {
            core?.clearAccounts()
            core?.clearAllAuthInfo()
            _regState.value = "Disconnected"
            _activeAccountId.value = ""
        }
    }

    fun register(server: String, user: String, pass: String, transportMode: String) {
        try {
            val c = core ?: return
            portRetryCount = 0
            applyNetworkPolicies(c)
            c.clearAccounts()
            c.clearAllAuthInfo()

            val cleanServer = server.trim()
            val cleanUser = user.trim()
            if (cleanServer.isEmpty() || cleanUser.isEmpty()) return

            val hostOnly = cleanServer.substringBefore(":")
            val authInfo = Factory.instance().createAuthInfo(cleanUser, cleanUser, pass, null, null, hostOnly, null)
            c.addAuthInfo(authInfo)

            val accountParams = c.createAccountParams()
            val identity = Factory.instance().createAddress("sip:$cleanUser@$hostOnly")
            val trans = transportMode.lowercase(Locale.US)
            val serverAddr = Factory.instance().createAddress("sip:$cleanServer;transport=$trans")

            if (identity != null && serverAddr != null) {
                accountParams.identityAddress = identity
                accountParams.serverAddress = serverAddr
                accountParams.isRegisterEnabled = true
                accountParams.isOutboundProxyEnabled = true
                accountParams.expires = 300
                accountParams.natPolicy = c.natPolicy

                val account = c.createAccount(accountParams)
                c.addAccount(account)
                c.defaultAccount = account
            }
        } catch (_: Exception) {}
    }

    fun makeCall(target: String, isVideo: Boolean = false) {
        try {
            val c = core ?: return
            val cleanNumber = target.trim().replace(" ", "")
            if (cleanNumber.isEmpty()) return

            _isVideoCall.value = isVideo
            acquireLongCallLocks()
            autoRouteBestAudioDevice()
            c.isNetworkReachable = true

            if (c.callsNb > 0) {
                currentSessionNumber = "$currentSessionNumber & $cleanNumber"
                c.calls.forEach { existingCall ->
                    if (existingCall.state == Call.State.StreamsRunning) {
                        existingCall.pause()
                    }
                }
            } else {
                currentSessionNumber = cleanNumber
                currentSessionDir = if (isVideo) "Video Outgoing" else "Outgoing"
            }

            val domain = c.defaultAccount?.params?.identityAddress?.domain
                ?: appContext?.getSharedPreferences("sip_prefs", Context.MODE_PRIVATE)?.getString("server", "")?.substringBefore(":")
                ?: return

            val address = if (cleanNumber.startsWith("sip:")) {
                Factory.instance().createAddress(cleanNumber)
            } else {
                Factory.instance().createAddress("sip:$cleanNumber@$domain")
            }

            if (address != null) {
                val params = c.createCallParams(null)
                params?.isAudioEnabled = true
                params?.isVideoEnabled = isVideo
                params?.mediaEncryption = MediaEncryption.None
                c.inviteAddressWithParams(address, params!!)
            }
        } catch (_: Exception) {}
    }

    fun sendMessage(target: String, text: String, viaSimSms: Boolean): Boolean {
        val cleanTarget = target.trim().replace(" ", "")
        val cleanText = text.trim()
        if (cleanTarget.isEmpty() || cleanText.isEmpty()) return false

        if (viaSimSms) {
            return try {
                val ctx = appContext ?: return false
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ctx.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                val parts = smsManager.divideMessage(cleanText)
                smsManager.sendMultipartTextMessage(cleanTarget, null, parts, null, null)
                saveMessage(cleanTarget, cleanText, isOutgoing = true, channel = "SIM SMS")
                true
            } catch (e: Exception) {
                false
            }
        } else {
            return try {
                val c = core ?: return false
                val account = c.defaultAccount ?: return false
                val domain = account.params.identityAddress?.domain ?: return false
                val peerAddr = if (cleanTarget.startsWith("sip:")) {
                    Factory.instance().createAddress(cleanTarget)
                } else {
                    Factory.instance().createAddress("sip:$cleanTarget@$domain")
                } ?: return false

                val roomParams = c.createDefaultChatRoomParams()
                val room = c.searchChatRoom(roomParams, account.params.identityAddress, peerAddr, arrayOf(peerAddr))
                    ?: c.createChatRoom(roomParams, account.params.identityAddress, arrayOf(peerAddr))
                    ?: return false

                val msg = room.createMessageFromUtf8(cleanText)
                msg.send()
                saveMessage(cleanTarget, cleanText, isOutgoing = true, channel = "SIP")
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    fun prepareAddCall() {
        val c = core ?: return
        c.currentCall?.pause()
        _isOnHold.value = true
        _isAddingCall.value = true
    }

    fun cancelAddCall() {
        _isAddingCall.value = false
        core?.currentCall?.resume()
        _isOnHold.value = false
    }

    fun mergeCallsToConference() {
        val c = core ?: return
        if (c.callsNb >= 2) {
            val confParams = c.createConferenceParams(null)
            confParams?.isAudioEnabled = true
            val conf = c.createConferenceWithParams(confParams!!)
            conf?.addParticipants(c.calls)
            _isInConference.value = true
            startLiveTimerIfNotRunning()
        }
    }

    fun transferCall(target: String) {
        val c = core ?: return
        val call = c.currentCall ?: return
        val clean = target.trim().replace(" ", "")
        if (clean.isEmpty()) return
        val domain = c.defaultAccount?.params?.identityAddress?.domain ?: return
        val destAddr = Factory.instance().createAddress("sip:$clean@$domain")
        if (destAddr != null) {
            call.transferTo(destAddr)
        }
    }

    fun answerCall(withVideo: Boolean = _isVideoCall.value) {
        val c = core ?: return
        val call = c.currentCall ?: return
        _isVideoCall.value = withVideo
        acquireLongCallLocks()
        autoRouteBestAudioDevice()
        val params = c.createCallParams(call)
        if (params != null) {
            params.isAudioEnabled = true
            params.isVideoEnabled = withVideo
            call.acceptWithParams(params)
        } else {
            call.accept()
        }
    }

    fun hangup() {
        val c = core ?: return
        releaseLongCallLocks()
        if (c.callsNb > 0) {
            c.terminateAllCalls()
        } else {
            stopLiveTimerAndGetFormatted()
        }
        _callState.value = "Idle"
        _isVideoCall.value = false
        _isAddingCall.value = false
        _isInConference.value = false
    }

    fun toggleMute() {
        val c = core ?: return
        val next = !_isMuted.value
        c.isMicEnabled = !next
        _isMuted.value = next
    }

    fun toggleHold() {
        val call = core?.currentCall ?: return
        if (_isOnHold.value) {
            call.resume()
            _isOnHold.value = false
        } else {
            call.pause()
            _isOnHold.value = true
        }
    }

    fun toggleSpeaker() {
        try {
            val c = core ?: return
            c.reloadSoundDevices()
            val next = !_isSpeaker.value
            if (next) {
                stopSystemBluetoothSco()
                val spk = c.audioDevices.firstOrNull { it.type == AudioDevice.Type.Speaker }
                if (spk != null) c.outputAudioDevice = spk
                _isSpeaker.value = true
                _isBluetoothActive.value = false
            } else {
                _isSpeaker.value = false
                autoRouteBestAudioDevice()
            }
            updateProximitySensorLock()
        } catch (_: Exception) {}
    }

    fun sendDtmf(digit: Char) {
        core?.currentCall?.sendDtmf(digit)
    }

    fun toggleRecord() {
        val call = core?.currentCall ?: return
        val ctx = appContext ?: return
        if (!_isRecording.value) {
            val dir = File(ctx.getExternalFilesDir(null), "Recordings")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "call_${System.currentTimeMillis()}.wav")
            call.params.recordFile = file.absolutePath
            call.startRecording()
            _isRecording.value = true
        } else {
            call.stopRecording()
            _isRecording.value = false
        }
    }

    private fun saveMessage(peer: String, body: String, isOutgoing: Boolean, channel: String) {
        val time = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date())
        val item = ChatMessageItem(UUID.randomUUID().toString(), peer, body, isOutgoing, channel, time)
        val updated = listOf(item) + _messages.value.take(100)
        _messages.value = updated

        val arr = JSONArray()
        updated.forEach { m ->
            val o = JSONObject()
            o.put("id", m.id)
            o.put("peer", m.peer)
            o.put("body", m.body)
            o.put("out", m.isOutgoing)
            o.put("ch", m.channel)
            o.put("time", m.time)
            arr.put(o)
        }
        appContext?.getSharedPreferences("sip_msgs", Context.MODE_PRIVATE)
            ?.edit()?.putString("msgs_json", arr.toString())?.apply()
    }

    private fun loadMessages() {
        val raw = appContext?.getSharedPreferences("sip_msgs", Context.MODE_PRIVATE)?.getString("msgs_json", "") ?: ""
        if (raw.isNotEmpty()) {
            val arr = JSONArray(raw)
            val list = mutableListOf<ChatMessageItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    ChatMessageItem(
                        id = o.getString("id"),
                        peer = o.getString("peer"),
                        body = o.getString("body"),
                        isOutgoing = o.getBoolean("out"),
                        channel = o.optString("ch", "SIP"),
                        time = o.getString("time")
                    )
                )
            }
            _messages.value = list
        }
    }

    private fun persistAccounts() {
        val arr = JSONArray()
        _accounts.value.forEach { acc ->
            val obj = JSONObject()
            obj.put("id", acc.id)
            obj.put("label", acc.label)
            obj.put("server", acc.server)
            obj.put("username", acc.username)
            obj.put("password", acc.password)
            obj.put("transport", acc.transport)
            arr.put(obj)
        }
        appContext?.getSharedPreferences("sip_prefs", Context.MODE_PRIVATE)
            ?.edit()?.putString("accounts_json", arr.toString())?.apply()
    }

    private fun loadAccounts() {
        val prefs = appContext?.getSharedPreferences("sip_prefs", Context.MODE_PRIVATE) ?: return
        val activeId = prefs.getString("active_id", "") ?: ""
        _activeAccountId.value = activeId

        val jsonStr = prefs.getString("accounts_json", "") ?: ""
        if (jsonStr.isNotEmpty()) {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<SipAccountItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    SipAccountItem(
                        id = o.getString("id"),
                        label = o.optString("label", o.getString("username")),
                        server = o.getString("server"),
                        username = o.getString("username"),
                        password = o.getString("password"),
                        transport = o.optString("transport", "UDP")
                    )
                )
            }
            _accounts.value = list
        }
    }

    private fun addHistory(number: String, type: String, duration: String) {
        val time = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date())
        val current = _recentCalls.value.toMutableList()
        current.add(0, CallHistoryItem(number, type, time, duration))
        val trimmed = current.take(50)
        _recentCalls.value = trimmed

        val prefs = appContext?.getSharedPreferences("sip_history", Context.MODE_PRIVATE) ?: return
        val serialized = trimmed.joinToString("||") { "${it.number}::${it.type}::${it.time}::${it.duration}" }
        prefs.edit().putString("logs", serialized).apply()
    }

    private fun loadHistory() {
        val prefs = appContext?.getSharedPreferences("sip_history", Context.MODE_PRIVATE) ?: return
        val raw = prefs.getString("logs", "") ?: ""
        if (raw.isNotEmpty()) {
            _recentCalls.value = raw.split("||").mapNotNull {
                val parts = it.split("::")
                when {
                    parts.size >= 4 -> CallHistoryItem(parts[0], parts[1], parts[2], parts[3])
                    parts.size == 3 -> CallHistoryItem(parts[0], parts[1], parts[2], "00:00")
                    else -> null
                }
            }
        }
    }
}
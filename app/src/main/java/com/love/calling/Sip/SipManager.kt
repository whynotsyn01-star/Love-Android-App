package com.love.calling.sip

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
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

    private val _enableStunIce = MutableStateFlow(true)
    val enableStunIce: StateFlow<Boolean> = _enableStunIce.asStateFlow()

    private val _enableRportSymmetric = MutableStateFlow(true)
    val enableRportSymmetric: StateFlow<Boolean> = _enableRportSymmetric.asStateFlow()

    private val _dndEnabled = MutableStateFlow(false)
    val dndEnabled: StateFlow<Boolean> = _dndEnabled.asStateFlow()

    private val _autoAnswerEnabled = MutableStateFlow(false)
    val autoAnswerEnabled: StateFlow<Boolean> = _autoAnswerEnabled.asStateFlow()

    private val _stunServer = MutableStateFlow("stun.l.google.com:19302")
    val stunServer: StateFlow<String> = _stunServer.asStateFlow()

    private val _keepAliveSec = MutableStateFlow(10)
    val keepAliveSec: StateFlow<Int> = _keepAliveSec.asStateFlow()

    private var currentSessionNumber: String = ""
    private var currentSessionDir: String = "Outgoing"
    private var wasAnswered = false

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
        }
        if (timerJob?.isActive == true) return
        timerJob = managerScope.launch {
            while (isActive) {
                val elapsed = (System.currentTimeMillis() - callStartTimeMs) / 1000L
                _callDurationSec.value = elapsed.coerceAtLeast(0L)
                delay(500L)
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
                    // যদি ওই নেটওয়ার্কে বর্তমান UDP পোর্ট ব্লক থাকে, তবে অটোমেটিক নতুন আরেকটি পোর্ট খুঁজে রিকানেক্ট করবে
                    if (_useRandomPort.value && portRetryCount < 3) {
                        portRetryCount++
                        managerScope.launch {
                            delay(1200L)
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
                Call.State.OutgoingInit, Call.State.OutgoingProgress, Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> {
                    if (core.callsNb <= 1) {
                        wasAnswered = false
                        currentSessionNumber = number
                        currentSessionDir = if (hasVideo) "Video Outgoing" else "Outgoing"
                    }
                    _callState.value = "Calling..."
                    _isAddingCall.value = false
                }
                Call.State.IncomingReceived, Call.State.IncomingEarlyMedia -> {
                    if (_dndEnabled.value) {
                        call.decline(Reason.Busy)
                        addHistory(number, "Missed (DND)", "00:00")
                        return
                    }
                    if (core.callsNb <= 1) {
                        wasAnswered = false
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
                    _callState.value = "Connected"
                    _isOnHold.value = false
                    _isAddingCall.value = false
                    if (hasVideo && !_isSpeaker.value) {
                        toggleSpeaker()
                    }
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
                        val totalDurationStr = stopLiveTimerAndGetFormatted()
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
                        _isOnHold.value = false
                        _isRecording.value = false
                        _isInConference.value = false
                        _isAddingCall.value = false
                    } else {
                        _callState.value = "Connected"
                        core.currentCall?.resume()
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

        val factory = Factory.instance()
        factory.setDebugMode(false, "LoveSIP")
        val c = factory.createCore(null, null, appContext!!)

        applyNetworkPolicies(c)

        c.isEchoCancellationEnabled = true
        c.isAdaptiveRateControlEnabled = true
        c.config.setInt("rtp", "audio_jitt_comp", 60)
        c.config.setInt("rtp", "video_jitt_comp", 80)
        c.config.setBool("sound", "ec_state", true)

        // Prioritize standard telecom codecs first (PCMU, PCMA, G722, Opus, GSM) so no PBX rejects the call
        val priorityOrder = listOf("PCMU", "PCMA", "G722", "opus", "GSM", "iLBC", "speex")
        val sortedAudio = c.audioPayloadTypes.sortedBy { pt ->
            val idx = priorityOrder.indexOfFirst { it.equals(pt.mimeType, ignoreCase = true) }
            if (idx == -1) 99 else idx
        }.toTypedArray()
        c.audioPayloadTypes = sortedAudio
        c.audioPayloadTypes.forEach { pt -> pt.enable(true) }
        refreshCodecList(c)

        c.isVideoCaptureEnabled = true
        c.isVideoDisplayEnabled = true
        val videoPolicy = c.videoActivationPolicy
        videoPolicy.automaticallyAccept = true
        videoPolicy.automaticallyInitiate = false
        c.videoActivationPolicy = videoPolicy
        c.setPreferredVideoDefinitionByName("720p")
        c.videoPayloadTypes.forEach { pt -> pt.enable(true) }

        val frontCam = c.videoDevicesList.firstOrNull { it.contains("Front", ignoreCase = true) }
            ?: c.videoDevicesList.firstOrNull()
        if (frontCam != null) {
            c.videoDevice = frontCam
        }

        c.addListener(coreListener)
        c.start()
        core = c

        registerNetworkWatcher()
    }

    // কোনো পোর্ট ব্লক থাকলে Registered (1024-49151) ও Dynamic/Ephemeral (49152-65535) রেঞ্জ থেকে অটো নতুন পোর্ট বেছে নেবে
    private fun rotateDynamicUdpPortAndRefresh() {
        val c = core ?: return
        if (!_useRandomPort.value || c.callsNb > 0) return
        val transports = c.transports
        // 1024 থেকে 65535-এর যেকোনো ওপেন UDP পোর্ট অথবা সিস্টেম অটো (-1) অ্যাসাইন করবে
        transports.udpPort = if (portRetryCount % 2 == 1) Random.nextInt(10000, 65000) else -1
        transports.tcpPort = -1
        transports.tlsPort = -1
        c.transports = transports
        c.setAudioPortRange(10000, 60000)
        c.setVideoPortRange(10000, 60000)
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
                    delay(700L)
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

        // যদি ইতিমধ্যে Online থাকে, তবে শুধু রেজিস্ট্রেশন রিফ্রেশ করবে যাতে কল না কাটে
        if (_regState.value == "Online" && c.accountList.isNotEmpty()) {
            c.isNetworkReachable = true
            c.refreshRegisters()
            return
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
    }

    fun applyNetworkPolicies(targetCore: Core? = core) {
        val c = targetCore ?: return

        c.isIpv6Enabled = _allowIpv6.value
        c.config.setBool("net", "allow_ipv6", _allowIpv6.value)

        val transports = c.transports
        if (_useRandomPort.value) {
            // Use Random Port ON: সব UDP পোর্ট (Registered 1024-49151 এবং Dynamic/Ephemeral 49152-65535) থেকে অটো ডিটেক্ট করবে
            transports.udpPort = -1
            transports.tcpPort = -1
            transports.tlsPort = -1
            c.setAudioPortRange(10000, 60000)
            c.setVideoPortRange(10000, 60000)
        } else {
            transports.udpPort = 5060
            transports.tcpPort = 5060
            transports.tlsPort = 5061
            c.setAudioPortRange(10000, 20000)
            c.setVideoPortRange(20000, 30000)
        }
        c.transports = transports

        c.config.setBool("rtp", "symmetric", _enableRportSymmetric.value)
        c.config.setBool("sip", "rport", _enableRportSymmetric.value)
        c.config.setBool("sip", "use_rport", _enableRportSymmetric.value)

        c.isKeepAliveEnabled = true
        c.config.setInt("sip", "keep_alive_period", _keepAliveSec.value * 1000)

        val natPolicy = c.createNatPolicy()
        natPolicy.stunServer = _stunServer.value.trim().ifEmpty { "stun.l.google.com:19302" }
        natPolicy.isStunEnabled = _enableStunIce.value
        natPolicy.isIceEnabled = _enableStunIce.value
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
            ?.putBoolean("stun_ice", stunIce)
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
        _enableStunIce.value = p.getBoolean("stun_ice", true)
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

    // এখানে আর কোনো ফিক্সড :5060 যোগ করা হয়নি—সরাসরি সার্ভার ডোমেইন/আইপি ও ট্রান্সপোর্ট দিয়ে কানেক্ট হবে
    fun register(server: String, user: String, pass: String, transportMode: String) {
        val c = core ?: return
        portRetryCount = 0
        applyNetworkPolicies(c)
        c.clearAccounts()
        c.clearAllAuthInfo()

        val cleanServer = server.trim()
        val cleanUser = user.trim()
        if (cleanServer.isEmpty() || cleanUser.isEmpty()) return

        val hostOnly = cleanServer.substringBefore(":")
        val authInfo = Factory.instance().createAuthInfo(cleanUser, null, pass, null, null, hostOnly, null)
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
            accountParams.natPolicy = c.natPolicy
            val account = c.createAccount(accountParams)
            c.addAccount(account)
            c.defaultAccount = account
        }
    }

    fun makeCall(target: String, isVideo: Boolean = false) {
        val c = core ?: return
        val cleanNumber = target.trim().replace(" ", "")
        if (cleanNumber.isEmpty()) return

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
            _isVideoCall.value = isVideo
            c.inviteAddressWithParams(address, params!!)
        }
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
            return true
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
        val c = core ?: return
        val next = !_isSpeaker.value
        val targetType = if (next) AudioDevice.Type.Speaker else AudioDevice.Type.Earpiece
        val device = c.audioDevices.firstOrNull { it.type == targetType }
        if (device != null) {
            c.outputAudioDevice = device
        }
        _isSpeaker.value = next
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
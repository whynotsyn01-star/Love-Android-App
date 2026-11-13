package com.love.calling.sip

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class CallHistoryItem(
    val number: String,
    val type: String, // "Outgoing", "Incoming", "Missed"
    val time: String
)

object SipManager {
    private var core: Core? = null
    private var appContext: Context? = null

    private val _regState = MutableStateFlow("Disconnected")
    val regState: StateFlow<String> = _regState.asStateFlow()

    private val _callState = MutableStateFlow("Idle")
    val callState: StateFlow<String> = _callState.asStateFlow()

    private val _remoteNumber = MutableStateFlow("")
    val remoteNumber: StateFlow<String> = _remoteNumber.asStateFlow()

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

    private val coreListener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState,
            message: String
        ) {
            _regState.value = when (state) {
                RegistrationState.Ok -> "Online"
                RegistrationState.Progress -> "Connecting..."
                RegistrationState.Failed -> "Failed: $message"
                RegistrationState.Cleared -> "Disconnected"
                else -> "Offline"
            }
        }

        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State,
            message: String
        ) {
            val number = call.remoteAddress.username ?: call.remoteAddress.asStringUriOnly()
            _remoteNumber.value = number

            when (state) {
                Call.State.OutgoingInit, Call.State.OutgoingProgress, Call.State.OutgoingRinging -> {
                    _callState.value = "Calling..."
                }
                Call.State.IncomingReceived -> {
                    _callState.value = "Incoming"
                    addHistory(number, "Incoming")
                }
                Call.State.StreamsRunning -> {
                    _callState.value = "Connected"
                    _isOnHold.value = false
                }
                Call.State.Paused, Call.State.PausedByRemote -> {
                    _callState.value = "On Hold"
                    _isOnHold.value = true
                }
                Call.State.End, Call.State.Released, Call.State.Error -> {
                    _callState.value = "Idle"
                    _isMuted.value = false
                    _isOnHold.value = false
                    _isRecording.value = false
                }
                else -> {}
            }
        }
    }

    fun init(context: Context) {
        if (core != null) return
        appContext = context.applicationContext
        loadHistory()

        val factory = Factory.instance()
        factory.setDebugMode(false, "LoveSIP")
        val c = factory.createCore(null, null, appContext!!)

        // SIM Mobile Data (MB) & Any IP Network Optimization
        c.isIpv6Enabled = false // Force IPv4 so Bangladeshi SIM data doesn't drop UDP
        c.isKeepAliveEnabled = true
        c.config.setInt("sip", "keep_alive_period", 15000)
        c.config.setBool("net", "allow_ipv6", false)
        c.config.setBool("rtp", "symmetric", true)

        // Enable STUN & ICE so audio works on Mobile Data (CGNAT) and any Wi-Fi
        val natPolicy = c.createNatPolicy()
        natPolicy.stunServer = "stun.l.google.com:19302"
        natPolicy.isStunEnabled = true
        natPolicy.isIceEnabled = true
        c.natPolicy = natPolicy

        // Enable Opus, PCMU, PCMA codecs for clear voice on low MB speed
        c.audioPayloadTypes.forEach { pt ->
            pt.enable(pt.mimeType.equals("opus", true) || pt.mimeType.equals("PCMU", true) || pt.mimeType.equals("PCMA", true))
        }

        c.addListener(coreListener)
        c.start()
        core = c
    }

    fun iterate() {
        core?.iterate()
    }

    fun register(server: String, user: String, pass: String, port: Int, useTls: Boolean) {
        val c = core ?: return
        c.clearAccounts()
        c.clearAllAuthInfo()

        val cleanServer = server.trim()
        val cleanUser = user.trim()
        if (cleanServer.isEmpty() || cleanUser.isEmpty()) return

        val authInfo = Factory.instance().createAuthInfo(cleanUser, null, pass, null, null, cleanServer, null)
        c.addAuthInfo(authInfo)

        val accountParams = c.createAccountParams()
        val identity = Factory.instance().createAddress("sip:$cleanUser@$cleanServer")
        val transport = if (useTls) "tls" else "udp"
        val serverAddr = Factory.instance().createAddress("sip:$cleanServer:$port;transport=$transport")

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

    fun makeCall(target: String) {
        val c = core ?: return
        val cleanNumber = target.trim().replace(" ", "")
        if (cleanNumber.isEmpty()) return

        addHistory(cleanNumber, "Outgoing")

        val domain = c.defaultAccount?.params?.identityAddress?.domain ?: return
        val address = if (cleanNumber.startsWith("sip:")) {
            Factory.instance().createAddress(cleanNumber)
        } else {
            Factory.instance().createAddress("sip:$cleanNumber@$domain")
        }

        if (address != null) {
            val params = c.createCallParams(null)
            params?.isAudioEnabled = true
            params?.isVideoEnabled = false
            c.inviteAddressWithParams(address, params!!)
        }
    }

    fun answerCall() {
        core?.currentCall?.accept()
    }

    fun hangup() {
        val c = core ?: return
        if (c.callsNb > 0) {
            c.terminateAllCalls()
        }
        _callState.value = "Idle"
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

    fun startConference() {
        val c = core ?: return
        if (c.callsNb >= 2) {
            val confParams = c.createConferenceParams(null)
            val conf = c.createConferenceWithParams(confParams)
            conf?.addParticipants(c.calls)
        } else {
            makeCall("8000")
        }
    }

    private fun addHistory(number: String, type: String) {
        val time = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date())
        val current = _recentCalls.value.toMutableList()
        current.add(0, CallHistoryItem(number, type, time))
        val trimmed = current.take(50)
        _recentCalls.value = trimmed

        val prefs = appContext?.getSharedPreferences("sip_history", Context.MODE_PRIVATE) ?: return
        val serialized = trimmed.joinToString("||") { "${it.number}::${it.type}::${it.time}" }
        prefs.edit().putString("logs", serialized).apply()
    }

    private fun loadHistory() {
        val prefs = appContext?.getSharedPreferences("sip_history", Context.MODE_PRIVATE) ?: return
        val raw = prefs.getString("logs", "") ?: ""
        if (raw.isNotEmpty()) {
            _recentCalls.value = raw.split("||").mapNotNull {
                val parts = it.split("::")
                if (parts.size == 3) CallHistoryItem(parts[0], parts[1], parts[2]) else null
            }
        }
    }
}
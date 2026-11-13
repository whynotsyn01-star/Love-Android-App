package com.love.calling.sip

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.linphone.core.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class CallHistoryItem(
    val number: String,
    val type: String,
    val time: String
)

data class SipAccountItem(
    val id: String,
    val label: String,
    val server: String,
    val username: String,
    val password: String,
    val port: Int,
    val useTls: Boolean
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

    private val _accounts = MutableStateFlow<List<SipAccountItem>>(emptyList())
    val accounts: StateFlow<List<SipAccountItem>> = _accounts.asStateFlow()

    private val _activeAccountId = MutableStateFlow("")
    val activeAccountId: StateFlow<String> = _activeAccountId.asStateFlow()

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
            _activeCallsCount.value = core.callsNb
            _isInConference.value = core.conference != null && (core.conference?.participantCount ?: 0) >= 2

            val number = call.remoteAddress.username ?: call.remoteAddress.asStringUriOnly()
            if (core.callsNb <= 1) {
                _remoteNumber.value = number
            } else {
                _remoteNumber.value = "${core.callsNb} Active Calls"
            }

            when (state) {
                Call.State.OutgoingInit, Call.State.OutgoingProgress, Call.State.OutgoingRinging -> {
                    _callState.value = "Calling..."
                    _isAddingCall.value = false
                }
                Call.State.IncomingReceived -> {
                    _callState.value = "Incoming"
                    _isAddingCall.value = false
                    addHistory(number, "Incoming")
                }
                Call.State.StreamsRunning -> {
                    _callState.value = "Connected"
                    _isOnHold.value = false
                    _isAddingCall.value = false
                }
                Call.State.Paused, Call.State.PausedByRemote -> {
                    if (core.callsNb == 1) {
                        _callState.value = "On Hold"
                        _isOnHold.value = true
                    }
                }
                Call.State.End, Call.State.Released, Call.State.Error -> {
                    _activeCallsCount.value = core.callsNb
                    if (core.callsNb == 0) {
                        _callState.value = "Idle"
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
        loadHistory()
        loadAccounts()

        val factory = Factory.instance()
        factory.setDebugMode(false, "LoveSIP")
        val c = factory.createCore(null, null, appContext!!)

        // Teletalk SIM ও Random IP / Strict CGNAT নেটওয়ার্কের জন্য বিশেষ অপ্টিমাইজেশন
        c.isIpv6Enabled = false
        c.config.setBool("net", "allow_ipv6", false)
        c.config.setBool("rtp", "symmetric", true)
        c.config.setBool("sip", "rport", true)
        c.config.setBool("sip", "use_rport", true)
        c.isKeepAliveEnabled = true
        c.config.setInt("sip", "keep_alive_period", 10000) // প্রতি ১০ সেকেন্ডে NAT পোর্ট খোলা রাখবে

        // Random Local Port ব্যবহার করা যাতে টেলিটক সিমে পোর্ট ব্লক বা কনফ্লিক্ট না হয়
        val transports = c.transports
        transports.udpPort = -1
        transports.tcpPort = -1
        transports.tlsPort = -1
        c.transports = transports

        // Multiple STUN & ICE চালু করা যাতে Random IP থেকেও অডিও পরিষ্কার যায়
        val natPolicy = c.createNatPolicy()
        natPolicy.stunServer = "stun.l.google.com:19302"
        natPolicy.isStunEnabled = true
        natPolicy.isIceEnabled = true
        c.natPolicy = natPolicy

        // লো-স্পিড মোবাইল ডেটাতেও ক্লিয়ার ভয়েসের জন্য কোডেক সেটআপ
        c.audioPayloadTypes.forEach { pt ->
            pt.enable(
                pt.mimeType.equals("opus", true) ||
                pt.mimeType.equals("PCMU", true) ||
                pt.mimeType.equals("PCMA", true)
            )
        }

        c.addListener(coreListener)
        c.start()
        core = c
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
        port: Int,
        useTls: Boolean
    ) {
        val cleanServer = server.trim()
        val cleanUser = user.trim()
        if (cleanServer.isEmpty() || cleanUser.isEmpty()) return

        val finalId = if (id.isNullOrEmpty()) UUID.randomUUID().toString() else id
        val finalLabel = label.trim().ifEmpty { "$cleanUser@$cleanServer" }
        val newItem = SipAccountItem(finalId, finalLabel, cleanServer, cleanUser, pass, port, useTls)

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
            ?.putInt("port", item.port)
            ?.putBoolean("tls", item.useTls)
            ?.apply()

        register(item.server, item.username, item.password, item.port, item.useTls)
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

        // যদি আগে থেকেই ১টি কল চালু থাকে, তবে সেটিকে Hold করে ২য় কল শুরু করবে (Add Call)
        if (c.callsNb > 0) {
            c.calls.forEach { existingCall ->
                if (existingCall.state == Call.State.StreamsRunning) {
                    existingCall.pause()
                }
            }
        }

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

    // কল চলাকালীন Add Call বাটনে চাপলে ১ম কল Hold করে নতুন নম্বর ডায়াল করার স্ক্রিন খুলবে
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

    // ২য় জন কল রিসিভ করার পর Merge চাপলে আপনিসহ ৩ জন একসাথে কনফারেন্সে যুক্ত হবেন
    fun mergeCallsToConference() {
        val c = core ?: return
        if (c.callsNb >= 2) {
            val confParams = c.createConferenceParams(null)
            confParams?.isAudioEnabled = true
            val conf = c.createConferenceWithParams(confParams!!)
            conf?.addParticipants(c.calls)
            _isInConference.value = true
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

    private fun persistAccounts() {
        val arr = JSONArray()
        _accounts.value.forEach { acc ->
            val obj = JSONObject()
            obj.put("id", acc.id)
            obj.put("label", acc.label)
            obj.put("server", acc.server)
            obj.put("username", acc.username)
            obj.put("password", acc.password)
            obj.put("port", acc.port)
            obj.put("useTls", acc.useTls)
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
                        port = o.optInt("port", 5060),
                        useTls = o.optBoolean("useTls", false)
                    )
                )
            }
            _accounts.value = list
        } else {
            // পুরনো সেভ করা ১টি অ্যাকাউন্ট থাকলে সেটিকে লিস্টে মাইগ্রেট করা
            val s = prefs.getString("server", "") ?: ""
            val u = prefs.getString("user", "") ?: ""
            val p = prefs.getString("pass", "") ?: ""
            val port = prefs.getInt("port", 5060)
            val tls = prefs.getBoolean("tls", false)
            if (s.isNotEmpty() && u.isNotEmpty()) {
                val migrated = SipAccountItem(UUID.randomUUID().toString(), "$u@$s", s, u, p, port, tls)
                _accounts.value = listOf(migrated)
                _activeAccountId.value = migrated.id
                persistAccounts()
            }
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
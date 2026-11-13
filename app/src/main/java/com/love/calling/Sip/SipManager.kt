package com.love.calling.sip

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.linphone.core.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object SipManager {
    private var core: Core? = null
    private var coreListener: CoreListenerStub? = null

    private val _registrationState = MutableStateFlow("Disconnected")
    val registrationState: StateFlow<String> = _registrationState

    private val _callState = MutableStateFlow("Idle")
    val callState: StateFlow<String> = _callState

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn

    fun init(context: Context) {
        if (core != null) return

        val factory = Factory.instance()
        factory.setDebugMode(true, "LoveCallingSIP")
        core = factory.createCore(null, null, context)

        // Enable Opus codec for crystal clear voice on weak SIM data / Wi-Fi
        core?.audioPayloadTypes?.forEach { payload ->
            payload.enable(payload.mimeType.equals("opus", ignoreCase = true) || payload.mimeType.equals("PCMU", ignoreCase = true))
        }

        // Enable IPv6 & Keep-Alive to prevent SIM operator drops
        core?.isIpv6Enabled = true
        core?.isKeepAliveEnabled = true

        coreListener = object : CoreListenerStub() {
            override fun onAccountRegistrationStateChanged(
                core: Core,
                account: Account,
                state: RegistrationState,
                message: String
            ) {
                _registrationState.value = when (state) {
                    RegistrationState.Ok -> "Online"
                    RegistrationState.Progress -> "Connecting..."
                    RegistrationState.Failed -> "Failed: $message"
                    else -> "Disconnected"
                }
            }

            override fun onCallStateChanged(
                core: Core,
                call: Call,
                state: Call.State,
                message: String
            ) {
                _callState.value = when (state) {
                    Call.State.IncomingReceived -> "Incoming: ${call.remoteAddress.username}"
                    Call.State.OutgoingInit, Call.State.OutgoingProgress, Call.State.OutgoingRinging -> "Calling..."
                    Call.State.Connected, Call.State.StreamsRunning -> "Connected"
                    Call.State.End, Call.State.Released -> {
                        _isRecording.value = false
                        _isMuted.value = false
                        "Idle"
                    }
                    Call.State.Error -> "Error: $message"
                    else -> state.name
                }
            }
        }

        core?.addListener(coreListener!!)
        core?.start()
    }

    fun iterate() {
        core?.iterate()
    }

    /**
     * Login to FreePBX/Asterisk using TLS (Port 5061) & STUN/TURN to bypass SIM/Wi-Fi blocks
     */
    fun login(
        domain: String,
        username: String,
        password: String,
        port: Int = 5061,
        useTls: Boolean = true,
        stunServer: String = "stun.l.google.com:19302"
    ) {
        val currentCore = core ?: return
        currentCore.clearAccounts()
        currentCore.clearAllAuthInfo()

        // Configure STUN/ICE for NAT traversal on Mobile Data & Wi-Fi
        val natPolicy = currentCore.createNatPolicy()
        natPolicy.stunServer = stunServer
        natPolicy.isStunEnabled = true
        natPolicy.isIceEnabled = true
        currentCore.natPolicy = natPolicy

        val authInfo = Factory.instance().createAuthInfo(username, null, password, null, null, domain, null)
        currentCore.addAuthInfo(authInfo)

        val accountParams = currentCore.createAccountParams()
        val identity = Factory.instance().createAddress("sip:$username@$domain")
        accountParams.identityAddress = identity

        val transport = if (useTls) "tls" else "udp"
        val serverAddress = Factory.instance().createAddress("sip:$domain:$port;transport=$transport")
        accountParams.serverAddress = serverAddress
        accountParams.isRegisterEnabled = true
        accountParams.natPolicy = natPolicy

        // Enable SRTP Media Encryption if using TLS
        if (useTls) {
            currentCore.mediaEncryption = MediaEncryption.SRTP
        }

        val account = currentCore.createAccount(accountParams)
        currentCore.addAccount(account)
        currentCore.defaultAccount = account
    }

    /**
     * Make a 1-on-1 call OR dial Conference Room (e.g. "8000")
     */
    fun makeCall(targetExtension: String, domain: String, context: Context) {
        val currentCore = core ?: return
        val remoteAddress = Factory.instance().createAddress("sip:$targetExtension@$domain") ?: return
        val params = currentCore.createCallParams(null) ?: return

        // Set up local recording file path
        val recordDir = File(context.getExternalFilesDir(null), "Recordings")
        if (!recordDir.exists()) recordDir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val recordFile = File(recordDir, "Call_${targetExtension}_$timestamp.wav")
        params.recordFile = recordFile.absolutePath

        currentCore.inviteAddressWithParams(remoteAddress, params)
    }

    /**
     * Join 3-Way Conference Room directly (Extension 8000 on FreePBX)
     */
    fun joinConferenceRoom(domain: String, context: Context, roomNumber: String = "8000") {
        makeCall(roomNumber, domain, context)
    }

    /**
     * Merge all active calls on phone into a local 3-way conference
     */
    fun mergeAllCallsToConference() {
        core?.addAllToConference()
    }

    fun answerCall() {
        core?.currentCall?.accept()
    }

    fun hangUp() {
        val currentCall = core?.currentCall
        if (currentCall != null) {
            currentCall.terminate()
        } else {
            core?.terminateAllCalls()
        }
    }

    fun toggleMute() {
        val currentCore = core ?: return
        val newState = !currentCore.isMicEnabled
        currentCore.isMicEnabled = newState
        _isMuted.value = !newState
    }

    fun toggleSpeaker() {
        val currentCore = core ?: return
        val newSpeakerState = !_isSpeakerOn.value
        val targetDevice = currentCore.audioDevices.find {
            if (newSpeakerState) it.type == AudioDevice.Type.Speaker
            else it.type == AudioDevice.Type.Earpiece
        }
        if (targetDevice != null) {
            currentCore.outputAudioDevice = targetDevice
            _isSpeakerOn.value = newSpeakerState
        }
    }

    fun toggleRecording() {
        val call = core?.currentCall ?: return
        if (_isRecording.value) {
            call.stopRecording()
            _isRecording.value = false
        } else {
            call.startRecording()
            _isRecording.value = true
        }
    }
}
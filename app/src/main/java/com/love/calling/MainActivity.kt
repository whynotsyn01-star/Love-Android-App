package com.love.calling

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.love.calling.sip.SipForegroundService
import com.love.calling.sip.SipManager

private val Blue = Color(0xFF3478F6)
private val Ink = Color(0xFF17243A)
private val Muted = Color(0xFF7C8798)
private val Page = Color(0xFFF5F7FB)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start background SIP Service
        val serviceIntent = Intent(this, SipForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        setContent { LoveApp() }
    }
}

private enum class Tab { Home, Contacts, Dialpad, Settings }

@Composable
private fun LoveApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("sip_prefs", Context.MODE_PRIVATE) }

    var tab by remember { mutableStateOf(Tab.Home) }
    var number by remember { mutableStateOf("") }
    var activeTarget by remember { mutableStateOf("") }
    var isGroupCall by remember { mutableStateOf(false) }

    // Saved SIP Credentials
    var sipDomain by remember { mutableStateOf(prefs.getString("domain", "") ?: "") }
    var sipUser by remember { mutableStateOf(prefs.getString("user", "") ?: "") }
    var sipPass by remember { mutableStateOf(prefs.getString("pass", "") ?: "") }
    var sipPort by remember { mutableStateOf(prefs.getString("port", "5061") ?: "5061") }
    var useTls by remember { mutableStateOf(prefs.getBoolean("tls", true)) }

    // Observe real-time SIP states
    val regState by SipManager.registrationState.collectAsState()
    val callState by SipManager.callState.collectAsState()

    // Request Mic & Notification permissions on launch
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissionLauncher.launch(perms.toTypedArray())

        // Auto-login if credentials exist
        if (sipDomain.isNotBlank() && sipUser.isNotBlank() && sipPass.isNotBlank()) {
            SipManager.login(sipDomain, sipUser, sipPass, sipPort.toIntOrNull() ?: 5061, useTls)
        }
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Blue,
            background = Page,
            surface = Color.White
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = Page) {
            // Show Active Call Screen automatically when calling, ringing, or connected
            if (callState != "Idle") {
                ActiveCallScreen(
                    target = activeTarget.ifBlank { callState.removePrefix("Incoming: ") },
                    status = callState,
                    isGroup = isGroupCall,
                    onAnswer = { SipManager.answerCall() },
                    onEnd = {
                        SipManager.hangUp()
                        isGroupCall = false
                    },
                    onMergeConference = { SipManager.mergeAllCallsToConference() }
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            Tab.Home -> HomeScreen(
                                regState = regState,
                                sipUser = sipUser,
                                onDial = { tab = Tab.Dialpad },
                                onGroup = {
                                    if (sipDomain.isNotBlank()) {
                                        activeTarget = "Group Conference (8000)"
                                        isGroupCall = true
                                        SipManager.joinConferenceRoom(sipDomain, context, "8000")
                                    } else {
                                        tab = Tab.Settings
                                    }
                                },
                                onGoSettings = { tab = Tab.Settings }
                            )
                            Tab.Contacts -> ContactsScreen(
                                onCallExt = { ext, name ->
                                    if (sipDomain.isNotBlank()) {
                                        activeTarget = "$name ($ext)"
                                        isGroupCall = false
                                        SipManager.makeCall(ext, sipDomain, context)
                                    } else {
                                        tab = Tab.Settings
                                    }
                                }
                            )
                            Tab.Dialpad -> DialpadScreen(
                                number = number,
                                onNumber = { number = it },
                                onAppCall = {
                                    if (number.isNotBlank() && sipDomain.isNotBlank()) {
                                        activeTarget = number
                                        isGroupCall = (number == "8000")
                                        SipManager.makeCall(number, sipDomain, context)
                                    } else if (sipDomain.isBlank()) {
                                        tab = Tab.Settings
                                    }
                                },
                                onSimCall = {
                                    if (number.isNotBlank()) {
                                        val intent = Intent(
                                            Intent.ACTION_DIAL,
                                            Uri.parse("tel:${Uri.encode(number)}")
                                        )
                                        try { context.startActivity(intent) } catch (_: Exception) {}
                                    }
                                }
                            )
                            Tab.Settings -> SettingsScreen(
                                domain = sipDomain,
                                user = sipUser,
                                pass = sipPass,
                                port = sipPort,
                                useTls = useTls,
                                regState = regState,
                                onSaveAndConnect = { d, u, p, prt, tls ->
                                    sipDomain = d
                                    sipUser = u
                                    sipPass = p
                                    sipPort = prt
                                    useTls = tls
                                    prefs.edit()
                                        .putString("domain", d)
                                        .putString("user", u)
                                        .putString("pass", p)
                                        .putString("port", prt)
                                        .putBoolean("tls", tls)
                                        .apply()
                                    SipManager.login(d, u, p, prt.toIntOrNull() ?: 5061, tls)
                                }
                            )
                        }
                    }

                    NavigationBar(containerColor = Color.White) {
                        NavItem(Tab.Home, tab, "Home", Icons.Default.Home) { tab = it }
                        NavItem(Tab.Contacts, tab, "Contacts", Icons.Default.People) { tab = it }
                        NavItem(Tab.Dialpad, tab, "Dialpad", Icons.Default.Dialpad) { tab = it }
                        NavItem(Tab.Settings, tab, "Settings", Icons.Default.Settings) { tab = it }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(
    item: Tab,
    current: Tab,
    label: String,
    icon: ImageVector,
    onSelect: (Tab) -> Unit
) {
    NavigationBarItem(
        selected = current == item,
        onClick = { onSelect(item) },
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label, fontSize = 11.sp) }
    )
}

@Composable
private fun Header(subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp)) {
        Text("Love", color = Blue, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Text(subtitle, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun HomeScreen(
    regState: String,
    sipUser: String,
    onDial: () -> Unit,
    onGroup: () -> Unit,
    onGoSettings: () -> Unit
) {
    val isOnline = regState == "Online"
    Column(Modifier.fillMaxSize()) {
        Header("Stay close, wherever you are.")
        Column(Modifier.weight(1f).padding(horizontal = 18.dp)) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (sipUser.isNotBlank()) "Extension: $sipUser" else "Your calling space",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text("Encrypted VoIP & Conference", color = Muted, fontSize = 12.sp)
                        }
                        Surface(
                            color = if (isOnline) Color(0xFFDFF7E8) else Color(0xFFFFF3D9),
                            shape = CircleShape
                        ) {
                            Text(
                                " $regState ",
                                color = if (isOnline) Color(0xFF137333) else Color(0xFF946200),
                                modifier = Modifier.padding(7.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        if (isOnline) "Ready for HD calls & 3-way conference on SIM or Wi-Fi."
                        else "Configure your SIP server in Settings to start calling.",
                        color = Muted,
                        fontSize = 13.sp
                    )
                    Button(
                        onClick = if (isOnline) onDial else onGoSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(if (isOnline) Icons.Default.Dialpad else Icons.Default.Settings, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isOnline) "Open dialpad" else "Setup SIP Server")
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Quick actions", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickCard("Dial a number", Icons.Default.Call, Modifier.weight(1f), onDial)
                QuickCard("3-Way Group (8000)", Icons.Default.Groups, Modifier.weight(1f), onGroup)
            }
        }
    }
}

@Composable
private fun QuickCard(title: String, icon: ImageVector, modifier: Modifier = Modifier, action: () -> Unit) {
    Card(
        modifier.height(105.dp).clickable { action() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, contentDescription = title, tint = Blue)
            Text(title, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ContactsScreen(onCallExt: (String, String) -> Unit) {
    val members = listOf(
        "101" to "Member 1 (101)",
        "102" to "Member 2 (102)",
        "103" to "Member 3 (103)",
        "8000" to "Group Conference Room (8000)"
    )
    Column(Modifier.fillMaxSize()) {
        Header("Team & Extensions")
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(members) { (ext, name) ->
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(ext.take(2))
                        Spacer(Modifier.width(12.dp))
                        Text(name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { onCallExt(ext, name) }) {
                            Icon(Icons.Default.Call, contentDescription = "Call", tint = Blue)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialpadScreen(
    number: String,
    onNumber: (String) -> Unit,
    onAppCall: () -> Unit,
    onSimCall: () -> Unit
) {
    val keys = listOf(
        "1" to "", "2" to "ABC", "3" to "DEF",
        "4" to "GHI", "5" to "JKL", "6" to "MNO",
        "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
        "*" to "", "0" to "+", "#" to ""
    )
    Column(Modifier.fillMaxSize()) {
        Header("Dial a number")
        Text(
            if (number.isBlank()) "Enter extension (e.g. 102 or 8000)" else number,
            Modifier.fillMaxWidth().padding(16.dp),
            textAlign = TextAlign.Center,
            fontSize = 21.sp,
            color = if (number.isBlank()) Muted else Ink
        )
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    row.forEach { (digit, letters) ->
                        Surface(
                            Modifier.size(76.dp).clickable { onNumber(number + digit) },
                            shape = CircleShape,
                            color = Color.White,
                            shadowElevation = 1.dp
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text(digit, fontSize = 25.sp, color = Ink)
                                Text(letters, fontSize = 9.sp, color = Muted)
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(18.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onSimCall) {
                    Icon(Icons.Default.PhoneForwarded, contentDescription = "SIM dialer", tint = Ink)
                }
                FilledIconButton(
                    onClick = onAppCall,
                    modifier = Modifier.size(62.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF20B56B))
                ) {
                    Icon(Icons.Default.Call, contentDescription = "SIP Call", tint = Color.White)
                }
                IconButton(onClick = { onNumber(number.dropLast(1)) }) {
                    Icon(Icons.Default.Backspace, contentDescription = "Delete", tint = Ink)
                }
            }
        }
    }
}

@Composable
private fun ActiveCallScreen(
    target: String,
    status: String,
    isGroup: Boolean,
    onAnswer: () -> Unit,
    onEnd: () -> Unit,
    onMergeConference: () -> Unit
) {
    val muted by SipManager.isMuted.collectAsState()
    val speaker by SipManager.isSpeakerOn.collectAsState()
    val recording by SipManager.isRecording.collectAsState()
    val isIncoming = status.startsWith("Incoming")

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(45.dp))
        Text(if (isGroup) "Group Conference Call" else "Encrypted Voice Call", color = Muted)
        Spacer(Modifier.height(12.dp))
        Text(target, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(status, color = Blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

        if (recording) {
            Spacer(Modifier.height(8.dp))
            Surface(color = Color(0xFFFCE8E6), shape = CircleShape) {
                Text(
                    " ● Recording Audio ",
                    color = Color(0xFFC5221F),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(Modifier.weight(1f))

        if (!isIncoming) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                CallActionButton(
                    icon = if (muted) Icons.Default.MicOff else Icons.Default.Mic,
                    label = if (muted) "Muted" else "Mute",
                    active = muted,
                    onClick = { SipManager.toggleMute() }
                )
                CallActionButton(
                    icon = Icons.Default.VolumeUp,
                    label = if (speaker) "Speaker On" else "Speaker",
                    active = speaker,
                    onClick = { SipManager.toggleSpeaker() }
                )
                CallActionButton(
                    icon = Icons.Default.FiberManualRecord,
                    label = if (recording) "Stop Rec" else "Record",
                    active = recording,
                    onClick = { SipManager.toggleRecording() }
                )
                CallActionButton(
                    icon = Icons.Default.MergeType,
                    label = "Merge 3-Way",
                    active = false,
                    onClick = onMergeConference
                )
            }
        }

        Spacer(Modifier.height(35.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            if (isIncoming) {
                FilledIconButton(
                    onClick = onAnswer,
                    modifier = Modifier.size(68.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF20B56B))
                ) {
                    Icon(Icons.Default.Call, contentDescription = "Answer", tint = Color.White)
                }
            }
            FilledIconButton(
                onClick = onEnd,
                modifier = Modifier.size(68.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE5484D))
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = "End Call", tint = Color.White)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun CallActionButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (active) Blue else Color.White,
                contentColor = if (active) Color.White else Ink
            )
        ) {
            Icon(icon, contentDescription = label)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = Muted)
    }
}

@Composable
private fun SettingsScreen(
    domain: String,
    user: String,
    pass: String,
    port: String,
    useTls: Boolean,
    regState: String,
    onSaveAndConnect: (String, String, String, String, Boolean) -> Unit
) {
    var d by remember { mutableStateOf(domain) }
    var u by remember { mutableStateOf(user) }
    var p by remember { mutableStateOf(pass) }
    var prt by remember { mutableStateOf(port) }
    var tls by remember { mutableStateOf(useTls) }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Header("SIP Server Setup")
        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Status: $regState", fontWeight = FontWeight.Bold, color = Blue)

                OutlinedTextField(
                    value = d,
                    onValueChange = { d = it },
                    label = { Text("Server IP or Domain") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = u,
                    onValueChange = { u = it },
                    label = { Text("Extension Number (e.g. 101)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = p,
                    onValueChange = { p = it },
                    label = { Text("Extension Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = prt,
                        onValueChange = { prt = it },
                        label = { Text("Port (5061)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = tls, onCheckedChange = { tls = it })
                        Text("TLS Encrypt", fontSize = 13.sp)
                    }
                }

                Button(
                    onClick = { onSaveAndConnect(d.trim(), u.trim(), p.trim(), prt.trim(), tls) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save & Connect")
                }
            }
        }
    }
}

@Composable
private fun Avatar(letter: String) {
    Surface(Modifier.size(46.dp), shape = CircleShape, color = Color(0xFFE9F0FF)) {
        Box(contentAlignment = Alignment.Center) {
            Text(letter, color = Blue, fontWeight = FontWeight.Bold)
        }
    }
}
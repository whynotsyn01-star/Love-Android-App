package com.love.calling

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.love.calling.sip.*

data class PhoneContact(val name: String, val number: String)

val PremiumBgBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF1D0B24),
        Color(0xFF0E121D),
        Color(0xFF07080C)
    )
)
val GlassCardColor = Color(0xFF1B1F2E).copy(alpha = 0.82f)
val GlassBorderColor = Color(0xFFFF2E63).copy(alpha = 0.28f)
val AccentPink = Color(0xFFFF2E63)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SipManager.init(this)

        val serviceIntent = Intent(this, SipForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        autoConnectIfNeeded()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = AccentPink)) {
                LoveCallingApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        autoConnectIfNeeded()
    }

    private fun autoConnectIfNeeded() {
        val prefs = getSharedPreferences("sip_prefs", Context.MODE_PRIVATE)
        val savedServer = prefs.getString("server", "") ?: ""
        val savedUser = prefs.getString("user", "") ?: ""
        val savedPass = prefs.getString("pass", "") ?: ""
        val savedPort = prefs.getInt("port", 5060)
        val savedTransport = prefs.getString("transport", "UDP") ?: "UDP"
        if (savedServer.isNotEmpty() && savedUser.isNotEmpty() &&
            SipManager.regState.value != "Online" &&
            !SipManager.regState.value.startsWith("Connecting")
        ) {
            SipManager.register(savedServer, savedUser, savedPass, savedPort, savedTransport)
        }
    }
}

@Composable
fun LoveCallingApp() {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val regState by SipManager.regState.collectAsState()
    val callState by SipManager.callState.collectAsState()
    val remoteNumber by SipManager.remoteNumber.collectAsState()
    val isAddingCall by SipManager.isAddingCall.collectAsState()
    val dndEnabled by SipManager.dndEnabled.collectAsState()
    val durationSec by SipManager.callDurationSec.collectAsState()

    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    var hasContactsPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasContactsPerm = result[Manifest.permission.READ_CONTACTS] == true
    }

    LaunchedEffect(Unit) {
        launcher.launch(permissions)
    }

    if (callState != "Idle" && !isAddingCall) {
        ActiveCallScreen(callState = callState, remoteNumber = remoteNumber)
        return
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF140B19).copy(alpha = 0.96f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Favorite, contentDescription = null, tint = AccentPink, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Love SIP", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        if (dndEnabled) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "DND",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE74C3C),
                                modifier = Modifier
                                    .background(Color(0xFFE74C3C).copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    val badgeColor = when {
                        regState == "Online" -> Color(0xFF2ECC71)
                        regState.startsWith("Connecting") -> Color(0xFFF39C12)
                        else -> Color(0xFFE74C3C)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(badgeColor.copy(alpha = 0.18f))
                            .border(1.dp, badgeColor.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(badgeColor))
                        Spacer(Modifier.width(6.dp))
                        Text(regState, color = badgeColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (isAddingCall) {
                    val liveTime = SipManager.formatDuration(durationSec)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2ECC71))
                            .clickable { SipManager.cancelAddCall() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("1st Call ($liveTime) • Dial 2nd Person", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Back to Call", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF100C18)) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Dialpad, contentDescription = "Keypad") },
                    label = { Text("Keypad", fontSize = 10.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.History, contentDescription = "Recents") },
                    label = { Text("Recents", fontSize = 10.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Chat, contentDescription = "Messages") },
                    label = { Text("SMS/Chat", fontSize = 10.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Contacts, contentDescription = "Contacts") },
                    label = { Text("Contacts", fontSize = 10.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(Icons.Default.ManageAccounts, contentDescription = "Accounts") },
                    label = { Text("Accounts", fontSize = 10.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 5,
                    onClick = { selectedTab = 5 },
                    icon = { Icon(Icons.Default.Tune, contentDescription = "Settings") },
                    label = { Text("Settings", fontSize = 10.sp) }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PremiumBgBrush)
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> IphoneDialpadScreen()
                1 -> RecentsScreen()
                2 -> MessagingScreen()
                3 -> ContactsScreen(hasPermission = hasContactsPerm, onRequestPerm = { launcher.launch(permissions) })
                4 -> MultiAccountScreen()
                5 -> AdvancedNetworkSettingsScreen()
            }
        }
    }
}

@Composable
fun IphoneDialpadScreen() {
    var dialedNumber by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    val keys = listOf(
        listOf("1" to "", "2" to "ABC", "3" to "DEF"),
        listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
        listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
        listOf("*" to "", "0" to "+", "#" to "")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    val clip = clipboard.getText()?.text ?: ""
                    if (clip.isNotEmpty()) {
                        dialedNumber += clip.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
                    }
                }) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste", modifier = Modifier.size(16.dp), tint = AccentPink)
                    Spacer(Modifier.width(4.dp))
                    Text("Paste", fontSize = 13.sp, color = Color.White)
                }
                if (dialedNumber.isNotEmpty()) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(dialedNumber)) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp), tint = AccentPink)
                        Spacer(Modifier.width(4.dp))
                        Text("Copy", fontSize = 13.sp, color = Color.White)
                    }
                }
            }

            OutlinedTextField(
                value = dialedNumber,
                onValueChange = { dialedNumber = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    color = Color.White
                ),
                placeholder = {
                    Text("Enter Number", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = Color.Gray)
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                )
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            keys.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    row.forEach { (digit, letters) ->
                        Box(
                            modifier = Modifier
                                .size(74.dp)
                                .clip(CircleShape)
                                .background(GlassCardColor)
                                .border(1.dp, GlassBorderColor, CircleShape)
                                .clickable { dialedNumber += digit },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(digit, fontSize = 29.sp, fontWeight = FontWeight.Medium, color = Color.White)
                                if (letters.isNotEmpty()) {
                                    Text(letters, fontSize = 9.sp, color = Color.LightGray, letterSpacing = 1.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { SipManager.makeCall("8000", isVideo = false) },
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(GlassCardColor)
                    .border(1.dp, GlassBorderColor, CircleShape)
            ) {
                Icon(Icons.Default.Groups, contentDescription = "Group Call", tint = Color.White)
            }

            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF2ECC71), Color(0xFF27AE60))))
                    .clickable {
                        if (dialedNumber.isNotEmpty()) {
                            SipManager.makeCall(dialedNumber, isVideo = false)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Call, contentDescription = "Audio Call", tint = Color.White, modifier = Modifier.size(32.dp))
            }

            Box(
                modifier = Modifier
                    .size(62.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF3498DB), Color(0xFF2980B9))))
                    .clickable {
                        if (dialedNumber.isNotEmpty()) {
                            SipManager.makeCall(dialedNumber, isVideo = true)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Videocam, contentDescription = "Video Call", tint = Color.White, modifier = Modifier.size(28.dp))
            }

            IconButton(
                onClick = {
                    if (dialedNumber.isNotEmpty()) {
                        dialedNumber = dialedNumber.dropLast(1)
                    }
                },
                modifier = Modifier.size(50.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete", tint = Color.LightGray, modifier = Modifier.size(26.dp))
            }
        }
    }
}

@Composable
fun MessagingScreen() {
    val messages by SipManager.messages.collectAsState()
    var recipient by remember { mutableStateOf("") }
    var textBody by remember { mutableStateOf("") }
    var useSimSms by remember { mutableStateOf(false) }
    var statusNotice by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Universal Messaging (SIP & Any SIM SMS)", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)

        OutlinedTextField(
            value = recipient,
            onValueChange = { recipient = it },
            label = { Text("Phone Number or SIP Extension") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = !useSimSms,
                onClick = { useSimSms = false },
                label = { Text("SIP Message (Free)") },
                leadingIcon = { Icon(Icons.Default.WifiCalling3, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
            FilterChip(
                selected = useSimSms,
                onClick = { useSimSms = true },
                label = { Text("Any SIM SMS") },
                leadingIcon = { Icon(Icons.Default.SimCard, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = textBody,
                onValueChange = { textBody = it },
                placeholder = { Text("Write message...") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                maxLines = 3
            )
            IconButton(
                onClick = {
                    val sent = SipManager.sendMessage(recipient, textBody, viaSimSms = useSimSms)
                    if (sent) {
                        textBody = ""
                        statusNotice = if (useSimSms) "✓ Sent via Phone SIM SMS" else "✓ Sent via SIP Server"
                    } else {
                        statusNotice = "Failed: Check number, SIP connection, or SMS permission"
                    }
                },
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(AccentPink)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
            }
        }

        if (statusNotice.isNotEmpty()) {
            Text(statusNotice, fontSize = 12.sp, color = Color(0xFF2ECC71))
        }

        HorizontalDivider(color = GlassBorderColor)

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { msg: ChatMessageItem ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { recipient = msg.peer },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, GlassBorderColor),
                    colors = CardDefaults.cardColors(
                        containerColor = if (msg.isOutgoing) Color(0xFF2A1635) else GlassCardColor
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                if (msg.isOutgoing) "To: ${msg.peer}" else "From: ${msg.peer}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentPink
                            )
                            Text("${msg.channel} • ${msg.time}", fontSize = 11.sp, color = Color.LightGray)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(msg.body, fontSize = 15.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun RecentsScreen() {
    val recentCalls by SipManager.recentCalls.collectAsState()

    if (recentCalls.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No recent calls yet", color = Color.Gray, fontSize = 16.sp)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        items(recentCalls) { item: CallHistoryItem ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clickable { SipManager.makeCall(item.number, isVideo = item.type.contains("Video")) },
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, GlassBorderColor),
                colors = CardDefaults.cardColors(containerColor = GlassCardColor)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        val icon = when {
                            item.type.startsWith("Missed") -> Icons.AutoMirrored.Filled.CallMissed
                            item.type.contains("Video") -> Icons.Default.Videocam
                            item.type.contains("Incoming") -> Icons.AutoMirrored.Filled.CallReceived
                            item.type == "Conference" -> Icons.Default.Groups
                            else -> Icons.AutoMirrored.Filled.CallMade
                        }
                        val tint = when {
                            item.type.startsWith("Missed") -> Color(0xFFE74C3C)
                            item.type.contains("Video") -> Color(0xFF3498DB)
                            item.type.contains("Incoming") -> Color(0xFF3498DB)
                            item.type == "Conference" -> AccentPink
                            else -> Color(0xFF2ECC71)
                        }
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(item.number, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("${item.type} • ${item.time}", fontSize = 12.sp, color = Color.LightGray)
                            Text("Duration: ${item.duration}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2ECC71))
                        }
                    }
                    Row {
                        IconButton(onClick = { SipManager.makeCall(item.number, isVideo = false) }) {
                            Icon(Icons.Default.Call, contentDescription = "Audio Call", tint = Color(0xFF2ECC71))
                        }
                        IconButton(onClick = { SipManager.makeCall(item.number, isVideo = true) }) {
                            Icon(Icons.Default.Videocam, contentDescription = "Video Call", tint = Color(0xFF3498DB))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContactsScreen(hasPermission: Boolean, onRequestPerm: () -> Unit) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<PhoneContact>>(emptyList()) }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            val list = mutableListOf<PhoneContact>()
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    val name = it.getString(nameIdx) ?: "Unknown"
                    val num = it.getString(numIdx)?.replace(" ", "") ?: ""
                    if (num.isNotEmpty()) list.add(PhoneContact(name, num))
                }
            }
            contacts = list.distinctBy { it.number }
        }
    }

    if (!hasPermission) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Contacts permission required to show phone numbers", color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRequestPerm) { Text("Allow Contacts") }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search contacts...") },
            shape = RoundedCornerShape(14.dp),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))

        val filtered = contacts.filter {
            it.name.contains(searchQuery, true) || it.number.contains(searchQuery)
        }

        LazyColumn {
            items(filtered) { contact ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, GlassBorderColor),
                    colors = CardDefaults.cardColors(containerColor = GlassCardColor)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(contact.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(contact.number, fontSize = 14.sp, color = Color.LightGray)
                        }
                        Row {
                            IconButton(onClick = { SipManager.makeCall(contact.number, isVideo = false) }) {
                                Icon(Icons.Default.Call, contentDescription = "Audio Call", tint = Color(0xFF2ECC71))
                            }
                            IconButton(onClick = { SipManager.makeCall(contact.number, isVideo = true) }) {
                                Icon(Icons.Default.Videocam, contentDescription = "Video Call", tint = Color(0xFF3498DB))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActiveCallScreen(callState: String, remoteNumber: String) {
    val isMuted by SipManager.isMuted.collectAsState()
    val isSpeaker by SipManager.isSpeaker.collectAsState()
    val isOnHold by SipManager.isOnHold.collectAsState()
    val isRecording by SipManager.isRecording.collectAsState()
    val activeCallsCount by SipManager.activeCallsCount.collectAsState()
    val isInConference by SipManager.isInConference.collectAsState()
    val durationSec by SipManager.callDurationSec.collectAsState()
    val isVideoCall by SipManager.isVideoCall.collectAsState()

    var showDtmf by remember { mutableStateOf(false) }
    var showTransferDialog by remember { mutableStateOf(false) }
    var transferTarget by remember { mutableStateOf("") }

    if (showTransferDialog) {
        AlertDialog(
            onDismissRequest = { showTransferDialog = false },
            title = { Text("Transfer Call") },
            text = {
                OutlinedTextField(
                    value = transferTarget,
                    onValueChange = { transferTarget = it },
                    label = { Text("Enter Extension / Number") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    SipManager.transferCall(transferTarget)
                    showTransferDialog = false
                }) { Text("Transfer") }
            },
            dismissButton = {
                TextButton(onClick = { showTransferDialog = false }) { Text("Cancel") }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(PremiumBgBrush)) {
        if (isVideoCall) {
            var remoteTex by remember { mutableStateOf<TextureView?>(null) }
            var previewTex by remember { mutableStateOf<TextureView?>(null) }

            DisposableEffect(Unit) {
                onDispose {
                    SipManager.setVideoViews(null, null)
                }
            }

            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).also {
                        remoteTex = it
                        SipManager.setVideoViews(remoteTex, previewTex)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 36.dp, end = 16.dp)
                    .size(width = 110.dp, height = 155.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.5.dp, AccentPink, RoundedCornerShape(14.dp))
            ) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).also {
                            previewTex = it
                            SipManager.setVideoViews(remoteTex, previewTex)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 28.dp)) {
                Text(
                    if (isInConference) "3-Way Conference" else remoteNumber,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))

                val liveTimer = SipManager.formatDuration(durationSec)
                val statusSubtext = when {
                    isInConference -> "You + 2 Participants Connected"
                    durationSec > 0L -> if (isOnHold) "Call On Hold" else if (isVideoCall) "HD Video Connected" else "HD Voice Connected"
                    else -> callState
                }

                Text(statusSubtext, fontSize = 15.sp, color = Color.LightGray)

                if (durationSec > 0L || callState == "Connected" || isInConference) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF2ECC71).copy(alpha = 0.22f))
                            .border(1.dp, Color(0xFF2ECC71).copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 18.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = liveTimer,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF2ECC71),
                            letterSpacing = 1.5.sp
                        )
                    }
                }
            }

            if (showDtmf) {
                val dtmfKeys = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'), listOf('*', '0', '#'))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    dtmfKeys.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            row.forEach { ch ->
                                Button(
                                    onClick = { SipManager.sendDtmf(ch) },
                                    modifier = Modifier.size(64.dp),
                                    shape = CircleShape
                                ) { Text(ch.toString(), fontSize = 22.sp) }
                            }
                        }
                    }
                    TextButton(onClick = { showDtmf = false }) { Text("Hide Keypad", color = Color.White) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        CallActionBtn("Mute", Icons.Default.MicOff, isMuted) { SipManager.toggleMute() }
                        CallActionBtn("Keypad", Icons.Default.Dialpad, showDtmf) { showDtmf = !showDtmf }
                        CallActionBtn("Speaker", Icons.Default.VolumeUp, isSpeaker) { SipManager.toggleSpeaker() }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        CallActionBtn("Hold", Icons.Default.Pause, isOnHold) { SipManager.toggleHold() }
                        CallActionBtn("Record", Icons.Default.FiberManualRecord, isRecording) { SipManager.toggleRecord() }
                        if (activeCallsCount >= 2 && !isInConference) {
                            CallActionBtn("Merge", Icons.Default.CallMerge, true) { SipManager.mergeCallsToConference() }
                        } else {
                            CallActionBtn("Add Call", Icons.Default.PersonAdd, false) { SipManager.prepareAddCall() }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        CallActionBtn("Video", Icons.Default.Videocam, isVideoCall) { SipManager.toggleVideoDuringCall() }
                        if (isVideoCall) {
                            CallActionBtn("Flip Cam", Icons.Default.Cameraswitch, false) { SipManager.switchCamera() }
                        }
                        CallActionBtn("Transfer", Icons.Default.PhoneForwarded, false) { showTransferDialog = true }
                    }
                }
            }

            if (callState == "Incoming") {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                    Box(
                        Modifier.size(70.dp).clip(CircleShape).background(Color(0xFFE74C3C)).clickable { SipManager.hangup() },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.CallEnd, "Decline", tint = Color.White, modifier = Modifier.size(30.dp)) }

                    Box(
                        Modifier.size(70.dp).clip(CircleShape).background(Color(0xFF2ECC71)).clickable { SipManager.answerCall(withVideo = false) },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Call, "Audio Answer", tint = Color.White, modifier = Modifier.size(30.dp)) }

                    if (isVideoCall) {
                        Box(
                            Modifier.size(70.dp).clip(CircleShape).background(Color(0xFF3498DB)).clickable { SipManager.answerCall(withVideo = true) },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Videocam, "Video Answer", tint = Color.White, modifier = Modifier.size(30.dp)) }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .padding(bottom = 24.dp)
                        .size(74.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE74C3C))
                        .clickable { SipManager.hangup() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = "End Call", tint = Color.White, modifier = Modifier.size(34.dp))
                }
            }
        }
    }
}

@Composable
fun CallActionBtn(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(if (active) AccentPink else GlassCardColor)
                .border(1.dp, GlassBorderColor, CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text(label, color = Color.LightGray, fontSize = 11.sp)
    }
}

@Composable
fun MultiAccountScreen() {
    val accounts by SipManager.accounts.collectAsState()
    val activeId by SipManager.activeAccountId.collectAsState()

    var editingId by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var port by remember { mutableStateOf("5060") }
    var transport by remember { mutableStateOf("UDP") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("SIP Accounts (${accounts.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)

        accounts.forEach { acc: SipAccountItem ->
            val isActive = acc.id == activeId
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { SipManager.activateAccount(acc) },
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.5.dp, if (isActive) Color(0xFF2ECC71) else GlassBorderColor),
                colors = CardDefaults.cardColors(containerColor = GlassCardColor)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(acc.label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            if (isActive) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "ACTIVE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2ECC71),
                                    modifier = Modifier
                                        .background(Color(0xFF2ECC71).copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text("${acc.username}@${acc.server}:${acc.port} (${acc.transport})", fontSize = 13.sp, color = Color.LightGray)
                    }

                    Row {
                        IconButton(onClick = {
                            editingId = acc.id
                            label = acc.label
                            server = acc.server
                            username = acc.username
                            password = acc.password
                            port = acc.port.toString()
                            transport = acc.transport
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Color.LightGray)
                        }
                        IconButton(onClick = { SipManager.deleteAccount(acc.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFE74C3C))
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = GlassBorderColor, modifier = Modifier.padding(vertical = 4.dp))

        Text(
            if (editingId == null) "Add New SIP Account" else "Edit SIP Account",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Account Name (e.g. Teletalk / Office)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text("Server IP or Domain") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("SIP Username / Extension") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("SIP Password") },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "Toggle Password",
                        tint = Color.LightGray
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { ch -> ch.isDigit() } },
            label = { Text("Server Port (5060 for UDP/TCP, 5061 for TLS)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Transport:", color = Color.White, fontSize = 14.sp)
            listOf("UDP", "TCP", "TLS").forEach { mode ->
                FilterChip(
                    selected = transport == mode,
                    onClick = {
                        transport = mode
                        port = if (mode == "TLS") "5061" else "5060"
                    },
                    label = { Text(mode) }
                )
            }
        }

        Button(
            onClick = {
                val p = port.toIntOrNull() ?: 5060
                SipManager.saveAndActivateAccount(editingId, label, server, username, password, p, transport)
                editingId = null
                label = ""
                server = ""
                username = ""
                password = ""
                port = "5060"
                transport = "UDP"
            },
            colors = ButtonDefaults.buttonColors(containerColor = AccentPink),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(
                if (editingId == null) "Save & Connect Account" else "Update & Connect",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun AdvancedNetworkSettingsScreen() {
    val initIpv6 by SipManager.allowIpv6.collectAsState()
    val initRandomPort by SipManager.useRandomPort.collectAsState()
    val initStunIce by SipManager.enableStunIce.collectAsState()
    val initRportSym by SipManager.enableRportSymmetric.collectAsState()
    val initDnd by SipManager.dndEnabled.collectAsState()
    val initAutoAns by SipManager.autoAnswerEnabled.collectAsState()
    val initStunServer by SipManager.stunServer.collectAsState()
    val initKeepAlive by SipManager.keepAliveSec.collectAsState()
    val audioCodecs by SipManager.audioCodecs.collectAsState()

    var allowIpv6 by remember(initIpv6) { mutableStateOf(initIpv6) }
    var useRandomPort by remember(initRandomPort) { mutableStateOf(initRandomPort) }
    var enableStunIce by remember(initStunIce) { mutableStateOf(initStunIce) }
    var enableRportSym by remember(initRportSym) { mutableStateOf(initRportSym) }
    var dndEnabled by remember(initDnd) { mutableStateOf(initDnd) }
    var autoAnswer by remember(initAutoAns) { mutableStateOf(initAutoAns) }
    var stunServer by remember(initStunServer) { mutableStateOf(initStunServer) }
    var keepAlive by remember(initKeepAlive) { mutableStateOf(initKeepAlive.toString()) }
    var savedBanner by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Network & SIM Bypass Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)

        SettingToggleCard(
            title = "Use Random Local Port",
            subtitle = "Recommended ON for Teletalk SIM & Random IP networks to prevent port blocks",
            checked = useRandomPort,
            onCheckedChange = { useRandomPort = it }
        )

        SettingToggleCard(
            title = "Allow IPv6",
            subtitle = "Keep OFF for Bangladeshi SIM mobile data (Teletalk/GP/Robi) to avoid UDP drops",
            checked = allowIpv6,
            onCheckedChange = { allowIpv6 = it }
        )

        SettingToggleCard(
            title = "STUN & ICE NAT Traversal",
            subtitle = "Fixes one-way audio and connects calls across random mobile IPs",
            checked = enableStunIce,
            onCheckedChange = { enableStunIce = it }
        )

        SettingToggleCard(
            title = "Symmetric RTP & Force Rport",
            subtitle = "Bypasses strict carrier CGNAT firewalls on mobile networks",
            checked = enableRportSym,
            onCheckedChange = { enableRportSym = it }
        )

        HorizontalDivider(color = GlassBorderColor)
        Text("Call Handling Features", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)

        SettingToggleCard(
            title = "Do Not Disturb (DND)",
            subtitle = "Automatically reject all incoming calls when enabled",
            checked = dndEnabled,
            onCheckedChange = { dndEnabled = it }
        )

        SettingToggleCard(
            title = "Auto-Answer Incoming Calls",
            subtitle = "Automatically pick up incoming SIP calls hands-free",
            checked = autoAnswer,
            onCheckedChange = { autoAnswer = it }
        )

        OutlinedTextField(
            value = stunServer,
            onValueChange = { stunServer = it },
            label = { Text("STUN Server Address") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )

        OutlinedTextField(
            value = keepAlive,
            onValueChange = { keepAlive = it.filter { ch -> ch.isDigit() } },
            label = { Text("UDP Keep-Alive Interval (Seconds)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )

        Button(
            onClick = {
                val ka = keepAlive.toIntOrNull() ?: 10
                SipManager.updateAdvancedSettings(
                    ipv6 = allowIpv6,
                    randomPort = useRandomPort,
                    stunIce = enableStunIce,
                    rportSym = enableRportSym,
                    dnd = dndEnabled,
                    autoAns = autoAnswer,
                    stunHost = stunServer,
                    keepAlive = ka
                )
                savedBanner = true
            },
            colors = ButtonDefaults.buttonColors(containerColor = AccentPink),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("Apply & Reconnect Network", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        if (savedBanner) {
            Text(
                "✓ Settings applied! Active account reconnected with new network rules.",
                color = Color(0xFF2ECC71),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        HorizontalDivider(color = GlassBorderColor)
        Text("HD Audio Codecs (All Enabled)", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)

        audioCodecs.forEach { codec ->
            SettingToggleCard(
                title = "${codec.mime} (${codec.clockRate} Hz)",
                subtitle = if (codec.mime.equals("opus", true) || codec.mime.equals("G722", true)) "HD Wideband Crystal Clear Voice" else "Universal Telecom Codec",
                checked = codec.enabled,
                onCheckedChange = { SipManager.toggleAudioCodec(codec.mime, codec.clockRate, it) }
            )
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun SettingToggleCard(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, GlassBorderColor),
        colors = CardDefaults.cardColors(containerColor = GlassCardColor)
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, color = Color.LightGray)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
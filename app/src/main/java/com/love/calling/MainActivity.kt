package com.love.calling

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.love.calling.sip.CallHistoryItem
import com.love.calling.sip.SipForegroundService
import com.love.calling.sip.SipManager

data class PhoneContact(val name: String, val number: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SipManager.init(this)

        // ব্যাকগ্রাউন্ড সার্ভিস চালু রাখা যাতে রিসেন্ট অ্যাপ থেকে কেটে দিলেও কানেকশন না কাটে
        val serviceIntent = Intent(this, SipForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // অ্যাপে ঢুকলে আগে থেকে Online থাকলে নতুন করে রিকানেক্ট না করে সরাসরি Online দেখাবে,
        // আর ডিসকানেক্ট থাকলে সাথে সাথে অটো-কানেক্ট করে নেবে
        val prefs = getSharedPreferences("sip_prefs", Context.MODE_PRIVATE)
        val savedServer = prefs.getString("server", "") ?: ""
        val savedUser = prefs.getString("user", "") ?: ""
        val savedPass = prefs.getString("pass", "") ?: ""
        val savedPort = prefs.getInt("port", 5060)
        val savedTls = prefs.getBoolean("tls", false)
        if (savedServer.isNotEmpty() && savedUser.isNotEmpty() && SipManager.regState.value != "Online") {
            SipManager.register(savedServer, savedUser, savedPass, savedPort, savedTls)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LoveCallingApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // অ্যাপ মিনিমাইজ বা স্ক্রিন অফ থেকে আবার ওপেন করলে কানেকশন চেক করা
        val prefs = getSharedPreferences("sip_prefs", Context.MODE_PRIVATE)
        val savedServer = prefs.getString("server", "") ?: ""
        val savedUser = prefs.getString("user", "") ?: ""
        val savedPass = prefs.getString("pass", "") ?: ""
        val savedPort = prefs.getInt("port", 5060)
        val savedTls = prefs.getBoolean("tls", false)
        if (savedServer.isNotEmpty() && savedUser.isNotEmpty() &&
            SipManager.regState.value != "Online" &&
            !SipManager.regState.value.startsWith("Connecting")
        ) {
            SipManager.register(savedServer, savedUser, savedPass, savedPort, savedTls)
        }
    }
}

@Composable
fun LoveCallingApp() {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0=Keypad, 1=Recents, 2=Contacts, 3=Settings
    val regState by SipManager.regState.collectAsState()
    val callState by SipManager.callState.collectAsState()
    val remoteNumber by SipManager.remoteNumber.collectAsState()

    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
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

    if (callState != "Idle") {
        ActiveCallScreen(callState = callState, remoteNumber = remoteNumber)
        return
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121418))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Love SIP", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                val badgeColor = when {
                    regState == "Online" -> Color(0xFF2ECC71)
                    regState.startsWith("Connecting") -> Color(0xFFF39C12)
                    else -> Color(0xFFE74C3C)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(badgeColor.copy(alpha = 0.2f))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(badgeColor))
                    Spacer(Modifier.width(6.dp))
                    Text(regState, color = badgeColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF121418)) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Dialpad, contentDescription = "Keypad") },
                    label = { Text("Keypad") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.History, contentDescription = "Recents") },
                    label = { Text("Recents") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Contacts, contentDescription = "Contacts") },
                    label = { Text("Contacts") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0D10))
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> IphoneDialpadScreen()
                1 -> RecentsScreen()
                2 -> ContactsScreen(hasPermission = hasContactsPerm, onRequestPerm = { launcher.launch(permissions) })
                3 -> SettingsScreen()
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
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // নম্বর ডিসপ্লে এবং কপি-পেস্ট বার
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = {
                    val clip = clipboard.getText()?.text ?: ""
                    if (clip.isNotEmpty()) {
                        dialedNumber += clip.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
                    }
                }) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Paste", fontSize = 13.sp)
                }
                if (dialedNumber.isNotEmpty()) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(dialedNumber)) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy", fontSize = 13.sp)
                    }
                }
            }

            OutlinedTextField(
                value = dialedNumber,
                onValueChange = { dialedNumber = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 34.sp,
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

        // বড় আইফোন স্টাইল গোল ডায়ালপ্যাড বাটন
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            keys.forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    row.forEach { (digit, letters) ->
                        Box(
                            modifier = Modifier
                                .size(82.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF22262E))
                                .clickable { dialedNumber += digit },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(digit, fontSize = 32.sp, fontWeight = FontWeight.Medium, color = Color.White)
                                if (letters.isNotEmpty()) {
                                    Text(letters, fontSize = 10.sp, color = Color.LightGray, letterSpacing = 1.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // নিচের গ্রুপ কল, সবুজ কল বাটন এবং ব্যাকস্পেস
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { SipManager.makeCall("8000") },
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2C3E50))
            ) {
                Icon(Icons.Default.Groups, contentDescription = "Group Call", tint = Color.White)
            }

            Box(
                modifier = Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2ECC71))
                    .clickable {
                        if (dialedNumber.isNotEmpty()) {
                            SipManager.makeCall(dialedNumber)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Call, contentDescription = "Call", tint = Color.White, modifier = Modifier.size(36.dp))
            }

            IconButton(
                onClick = {
                    if (dialedNumber.isNotEmpty()) {
                        dialedNumber = dialedNumber.dropLast(1)
                    }
                },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete", tint = Color.LightGray, modifier = Modifier.size(28.dp))
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
                    .padding(vertical = 4.dp)
                    .clickable { SipManager.makeCall(item.number) },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF181B20))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(item.number, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("${item.type} • ${item.time}", fontSize = 13.sp, color = Color.Gray)
                    }
                    IconButton(onClick = { SipManager.makeCall(item.number) }) {
                        Icon(Icons.Default.Call, contentDescription = "Call", tint = Color(0xFF2ECC71))
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
                        .padding(vertical = 4.dp)
                        .clickable { SipManager.makeCall(contact.number) },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF181B20))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(contact.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(contact.number, fontSize = 14.sp, color = Color.LightGray)
                        }
                        Icon(Icons.Default.Call, contentDescription = "Call", tint = Color(0xFF2ECC71))
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
    var showDtmf by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D10))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 32.dp)) {
            Text(remoteNumber, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(callState, fontSize = 16.sp, color = Color(0xFF2ECC71))
        }

        if (showDtmf) {
            val dtmfKeys = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'), listOf('*', '0', '#'))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    CallActionBtn("Mute", Icons.Default.MicOff, isMuted) { SipManager.toggleMute() }
                    CallActionBtn("Keypad", Icons.Default.Dialpad, showDtmf) { showDtmf = !showDtmf }
                    CallActionBtn("Speaker", Icons.Default.VolumeUp, isSpeaker) { SipManager.toggleSpeaker() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    CallActionBtn("Hold", Icons.Default.Pause, isOnHold) { SipManager.toggleHold() }
                    CallActionBtn("Record", Icons.Default.FiberManualRecord, isRecording) { SipManager.toggleRecord() }
                    CallActionBtn("3-Way", Icons.Default.Groups, false) { SipManager.startConference() }
                }
            }
        }

        if (callState == "Incoming") {
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(Color(0xFFE74C3C)).clickable { SipManager.hangup() },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.CallEnd, "Decline", tint = Color.White, modifier = Modifier.size(32.dp)) }

                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(Color(0xFF2ECC71)).clickable { SipManager.answerCall() },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Call, "Answer", tint = Color.White, modifier = Modifier.size(32.dp)) }
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

@Composable
fun CallActionBtn(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(66.dp)
                .clip(CircleShape)
                .background(if (active) Color(0xFF2ECC71) else Color(0xFF22262E))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.LightGray, fontSize = 12.sp)
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("sip_prefs", Context.MODE_PRIVATE) }

    var server by remember { mutableStateOf(prefs.getString("server", "") ?: "") }
    var username by remember { mutableStateOf(prefs.getString("user", "") ?: "") }
    var password by remember { mutableStateOf(prefs.getString("pass", "") ?: "") }
    var port by remember { mutableStateOf((prefs.getInt("port", 5060)).toString()) }
    var useTls by remember { mutableStateOf(prefs.getBoolean("tls", false)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("SIP Account Setup (UDP / TLS)", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)

        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text("Server IP or Domain") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("SIP Username / Extension") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("SIP Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { ch -> ch.isDigit() } },
            label = { Text("Port (5060 for UDP, 5061 for TLS)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = useTls, onCheckedChange = {
                useTls = it
                port = if (it) "5061" else "5060"
            })
            Text("Use TLS Encryption (Uncheck for UDP)", color = Color.White)
        }

        Button(
            onClick = {
                val p = port.toIntOrNull() ?: 5060
                prefs.edit()
                    .putString("server", server.trim())
                    .putString("user", username.trim())
                    .putString("pass", password)
                    .putInt("port", p)
                    .putBoolean("tls", useTls)
                    .apply()
                SipManager.register(server, username, password, p, useTls)
            },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("Save & Connect", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(40.dp))
    }
}
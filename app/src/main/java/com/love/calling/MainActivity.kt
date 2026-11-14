package com.love.calling

import android.Manifest
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
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
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

val PremiumBgBrush = Brush.verticalGradient(listOf(Color(0xFF0A192F), Color(0xFF13112B), Color(0xFF070A12)))
val DialButtonBrush = Brush.verticalGradient(listOf(Color(0xFF1E2942).copy(alpha = 0.92f), Color(0xFF121829).copy(alpha = 0.95f)))
val GlassCardColor = Color(0xFF162038).copy(alpha = 0.85f)
val GlassBorderColor = Color(0xFF38BDF8).copy(alpha = 0.32f)
val AccentPrimary = Color(0xFF00E5FF)
val AccentRose = Color(0xFFFF2E63)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SipManager.init(this)
        val serviceIntent = Intent(this, SipForegroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
            else startService(serviceIntent)
        } catch (_: Throwable) {}
        SipManager.forceNetworkRebind()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = AccentPrimary)) {
                LoveCallingApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SipManager.forceNetworkRebind()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    var hasContactsPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        hasContactsPerm = res[Manifest.permission.READ_CONTACTS] == true
    }

    LaunchedEffect(Unit) { launcher.launch(permissions) }

    if (callState != "Idle" && !isAddingCall) {
        ActiveCallScreen(callState = callState, remoteNumber = remoteNumber)
        return
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF0B162C).copy(alpha = 0.96f)).statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Favorite, contentDescription = null, tint = AccentRose, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Love", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        if (dndEnabled) {
                            Spacer(Modifier.width(8.dp))
                            Text("DND", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE74C3C),
                                modifier = Modifier.background(Color(0xFFE74C3C).copy(alpha = 0.2f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }

                    val badgeColor = when {
                        regState == "Online" -> Color(0xFF2ECC71)
                        regState.startsWith("Connecting") -> Color(0xFFF39C12)
                        else -> Color(0xFFE74C3C)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(badgeColor.copy(alpha = 0.18f))
                            .border(1.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                            .clickable { SipManager.forceNetworkRebind() }.padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(badgeColor))
                        Spacer(Modifier.width(6.dp))
                        Text(regState, color = badgeColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (isAddingCall) {
                    val liveTime = SipManager.formatDuration(durationSec)
                    Row(
                        modifier = Modifier.fillMaxWidth().background(Color(0xFF2ECC71)).clickable { SipManager.cancelAddCall() }.padding(horizontal = 16.dp, vertical = 8.dp),
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
            NavigationBar(containerColor = Color(0xFF091224)) {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.Dialpad, "Keypad") }, label = { Text("Keypad", fontSize = 10.sp) })
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.History, "Recents") }, label = { Text("Recents", fontSize = 10.sp) })
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Default.Chat, "Messages") }, label = { Text("SMS/Chat", fontSize = 10.sp) })
                NavigationBarItem(selected = selectedTab == 3, onClick = { selectedTab = 3 }, icon = { Icon(Icons.Default.Contacts, "Contacts") }, label = { Text("Contacts", fontSize = 10.sp) })
                NavigationBarItem(selected = selectedTab == 4, onClick = { selectedTab = 4 }, icon = { Icon(Icons.Default.ManageAccounts, "Accounts") }, label = { Text("Accounts", fontSize = 10.sp) })
                NavigationBarItem(selected = selectedTab == 5, onClick = { selectedTab = 5 }, icon = { Icon(Icons.Default.Tune, "Settings") }, label = { Text("Settings", fontSize = 10.sp) })
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().background(PremiumBgBrush).padding(innerPadding)) {
            when (selectedTab) {
                0 -> StandardLargeDialpadScreen()
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
fun StandardLargeDialpadScreen() {
    var dialedNumber by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val keys = listOf(
        listOf("1" to "", "2" to "ABC", "3" to "DEF"),
        listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
        listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
        listOf("*" to "", "0" to "+", "#" to "")
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    val clip = clipboard.getText()?.text ?: ""
                    if (clip.isNotEmpty()) dialedNumber += clip.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
                }) {
                    Icon(Icons.Default.ContentPaste, "Paste", modifier = Modifier.size(16.dp), tint = AccentPrimary)
                    Spacer(Modifier.width(4.dp))
                    Text("Paste", fontSize = 13.sp, color = Color.White)
                }
                if (dialedNumber.isNotEmpty()) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(dialedNumber)) }) {
                        Icon(Icons.Default.ContentCopy, "Copy", modifier = Modifier.size(16.dp), tint = AccentPrimary)
                        Spacer(Modifier.width(4.dp))
                        Text("Copy", fontSize = 13.sp, color = Color.White)
                    }
                }
            }

            OutlinedTextField(
                value = dialedNumber,
                onValueChange = { dialedNumber = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(fontSize = 36.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = Color.White),
                placeholder = { Text("Enter Number", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = Color(0xFF7F8FA6), fontSize = 26.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent)
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            keys.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    row.forEach { (digit, letters) ->
                        Box(
                            modifier = Modifier.size(84.dp).clip(CircleShape).background(DialButtonBrush).border(1.2.dp, GlassBorderColor, CircleShape).clickable { dialedNumber += digit },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(digit, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                if (letters.isNotEmpty()) Text(letters, fontSize = 10.sp, color = AccentPrimary.copy(alpha = 0.85f), letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { SipManager.makeCall("8000", isVideo = false) },
                modifier = Modifier.size(54.dp).clip(CircleShape).background(GlassCardColor).border(1.dp, GlassBorderColor, CircleShape)
            ) { Icon(Icons.Default.Groups, "Group Call", tint = Color.White) }

            Box(
                modifier = Modifier.size(72.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF00E676), Color(0xFF00C853))))
                    .clickable { if (dialedNumber.isNotEmpty()) SipManager.makeCall(dialedNumber, isVideo = false) },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Call, "Audio Call", tint = Color.White, modifier = Modifier.size(34.dp)) }

            Box(
                modifier = Modifier.size(64.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF38BDF8), Color(0xFF0284C7))))
                    .clickable { if (dialedNumber.isNotEmpty()) SipManager.makeCall(dialedNumber, isVideo = true) },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Videocam, "Video Call", tint = Color.White, modifier = Modifier.size(30.dp)) }

            IconButton(onClick = { if (dialedNumber.isNotEmpty()) dialedNumber = dialedNumber.dropLast(1) }, modifier = Modifier.size(54.dp)) {
                Icon(Icons.AutoMirrored.Filled.Backspace, "Delete", tint = Color.LightGray, modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
fun ActiveCallScreen(callState: String, remoteNumber: String) {
    val isMuted by SipManager.isMuted.collectAsState()
    val isSpeaker by SipManager.isSpeaker.collectAsState()
    val isBluetoothAvailable by SipManager.isBluetoothAvailable.collectAsState()
    val isBluetoothActive by SipManager.isBluetoothActive.collectAsState()
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
            text = { OutlinedTextField(value = transferTarget, onValueChange = { transferTarget = it }, label = { Text("Enter Extension / Number") }, singleLine = true) },
            confirmButton = { Button(onClick = { SipManager.transferCall(transferTarget); showTransferDialog = false }) { Text("Transfer") } },
            dismissButton = { TextButton(onClick = { showTransferDialog = false }) { Text("Cancel") } }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(PremiumBgBrush).statusBarsPadding()) {
        if (isVideoCall) {
            var remoteTex by remember { mutableStateOf<TextureView?>(null) }
            var previewTex by remember { mutableStateOf<TextureView?>(null) }
            DisposableEffect(Unit) { onDispose { SipManager.setVideoViews(null, null) } }
            AndroidView(factory = { ctx -> TextureView(ctx).also { remoteTex = it; SipManager.setVideoViews(remoteTex, previewTex) } }, modifier = Modifier.fillMaxSize())
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 16.dp).size(width = 110.dp, height = 155.dp).clip(RoundedCornerShape(14.dp)).border(1.5.dp, AccentPrimary, RoundedCornerShape(14.dp))) {
                AndroidView(factory = { ctx -> TextureView(ctx).also { previewTex = it; SipManager.setVideoViews(remoteTex, previewTex) } }, modifier = Modifier.fillMaxSize())
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 16.dp)) {
                Text(if (isInConference) "3-Way Conference" else remoteNumber, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))

                val liveTimer = SipManager.formatDuration(durationSec)
                val statusSubtext = when {
                    isInConference -> "You + 2 Participants Connected"
                    isOnHold -> "Call On Hold (Tap Unhold to Resume)"
                    durationSec > 0L -> when {
                        isBluetoothActive -> "HD Bluetooth Earbuds Active"
                        isVideoCall -> "HD Video Connected"
                        else -> "HD Voice Connected"
                    }
                    else -> callState
                }

                Text(statusSubtext, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (isOnHold) Color(0xFFF39C12) else Color.LightGray)

                if (durationSec > 0L || isInConference) {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xFF2ECC71).copy(alpha = 0.22f))
                            .border(1.dp, Color(0xFF2ECC71).copy(alpha = 0.6f), RoundedCornerShape(22.dp)).padding(horizontal = 20.dp, vertical = 6.dp)
                    ) {
                        Text(text = liveTimer, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF2ECC71), letterSpacing = 1.5.sp)
                    }
                }
            }

            if (showDtmf) {
                val dtmfKeys = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'), listOf('*', '0', '#'))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    dtmfKeys.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            row.forEach { ch ->
                                Button(onClick = { SipManager.sendDtmf(ch) }, modifier = Modifier.size(72.dp), shape = CircleShape) {
                                    Text(ch.toString(), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    TextButton(onClick = { showDtmf = false }) { Text("Hide Keypad", color = Color.White, fontSize = 15.sp) }
                }
            } else {
                // 3x3 Medium-Large Grid with dedicated Bluetooth Earbuds Button & Unhold Support
                Column(verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        CallActionBtn("Mute", Icons.Default.MicOff, isMuted) { SipManager.toggleMute() }
                        CallActionBtn("Keypad", Icons.Default.Dialpad, showDtmf) { showDtmf = !showDtmf }
                        CallActionBtn("Speaker", Icons.Default.VolumeUp, isSpeaker) { SipManager.toggleSpeaker() }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        CallActionBtn(
                            label = if (isOnHold) "Unhold" else "Hold",
                            icon = if (isOnHold) Icons.Default.PlayArrow else Icons.Default.Pause,
                            active = isOnHold
                        ) { SipManager.toggleHold() }

                        CallActionBtn("Record", Icons.Default.FiberManualRecord, isRecording) { SipManager.toggleRecord() }
                        if (activeCallsCount >= 2 && !isInConference) {
                            CallActionBtn("Merge", Icons.Default.CallMerge, true) { SipManager.mergeCallsToConference() }
                        } else {
                            CallActionBtn("Add Call", Icons.Default.PersonAdd, false) { SipManager.prepareAddCall() }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        CallActionBtn(
                            label = if (isBluetoothActive) "Earbuds ON" else "Bluetooth",
                            icon = if (isBluetoothAvailable || isBluetoothActive) Icons.Default.BluetoothAudio else Icons.AutoMirrored.Filled.BluetoothSearching,
                            active = isBluetoothActive
                        ) { SipManager.toggleBluetoothAudio() }

                        CallActionBtn("Video", Icons.Default.Videocam, isVideoCall) { SipManager.toggleVideoDuringCall() }
                        if (isVideoCall) {
                            CallActionBtn("Flip Cam", Icons.Default.Cameraswitch, false) { SipManager.switchCamera() }
                        } else {
                            CallActionBtn("Transfer", Icons.Default.PhoneForwarded, false) { showTransferDialog = true }
                        }
                    }
                }
            }

            if (callState == "Incoming") {
                Row(horizontalArrangement = Arrangement.spacedBy(36.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                    Box(Modifier.size(78.dp).clip(CircleShape).background(Color(0xFFE74C3C)).clickable { SipManager.hangup() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.CallEnd, "Decline", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    Box(Modifier.size(78.dp).clip(CircleShape).background(Color(0xFF2ECC71)).clickable { SipManager.answerCall(withVideo = false) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Call, "Audio Answer", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    if (isVideoCall) {
                        Box(Modifier.size(78.dp).clip(CircleShape).background(AccentPrimary).clickable { SipManager.answerCall(withVideo = true) }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Videocam, "Video Answer", tint = Color.Black, modifier = Modifier.size(36.dp))
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.padding(bottom = 24.dp).size(82.dp).clip(CircleShape).background(Color(0xFFE74C3C)).clickable { SipManager.hangup() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = "End Call", tint = Color.White, modifier = Modifier.size(38.dp))
                }
            }
        }
    }
}

@Composable
fun CallActionBtn(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(76.dp).clip(CircleShape).background(if (active) AccentRose else GlassCardColor)
                .border(1.3.dp, if (active) AccentRose else GlassBorderColor, CircleShape).clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(7.dp))
        Text(label, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun MessagingScreen() {
    val messages by SipManager.messages.collectAsState()
    var recipient by remember { mutableStateOf("") }
    var textBody by remember { mutableStateOf("") }
    var useSimSms by remember { mutableStateOf(false) }
    var statusNotice by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Universal Messaging (SIP & Any SIM SMS)", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
        OutlinedTextField(value = recipient, onValueChange = { recipient = it }, label = { Text("Phone Number or SIP Extension") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !useSimSms, onClick = { useSimSms = false }, label = { Text("SIP Message (Free)") }, leadingIcon = { Icon(Icons.Default.WifiCalling3, null, modifier = Modifier.size(16.dp)) })
            FilterChip(selected = useSimSms, onClick = { useSimSms = true }, label = { Text("Any SIM SMS") }, leadingIcon = { Icon(Icons.Default.SimCard, null, modifier = Modifier.size(16.dp)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = textBody, onValueChange = { textBody = it }, placeholder = { Text("Write message...") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), maxLines = 3)
            IconButton(
                onClick = {
                    val sent = SipManager.sendMessage(recipient, textBody, viaSimSms = useSimSms)
                    if (sent) { textBody = ""; statusNotice = if (useSimSms) "✓ Sent via Phone SIM SMS" else "✓ Sent via SIP Server" }
                    else statusNotice = "Failed: Check number, SIP connection, or SMS permission"
                },
                modifier = Modifier.size(52.dp).clip(CircleShape).background(AccentRose)
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White) }
        }
        if (statusNotice.isNotEmpty()) Text(statusNotice, fontSize = 12.sp, color = Color(0xFF2ECC71))
        HorizontalDivider(color = GlassBorderColor)
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { recipient = msg.peer },
                    shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, GlassBorderColor),
                    colors = CardDefaults.cardColors(containerColor = if (msg.isOutgoing) Color(0xFF1D2D50) else GlassCardColor)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (msg.isOutgoing) "To: ${msg.peer}" else "From: ${msg.peer}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AccentPrimary)
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No recent calls yet", color = Color.Gray, fontSize = 16.sp) }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        items(recentCalls) { item ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { SipManager.makeCall(item.number, isVideo = item.type.contains("Video")) },
                shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, GlassBorderColor), colors = CardDefaults.cardColors(containerColor = GlassCardColor)
            ) {
                Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
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
                            item.type.contains("Video") -> AccentPrimary
                            item.type.contains("Incoming") -> AccentPrimary
                            item.type == "Conference" -> AccentRose
                            else -> Color(0xFF2ECC71)
                        }
                        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(item.number, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("${item.type} • ${item.time}", fontSize = 12.sp, color = Color.LightGray)
                            Text("Duration: ${item.duration}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2ECC71))
                        }
                    }
                    Row {
                        IconButton(onClick = { SipManager.makeCall(item.number, isVideo = false) }) { Icon(Icons.Default.Call, "Audio Call", tint = Color(0xFF2ECC71)) }
                        IconButton(onClick = { SipManager.makeCall(item.number, isVideo = true) }) { Icon(Icons.Default.Videocam, "Video Call", tint = AccentPrimary) }
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
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
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
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Contacts permission required to show phone numbers", color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRequestPerm) { Text("Allow Contacts") }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search contacts...") }, shape = RoundedCornerShape(14.dp), singleLine = true)
        Spacer(Modifier.height(8.dp))
        val filtered = contacts.filter { it.name.contains(searchQuery, true) || it.number.contains(searchQuery) }
        LazyColumn {
            items(filtered) { contact ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, GlassBorderColor), colors = CardDefaults.cardColors(containerColor = GlassCardColor)) {
                    Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(contact.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(contact.number, fontSize = 14.sp, color = Color.LightGray)
                        }
                        Row {
                            IconButton(onClick = { SipManager.makeCall(contact.number, isVideo = false) }) { Icon(Icons.Default.Call, "Audio Call", tint = Color(0xFF2ECC71)) }
                            IconButton(onClick = { SipManager.makeCall(contact.number, isVideo = true) }) { Icon(Icons.Default.Videocam, "Video Call", tint = AccentPrimary) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MultiAccountScreen() {
    val accounts by SipManager.accounts.collectAsState()
    val activeId by SipManager.activeAccountId.collectAsState()
    val useRandomPort by SipManager.useRandomPort.collectAsState()

    var editingId by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var transport by remember { mutableStateOf("UDP") }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SIP Accounts (${accounts.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        accounts.forEach { acc ->
            val isActive = acc.id == activeId
            Card(
                modifier = Modifier.fillMaxWidth().clickable { SipManager.activateAccount(acc) },
                shape = RoundedCornerShape(16.dp), border = BorderStroke(1.5.dp, if (isActive) Color(0xFF2ECC71) else GlassBorderColor), colors = CardDefaults.cardColors(containerColor = GlassCardColor)
            ) {
                Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(acc.label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            if (isActive) {
                                Spacer(Modifier.width(8.dp))
                                Text("ACTIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2ECC71), modifier = Modifier.background(Color(0xFF2ECC71).copy(alpha = 0.2f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Text("${acc.username}@${acc.server} (${acc.transport})", fontSize = 13.sp, color = Color.LightGray)
                    }
                    Row {
                        IconButton(onClick = { editingId = acc.id; label = acc.label; server = acc.server; username = acc.username; password = acc.password; transport = acc.transport }) {
                            Icon(Icons.Default.Edit, "Edit", tint = Color.LightGray)
                        }
                        IconButton(onClick = { SipManager.deleteAccount(acc.id) }) {
                            Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFE74C3C))
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = GlassBorderColor, modifier = Modifier.padding(vertical = 4.dp))
        Text(if (editingId == null) "Add New SIP Account" else "Edit SIP Account", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Account Name (e.g. Sohan Ahmed)") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), singleLine = true)
        OutlinedTextField(value = server, onValueChange = { server = it }, label = { Text("Server IP or Domain") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), singleLine = true)
        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("SIP Username / Extension") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), singleLine = true)
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("SIP Password") },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, "Toggle Password", tint = Color.LightGray)
                }
            },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), singleLine = true
        )

        SettingToggleCard(
            title = "Use random ports (Auto-Detect UDP)",
            subtitle = "Auto-detects working UDP port (1024–65535) for Teletalk SIM & Wi-Fi",
            checked = useRandomPort,
            onCheckedChange = { SipManager.setUseRandomPortQuick(it) }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Transport:", color = Color.White, fontSize = 14.sp)
            listOf("UDP", "TCP", "TLS").forEach { mode ->
                FilterChip(selected = transport == mode, onClick = { transport = mode }, label = { Text(mode) })
            }
        }

        Button(
            onClick = {
                SipManager.saveAndActivateAccount(editingId, label, server, username, password, transport)
                editingId = null; label = ""; server = ""; username = ""; password = ""; transport = "UDP"
            },
            colors = ButtonDefaults.buttonColors(containerColor = AccentRose),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(if (editingId == null) "Save & Connect Account" else "Update & Connect", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(40.dp))
    }
}

// Instant Auto-Save Customization Screen: Every toggle saves immediately to SharedPreferences!
@Composable
fun AdvancedNetworkSettingsScreen() {
    val useWifiOnly by SipManager.useWifiOnly.collectAsState()
    val allowIpv6 by SipManager.allowIpv6.collectAsState()
    val useRandomPort by SipManager.useRandomPort.collectAsState()
    val enableStunIce by SipManager.enableStunIce.collectAsState()
    val enableRportSym by SipManager.enableRportSymmetric.collectAsState()
    val dndEnabled by SipManager.dndEnabled.collectAsState()
    val autoAnswer by SipManager.autoAnswerEnabled.collectAsState()
    val initStunServer by SipManager.stunServer.collectAsState()
    val initKeepAlive by SipManager.keepAliveSec.collectAsState()
    val audioCodecs by SipManager.audioCodecs.collectAsState()

    var stunServer by remember(initStunServer) { mutableStateOf(initStunServer) }
    var keepAlive by remember(initKeepAlive) { mutableStateOf(initKeepAlive.toString()) }
    var savedBanner by remember { mutableStateOf(false) }

    fun instantSave(
        wifi: Boolean = useWifiOnly,
        ipv6: Boolean = allowIpv6,
        randPort: Boolean = useRandomPort,
        stun: Boolean = enableStunIce,
        rport: Boolean = enableRportSym,
        dnd: Boolean = dndEnabled,
        autoAns: Boolean = autoAnswer,
        reconnect: Boolean = true
    ) {
        val ka = keepAlive.toIntOrNull() ?: 15
        SipManager.updateAdvancedSettings(wifi, ipv6, randPort, stun, rport, dnd, autoAns, stunServer, ka, reconnectNow = reconnect)
        savedBanner = true
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Network (Teletalk SIM & Wi-Fi) • Auto-Save ON", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)

        if (savedBanner) {
            Text("✓ Saved permanently! Your customization will stay active even after closing the app.", color = Color(0xFF2ECC71), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        SettingToggleCard(
            title = "Use WiFi only",
            subtitle = "Keep OFF so calls work on Teletalk SIM Mobile Data (MB) as well as Wi-Fi",
            checked = useWifiOnly,
            onCheckedChange = { instantSave(wifi = it) }
        )

        SettingToggleCard(
            title = "Allow IPv6",
            subtitle = "Keep ON for Teletalk SIM IPv6/IPv4 dual-stack mobile internet compatibility",
            checked = allowIpv6,
            onCheckedChange = { instantSave(ipv6 = it) }
        )

        SettingToggleCard(
            title = "Use random ports",
            subtitle = "Auto-detects working UDP port across Registered & Ephemeral ranges",
            checked = useRandomPort,
            onCheckedChange = { instantSave(randPort = it) }
        )

        SettingToggleCard(
            title = "STUN & ICE NAT Traversal",
            subtitle = "Keep OFF for direct Teletalk SIM routing (like jiPBX); turn ON only if required",
            checked = enableStunIce,
            onCheckedChange = { instantSave(stun = it) }
        )

        SettingToggleCard(
            title = "Symmetric RTP & Force Rport",
            subtitle = "Keeps incoming/outgoing voice packets aligned through SIM CGNAT",
            checked = enableRportSym,
            onCheckedChange = { instantSave(rport = it) }
        )

        HorizontalDivider(color = GlassBorderColor)
        Text("Call Handling Features", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)

        SettingToggleCard(
            title = "Do Not Disturb (DND)",
            subtitle = "Automatically reject all incoming calls when enabled",
            checked = dndEnabled,
            onCheckedChange = { instantSave(dnd = it, reconnect = false) }
        )

        SettingToggleCard(
            title = "Auto-Answer Incoming Calls",
            subtitle = "Automatically pick up incoming SIP calls hands-free",
            checked = autoAnswer,
            onCheckedChange = { instantSave(autoAns = it, reconnect = false) }
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
            onClick = { instantSave(reconnect = true) },
            colors = ButtonDefaults.buttonColors(containerColor = AccentRose),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("Save STUN / Keep-Alive & Reconnect", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        HorizontalDivider(color = GlassBorderColor)
        Text("HD Audio Codecs (Auto-Saved)", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)

        audioCodecs.forEach { codec ->
            SettingToggleCard(
                title = "${codec.mime} (${codec.clockRate} Hz)",
                subtitle = if (codec.mime.equals("opus", true) || codec.mime.equals("G722", true)) "HD Wideband Crystal Clear Voice" else "Standard Telecom Codec",
                checked = codec.enabled,
                onCheckedChange = {
                    SipManager.toggleAudioCodec(codec.mime, codec.clockRate, it)
                    savedBanner = true
                }
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
        Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, color = Color.LightGray)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
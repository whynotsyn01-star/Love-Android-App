package com.love.calling

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Love App Blue & White Light Theme Palette
private val Blue = Color(0xFF3478F6)
private val Ink = Color(0xFF17243A)
private val Muted = Color(0xFF7C8798)
private val Page = Color(0xFFF5F7FB)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LoveApp() }
    }
}

private enum class Tab { Home, Contacts, Dialpad, Settings }

@Composable
private fun LoveApp() {
    var tab by remember { mutableStateOf(Tab.Home) }
    var number by remember { mutableStateOf("") }
    var callPreview by remember { mutableStateOf(false) }
    var groupPreview by remember { mutableStateOf(false) }
    val context = LocalContext.current

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Blue,
            background = Page,
            surface = Color.White
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = Page) {
            if (callPreview) {
                CallPreview(group = groupPreview) { callPreview = false }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            Tab.Home -> HomeScreen(
                                onDial = { tab = Tab.Dialpad },
                                onGroup = { groupPreview = true; callPreview = true }
                            )
                            Tab.Contacts -> ContactsScreen {
                                callPreview = true
                                groupPreview = false
                            }
                            Tab.Dialpad -> DialpadScreen(
                                number = number,
                                onNumber = { number = it },
                                onAppCall = { groupPreview = false; callPreview = true },
                                onSimCall = {
                                    if (number.isNotBlank()) {
                                        val intent = Intent(
                                            Intent.ACTION_DIAL,
                                            Uri.parse("tel:${Uri.encode(number)}")
                                        )
                                        try {
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            // Handle cases where dialer intent fails
                                        }
                                    }
                                }
                            )
                            Tab.Settings -> SettingsScreen()
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
private fun HomeScreen(onDial: () -> Unit, onGroup: () -> Unit) {
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
                            Text("Your calling space", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("Personal voice calling", color = Muted, fontSize = 12.sp)
                        }
                        Surface(color = Color(0xFFFFF3D9), shape = CircleShape) {
                            Text(
                                " Not connected ",
                                color = Color(0xFF946200),
                                modifier = Modifier.padding(7.dp),
                                fontSize = 10.sp
                            )
                        }
                    }
                    Text("Connect a SIP service to make real internet calls.", color = Muted, fontSize = 13.sp)
                    Button(onClick = onDial, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Dialpad, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open dialpad")
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Quick actions", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickCard("Dial a number", Icons.Default.Call, Modifier.weight(1f), onDial)
                QuickCard("Group call", Icons.Default.Groups, Modifier.weight(1f), onGroup)
            }
            Spacer(Modifier.height(20.dp))
            Text("Recent calls", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar("L")
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("No calls yet", fontWeight = FontWeight.SemiBold)
                        Text("Your recent calls will appear here.", color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    action: () -> Unit
) {
    Card(
        modifier
            .height(105.dp)
            .clickable { action() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(icon, contentDescription = title, tint = Blue)
            Text(title, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ContactsScreen(onCall: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header("People")
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(listOf("Alex Morgan", "Maya Rahman", "Sam Wilson")) { person ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(person.take(1))
                        Spacer(Modifier.width(12.dp))
                        Text(person, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = onCall) {
                            Icon(Icons.Default.Call, contentDescription = "Call preview", tint = Blue)
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
            if (number.isBlank()) "Enter number or SIP address" else number,
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    row.forEach { (digit, letters) ->
                        Surface(
                            Modifier
                                .size(76.dp)
                                .clickable { onNumber(number + digit) },
                            shape = CircleShape,
                            color = Color.White,
                            shadowElevation = 1.dp
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
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
                    Icon(Icons.Default.Call, contentDescription = "Open SIM dialer", tint = Ink)
                }
                FilledIconButton(
                    onClick = onAppCall,
                    modifier = Modifier.size(62.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF20B56B))
                ) {
                    Icon(Icons.Default.Call, contentDescription = "App call", tint = Color.White)
                }
                IconButton(onClick = { onNumber(number.dropLast(1)) }) {
                    Icon(Icons.Default.Backspace, contentDescription = "Delete", tint = Ink)
                }
            }
            Text(
                "App call is a preview until a calling server is connected.",
                color = Muted,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun CallPreview(group: Boolean, onEnd: () -> Unit) {
    var muted by remember { mutableStateOf(false) }
    var speaker by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(45.dp))
        Text(if (group) "Group call preview" else "Call preview", color = Muted)
        Spacer(Modifier.height(12.dp))
        Text(if (group) "Love Group" else "Demo contact", fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("Demo only • No audio is connected", color = Color(0xFFB7791F), fontSize = 12.sp)
        Spacer(Modifier.height(36.dp))

        if (group) {
            listOf("You", "Alex", "Maya", "Sam").forEach { person ->
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(person.take(1))
                    Spacer(Modifier.width(12.dp))
                    Text(person, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(onClick = { muted = !muted }) {
                    Icon(
                        if (muted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (muted) "Unmute" else "Mute"
                    )
                }
                Text(if (muted) "Muted" else "Mute", fontSize = 11.sp, color = Muted)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(onClick = { speaker = !speaker }) {
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription = if (speaker) "Speaker off" else "Speaker on"
                    )
                }
                Text(if (speaker) "Speaker on" else "Speaker", fontSize = 11.sp, color = Muted)
            }
        }

        Spacer(Modifier.height(25.dp))

        FilledIconButton(
            onClick = onEnd,
            modifier = Modifier.size(68.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE5484D))
        ) {
            Icon(Icons.Default.CallEnd, contentDescription = "End preview", tint = Color.White)
        }
        Text("End preview", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
    }
}

@Composable
private fun SettingsScreen() {
    Column(Modifier.fillMaxSize()) {
        Header("Preferences")
        listOf(
            "SIP account" to "Not configured",
            "Network" to "Wi-Fi and mobile data support planned",
            "Audio" to "HD audio integration planned",
            "Version" to "Love 1.0.1 • UI build"
        ).forEach { (title, detail) ->
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp),
                shape = RoundedCornerShape(15.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(detail, color = Muted, fontSize = 12.sp)
                }
            }
        }
        Card(
            Modifier.padding(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF5E6))
        ) {
            Text(
                "Real internet calling, group audio and PSTN calling require a configured calling server. This version is a UI prototype.",
                Modifier.padding(16.dp),
                color = Color(0xFF805500),
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun Avatar(letter: String) {
    Surface(Modifier.size(46.dp), shape = CircleShape, color = Color(0xFFE9F0FF)) {
        Box(contentAlignment = Alignment.Center) {
            Text(letter.take(1), color = Blue, fontWeight = FontWeight.Bold)
        }
    }
}

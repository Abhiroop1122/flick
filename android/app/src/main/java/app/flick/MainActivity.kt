package app.flick

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flick.core.AskResult
import app.flick.core.Receiver
import app.flick.core.TransferState
import app.flick.ui.AddByIpDialog
import app.flick.ui.Brand
import app.flick.ui.FlickTheme
import app.flick.ui.HintRow
import app.flick.ui.Radar
import app.flick.ui.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var app: FlickApp

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        app = FlickApp.get(this)
        setContent { FlickTheme { Home(app) } }
    }

    override fun onStart() {
        super.onStart()
        app.discovery.acquire()
        LinkService.start(this)
    }

    override fun onStop() { super.onStop(); app.discovery.release() }

    // Flick has focus right now, so it may read the clipboard: send anything new to the computer.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && app.store.sendClipboard.value) ClipShare.send(this, ClipShare.read(this), manual = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Home(app: FlickApp) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by app.discovery.receivers.collectAsStateWithLifecycle()
    val known by app.store.known.collectAsStateWithLifecycle()
    val onLan by app.discovery.onLan.collectAsStateWithLifecycle()
    val phoneName by app.store.phoneName.collectAsStateWithLifecycle()
    val sending by TransferHub.states.collectAsStateWithLifecycle(emptyMap())
    val links by LinkHub.states.collectAsStateWithLifecycle()
    val stay by app.store.stayConnected.collectAsStateWithLifecycle()
    val clip by app.store.clipboardSync.collectAsStateWithLifecycle()
    val auto by app.store.autoAcceptFiles.collectAsStateWithLifecycle()
    val sendClip by app.store.sendClipboard.collectAsStateWithLifecycle()
    var clipSetup by remember { mutableStateOf(false) }
    var crash by remember { mutableStateOf(CrashLog.pending(ctx)) }

    var target by rememberSaveable { mutableStateOf<String?>(null) } // computer chosen before picking files
    var sheetFor by remember { mutableStateOf<Known?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val pairing = remember { mutableStateMapOf<String, Boolean>() }
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { resumeTick++; onPauseOrDispose { } }
    val batteryOk = remember(resumeTick) { LinkService.ignoringBatteryOptimizations(ctx) }
    val clipHelperOn = remember(resumeTick) { ClipWatchService.isEnabled(ctx) }

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
    fun receiverFor(id: String?): Receiver? = live.firstOrNull { it.id == id } ?: id?.let { app.store.get(it)?.toReceiver() }

    fun sendUris(uris: List<Uri>) {
        val r = receiverFor(target) ?: return
        if (uris.isEmpty()) return
        scope.launch {
            val items = withContext(Dispatchers.IO) { Payloads.describe(ctx.contentResolver, uris) }
            TransferService.send(ctx, r, items, null)
        }
    }

    fun connect(k: Known) {
        if (pairing[k.id] == true) return
        pairing[k.id] = true
        scope.launch {
            val r = receiverFor(k.id) ?: return@launch
            val res = runCatching { app.client.pair(r, app.store.sender(k.id)) }.getOrElse { AskResult.Failed(0, it.message ?: "") }
            pairing.remove(k.id)
            when (res) {
                is AskResult.TextDelivered -> {
                    val key = res.trustKey
                    if (key == null) toast("Update Flick on ${k.name}, then try again.")
                    else {
                        app.store.setTrustKey(k.id, key)
                        if (!app.store.stayConnected.value) app.store.setStayConnected(true)
                        LinkService.start(ctx)
                        toast("Connected to ${k.name}")
                    }
                }
                AskResult.Declined -> toast("${k.name} didn't allow it.")
                AskResult.TimedOut -> toast("Nobody answered on ${k.name}.")
                is AskResult.Failed -> toast(
                    if (res.code == 400) "Update Flick on ${k.name} to the latest version, then try again."
                    else "Couldn't reach ${k.name}. Is it on the same Wi-Fi with Flick turned on?",
                )
                else -> toast("Couldn't connect to ${k.name}.")
            }
        }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { sendUris(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        // Keep read access even if this screen closes mid-send; released when the send finishes.
        uris.forEach { u -> runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        sendUris(uris)
    }

    // Notifications show transfers and incoming files (Android 13+); storage is needed on Android 8–9.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    val liveIds = live.map { it.id }.toSet()
    val computers = (known + live.filter { r -> known.none { it.id == r.id } }
        .map { Known(it.id, it.name, it.os, it.host, it.port, it.fingerprint, it.lastSeen) })
        .sortedWith(compareByDescending<Known> { it.trustKey != null }.thenByDescending { it.id in liveIds }.thenByDescending { maxOf(it.lastUsed, it.lastSeen) })
    val pairedCount = computers.count { it.trustKey != null }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = insets.calculateTopPadding() + 20.dp, bottom = insets.calculateBottomPadding() + 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Brand.gradient), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_flick_glyph), null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Flick", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("Your phone ⇄ your Mac or PC", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                Card {
                    Row(Modifier.clickable { renaming = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("This phone appears as", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(phoneName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(painterResource(R.drawable.ic_edit), "Rename", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            }

            item { SectionLabel("Your computers", Modifier.padding(start = 4.dp, top = 6.dp)) }
            item {
                Card {
                    Column {
                        if (computers.isEmpty()) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Radar()
                                Text("Looking for computers…", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    if (onLan) "Open Flick on your Mac or PC on this Wi-Fi." else "Connect to Wi-Fi to find your computer.",
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        computers.forEachIndexed { i, k ->
                            ComputerRow(
                                k = k,
                                isLive = k.id in liveIds,
                                link = links[k.id],
                                sending = sending[k.id],
                                stay = stay,
                                clip = clip,
                                pairing = pairing[k.id] == true,
                                onClick = { sheetFor = k },
                                onConnect = { connect(k) },
                            )
                            if (i < computers.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 72.dp))
                        }
                        TextButton(onClick = { showAdd = true }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp)) {
                            Text("Add a computer by IP address")
                        }
                    }
                }
            }

            if (stay && pairedCount > 0 && !batteryOk) {
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Let Flick stay connected", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(
                                "Android may pause Flick to save battery, and then your computer can't reach this phone. Allow Flick to run in the background — it uses almost no battery while idle.",
                                fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            FilledTonalButton(onClick = {
                                runCatching {
                                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                                }.onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                            }) { Text("Allow") }
                        }
                    }
                }
            }

            item { SectionLabel("From your computer", Modifier.padding(start = 4.dp, top = 6.dp)) }
            item {
                Card {
                    Column {
                        SettingRow("Stay connected", "Your connected computers can send files and their clipboard to this phone any time.", stay) {
                            app.store.setStayConnected(it)
                            LinkService.start(ctx)
                        }
                        if (stay) {
                            // Android requires a notification while Flick stays connected; this switches it off (Flick keeps running).
                            TextButton(onClick = {
                                runCatching {
                                    ctx.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                        .putExtra(Settings.EXTRA_CHANNEL_ID, LinkService.CHANNEL_CONNECTION))
                                }
                            }, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) { Text("Hide the “Connected” notification") }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 16.dp))
                        SettingRow("Get what I copy on my computer", "Copy text or a link on your computer, paste it here.", clip, enabled = stay) {
                            app.store.setClipboardSync(it)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 16.dp))
                        SettingRow(
                            "Send what I copy on this phone",
                            when {
                                !sendClip -> "Copy on this phone, paste on your computer."
                                clipHelperOn -> "On — copy anything here, paste it on your computer."
                                else -> "Tap to switch it on (one-time setup)."
                            },
                            sendClip, enabled = stay,
                            onRowClick = { if (sendClip && !clipHelperOn) clipSetup = true else app.store.setSendClipboard(!sendClip) },
                        ) {
                            app.store.setSendClipboard(it)
                            if (it && !clipHelperOn) clipSetup = true
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 16.dp))
                        SettingRow("Accept files automatically", "Files from your connected computers are saved without asking.", auto, enabled = stay) {
                            app.store.setAutoAcceptFiles(it)
                        }
                    }
                }
            }

            item { SectionLabel("How it works", Modifier.padding(start = 4.dp, top = 6.dp)) }
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Step(1, "Install Flick on your Mac or PC and open it once. It stays on in the background.")
                        Step(2, "Phone → computer: tap Share in any app and choose Flick, then tap your computer.")
                        Step(3, "Tap Connect next to your computer above. Then it can send to this phone too.")
                        Step(4, "Computer → phone: right-click files → Send to → Phone (Flick) on Windows, or Quick Actions → Send with Flick on a Mac.")
                        Step(5, "Copy text or a link on either one, and paste it on the other.")
                    }
                }
            }
            item {
                Text(
                    "Flick ${appVersion(ctx)} · everything goes straight over your Wi-Fi, encrypted, never through the internet.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp),
                )
            }
        }
    }

    sheetFor?.let { k ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val reachable = k.id in liveIds || links[k.id] == LinkState.CONNECTED
        ModalBottomSheet(onDismissRequest = { sheetFor = null }, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(k.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                if (reachable) {
                    PickRow(R.drawable.ic_photos, "Send photos & videos") {
                        target = k.id; sheetFor = null
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }
                    PickRow(R.drawable.ic_folder, "Send files") {
                        target = k.id; sheetFor = null
                        pickFiles.launch(arrayOf("*/*"))
                    }
                } else {
                    HintRow(R.drawable.ic_wifi_off, "${k.name} isn't on this Wi-Fi right now (or Flick is off on it).")
                }
                if (k.trustKey == null && reachable) {
                    PickRow(R.drawable.ic_link, "Connect, so it can send to this phone") { sheetFor = null; connect(k) }
                }
                if (k.trustKey != null) {
                    PickRow(R.drawable.ic_close, "Disconnect") {
                        app.store.clearTrust(k.id); LinkService.start(ctx); sheetFor = null
                    }
                }
                PickRow(R.drawable.ic_trash, "Forget this computer") {
                    app.store.forget(k.id); Shortcuts.remove(ctx, k.id); LinkService.start(ctx); sheetFor = null
                }
            }
        }
    }

    if (clipSetup) {
        AlertDialog(
            onDismissRequest = { clipSetup = false },
            title = { Text("Send what you copy, automatically") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Android only lets the keyboard read your clipboard in the background. To send copies by itself, Flick needs its " +
                            "helper switched on in Accessibility settings. It only notices the moment you tap Copy — it doesn't read, keep or send anything else on your screen.",
                        fontSize = 14.sp, lineHeight = 20.sp,
                    )
                    Text("1. Tap Open settings → Flick — send what I copy (it may be under Installed or Downloaded apps) → turn it on.", fontSize = 14.sp, lineHeight = 20.sp)
                    Text("2. If it's greyed out (“Restricted setting”): tap App info → ⋮ (top right) → Allow restricted settings, then do step 1 again.", fontSize = 14.sp, lineHeight = 20.sp)
                    Text(
                        "Each time, Android shows “Flick pasted from your clipboard” — that's Flick sending it. Passwords are never sent.",
                        fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { clipSetup = false; ClipWatchService.openAccessibilitySettings(ctx) }) { Text("Open settings") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { ClipWatchService.openAppInfo(ctx) }) { Text("App info") }
                    TextButton(onClick = { clipSetup = false }) { Text("Later") }
                }
            },
        )
    }

    crash?.let { report ->
        AlertDialog(
            onDismissRequest = { CrashLog.clear(ctx); crash = null },
            title = { Text("Flick stopped unexpectedly") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Sorry about that. Tap Copy details and paste them where you report the problem, so it can be fixed.", fontSize = 14.sp, lineHeight = 20.sp)
                    Text(
                        report, fontSize = 11.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Flick crash details", report)) }
                    CrashLog.clear(ctx); crash = null
                    toast("Copied. Paste it in your message.")
                }) { Text("Copy details") }
            },
            dismissButton = { TextButton(onClick = { CrashLog.clear(ctx); crash = null }) { Text("Dismiss") } },
        )
    }

    if (showAdd) AddByIpDialog(onDismiss = { showAdd = false }, onAdd = { app.discovery.addByAddress(it) })

    if (renaming) {
        var name by remember { mutableStateOf(phoneName) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Phone name") },
            text = {
                OutlinedTextField(name, { name = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Shown on your computer.") })
            },
            confirmButton = { TextButton(onClick = { app.store.setPhoneName(name); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ComputerRow(
    k: Known, isLive: Boolean, link: LinkState?, sending: TransferState?, stay: Boolean, clip: Boolean, pairing: Boolean,
    onClick: () -> Unit, onConnect: () -> Unit,
) {
    val paired = k.trustKey != null
    val connected = paired && link == LinkState.CONNECTED
    val green = Brand.Ok
    val status = when {
        sending is TransferState.Sending -> sending.fraction?.let { "Sending ${(it * 100).toInt()}%" } ?: "Sending…"
        sending == TransferState.WaitingForAccept -> "Waiting for you to accept on the computer…"
        connected -> if (clip) "Connected · files & clipboard" else "Connected · files"
        paired && !stay -> "Paired · staying connected is off"
        paired && link == LinkState.CONNECTING -> "Connecting…"
        paired && isLive -> "On this Wi-Fi · reconnecting…"
        paired -> "Paired · not nearby"
        isLive -> "On this Wi-Fi"
        else -> "Not nearby"
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Box(
                Modifier.size(42.dp).clip(CircleShape).then(if (connected) Modifier.background(Brand.gradient) else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(if (k.os == "mac") R.drawable.ic_laptop else R.drawable.ic_desktop), null,
                    tint = if (connected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp),
                )
            }
            if (connected || isLive) {
                Box(
                    Modifier.size(13.dp).align(Alignment.BottomEnd).offset(x = 1.dp, y = 1.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface).padding(2.dp).clip(CircleShape)
                        .background(if (connected) green else MaterialTheme.colorScheme.outline),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(k.name, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, fontSize = 12.sp, color = if (connected) green else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            !paired && isLive -> FilledTonalButton(onClick = onConnect, enabled = !pairing, contentPadding = PaddingValues(horizontal = 14.dp)) {
                if (pairing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Connect")
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String, sub: String, checked: Boolean, enabled: Boolean = true,
    onRowClick: (() -> Unit)? = null, onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onRowClick?.invoke() ?: onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(sub, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        content()
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun PickRow(icon: Int, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

private fun appVersion(ctx: android.content.Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""

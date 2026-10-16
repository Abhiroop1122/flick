package app.flick

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flick.core.Receiver
import app.flick.core.TransferState
import app.flick.ui.AddByIpDialog
import app.flick.ui.DeviceBubble
import app.flick.ui.FlickTheme
import app.flick.ui.HintRow
import app.flick.ui.PreviewStack
import app.flick.ui.Radar
import app.flick.ui.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Appears when you pick "Flick" in any app's share menu.
 * Shows nearby computers; tap one and it pops up on that computer for you to accept.
 */
class ShareActivity : ComponentActivity() {
    private lateinit var app: FlickApp
    /** Computers the person sent to from this sheet. */
    private val tapped = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        app = FlickApp.get(this)
        val incoming = Payloads.fromIntent(intent)
        if (incoming.items.isEmpty() && incoming.text == null) {
            Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val direct = Shortcuts.receiverIdFrom(intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID))
        if (savedInstanceState == null) TransferHub.clearFinished()
        setContent { FlickTheme { ShareSheet(app, incoming, direct, tapped, onClose = ::requestClose) } }
    }

    /**
     * Android ties read access to shared files to this screen and revokes it when it closes.
     * If sends are still running, open the remaining files first: an open file stays readable,
     * so the send carries on in the background.
     */
    private fun requestClose() {
        val running = tapped.filter { TransferHub.isBusy(it) }
        if (running.isEmpty()) { finish(); return }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { running.forEach { TransferHub.active.value[it]?.items?.forEach(Shared::preopen) } }
            finish()
        }
    }

    override fun onStart() { super.onStart(); app.discovery.acquire() }
    override fun onStop() { super.onStop(); app.discovery.release() }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ShareSheet(app: FlickApp, incoming: Incoming, directId: String?, tapped: MutableList<String>, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { onClose() } }

    val items by produceState<List<Shared>?>(null) {
        value = withContext(Dispatchers.IO) { Payloads.describe(ctx.contentResolver, incoming.items) }
    }
    val thumbs by produceState<List<ImageBitmap>>(emptyList(), items) {
        val list = items ?: return@produceState
        value = withContext(Dispatchers.IO) {
            list.filter { it.isVisual }.take(3).mapNotNull { Payloads.thumbnail(ctx.contentResolver, it, 200)?.asImageBitmap() }
        }
    }
    val live by app.discovery.receivers.collectAsStateWithLifecycle()
    val onLan by app.discovery.onLan.collectAsStateWithLifecycle()
    val active by TransferHub.active.collectAsStateWithLifecycle()
    val states by TransferHub.states.collectAsStateWithLifecycle(emptyMap())
    var showAdd by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(5_000); slow = true }

    // Computers on screen: everything live, plus anything we're sending to that dropped off the list.
    val shown: List<Receiver> = remember(live, active) {
        live + active.values.map { it.receiver }.filter { r -> live.none { it.id == r.id } && r.id in tapped }
    }

    fun send(r: Receiver) {
        val list = items ?: return
        if (TransferHub.isBusy(r.id)) return
        if (r.id !in tapped) tapped += r.id
        TransferService.send(ctx, r, list, incoming.text)
    }

    // Direct Share: the person picked a specific computer in the system share sheet.
    var autoSent by remember { mutableStateOf(false) }
    LaunchedEffect(items, live) {
        if (directId == null || autoSent || items == null) return@LaunchedEffect
        live.firstOrNull { it.id == directId }?.let { autoSent = true; send(it) }
    }
    LaunchedEffect(items) {
        if (directId == null || items == null) return@LaunchedEffect
        delay(2_500) // not seen live yet: fall back to its last known address
        if (!autoSent) app.store.get(directId)?.let { autoSent = true; send(it.toReceiver()) }
    }

    // When everything we sent has arrived, tuck the sheet away.
    val mine = tapped.mapNotNull { states[it] }
    LaunchedEffect(mine) {
        if (mine.isNotEmpty() && mine.all { it is TransferState.Done }) { delay(1_300); close() }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).animateContentSize()) {
            // What's being sent
            Row(verticalAlignment = Alignment.CenterVertically) {
                val list = items.orEmpty()
                val icon = when {
                    list.isEmpty() && incoming.text != null -> if (Payloads.describeLabel(list, incoming.text) == "a link") R.drawable.ic_link else R.drawable.ic_text
                    list.all { it.mime.startsWith("video/") } -> R.drawable.ic_video
                    list.all { it.mime.startsWith("image/") } -> R.drawable.ic_image
                    else -> R.drawable.ic_file
                }
                PreviewStack(thumbs, icon, list.size)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    val label = if (items == null) "Preparing…" else Payloads.describeLabel(list, incoming.text)
                    Text(label.replaceFirstChar { it.uppercase() }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    val sub = when {
                        list.isNotEmpty() -> {
                            val total = list.sumOf { maxOf(0L, it.size) }
                            listOfNotNull(Format.size(total).takeIf { total > 0 }, list.first().name.takeIf { list.size == 1 }).joinToString(" · ")
                        }
                        else -> incoming.text.orEmpty()
                    }
                    if (sub.isNotBlank()) Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(14.dp))
            SectionLabel("Send to a computer")
            Spacer(Modifier.height(6.dp))

            if (shown.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Radar()
                    Text("Looking for computers…", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Open Flick on your Mac or PC.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(slow, enter = fadeIn()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                        HintRow(
                            if (onLan) R.drawable.ic_desktop else R.drawable.ic_wifi_off,
                            if (onLan) "Not showing up? Make sure the computer is awake, Flick is running, and both are on the same Wi-Fi."
                            else "You're not on Wi-Fi. Connect to the same network as your computer.",
                        )
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(onClick = { showAdd = true }) { Text("Add a computer by IP address") }
                        }
                    }
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (r in shown) {
                        val st = states[r.id]?.takeIf { r.id in tapped }
                        DeviceBubble(r, st, onClick = { send(r) })
                    }
                }
                val failed = tapped.mapNotNull { states[it] as? TransferState.Failed }.lastOrNull()
                if (failed != null) {
                    Spacer(Modifier.height(8.dp))
                    HintRow(R.drawable.ic_close, TransferService.failText(failed.reason))
                }
                val sending = tapped.any { states[it]?.finished == false }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showAdd = true }) { Text("Add by IP") }
                    if (sending) TextButton(onClick = close) { Text("Hide") }
                }
            }
        }
    }

    if (showAdd) AddByIpDialog(onDismiss = { showAdd = false }, onAdd = { app.discovery.addByAddress(it) })
}

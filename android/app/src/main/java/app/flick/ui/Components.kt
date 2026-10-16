package app.flick.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flick.R
import app.flick.core.FailReason
import app.flick.core.Receiver
import app.flick.core.TransferState
import kotlinx.coroutines.launch

/** A computer, AirDrop-style: round avatar, name, and a live status ring. */
@Composable
fun DeviceBubble(r: Receiver, state: TransferState?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val busy = state != null && !state.finished
    Column(
        modifier
            .width(96.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = !busy, onClick = onClick)
            .padding(top = 8.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
            ProgressRing(state, Modifier.fillMaxSize())
            val pressedScale by animateFloatAsState(if (busy) 0.92f else 1f, spring(dampingRatio = 0.5f), label = "s")
            val bg = when (state) {
                is TransferState.Done -> Modifier.background(Brand.Ok)
                is TransferState.Declined, is TransferState.Failed -> Modifier.background(Brand.Bad)
                else -> Modifier.background(Brand.gradient)
            }
            Box(
                Modifier.size(64.dp).scale(pressedScale).clip(CircleShape).then(bg),
                contentAlignment = Alignment.Center,
            ) {
                val icon = when (state) {
                    is TransferState.Done -> R.drawable.ic_check
                    is TransferState.Declined, is TransferState.Failed, is TransferState.Cancelled -> R.drawable.ic_close
                    else -> if (r.isMac) R.drawable.ic_laptop else R.drawable.ic_desktop
                }
                AnimatedContent(icon, transitionSpec = { (scaleIn() + fadeIn()) togetherWith fadeOut() }, label = "icon") { res ->
                    Icon(painterResource(res), contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            r.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 4.dp),
        )
        val (label, color) = statusLabel(r, state)
        Text(label, fontSize = 11.sp, color = color, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun statusLabel(r: Receiver, s: TransferState?): Pair<String, Color> {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    return when (s) {
        null -> (if (r.isMac) "Mac" else if (r.isWindows) "Windows PC" else "Computer") to muted
        TransferState.Connecting -> "Connecting…" to accent
        TransferState.WaitingForAccept -> "Waiting…" to accent
        is TransferState.Sending -> (s.fraction?.let { "Sending ${(it * 100).toInt()}%" } ?: "Sending…") to accent
        is TransferState.Done -> "Sent" to Brand.Ok
        TransferState.Declined -> "Declined" to Brand.Bad
        TransferState.Cancelled -> "Cancelled" to muted
        is TransferState.Failed -> when (s.reason) {
            FailReason.UNREACHABLE -> "Not reachable"
            FailReason.NO_ANSWER -> "No answer"
            FailReason.NO_SPACE -> "Disk full"
            FailReason.BUSY -> "Busy"
            FailReason.SECURITY -> "Security error"
            FailReason.INTERRUPTED -> "Stopped"
            FailReason.FILE_ACCESS -> "Can't read file"
            FailReason.UNKNOWN -> "Failed"
        } to Brand.Bad
    }
}

@Composable
private fun ProgressRing(state: TransferState?, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "a")
    val target = (state as? TransferState.Sending)?.fraction ?: if (state is TransferState.Done) 1f else 0f
    val sweep by animateFloatAsState(target * 360f, tween(250), label = "sweep")
    Canvas(modifier) {
        val w = 3.5.dp.toPx()
        val inset = w / 2 + 1.dp.toPx()
        val tl = androidx.compose.ui.geometry.Offset(inset, inset)
        val sz = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
        when {
            state is TransferState.Sending && state.fraction != null -> {
                drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(w))
                drawArc(primary, -90f, sweep, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
            }
            state is TransferState.Done -> drawArc(Brand.Ok, -90f, 360f, false, tl, sz, style = Stroke(w))
            state != null && !state.finished -> {
                drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(w))
                drawArc(primary, angle - 90f, 80f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
    }
}

/** Pulsing rings while we look for computers. */
@Composable
fun Radar(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "radar")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "p")
    val color = MaterialTheme.colorScheme.primary
    Box(modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            for (i in 0 until 3) {
                val p = (phase + i / 3f) % 1f
                drawCircle(color.copy(alpha = (1f - p) * 0.28f), radius = size.minDimension / 2 * (0.32f + p * 0.68f), style = Stroke(2.dp.toPx()))
            }
        }
        Box(Modifier.size(54.dp).clip(CircleShape).background(Brand.gradient), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_phone), null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }
}

/** Up to three thumbnails fanned out like a stack of photos. */
@Composable
fun PreviewStack(thumbs: List<ImageBitmap>, fallbackIcon: Int, count: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(64.dp), contentAlignment = Alignment.Center) {
        if (thumbs.isEmpty()) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Brand.gradient), contentAlignment = Alignment.Center) {
                Icon(painterResource(fallbackIcon), null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        } else {
            val angles = listOf(0f, -8f, 8f)
            thumbs.take(3).reversed().forEachIndexed { i, img ->
                val idx = thumbs.take(3).size - 1 - i
                Image(
                    img, null, contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(54.dp)
                        .rotate(angles[idx])
                        .offset(x = (idx * 3).dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(2.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
                )
            }
        }
        if (count > 1) {
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface).padding(horizontal = 7.dp, vertical = 2.dp),
            ) { Text("$count", color = MaterialTheme.colorScheme.surface, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** Dialog to add a computer by its IP address (for networks that block discovery). */
@Composable
fun AddByIpDialog(onDismiss: () -> Unit, onAdd: suspend (String) -> String?) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a computer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Open Flick on the computer and type the Wi-Fi address shown at the bottom of its window.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it.trim(); error = null },
                    placeholder = { Text("192.168.1.20") }, singleLine = true, isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    error = onAdd(text)
                    busy = false
                    if (error == null) onDismiss()
                }
            }) { Text(if (busy) "Checking…" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier = modifier, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun HintRow(icon: Int, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

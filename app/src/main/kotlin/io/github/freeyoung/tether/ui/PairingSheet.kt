package io.github.freeyoung.tether.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.freeyoung.tether.R
import io.github.freeyoung.tether.Snapshot
import io.github.freeyoung.tether.Tether

// The pairing sheet of the iPhone app. It shows while a device asks to pair,
// or while this phone waits for a computer to approve it.
@Composable
fun PairingSheet(s: Snapshot) {
    val request = s.pending.firstOrNull()
    val waitingFor = s.outgoing.firstOrNull()
    if (request == null && waitingFor == null) return
    Dialog(
        onDismissRequest = { if (request == null) Tether.cancelPairing() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        val c = Tint.colors
        Column(
            Modifier
                .fillMaxSize()
                .background(c.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.padding(top = 4.dp).width(36.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.tertiary))
            Spacer(Modifier.weight(1f))
            Ring()
            Spacer(Modifier.size(32.dp))
            Text(stringResource(R.string.pairing_sheet_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label)
            Spacer(Modifier.size(12.dp))
            val text = if (request != null) {
                stringResource(if (request.kind == "phone") R.string.pairing_inbound_phone else R.string.pairing_inbound_computer, request.name)
            } else {
                stringResource(R.string.pairing_outbound, waitingFor!!)
            }
            Text(text, Modifier.padding(horizontal = 16.dp), fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center)
            Spacer(Modifier.size(32.dp))
            Text(
                stringResource(if (request != null) R.string.their_fingerprint else R.string.your_fingerprint).uppercase(),
                fontSize = 12.sp,
                color = c.secondary,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                colonFingerprint(request?.fingerprint ?: s.fingerprint),
                Modifier.clip(RoundedCornerShape(8.dp)).background(c.card).padding(horizontal = 16.dp, vertical = 10.dp),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = c.secondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (request != null) {
                    ProminentButton(stringResource(R.string.accept)) { Tether.approve(request.fingerprint) }
                    PlainButton(stringResource(R.string.reject)) { Tether.reject(request.fingerprint) }
                } else {
                    PlainButton(stringResource(android.R.string.cancel)) { Tether.cancelPairing() }
                }
            }
        }
    }
}

// A ring with a gradient that turns once in 3 seconds, around a lock.
@Composable
private fun Ring() {
    val c = Tint.colors
    val angle by rememberInfiniteTransition(label = "ring")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "angle")
    Box(Modifier.size(100.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(100.dp).rotate(angle)) {
            val stroke = 3.dp.toPx()
            drawCircle(
                Brush.sweepGradient(listOf(c.teal, c.teal.copy(alpha = 0.2f), c.teal)),
                radius = size.minDimension / 2 - stroke / 2,
                style = Stroke(stroke),
            )
        }
        Sym(R.drawable.sym_shield_lock, c.teal, 40.dp)
    }
}

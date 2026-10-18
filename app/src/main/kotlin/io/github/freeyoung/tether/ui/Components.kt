package io.github.freeyoung.tether.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.freeyoung.tether.Peer
import io.github.freeyoung.tether.R

// The parts the screens share, after the SwiftUI views of the iPhone app.

// Room below the content for the floating tab bar.
val TabBarClearance = 104.dp

@Composable
fun Sym(@DrawableRes id: Int, tint: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Icon(painterResource(id), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

// A screen with a large title, like a NavigationStack with a large title.
@Composable
fun Page(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Tint.colors.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = TabBarClearance),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            actions()
        }
        Text(
            title,
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = Tint.colors.label,
        )
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
    }
}

// The round group of toolbar buttons at the top right.
@Composable
fun ToolbarPill(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Tint.colors.card).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun ToolbarButton(@DrawableRes icon: Int, description: String, tint: Color = Tint.colors.teal, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(icon), contentDescription = description, tint = tint)
    }
}

// A rounded panel, like .ultraThinMaterial in a RoundedRectangle.
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    radius: Dp = 14.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Tint.colors.card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        content = content,
    )
}

// A white symbol on a gradient of one color.
@Composable
fun GradientBadge(
    @DrawableRes icon: Int,
    color: Color,
    size: Dp,
    iconSize: Dp,
    shape: Shape = CircleShape,
    glow: Boolean = false,
) {
    Box(
        Modifier
            .then(if (glow) Modifier.shadow(14.dp, shape, ambientColor = color, spotColor = color) else Modifier)
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.7f)))),
        contentAlignment = Alignment.Center,
    ) {
        Sym(icon, Color.White, iconSize)
    }
}

// The dot of ConnectionStatusView. It pulses while something is under way.
@Composable
fun StatusDot(color: Color, pulsing: Boolean, size: Dp = 10.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "phase")
    val p = if (pulsing) phase else 0f
    Box(
        Modifier
            .size(size)
            .scale(1f + 0.3f * p)
            .alpha(1f - 0.4f * p)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
fun StatusLine(color: Color, pulsing: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color, pulsing)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 15.sp, color = Tint.colors.secondary)
    }
}

// A heading over a group of panels, like "Quick Actions".
@Composable
fun SectionTitle(text: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Tint.colors.secondary)
        trailing()
    }
}

// A column of panels under a heading, 12 dp apart.
@Composable
fun Section(title: String, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(title, trailing)
        content()
    }
}

// What a screen shows when there is nothing to list.
@Composable
fun EmptyState(@DrawableRes icon: Int, title: String, subtitle: String? = null, iconSize: Dp = 36.dp, top: Dp = 32.dp) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = top, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Sym(icon, Tint.colors.tertiary, iconSize)
        Text(title, fontSize = 15.sp, color = Tint.colors.secondary, textAlign = TextAlign.Center)
        if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = Tint.colors.secondary, textAlign = TextAlign.Center)
    }
}

// A row of a panel: symbol, title and subtitle, then whatever trails.
@Composable
fun PanelRow(
    @DrawableRes icon: Int?,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> {
                leading()
                Spacer(Modifier.width(14.dp))
            }
            icon != null -> {
                Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) { Sym(icon, iconTint) }
                Spacer(Modifier.width(14.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Tint.colors.label)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = Tint.colors.secondary)
            subtitleContent?.invoke()
        }
        trailing()
    }
}

@Composable
fun Chevron() {
    Sym(R.drawable.sym_chevron_right, Tint.colors.tertiary, 20.dp)
}

// A grouped list section of the Settings screen: an uppercase header, rows
// with lines between them, and a footer.
@Composable
fun ListSection(header: String, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(header.uppercase(), Modifier.padding(start = 16.dp, bottom = 6.dp), fontSize = 13.sp, color = Tint.colors.secondary)
        Panel(radius = 12.dp, content = content)
        if (footer != null) {
            Text(footer, Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp), fontSize = 13.sp, color = Tint.colors.secondary)
        }
    }
}

@Composable
fun RowDivider(start: Dp = 60.dp) {
    HorizontalDivider(Modifier.padding(start = start), thickness = 0.5.dp, color = Tint.colors.separator)
}

// A plain "Label ... value" row of a list section.
@Composable
fun ValueRow(label: String, value: String, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 16.sp, color = Tint.colors.label)
        Text(value, fontSize = 16.sp, color = Tint.colors.secondary, fontFamily = if (monospace) FontFamily.Monospace else null)
    }
}

// A full-width filled button in the accent color.
@Composable
fun ProminentButton(text: String, color: Color = Tint.colors.teal, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color).clickable(onClick = onClick).padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

@Composable
fun PlainButton(text: String, color: Color = Tint.colors.secondary, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text, fontSize = 15.sp, color = color)
    }
}

// The symbol of a paired device.
@DrawableRes
fun iconOf(peer: Peer) = if (peer.isPhone) R.drawable.sym_smartphone else R.drawable.sym_computer

// The fingerprint as the iPhone app writes it: pairs of hex digits with
// colons, 11 pairs to a line, so that no line breaks inside a pair.
fun colonFingerprint(fingerprint: String) = fingerprint.chunked(2).chunked(11).joinToString(":\n") { it.joinToString(":") }

fun shortColonFingerprint(fingerprint: String) = fingerprint.take(16).chunked(2).joinToString(":") + "…"

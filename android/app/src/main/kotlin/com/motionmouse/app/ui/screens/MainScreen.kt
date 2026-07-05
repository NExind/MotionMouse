package com.motionmouse.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motionmouse.app.connection.ConnectionState
import com.motionmouse.app.connection.DiscoveredHost
import com.motionmouse.app.ui.MainUiState
import com.motionmouse.app.ui.theme.Connected
import com.motionmouse.app.ui.theme.Disconnected
import com.motionmouse.app.ui.theme.ErrorRed
import com.motionmouse.app.ui.theme.LeftClickColor
import com.motionmouse.app.ui.theme.MotionBlue
import com.motionmouse.app.ui.theme.MotionBlueDim
import com.motionmouse.app.ui.theme.RightClickColor
import com.motionmouse.app.ui.theme.Searching
import com.motionmouse.app.ui.theme.Surface1
import com.motionmouse.app.ui.theme.Surface2
import com.motionmouse.app.ui.theme.Surface3
import com.motionmouse.app.ui.theme.TextPrimary
import com.motionmouse.app.ui.theme.TextSecondary

/**
 * Main screen of Motion Mouse.
 *
 * Layout philosophy:
 *   The screen has two modes — disconnected and connected.
 *   In disconnected mode: status + discovered PC list fills the screen.
 *   In connected mode: status info at top, two large click buttons dominate,
 *   lock button accessible but not prominent.
 *
 *   The click buttons are intentionally HUGE — this is a physical
 *   controller and the buttons must be easy to press without looking
 *   at the screen. They should feel like physical buttons.
 *
 * This is a pure composable — all state comes from [uiState],
 * all events go through lambda callbacks. No ViewModel reference here.
 */
@Composable
fun MainScreen(
    uiState: MainUiState,
    onConnectTo: (DiscoveredHost) -> Unit,
    onDisconnect: () -> Unit,
    onLeftDown: () -> Unit,
    onLeftUp: () -> Unit,
    onRightDown: () -> Unit,
    onRightUp: () -> Unit,
    onToggleLock: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        AnimatedContent(
            targetState = uiState.isConnected,
            transitionSpec = {
                fadeIn(tween(300)) togetherWith fadeOut(tween(300))
            },
            label = "connection_state_transition"
        ) { isConnected ->
            if (isConnected) {
                ConnectedScreen(
                    uiState = uiState,
                    onDisconnect = onDisconnect,
                    onLeftDown = onLeftDown,
                    onLeftUp = onLeftUp,
                    onRightDown = onRightDown,
                    onRightUp = onRightUp,
                    onToggleLock = onToggleLock,
                    onOpenSettings = onOpenSettings
                )
            } else {
                DisconnectedScreen(
                    uiState = uiState,
                    onConnectTo = onConnectTo,
                    onOpenSettings = onOpenSettings
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Connected layout
// ---------------------------------------------------------------------------

@Composable
private fun ConnectedScreen(
    uiState: MainUiState,
    onDisconnect: () -> Unit,
    onLeftDown: () -> Unit,
    onLeftUp: () -> Unit,
    onRightDown: () -> Unit,
    onRightUp: () -> Unit,
    onToggleLock: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // --- Top bar ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Lock toggle
            IconButton(onClick = onToggleLock) {
                Icon(
                    imageVector = if (uiState.isLocked)
                        Icons.Default.Lock else Icons.Outlined.Lock,
                    contentDescription = if (uiState.isLocked) "Unlock cursor" else "Lock cursor",
                    tint = if (uiState.isLocked) MotionBlue else TextSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Settings
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = TextSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Click buttons at the TOP ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ClickButton(
                label = "LEFT",
                color = LeftClickColor,
                onDown = onLeftDown,
                onUp = onLeftUp,
                modifier = Modifier.weight(1f)
            )
            ClickButton(
                label = "RIGHT",
                color = Surface3,
                onDown = onRightDown,
                onUp = onRightUp,
                modifier = Modifier.weight(1f)
            )
        }

        // --- Status info ---
        StatusCard(uiState = uiState, onDisconnect = onDisconnect)

        Spacer(modifier = Modifier.height(24.dp))

        // --- Lock indicator ---
        AnimatedVisibility(visible = uiState.isLocked) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MotionBlueDim.copy(alpha = 0.3f))
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "CURSOR LOCKED",
                    style = MaterialTheme.typography.labelSmall,
                    color = MotionBlue,
                    letterSpacing = 2.sp
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

/**
 * A single large click button.
 *
 * Uses pointerInput with detectTapGestures to distinguish press (down)
 * from release (up) — essential for sending separate PRESS and RELEASE
 * packets. Standard Button composable only fires onClick on release,
 * which would make click-and-drag impossible.
 *
 * The button changes appearance on press via a simple alpha shift —
 * subtle enough not to distract, visible enough to confirm the press.
 */
@Composable
private fun ClickButton(
    label: String,
    color: Color,
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(320.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(color)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onDown()
                        tryAwaitRelease()
                        onUp()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            letterSpacing = 3.sp
        )
    }
}

/**
 * Connection status card shown while connected.
 * Displays PC name, connection type, and latency.
 */
@Composable
private fun StatusCard(
    uiState: MainUiState,
    onDisconnect: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .padding(16.dp)
    ) {
        Column {
            // Connected indicator dot + label
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Connected)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Connected",
                    style = MaterialTheme.typography.labelSmall,
                    color = Connected
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // PC name
            Text(
                text = uiState.pcName,
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Connection type and latency in a row
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                InfoChip(
                    label = uiState.connectionTypeName
                )
                if (uiState.latencyMs > 0L) {
                    InfoChip(
                        label = "${uiState.latencyMs}ms"
                    )
                }
            }
        }
    }
}

/**
 * Small pill-shaped info chip — connection type, latency.
 */
@Composable
private fun InfoChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(Surface2)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

// ---------------------------------------------------------------------------
// Disconnected layout
// ---------------------------------------------------------------------------

@Composable
private fun DisconnectedScreen(
    uiState: MainUiState,
    onConnectTo: (DiscoveredHost) -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Settings button top right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // App title
        Text(
            text = "Motion Mouse",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Dynamic status line
        SearchingStatusText(state = uiState.connectionState)

        Spacer(modifier = Modifier.height(40.dp))

        // Discovered hosts list
        if (uiState.discoveredHosts.isEmpty()) {
            EmptyHostsPlaceholder(state = uiState.connectionState)
        } else {
            Text(
                text = "AVAILABLE PCs",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                letterSpacing = 2.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = uiState.discoveredHosts,
                    key = { it.address }
                ) { host ->
                    DiscoveredHostCard(
                        host = host,
                        onConnect = { onConnectTo(host) }
                    )
                }
            }
        }
    }
}

/**
 * Animated searching status text.
 * Pulses opacity when searching to signal active scanning.
 */
@Composable
private fun SearchingStatusText(state: ConnectionState) {
    val isSearching = state is ConnectionState.Searching

    val infiniteTransition = rememberInfiniteTransition(label = "search_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "search_alpha"
    )

    val (text, color) = when (state) {
        is ConnectionState.Searching ->
            "Searching for PCs…" to Searching
        is ConnectionState.Connecting ->
            "Connecting to ${state.pcName}…" to MotionBlue
        is ConnectionState.Reconnecting ->
            "Reconnecting… (${state.attempt})" to Searching
        is ConnectionState.Error ->
            state.message to ErrorRed
        else ->
            "Open Motion Mouse on your PC" to TextSecondary
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.alpha(if (isSearching) alpha else 1f),
        textAlign = TextAlign.Center
    )
}

/**
 * Placeholder shown when no hosts have been discovered yet.
 */
@Composable
private fun EmptyHostsPlaceholder(state: ConnectionState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "No PCs found",
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Make sure Motion Mouse is running\non your Windows PC and both devices\nare on the same network.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Card for a single discovered Windows PC.
 * Shows the PC name, connection type badge, and a Connect button.
 */
@Composable
private fun DiscoveredHostCard(
    host: DiscoveredHost,
    onConnect: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .border(
                width = 1.dp,
                color = Surface2,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = host.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                InfoChip(label = host.connectionType.displayName())
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Connect button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MotionBlue)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onConnect() })
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Connect",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }
        }
    }
}

package com.motionmouse.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.motionmouse.app.connection.ConnectionType
import com.motionmouse.app.settings.MotionSettings
import com.motionmouse.app.ui.CalibrationState
import com.motionmouse.app.ui.SettingsViewModel
import com.motionmouse.app.ui.theme.Connected
import com.motionmouse.app.ui.theme.ErrorRed
import com.motionmouse.app.ui.theme.MotionBlue
import com.motionmouse.app.ui.theme.MotionBlueDim
import com.motionmouse.app.ui.theme.Searching
import com.motionmouse.app.ui.theme.Surface1
import com.motionmouse.app.ui.theme.Surface2
import com.motionmouse.app.ui.theme.Surface3
import com.motionmouse.app.ui.theme.TextPrimary
import com.motionmouse.app.ui.theme.TextSecondary
import androidx.compose.foundation.gestures.detectTapGestures

/**
 * Settings screen.
 *
 * Presents five controls:
 *   1. Sensitivity slider
 *   2. Motion smoothing slider
 *   3. Dead zone slider
 *   4. Calibration button + progress
 *   5. Preferred connection selector
 *
 * Plus a reset to defaults option at the bottom.
 *
 * All sliders update in real-time — the user can feel the effect
 * of each adjustment while actively using Motion Mouse.
 *
 * Design: same dark surface language as the main screen.
 * Each setting group is in its own card for visual separation.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val calibrationState by viewModel.calibrationState.collectAsStateWithLifecycle()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // --- Top bar ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextSecondary
                    )
                }
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Motion settings card ---
            SettingsCard(title = "MOTION") {

                // Sensitivity uses a log-scale slider for fine control at low end
                LogSensitivitySlider(
                    label = "Horizontal Sensitivity",
                    value = settings.sensitivityX,
                    min = MotionSettings.SENSITIVITY_MIN,
                    max = MotionSettings.SENSITIVITY_MAX,
                    onValueChange = viewModel::setSensitivityX,
                    description = "Left/Right cursor speed"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                        .height(1.dp)
                        .background(Surface2)
                )

                LogSensitivitySlider(
                    label = "Vertical Sensitivity",
                    value = settings.sensitivityY,
                    min = MotionSettings.SENSITIVITY_MIN,
                    max = MotionSettings.SENSITIVITY_MAX,
                    onValueChange = viewModel::setSensitivityY,
                    description = "Up/Down cursor speed"
                )

                Spacer(modifier = Modifier.height(20.dp))

                SettingSlider(
                    label = "Smoothing",
                    value = settings.smoothingFactor,
                    valueRange = MotionSettings.SMOOTHING_MIN..MotionSettings.SMOOTHING_MAX,
                    displayValue = String.format("%.0f%%", settings.smoothingFactor * 100),
                    parseInput = { it.trimEnd('%').toFloatOrNull()?.div(100f) },
                    onValueChange = viewModel::setSmoothing,
                    description = "Reduces jitter. Higher = smoother but adds slight lag"
                )

                Spacer(modifier = Modifier.height(20.dp))

                SettingSlider(
                    label = "Dead Zone",
                    value = settings.deadZone,
                    valueRange = MotionSettings.DEAD_ZONE_MIN..MotionSettings.DEAD_ZONE_MAX,
                    displayValue = String.format("%.4f", settings.deadZone),
                    parseInput = { it.toFloatOrNull() },
                    onValueChange = viewModel::setDeadZone,
                    description = "Filters micro-movements. Higher = more stillness required"
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Calibration card ---
            SettingsCard(title = "CALIBRATION") {
                Text(
                    text = "Place the phone flat and completely still on a surface, then tap Calibrate. The app will measure and cancel any resting sensor drift.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                CalibrationControl(
                    state = calibrationState,
                    onStart = viewModel::startCalibration,
                    onCancel = viewModel::cancelCalibration
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Connection card ---
            SettingsCard(title = "CONNECTION") {
                Text(
                    text = "Preferred method",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                ConnectionSelector(
                    current = settings.preferredConnection,
                    onSelect = viewModel::setPreferredConnection
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Controls card ---
            SettingsCard(title = "CONTROLS") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Volume Button Clicks",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Vol Up = Left click  ·  Vol Down = Right click",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = settings.useVolumeButtons,
                        onCheckedChange = viewModel::setUseVolumeButtons,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextPrimary,
                            checkedTrackColor = MotionBlue
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Reset card ---
            SettingsCard(title = "RESET") {
                Text(
                    text = "Restore all settings to their factory defaults.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                ActionButton(
                    label = "Reset to Defaults",
                    color = ErrorRed.copy(alpha = 0.15f),
                    textColor = ErrorRed,
                    onClick = viewModel::resetToDefaults
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Reusable setting components
// ---------------------------------------------------------------------------

/**
 * Card wrapper for a group of settings.
 */
@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .padding(20.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        content()
    }
}

/**
 * Formats a sensitivity float with enough decimal places to be readable.
 * e.g. 0.001 -> "0.001x", 0.05 -> "0.050x", 1.5 -> "1.500x"
 */
private fun formatSensitivity(value: Float): String = String.format("%.3fx", value)

/**
 * Converts a real sensitivity value -> normalised 0..1 position on the log slider.
 * The slider is split at the midpoint:
 *   0.0 -> min (0.001)  |  0.5 -> knee (0.01)  |  1.0 -> max (1.5)
 * This gives the bottom half to [0.001, 0.01] and the top half to [0.01, 1.5],
 * making the low end move slowly and the high end move quickly.
 */
private fun sensitivityToSlider(value: Float, min: Float, knee: Float, max: Float): Float {
    return if (value <= knee) {
        // lower segment: linear between min and knee mapped to [0, 0.5]
        0.5f * (value - min) / (knee - min)
    } else {
        // upper segment: linear between knee and max mapped to [0.5, 1.0]
        0.5f + 0.5f * (value - knee) / (max - knee)
    }
}

/** Inverse of sensitivityToSlider */
private fun sliderToSensitivity(pos: Float, min: Float, knee: Float, max: Float): Float {
    return if (pos <= 0.5f) {
        min + (pos / 0.5f) * (knee - min)
    } else {
        knee + ((pos - 0.5f) / 0.5f) * (max - knee)
    }
}

/**
 * Sensitivity-specific slider with piecewise-linear scale.
 * Low half of slider = 0.001..0.01 (slow, precise)
 * High half of slider = 0.01..1.5  (fast, coarse)
 * Includes inline text entry for exact values.
 */
@Composable
private fun LogSensitivitySlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onValueChange: (Float) -> Unit,
    description: String
) {
    val knee = 0.01f
    val sliderPos = sensitivityToSlider(value, min, knee, max)
    val focusManager = LocalFocusManager.current
    var inputText by remember(value) { mutableStateOf(formatSensitivity(value)) }
    var isEditing by remember { mutableStateOf(false) }

    // Sync input text when value changes externally (slider drag)
    LaunchedEffect(value) {
        if (!isEditing) inputText = formatSensitivity(value)
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            // Inline text field showing the value, editable
            BasicTextField(
                value = inputText,
                onValueChange = { inputText = it; isEditing = true },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    val parsed = inputText.trimEnd('x', ' ').toFloatOrNull()
                    if (parsed != null) {
                        onValueChange(parsed.coerceIn(min, max))
                    }
                    isEditing = false
                    focusManager.clearFocus()
                }),
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = MotionBlue,
                    textAlign = TextAlign.End
                ),
                cursorBrush = SolidColor(MotionBlue),
                modifier = Modifier
                    .width(80.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isEditing) Surface2 else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        Slider(
            value = sliderPos,
            onValueChange = { pos ->
                isEditing = false
                val real = sliderToSensitivity(pos, min, knee, max)
                onValueChange(real)
            },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = MotionBlue,
                activeTrackColor = MotionBlue,
                inactiveTrackColor = Surface3
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary.copy(alpha = 0.7f)
        )
    }
}

/**
 * A labeled slider with current value display, description, and
 * an inline editable text field for manual entry.
 */
@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    parseInput: (String) -> Float?,
    onValueChange: (Float) -> Unit,
    description: String
) {
    val focusManager = LocalFocusManager.current
    var inputText by remember(value) { mutableStateOf(displayValue) }
    var isEditing by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (!isEditing) inputText = displayValue
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            BasicTextField(
                value = inputText,
                onValueChange = { inputText = it; isEditing = true },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    val parsed = parseInput(inputText)
                    if (parsed != null) {
                        onValueChange(parsed.coerceIn(valueRange.start, valueRange.endInclusive))
                    }
                    isEditing = false
                    focusManager.clearFocus()
                }),
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = MotionBlue,
                    textAlign = TextAlign.End
                ),
                cursorBrush = SolidColor(MotionBlue),
                modifier = Modifier
                    .width(80.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isEditing) Surface2 else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        Slider(
            value = value,
            onValueChange = { isEditing = false; onValueChange(it) },
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = MotionBlue,
                activeTrackColor = MotionBlue,
                inactiveTrackColor = Surface3
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary.copy(alpha = 0.7f)
        )
    }
}

/**
 * Calibration UI — shows idle button, progress bar, or result.
 */
@Composable
private fun CalibrationControl(
    state: CalibrationState,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    when (state) {
        is CalibrationState.Idle -> {
            ActionButton(
                label = "Calibrate",
                color = MotionBlueDim.copy(alpha = 0.3f),
                textColor = MotionBlue,
                onClick = onStart
            )
        }

        is CalibrationState.Running -> {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Measuring…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Searching
                    )
                    Text(
                        text = "${(state.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = Searching
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Searching,
                    trackColor = Surface3,
                    strokeCap = StrokeCap.Round
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Keep the phone flat and completely still…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        is CalibrationState.Complete -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Connected.copy(alpha = 0.1f))
                    .padding(12.dp)
            ) {
                Text(
                    text = "✓  Calibration complete",
                    style = MaterialTheme.typography.titleMedium,
                    color = Connected
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Yaw bias: ${String.format("%.4f", state.yawBias)} rad/s\n" +
                            "Pitch bias: ${String.format("%.4f", state.pitchBias)} rad/s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }

        is CalibrationState.Failed -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ErrorRed.copy(alpha = 0.1f))
                    .padding(12.dp)
            ) {
                Text(
                    text = "✗  Calibration failed",
                    style = MaterialTheme.typography.titleMedium,
                    color = ErrorRed
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                ActionButton(
                    label = "Try Again",
                    color = MotionBlueDim.copy(alpha = 0.3f),
                    textColor = MotionBlue,
                    onClick = onStart
                )
            }
        }
    }
}

/**
 * Connection type selector — three segmented options.
 * AUTO, BLUETOOTH, WIFI displayed as tap targets.
 */
@Composable
private fun ConnectionSelector(
    current: ConnectionType,
    onSelect: (ConnectionType) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Surface2),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ConnectionType.entries.forEach { type ->
            val isSelected = type == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) MotionBlue else Color.Transparent)
                    .pointerInput(type) {
                        detectTapGestures(onTap = { onSelect(type) })
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = type.displayName(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) Color.White else TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Generic action button used for Calibrate and Reset.
 */
@Composable
private fun ActionButton(
    label: String,
    color: Color,
    textColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = textColor
        )
    }
}

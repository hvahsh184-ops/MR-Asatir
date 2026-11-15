package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompassCalibration
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.RadarUiState
import java.util.Locale

@Composable
fun MagnetometerLivePanel(
    state: RadarUiState,
    onCalibrateBaseline: () -> Unit,
    onSensitivityChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val reading = state.magnetometerReading
    val magMag = reading?.magnitudeUt ?: 0.0
    val deltaB = state.magDeltaUt
    val zScore = state.magZScore
    val isCalibrated = state.isMagBaselineCalibrated
    val threshold = state.magSensitivityThresholdUt

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Sensors,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "پنل تخصصی مگنتومتر (۵ سانتیمتری زمین)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "نمایش بلادرنگ شدت میدان مغناطیسی B با فیلتر کالمن و کالیبراسیون مستقل",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Realtime Readings Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("مجموع میدان (|B|)", style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = String.format(Locale.US, "%.2f µT", magMag),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("انحراف از پایه (ΔB)", style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = String.format(Locale.US, "%.2f µT", deltaB),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (abs(deltaB) >= threshold) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            // X, Y, Z Raw Components
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Bx: ${String.format(Locale.US, "%.1f", reading?.bxUt ?: 0.0)}", style = MaterialTheme.typography.bodySmall)
                Text(text = "By: ${String.format(Locale.US, "%.1f", reading?.byUt ?: 0.0)}", style = MaterialTheme.typography.bodySmall)
                Text(text = "Bz: ${String.format(Locale.US, "%.1f", reading?.bzUt ?: 0.0)}", style = MaterialTheme.typography.bodySmall)
                Text(text = "Z-Score: ${String.format(Locale.US, "%.2f", zScore)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }

            // Calibration & Sensitivity Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onCalibrateBaseline,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f).testTag("mag_calibrate_btn")
                ) {
                    Icon(imageVector = Icons.Default.CompassCalibration, contentDescription = null)
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(text = if (isCalibrated) "کالیبره شد (صفر)" else "کالیبراسیون پایه")
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "آستانه حساسیت مغناطیسی: ${String.format(Locale.US, "%.1f", threshold)} µT",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                Slider(
                    value = threshold,
                    onValueChange = onSensitivityChanged,
                    valueRange = 2f..25f,
                    modifier = Modifier.testTag("mag_sensitivity_slider")
                )
            }
        }
    }
}

private fun abs(v: Double) = if (v < 0) -v else v

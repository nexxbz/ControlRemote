package com.example.remoto

import android.content.Context
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun isServiceEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return enabled.contains("RemoteAccessibilityService")
}

private val CardSurface = Color(0xFF131720)
private val CardBorder = Color(0xFF232A38)
private val AmberAccent = Color(0xFFF5C451)
private val AmberPillBg = Color(0xFF33260E)
private val GreenAccent = Color(0xFF34D399)
private val GreenPillBg = Color(0xFF0E2E22)
private val MutedText = Color(0xFFA0A8B8)

@Composable
fun ReceiverScreen(
    enabled: Boolean,
    imeEnabled: Boolean,
    imeSelected: Boolean,
    pin: String,
    ip: String?,
    canWriteSettings: Boolean,
    canDrawOverlays: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onSelectIme: () -> Unit,
    onOpenWriteSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onNewPin: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("receiver_back_button")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Ico)
            }
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(GreenPillBg)
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text("RECEPTOR", color = GreenAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text("Recibir Control", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 1. Servicio de Accesibilidad
        StatusCard(
            icon = Icons.Filled.AccessibilityNew,
            title = "Servicio de Accesibilidad",
            active = enabled,
            description = if (enabled) {
                "Activado. Este teléfono ya aparece en la red para controlarlo."
            } else {
                "Ejecuta clics, gestos de desplazamiento y botones del sistema."
            }
        ) {
            if (!enabled) {
                AmberOutlinedButton(
                    text = "Activar Accesibilidad",
                    tag = "activate_accessibility_button",
                    onClick = onOpenAccessibility
                )
                Text(
                    "Si Android dice \"Configuración restringida\" o no deja encenderlo: abre la info de la app, " +
                        "toca el menú ⋮ y elige \"Permitir ajustes restringidos\".",
                    color = MutedText,
                    fontSize = 12.sp
                )
                OutlinedButton(
                    onClick = onOpenAppInfo,
                    modifier = Modifier.fillMaxWidth().testTag("open_app_info_button"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Text("Abrir info de la app", color = Ico)
                }
            }
        }

        // 2. Teclado PC (Flechas y Enter)
        val imeReady = imeEnabled && imeSelected
        StatusCard(
            icon = Icons.Filled.Keyboard,
            title = "Teclado PC (Flechas y Enter)",
            active = imeReady,
            description = "Envía teclas reales de teclado PC (Up, Down, Left, Right y Enter) en este teléfono para apps como Xuper TV sin mostrar teclado en pantalla."
        ) {
            if (!imeEnabled) {
                AmberOutlinedButton(
                    text = "Activar Teclado Remoto",
                    tag = "activate_remote_keyboard_button",
                    onClick = onOpenImeSettings
                )
            } else if (!imeSelected) {
                Text(
                    "Teclado habilitado. Selecciónalo como teclado activo para que funcionen las flechas y Enter.",
                    color = AmberAccent,
                    fontSize = 12.sp
                )
                AmberOutlinedButton(
                    text = "Seleccionar Teclado Remoto",
                    tag = "select_remote_keyboard_button",
                    onClick = onSelectIme
                )
                TextButton(
                    onClick = onOpenImeSettings,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Ajustes de teclado", color = MutedText, fontSize = 12.sp)
                }
            } else {
                Text(
                    "Activo. Las flechas y Enter se envían como teclado físico de PC.",
                    color = GreenAccent,
                    fontSize = 12.sp
                )
                OutlinedButton(
                    onClick = onSelectIme,
                    modifier = Modifier.fillMaxWidth().testTag("switch_keyboard_button"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Text("Cambiar teclado", color = Ico)
                }
            }
        }

        // 3. Modificar brillo del sistema
        StatusCard(
            icon = Icons.Filled.BrightnessMedium,
            title = "Modificar brillo del sistema",
            active = canWriteSettings,
            description = if (canWriteSettings) {
                "Permiso concedido: los botones de brillo funcionan."
            } else {
                "Permite subir y bajar el brillo directamente (WRITE_SETTINGS)."
            }
        ) {
            if (!canWriteSettings) {
                AmberOutlinedButton(
                    text = "Permitir Brillo",
                    tag = "allow_brightness_button",
                    onClick = onOpenWriteSettings
                )
            }
        }

        // 4. Puntero en segunda pantalla (scrcpy --new-display)
        StatusCard(
            icon = Icons.Filled.Layers,
            title = "Puntero en segunda pantalla (scrcpy)",
            active = canDrawOverlays,
            description = if (canDrawOverlays) {
                "Permiso concedido: la flecha del mouse se dibuja también en pantallas virtuales."
            } else {
                "Necesario en algunos teléfonos para mostrar la flecha del mouse en scrcpy --new-display."
            }
        ) {
            if (!canDrawOverlays) {
                AmberOutlinedButton(
                    text = "Permitir sobre otras apps",
                    tag = "allow_overlay_button",
                    onClick = onOpenOverlaySettings
                )
            }
        }

        // 4. PIN para conectarse
        InfoCard("PIN para conectarse") {
            Text(pin, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text("Escríbelo en el otro teléfono la primera vez que se conecte.", color = MutedText, fontSize = 13.sp)
            OutlinedButton(
                onClick = onNewPin,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, CardBorder),
                modifier = Modifier.testTag("new_pin_button")
            ) {
                Text("Generar otro PIN", color = Ico)
            }
        }

        // 5. Dirección en la red
        InfoCard("Dirección en la red") {
            Text(ip ?: "Sin wifi", color = Ico, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text("Solo funciona si ambos teléfonos están en la misma wifi.", color = MutedText, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StatusCard(
    icon: ImageVector,
    title: String,
    active: Boolean,
    description: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val accent = if (active) GreenAccent else AmberAccent
    val pillBg = if (active) GreenPillBg else AmberPillBg
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardSurface)
            .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            Text(
                title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(pillBg)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    if (active) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    if (active) "Activo" else "Pendiente",
                    color = accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Text(description, color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
        content()
    }
}

@Composable
private fun AmberOutlinedButton(text: String, tag: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(tag),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.7f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberAccent)
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardSurface)
            .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = Ico, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

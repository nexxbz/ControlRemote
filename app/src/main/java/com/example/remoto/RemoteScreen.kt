package com.example.remoto

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Códigos android.view.KeyEvent. Sirven igual para Android TV Remote (TV) y ADB (teléfonos). */
enum class Key(val code: Int) {
    UP(19), DOWN(20), LEFT(21), RIGHT(22), OK(23),
    HOME(3), BACK(4), MENU(82), GUIDE(172), SETTINGS(176), POWER(26),
    VOL_UP(24), VOL_DOWN(25), MUTE(164), CAPTIONS(175),
    BRIGHT_UP(221), BRIGHT_DOWN(220),
    REWIND(89), PLAY(126), PAUSE(127), FORWARD(90),
    N0(7), N1(8), N2(9), N3(10), N4(11), N5(12), N6(13), N7(14), N8(15), N9(16)
}

/** Implementa esto una vez por protocolo: TV (Android TV Remote) y teléfono (ADB por wifi). */
interface RemoteController {
    fun send(key: Key)
    fun sendText(text: String)
}

private val Bg = Color(0xFF000000)
private val Btn = Color(0xFF0F1014)
private val Ico = Color(0xFFE4E4EA)

@Composable
fun RemoteScreen(deviceName: String, status: String, controller: RemoteController, onKeyboard: () -> Unit = {}) {
    var showNumbers by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(deviceName, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                if (status.isNotEmpty()) Text(status, color = Color.Gray, fontSize = 12.sp)
            }
            Icon(Icons.Filled.Keyboard, null, tint = Ico, modifier = Modifier.padding(8.dp).clickable { onKeyboard() })
        }

        // Reproducción
        Row(
            Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(50)).background(Btn),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
        ) {
            IconKey(Icons.Filled.FastRewind) { controller.send(Key.REWIND) }
            IconKey(Icons.Filled.PlayArrow) { controller.send(Key.PLAY) }
            IconKey(Icons.Filled.Pause) { controller.send(Key.PAUSE) }
            IconKey(Icons.Filled.FastForward) { controller.send(Key.FORWARD) }
        }

        // Fila 1-2: volumen | mute, CC | brillo
        Row(Modifier.fillMaxWidth().height(224.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill(Modifier.weight(1f), Icons.Filled.VolumeUp, Icons.Filled.VolumeDown,
                { controller.send(Key.VOL_UP) }, { controller.send(Key.VOL_DOWN) })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Round(Modifier.weight(1f), Icons.Filled.VolumeOff) { controller.send(Key.MUTE) }
                Round(Modifier.weight(1f), Icons.Filled.Dialpad) { showNumbers = !showNumbers }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Round(Modifier.weight(1f), Icons.Filled.ClosedCaption) { controller.send(Key.CAPTIONS) }
                Round(Modifier.weight(1f), Icons.Filled.MoreHoriz) { controller.send(Key.SETTINGS) }
            }
            Pill(Modifier.weight(1f), Icons.Filled.BrightnessHigh, Icons.Filled.BrightnessLow,
                { controller.send(Key.BRIGHT_UP) }, { controller.send(Key.BRIGHT_DOWN) })
        }

        if (showNumbers) {
            val rows = listOf(listOf(Key.N1, Key.N2, Key.N3), listOf(Key.N4, Key.N5, Key.N6), listOf(Key.N7, Key.N8, Key.N9), listOf(Key.N0))
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    r.forEach { k ->
                        Box(Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(28.dp)).background(Btn)
                            .clickable { controller.send(k) }, contentAlignment = Alignment.Center) {
                            Text(k.name.removePrefix("N"), color = Ico, fontSize = 22.sp)
                        }
                    }
                }
            }
        }

        // Atrás, inicio, menú, encendido
        Row(Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Round(Modifier.weight(1f), Icons.AutoMirrored.Filled.ArrowBack) { controller.send(Key.BACK) }
            Round(Modifier.weight(1f), Icons.Filled.Home) { controller.send(Key.HOME) }
            Round(Modifier.weight(1f), Icons.AutoMirrored.Filled.List) { controller.send(Key.GUIDE) }
            Round(Modifier.weight(1f), Icons.Filled.PowerSettingsNew) { controller.send(Key.POWER) }
        }

        // D-pad
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(Btn)) {
            Icon(Icons.Filled.KeyboardArrowUp, null, tint = Ico, modifier = Modifier.align(Alignment.TopCenter).padding(32.dp).size(56.dp).clickable { controller.send(Key.UP) })
            Icon(Icons.Filled.KeyboardArrowDown, null, tint = Ico, modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp).size(56.dp).clickable { controller.send(Key.DOWN) })
            Icon(Icons.Filled.KeyboardArrowLeft, null, tint = Ico, modifier = Modifier.align(Alignment.CenterStart).padding(32.dp).size(56.dp).clickable { controller.send(Key.LEFT) })
            Icon(Icons.Filled.KeyboardArrowRight, null, tint = Ico, modifier = Modifier.align(Alignment.CenterEnd).padding(32.dp).size(56.dp).clickable { controller.send(Key.RIGHT) })
            Box(Modifier.align(Alignment.Center).size(110.dp).clip(CircleShape).background(Ico).padding(8.dp)
                .clip(CircleShape).background(Btn).clickable { controller.send(Key.OK) })
        }
    }
}

@Composable
private fun IconKey(icon: ImageVector, onClick: () -> Unit) =
    Icon(icon, null, tint = Ico, modifier = Modifier.size(48.dp).clickable { onClick() })

@Composable
private fun Round(modifier: Modifier, icon: ImageVector, onClick: () -> Unit) =
    Box(modifier.fillMaxHeight().clip(CircleShape).background(Btn).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Ico, modifier = Modifier.size(40.dp))
    }

@Composable
private fun Pill(modifier: Modifier, top: ImageVector, bottom: ImageVector, onTop: () -> Unit, onBottom: () -> Unit) =
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(50)).background(Btn)) {
        Box(Modifier.weight(1f).fillMaxWidth().clickable { onTop() }, contentAlignment = Alignment.Center) { Icon(top, null, tint = Ico, modifier = Modifier.size(40.dp)) }
        Box(Modifier.weight(1f).fillMaxWidth().clickable { onBottom() }, contentAlignment = Alignment.Center) { Icon(bottom, null, tint = Ico, modifier = Modifier.size(40.dp)) }
    }

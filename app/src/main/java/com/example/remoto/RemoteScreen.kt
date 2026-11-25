package com.example.remoto

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Teclas del control. Se envían por wifi como texto con su nombre (por ejemplo "K:VOL_UP"). */
enum class Key {
    UP, DOWN, LEFT, RIGHT, OK,
    HOME, BACK, MENU, GUIDE, POWER, SETTINGS,
    VOL_UP, VOL_DOWN, MUTE, CAPTIONS,
    BRIGHT_UP, BRIGHT_DOWN,
    REWIND, PLAY, PAUSE, FORWARD,
    N0, N1, N2, N3, N4, N5, N6, N7, N8, N9
}

interface RemoteController {
    fun send(key: Key)
    fun sendText(text: String)
    fun moveMouse(dx: Int, dy: Int)
    fun click(right: Boolean)
    fun scroll(amount: Int)
}

internal val Bg = Color(0xFF000000)
internal val Btn = Color(0xFF0F1014)
internal val Ico = Color(0xFFE4E4EA)

@Composable
fun RemoteScreen(
    deviceName: String,
    status: String,
    controller: RemoteController,
    onMouse: () -> Unit,
    onKeyboard: () -> Unit,
    onDevices: () -> Unit,
    onDisconnect: () -> Unit
) {
    var showNumbers by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Encabezado: nombre + mouse, teclado, menú
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(deviceName, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (status.isNotEmpty()) Text(status, color = Color.Gray, fontSize = 12.sp)
            }
            IconButton(onClick = onMouse) { Icon(Icons.Filled.Mouse, null, tint = Ico, modifier = Modifier.size(32.dp)) }
            IconButton(onClick = onKeyboard) { Icon(Icons.Filled.Keyboard, null, tint = Ico, modifier = Modifier.size(32.dp)) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null, tint = Ico, modifier = Modifier.size(32.dp)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Dispositivos") }, onClick = { menu = false; onDevices() })
                    DropdownMenuItem(text = { Text("Desconectar") }, onClick = { menu = false; onDisconnect() })
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 12.dp
            val cell = (maxWidth - gap * 3) / 4

            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                // Reproducción
                Row(
                    Modifier.fillMaxWidth().height(cell).clip(RoundedCornerShape(50)).background(Btn),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
                ) {
                    IconKey(Icons.Filled.FastRewind) { controller.send(Key.REWIND) }
                    IconKey(Icons.Filled.PlayArrow) { controller.send(Key.PLAY) }
                    IconKey(Icons.Filled.Pause) { controller.send(Key.PAUSE) }
                    IconKey(Icons.Filled.FastForward) { controller.send(Key.FORWARD) }
                }

                // Volumen | mute, números | CC, más | brillo
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Pill(cell, cell * 2 + gap, Icons.Filled.VolumeUp, Icons.Filled.VolumeDown,
                        { controller.send(Key.VOL_UP) }, { controller.send(Key.VOL_DOWN) })
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        Round(cell, Icons.Filled.VolumeOff) { controller.send(Key.MUTE) }
                        Round(cell, Icons.Filled.Dialpad) { showNumbers = !showNumbers }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        Round(cell, Icons.Filled.ClosedCaption) { controller.send(Key.CAPTIONS) }
                        Round(cell, Icons.Filled.MoreHoriz) { controller.send(Key.SETTINGS) }
                    }
                    Pill(cell, cell * 2 + gap, Icons.Filled.BrightnessHigh, Icons.Filled.BrightnessLow,
                        { controller.send(Key.BRIGHT_UP) }, { controller.send(Key.BRIGHT_DOWN) })
                }

                if (showNumbers) {
                    val rows = listOf(
                        listOf(Key.N1, Key.N2, Key.N3), listOf(Key.N4, Key.N5, Key.N6),
                        listOf(Key.N7, Key.N8, Key.N9), listOf(Key.N0)
                    )
                    rows.forEach { r ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                            r.forEach { k ->
                                Box(
                                    Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(30.dp)).background(Btn)
                                        .clickable { controller.send(k) },
                                    contentAlignment = Alignment.Center
                                ) { Text(k.name.removePrefix("N"), color = Ico, fontSize = 22.sp) }
                            }
                        }
                    }
                }

                // Atrás, inicio, menú, encendido
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Round(cell, Icons.AutoMirrored.Filled.ArrowBack) { controller.send(Key.BACK) }
                    Round(cell, Icons.Filled.Home) { controller.send(Key.HOME) }
                    Round(cell, Icons.AutoMirrored.Filled.List) { controller.send(Key.MENU) }
                    Round(cell, Icons.Filled.PowerSettingsNew) { controller.send(Key.POWER) }
                }

                // Cruceta
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(Btn)) {
                    Icon(Icons.Filled.KeyboardArrowUp, null, tint = Ico,
                        modifier = Modifier.align(Alignment.TopCenter).padding(28.dp).size(64.dp).clickable { controller.send(Key.UP) })
                    Icon(Icons.Filled.KeyboardArrowDown, null, tint = Ico,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(28.dp).size(64.dp).clickable { controller.send(Key.DOWN) })
                    Icon(Icons.Filled.KeyboardArrowLeft, null, tint = Ico,
                        modifier = Modifier.align(Alignment.CenterStart).padding(28.dp).size(64.dp).clickable { controller.send(Key.LEFT) })
                    Icon(Icons.Filled.KeyboardArrowRight, null, tint = Ico,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(28.dp).size(64.dp).clickable { controller.send(Key.RIGHT) })
                    Box(
                        Modifier.align(Alignment.Center).fillMaxWidth(0.28f).aspectRatio(1f).clip(CircleShape)
                            .background(Ico).padding(8.dp).clip(CircleShape).background(Btn)
                            .clickable { controller.send(Key.OK) }
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun IconKey(icon: ImageVector, onClick: () -> Unit) =
    Icon(icon, null, tint = Ico, modifier = Modifier.size(44.dp).clickable { onClick() })

@Composable
private fun Round(size: Dp, icon: ImageVector, onClick: () -> Unit) =
    Box(
        Modifier.size(size).clip(CircleShape).background(Btn).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = Ico, modifier = Modifier.size(size * 0.45f)) }

@Composable
private fun Pill(width: Dp, height: Dp, top: ImageVector, bottom: ImageVector, onTop: () -> Unit, onBottom: () -> Unit) =
    Column(Modifier.size(width, height).clip(RoundedCornerShape(50)).background(Btn)) {
        Box(Modifier.weight(1f).fillMaxWidth().clickable { onTop() }, contentAlignment = Alignment.Center) {
            Icon(top, null, tint = Ico, modifier = Modifier.size(width * 0.45f))
        }
        Box(Modifier.weight(1f).fillMaxWidth().clickable { onBottom() }, contentAlignment = Alignment.Center) {
            Icon(bottom, null, tint = Ico, modifier = Modifier.size(width * 0.45f))
        }
    }

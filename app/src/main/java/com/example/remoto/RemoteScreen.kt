package com.example.remoto

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Teclas del control. Se envían por wifi como texto con su nombre (por ejemplo "K:VOL_UP"). */
enum class Key {
    UP, DOWN, LEFT, RIGHT, OK,
    HOME, BACK, MENU, GUIDE, POWER, SETTINGS,
    VOL_UP, VOL_DOWN, MUTE, CAPTIONS,
    BRIGHT_UP, BRIGHT_DOWN,
    REWIND, PLAY, PAUSE, PLAY_PAUSE, FORWARD, STOP, PREV, NEXT, REPEAT, SCREENSHOT,
    RED, GREEN, BLUE, YELLOW,
    SPACE, BACKSPACE, ENTER,
    N0, N1, N2, N3, N4, N5, N6, N7, N8, N9
}

interface RemoteController {
    fun send(key: Key)
    fun sendText(text: String)
    fun moveMouse(dx: Int, dy: Int)
    fun click(right: Boolean)
    fun scroll(amount: Int)
    fun drag(start: Boolean)
}

internal val Bg = Color(0xFF000000)
internal val Btn = Color(0xFF0F1014)
internal val Ico = Color(0xFFE4E4EA)

/**
 * Control remoto. Se adapta al tamaño de la ventana: en pantalla completa usa la cruceta grande y,
 * si la ventana es pequeña (modo ventana), encoge botones y cruceta para que todo quepa.
 */
@Composable
fun RemoteScreen(
    deviceName: String,
    status: String,
    controller: RemoteController,
    settings: AppSettings,
    onKeyboard: () -> Unit,
    onBluetooth: () -> Unit,
    onDevices: () -> Unit,
    onDisconnect: () -> Unit
) {
    var touchpad by remember { mutableStateOf(settings.touchpadDefault) }
    var showNumbers by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()) {
        val gap = 12f
        val w = (maxWidth.value - 32f).coerceAtLeast(120f)
        val headerH = if (status.isNotEmpty()) 74f else 56f
        val availH = maxHeight.value - headerH - gap
        val cellFull = (w - gap * 3f) / 4f

        // Se encogen los botones hasta que la cruceta tenga un tamaño razonable
        var k = 1f
        var cell = cellFull
        var pad = availH - 4f * cell - 4f * gap
        while (k > 0.4f && pad < 2.6f * cell) {
            k -= 0.05f
            cell = cellFull * k
            pad = availH - 4f * cell - 4f * gap
        }
        val rowW = 4f * cell + 3f * gap
        pad = pad.coerceIn(90f, rowW)

        val c = cell.dp
        val g = gap.dp

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(g)
        ) {
            // Encabezado: nombre + mouse/cruceta, teclado, menú
            Row(Modifier.fillMaxWidth().height(headerH.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        deviceName, color = Color.White,
                        fontSize = if (w < 300f) 22.sp else 30.sp, fontWeight = FontWeight.Bold, maxLines = 1
                    )
                    if (status.isNotEmpty()) Text(status, color = Color.Gray, fontSize = 11.sp, maxLines = 2)
                }
                IconButton(onClick = { touchpad = !touchpad }) {
                    Icon(if (touchpad) Icons.Filled.OpenWith else Icons.Filled.Mouse, null, tint = Ico, modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = onKeyboard) { Icon(Icons.Filled.Keyboard, null, tint = Ico, modifier = Modifier.size(28.dp)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null, tint = Ico, modifier = Modifier.size(28.dp)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (settings.btMode) {
                            DropdownMenuItem(text = { Text("Ratón y teclas por Bluetooth") }, onClick = { menu = false; onBluetooth() })
                        }
                        DropdownMenuItem(text = { Text("Dispositivos") }, onClick = { menu = false; onDevices() })
                        DropdownMenuItem(text = { Text("Desconectar") }, onClick = { menu = false; onDisconnect() })
                    }
                }
            }

            // Reproducción
            MediaPill((rowW).dp, c, controller)

            // Volumen | mute, números | CC, más | brillo
            Row(horizontalArrangement = Arrangement.spacedBy(g)) {
                Pill(c, c * 2 + g, Icons.Filled.VolumeUp, Icons.Filled.VolumeDown,
                    { controller.send(Key.VOL_UP) }, { controller.send(Key.VOL_DOWN) })
                Column(verticalArrangement = Arrangement.spacedBy(g)) {
                    Round(c, Icons.Filled.VolumeOff) { controller.send(Key.MUTE) }
                    Round(c, Icons.Filled.Dialpad) { showNumbers = true }
                }
                Column(verticalArrangement = Arrangement.spacedBy(g)) {
                    Round(c, Icons.Filled.ClosedCaption) { controller.send(Key.CAPTIONS) }
                    Round(c, Icons.Filled.MoreHoriz) { showMore = true }
                }
                Pill(c, c * 2 + g, Icons.Filled.BrightnessHigh, Icons.Filled.BrightnessLow,
                    { controller.send(Key.BRIGHT_UP) }, { controller.send(Key.BRIGHT_DOWN) })
            }

            // Atrás, inicio, menú, encendido/apagado
            Row(horizontalArrangement = Arrangement.spacedBy(g)) {
                Round(c, Icons.AutoMirrored.Filled.ArrowBack) { controller.send(Key.BACK) }
                Round(c, Icons.Filled.Home) { controller.send(Key.HOME) }
                Round(c, Icons.AutoMirrored.Filled.List) { controller.send(Key.MENU) }
                Round(c, Icons.Filled.PowerSettingsNew) { controller.send(Key.POWER) }
            }

            // Cruceta o panel táctil (en el mismo lugar)
            if (touchpad) {
                TouchpadArea(controller, settings, Modifier.width(rowW.dp).height(pad.dp))
            } else {
                DPad(pad.dp, controller)
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    if (showNumbers) NumberPadDialog(controller) { showNumbers = false }
    if (showMore) MoreButtonsDialog(controller) { showMore = false }
}

@Composable
private fun DPad(size: Dp, controller: RemoteController) {
    Box(Modifier.size(size).clip(CircleShape).background(Btn)) {
        ArrowBtn(Modifier.align(Alignment.TopCenter).padding(top = size * 0.05f), size, Icons.Filled.KeyboardArrowUp) { controller.send(Key.UP) }
        ArrowBtn(Modifier.align(Alignment.BottomCenter).padding(bottom = size * 0.05f), size, Icons.Filled.KeyboardArrowDown) { controller.send(Key.DOWN) }
        ArrowBtn(Modifier.align(Alignment.CenterStart).padding(start = size * 0.05f), size, Icons.Filled.KeyboardArrowLeft) { controller.send(Key.LEFT) }
        ArrowBtn(Modifier.align(Alignment.CenterEnd).padding(end = size * 0.05f), size, Icons.Filled.KeyboardArrowRight) { controller.send(Key.RIGHT) }
        Box(
            Modifier.align(Alignment.Center).size(size * 0.3f).clip(CircleShape)
                .background(Ico).padding(size * 0.025f).clip(CircleShape).background(Btn)
                .clickable { controller.send(Key.OK) }
        )
    }
}

@Composable
private fun ArrowBtn(modifier: Modifier, size: Dp, icon: ImageVector, onClick: () -> Unit) {
    val currentOnClick by rememberUpdatedState(onClick)
    val interaction = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .size(size * 0.28f)
            .clip(CircleShape)
            .indication(interaction, indication)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val press = PressInteraction.Press(down.position)
                    interaction.tryEmit(press)
                    currentOnClick()
                    val job = scope.launch {
                        delay(350L)
                        while (true) {
                            currentOnClick()
                            delay(110L)
                        }
                    }
                    val up = waitForUpOrCancellation()
                    job.cancel()
                    if (up != null) {
                        interaction.tryEmit(PressInteraction.Release(press))
                    } else {
                        interaction.tryEmit(PressInteraction.Cancel(press))
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = Ico, modifier = Modifier.size(size * 0.22f)) }
}

@Composable
private fun IconKey(icon: ImageVector, cell: Dp, onClick: () -> Unit) =
    Icon(icon, null, tint = Ico, modifier = Modifier.size(cell * 0.5f).clickable { onClick() })

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

/** Píldora de reproducción con los íconos redondeados del control original. */
@Composable
private fun MediaPill(width: Dp, height: Dp, controller: RemoteController) {
    Box(Modifier.width(width).height(height).clip(RoundedCornerShape(50)).background(Btn)) {
        Canvas(Modifier.matchParentSize()) {
            val u = size.height / 223f
            val cx = size.width / 2f
            val cy = size.height / 2f
            drawSkip(Offset(size.width * 0.133f, cy), u, false)
            drawSkip(Offset(size.width * 0.864f, cy), u, true)

            // Play: triángulo con esquinas redondeadas
            val pc = Offset(cx - 61f * u, cy)
            roundedTriangle(
                Offset(pc.x - 28.5f * u, pc.y - 36f * u),
                Offset(pc.x - 28.5f * u, pc.y + 36f * u),
                Offset(pc.x + 28.5f * u, pc.y),
                7f * u, Ico
            )

            // Pausa: dos barras redondeadas
            val bc = Offset(cx + 56f * u, cy)
            drawRoundRect(Ico, Offset(bc.x - 33f * u, bc.y - 39f * u), Size(22f * u, 78f * u), CornerRadius(11f * u))
            drawRoundRect(Ico, Offset(bc.x + 11f * u, bc.y - 39f * u), Size(22f * u, 78f * u), CornerRadius(11f * u))
        }
        Row(Modifier.matchParentSize()) {
            Box(Modifier.weight(30f).fillMaxHeight().clickable { controller.send(Key.REWIND) })
            Box(Modifier.weight(40f).fillMaxHeight().clickable { controller.send(Key.PLAY_PAUSE) })
            Box(Modifier.weight(30f).fillMaxHeight().clickable { controller.send(Key.FORWARD) })
        }
    }
}

/** Retroceder / adelantar: dos triángulos redondeados juntos. */
private fun DrawScope.drawSkip(center: Offset, u: Float, forward: Boolean) {
    val dir = if (forward) 1f else -1f
    val tips = if (forward) listOf(-2f, 46f) else listOf(-47f, 3f)
    for (t in tips) {
        val tip = Offset(center.x + t * u, center.y)
        val baseX = tip.x - dir * 45f * u
        roundedTriangle(
            tip,
            Offset(baseX, center.y - 29f * u),
            Offset(baseX, center.y + 29f * u),
            5f * u, Ico
        )
    }
}

/** Triángulo con las esquinas redondeadas: se reduce hacia su centro y se rellena con un borde redondo. */
private fun DrawScope.roundedTriangle(a: Offset, b: Offset, c: Offset, r: Float, color: Color) {
    val la = (b - c).getDistance()
    val lb = (a - c).getDistance()
    val lc = (a - b).getDistance()
    val per = la + lb + lc
    val inc = Offset((la * a.x + lb * b.x + lc * c.x) / per, (la * a.y + lb * b.y + lc * c.y) / per)
    val area = abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) / 2f
    val rho = 2f * area / per
    val k = ((rho - r) / rho).coerceIn(0.1f, 1f)
    fun sc(p: Offset) = Offset(inc.x + (p.x - inc.x) * k, inc.y + (p.y - inc.y) * k)
    val pa = sc(a)
    val pb = sc(b)
    val pc = sc(c)
    val path = Path()
    path.moveTo(pa.x, pa.y)
    path.lineTo(pb.x, pb.y)
    path.lineTo(pc.x, pc.y)
    path.close()
    drawPath(path, color)
    drawPath(path, color, style = Stroke(width = 2f * r, join = StrokeJoin.Round, cap = StrokeCap.Round))
}

package com.example.remoto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs

/**
 * Panel táctil basado en TouchPad:
 * - Un dedo desliza el cursor.
 * - Toque con un dedo = clic izquierdo.
 * - Dos dedos deslizan = desplazamiento (scroll).
 * - Toque con dos dedos = clic derecho.
 * - Botones inferiores "Clic izq." (permite arrastrar manteniendo presionado) y "Clic der.".
 */
@Composable
fun TouchpadArea(controller: RemoteController, settings: AppSettings, modifier: Modifier) {
    val curController by rememberUpdatedState(controller)
    val curSettings by rememberUpdatedState(settings)

    AndroidView(
        modifier = modifier.clip(RoundedCornerShape(16.dp)),
        factory = { ctx ->
            TouchPad(ctx).apply {
                var leftDown = false
                onTap = { right ->
                    curController.click(right)
                }
                onMouse = { btns, dx, dy, w ->
                    val wantLeft = (btns and 1) != 0
                    if (wantLeft != leftDown) {
                        leftDown = wantLeft
                        curController.drag(wantLeft)
                    }
                    if (dx != 0 || dy != 0) {
                        curController.moveMouse(dx, dy)
                    }
                    if (w != 0) {
                        curController.scroll(if (curSettings.invertScroll) w else -w)
                    }
                }
            }
        },
        update = { pad ->
            pad.speed = 1.5f * settings.pointerSpeed
            pad.scroll = 0.05f * settings.scrollSpeed
        }
    )
}

/** Ratón táctil: un dedo mueve, toque = clic, dos dedos desplazan, toque con dos dedos = clic derecho. */
class TouchPad(ctx: Context) : View(ctx) {
    var speed = 1.5f
    var scroll = .05f
    var onMouse: ((Int, Int, Int, Int) -> Unit)? = null
    var onTap: ((Boolean) -> Unit)? = null

    private var btns = 0
    private val zone = HashMap<Int, Int>()
    private val last = HashMap<Int, PointF>()
    private var t0 = 0L
    private var moved = 0f
    private var maxPtr = 0
    private var ax = 0f
    private var ay = 0f
    private var sc = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF1A1F2B.toInt())
        val top = height * .78f
        p.color = 0xFF2C3447.toInt()
        c.drawRect(2f, top, width / 2f - 2f, height - 2f, p)
        c.drawRect(width / 2f + 2f, top, width - 2f, height - 2f, p)
        val ts = minOf(width, height) * .06f
        p.color = 0xFFEDF1FF.toInt()
        p.textAlign = Paint.Align.CENTER
        p.textSize = ts
        c.drawText("Clic izq.", width / 4f, (top + height) / 2f + ts * .35f, p)
        c.drawText("Clic der.", width * .75f, (top + height) / 2f + ts * .35f, p)
        p.color = 0xFF8590A6.toInt()
        c.drawText("Desliza para mover", width / 2f, top / 2f, p)
        c.drawText("Dos dedos: desplazar", width / 2f, top / 2f + ts * 1.6f, p)
    }

    private fun emit(dx: Int, dy: Int, w: Int) {
        onMouse?.invoke(btns, dx, dy, w)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val barTop = height * .78f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val i = e.actionIndex
                val id = e.getPointerId(i)
                if (e.getY(i) > barTop) {
                    val bit = if (e.getX(i) < width / 2f) 1 else 2
                    zone[id] = bit
                    if (bit == 1) {
                        btns = btns or 1
                        emit(0, 0, 0)
                    } else if (onTap == null) {
                        btns = btns or 2
                        emit(0, 0, 0)
                    }
                } else {
                    last[id] = PointF(e.getX(i), e.getY(i))
                    if (last.size == 1) {
                        t0 = e.eventTime
                        moved = 0f
                        maxPtr = 1
                    } else {
                        maxPtr = maxOf(maxPtr, last.size)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                var sdx = 0f
                var sdy = 0f
                for (i in 0 until e.pointerCount) {
                    val l = last[e.getPointerId(i)] ?: continue
                    val dx = e.getX(i) - l.x
                    val dy = e.getY(i) - l.y
                    l.set(e.getX(i), e.getY(i))
                    sdx += dx
                    sdy += dy
                    moved += abs(dx) + abs(dy)
                }
                val n = last.size
                if (n == 1) {
                    ax += sdx * speed
                    ay += sdy * speed
                    val ix = ax.toInt()
                    val iy = ay.toInt()
                    ax -= ix
                    ay -= iy
                    if (ix != 0 || iy != 0) emit(ix, iy, 0)
                } else if (n >= 2) {
                    sc += -sdy / n * scroll
                    val w = sc.toInt()
                    if (w != 0) {
                        sc -= w
                        emit(0, 0, w)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                val z = zone.remove(id)
                if (z != null) {
                    if (z == 2 && onTap != null) {
                        onTap?.invoke(true)
                    } else {
                        btns = zone.values.filter { it == 1 || onTap == null }.fold(0) { a, b -> a or b }
                        emit(0, 0, 0)
                    }
                } else if (last.remove(id) != null && last.isEmpty() && e.eventTime - t0 < 220 && moved < 20f) {
                    val right = maxPtr >= 2
                    val tapHandler = onTap
                    if (tapHandler != null) {
                        tapHandler(right)
                    } else {
                        val b = if (right) 2 else 1
                        onMouse?.invoke(btns or b, 0, 0, 0)
                        onMouse?.invoke(btns, 0, 0, 0)
                    }
                }
                if (zone.isEmpty() && last.isEmpty()) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                zone.clear()
                last.clear()
                btns = 0
                emit(0, 0, 0)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}

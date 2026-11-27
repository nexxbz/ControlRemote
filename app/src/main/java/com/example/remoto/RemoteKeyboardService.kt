package com.example.remoto

import android.content.ComponentName
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager

/**
 * Teclado remoto invisible (IME): envía teclas reales de teclado de PC
 * (Up, Down, Left, Right y Enter) sin mostrar un teclado en pantalla.
 */
class RemoteKeyboardService : InputMethodService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBindInput() {
        super.onBindInput()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onEvaluateInputViewShown(): Boolean = false

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean = false

    override fun onCreateInputView(): View? = null

    fun sendPcKey(keyCode: Int): Boolean {
        val ic = currentInputConnection ?: return false
        val downTime = SystemClock.uptimeMillis()
        val down = KeyEvent(
            downTime,
            downTime,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD
        )
        val up = KeyEvent(
            downTime,
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD
        )
        val sentDown = ic.sendKeyEvent(down)
        val sentUp = ic.sendKeyEvent(up)
        return sentDown || sentUp
    }

    companion object {
        @Volatile
        var instance: RemoteKeyboardService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
            val pkg = context.packageName
            val svc = RemoteKeyboardService::class.java.name
            return imm.enabledInputMethodList.any { it.packageName == pkg && it.serviceName == svc }
        }

        fun isSelected(context: Context): Boolean {
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            ) ?: return false
            val cn = ComponentName(context, RemoteKeyboardService::class.java)
            return current == cn.flattenToShortString() || current == cn.flattenToString()
        }

        fun showPicker(context: Context) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
            imm.showInputMethodPicker()
        }
    }
}

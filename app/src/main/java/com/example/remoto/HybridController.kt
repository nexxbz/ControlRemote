package com.example.remoto

/**
 * Une los dos canales: si hay conexión Bluetooth, el ratón y las teclas principales van por Bluetooth
 * (ratón y teclas reales del sistema); todo lo demás (encendido, brillo, captura...) sigue yendo por wifi.
 */
class HybridController(private val wifi: WifiController, private val bt: BtHidController) : RemoteController {

    val statusText: String
        get() = if (bt.isConnected) {
            "Ratón y teclas reales por Bluetooth" + if (wifi.connected) " · wifi conectado" else ""
        } else {
            wifi.status
        }

    override fun send(key: Key) {
        if (bt.isConnected && bt.supports(key)) bt.send(key) else wifi.send(key)
    }

    override fun sendText(text: String) {
        if (bt.isConnected) bt.sendText(text) else wifi.sendText(text)
    }

    override fun moveMouse(dx: Int, dy: Int) {
        if (bt.isConnected) bt.moveMouse(dx, dy) else wifi.moveMouse(dx, dy)
    }

    override fun click(right: Boolean) {
        if (bt.isConnected) bt.click(right) else wifi.click(right)
    }

    override fun scroll(amount: Int) {
        if (bt.isConnected) bt.scroll(amount) else wifi.scroll(amount)
    }

    override fun drag(start: Boolean) {
        if (bt.isConnected) bt.drag(start) else wifi.drag(start)
    }
}

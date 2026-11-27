package com.example.remoto

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun BluetoothDevice.safeName(): String =
    try { name ?: address } catch (e: SecurityException) { address }

/** Elegir el teléfono para el modo Bluetooth (ratón y teclas reales). */
@Composable
fun BluetoothDialog(
    bt: BtHidController,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onVisible: () -> Unit,
    onBluetoothSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val devices = remember(hasPermission, bt.ready, bt.connectedAddress) { if (hasPermission) bt.bonded() else emptyList() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ratón y teclas por Bluetooth") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Con Bluetooth el otro teléfono ve un ratón y un teclado reales: aparece el puntero del sistema " +
                        "y las flechas y OK son teclas verdaderas. Primero empareja los dos teléfonos por Bluetooth.",
                    fontSize = 13.sp
                )
                Text(
                    when {
                        !hasPermission -> "Falta el permiso de Bluetooth."
                        bt.status.isNotEmpty() -> bt.status
                        !bt.ready -> "Iniciando…"
                        else -> "Toca el teléfono que quieres controlar:"
                    },
                    fontSize = 14.sp
                )
                if (!hasPermission) Button(onClick = onRequestPermission) { Text("Dar permiso") }
                if (bt.failed) Button(onClick = { bt.retry() }) { Text("Reintentar") }
                devices.forEach { d ->
                    TextButton(onClick = { bt.connect(d) }, modifier = Modifier.fillMaxWidth()) {
                        Text(d.safeName() + if (d.address == bt.connectedAddress) "  ✓" else "")
                    }
                }
                if (hasPermission && devices.isEmpty()) {
                    Text("No hay teléfonos emparejados todavía.", fontSize = 13.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onVisible, modifier = Modifier.weight(1f)) { Text("Hacer visible", fontSize = 12.sp) }
                    OutlinedButton(onClick = onBluetoothSettings, modifier = Modifier.weight(1f)) { Text("Ajustes Bluetooth", fontSize = 12.sp) }
                }
                if (bt.isConnected) OutlinedButton(onClick = { bt.disconnect() }) { Text("Desconectar Bluetooth") }
                Text("Cierra otras apps de control Bluetooth: solo una puede usarlo a la vez.", fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

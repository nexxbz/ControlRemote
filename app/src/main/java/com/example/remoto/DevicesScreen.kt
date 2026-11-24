package com.example.remoto

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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

fun BluetoothDevice.safeName(): String =
    try { name ?: address } catch (e: SecurityException) { address }

fun deviceIcon(d: BluetoothDevice): ImageVector {
    val major = try { d.bluetoothClass?.majorDeviceClass } catch (e: SecurityException) { null }
    return when (major) {
        BluetoothClass.Device.Major.PHONE -> Icons.Filled.PhoneAndroid
        BluetoothClass.Device.Major.AUDIO_VIDEO -> Icons.Filled.Tv
        BluetoothClass.Device.Major.COMPUTER -> Icons.Filled.Computer
        else -> Icons.Filled.Devices
    }
}

@Composable
fun DevicesScreen(
    status: String,
    permissionGranted: Boolean,
    devices: List<BluetoothDevice>,
    lastAddress: String?,
    onRequestPermission: () -> Unit,
    onPair: () -> Unit,
    onHelp: () -> Unit,
    onSettings: () -> Unit,
    onPick: (BluetoothDevice) -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Dispositivos", color = Ico, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onPair) { Icon(Icons.Filled.Bluetooth, null, tint = Ico, modifier = Modifier.size(30.dp)) }
            IconButton(onClick = onHelp) { Icon(Icons.Filled.Info, null, tint = Ico, modifier = Modifier.size(30.dp)) }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, null, tint = Ico, modifier = Modifier.size(30.dp)) }
        }

        // Tarjeta de información / estado
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Btn).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Info, null, tint = Ico)
                Text("Información", color = Ico, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                if (status.isNotEmpty()) status else "Ante cualquier problema, consulte la sección de ayuda.",
                color = Ico, fontSize = 16.sp
            )
        }

        Text("Dispositivos emparejados", color = Color.Gray, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))

        if (!permissionGranted) {
            Text("Falta el permiso de Bluetooth para ver los dispositivos.", color = Color.Gray)
            Button(onClick = onRequestPermission) { Text("Dar permiso") }
        } else if (devices.isEmpty()) {
            Text(
                "No hay dispositivos emparejados. Toca el ícono de Bluetooth para emparejar tu TV o teléfono.",
                color = Color.Gray
            )
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(devices, key = { it.address }) { d ->
                var open by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(d) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(60.dp).clip(CircleShape).background(Color(0xFFA8C7FA)),
                        contentAlignment = Alignment.Center
                    ) { Icon(deviceIcon(d), null, tint = Color(0xFF062E6F), modifier = Modifier.size(30.dp)) }
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(d.safeName(), color = Ico, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(d.address, color = Ico, fontSize = 17.sp)
                    }
                    if (d.address == lastAddress) {
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(Btn).padding(horizontal = 22.dp, vertical = 12.dp)
                        ) { Text("Auto", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    }
                    Box {
                        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, null, tint = Ico) }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            DropdownMenuItem(text = { Text("Conectar") }, onClick = { open = false; onPick(d) })
                        }
                    }
                }
            }
        }
    }
}

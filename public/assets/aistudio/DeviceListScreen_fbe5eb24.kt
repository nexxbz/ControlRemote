package com.example.remoto

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions

@Composable
fun DeviceListScreen(
    devices: List<Device>,
    onAdd: (Device) -> Unit,
    onDelete: (Device) -> Unit,
    onOpen: (Device) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Color.Black).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mis dispositivos", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("TVs y teléfonos Android con ADB por red activado.", color = Color.Gray, fontSize = 13.sp)

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(devices) { d ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF0F1014))
                        .clickable { onOpen(d) }.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, color = Color.White, fontSize = 20.sp)
                        Text("${d.host}:${d.port}", color = Color.Gray, fontSize = 13.sp)
                    }
                    IconButton(onClick = { onDelete(d) }) { Icon(Icons.Filled.Delete, null, tint = Color.Gray) }
                }
            }
        }
        Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Agregar dispositivo") }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var host by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("5555") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Nuevo dispositivo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Nombre (ej. TCL 30 Z)") }, singleLine = true)
                    OutlinedTextField(host, { host = it }, label = { Text("Dirección IP (ej. 192.168.1.50)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(port, { port = it }, label = { Text("Puerto") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = host.isNotBlank() && port.toIntOrNull() != null,
                    onClick = {
                        onAdd(Device(name.ifBlank { host.trim() }, host.trim(), port.toInt()))
                        showAdd = false
                    }
                ) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancelar") } }
        )
    }
}

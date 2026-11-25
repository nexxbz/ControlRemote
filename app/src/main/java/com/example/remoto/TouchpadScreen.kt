package com.example.remoto

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TouchpadScreen(controller: RemoteController, onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Ico) }
            Text("Panel táctil", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        // Panel + franja de desplazamiento
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(Btn)
                    .pointerInput(Unit) { detectTapGestures(onTap = { controller.click(false) }) }
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            controller.moveMouse((drag.x * 2.2f).toInt(), (drag.y * 2.2f).toInt())
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Desliza para mover el cursor\nToca para hacer clic",
                    color = Color.Gray, textAlign = TextAlign.Center
                )
            }
            Column(Modifier.width(64.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Btn)
                        .clickable { controller.scroll(3) },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.KeyboardArrowUp, null, tint = Ico) }
                Box(
                    Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Btn)
                        .clickable { controller.scroll(-3) },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.KeyboardArrowDown, null, tint = Ico) }
            }
        }

        // Botones: izquierdo, atrás, derecho (mantener pulsado)
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PadButton(Modifier.weight(1f), "Clic") { controller.click(false) }
            PadButton(Modifier.weight(1f), "Atrás") { controller.send(Key.BACK) }
            PadButton(Modifier.weight(1f), "Mantener") { controller.click(true) }
        }
    }
}

@Composable
private fun PadButton(modifier: Modifier, label: String, onClick: () -> Unit) {
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(Btn).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, color = Ico, fontSize = 16.sp) }
}

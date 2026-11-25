package com.example.remoto

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Ico) }
            Text("Panel táctil", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(Btn)
                .pointerInput(Unit) { detectTapGestures(onTap = { controller.click(false) }) }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        controller.moveMouse((drag.x * 2.2f).toInt(), (drag.y * 2.2f).toInt())
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text("Desliza para mover el cursor en el otro teléfono\nToca para hacer clic", color = Color.Gray, textAlign = TextAlign.Center)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { controller.scroll(3) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Subir") }
            Button(onClick = { controller.scroll(-3) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Bajar") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { controller.click(false) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Clic izquierdo") }
            OutlinedButton(onClick = { controller.click(true) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Mantener pulsado") }
        }
    }
}

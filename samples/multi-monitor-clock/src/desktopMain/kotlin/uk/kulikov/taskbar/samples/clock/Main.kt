package uk.kulikov.taskbar.samples.clock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import uk.kulikov.taskbar.TaskBarAlignment
import uk.kulikov.taskbar.TaskBarTargets
import uk.kulikov.taskbar.WindowsTaskBar
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A clock on **every** taskbar, the way ElevenClock puts one on secondary displays that Windows 11
 * leaves bare.
 *
 * `targets = TaskBarTargets.All` creates one widget instance per taskbar, each with its own
 * composition and its own geometry — so each instance can lay itself out for the display it is on.
 * `taskBar.monitorDeviceName` and `taskBar.isPrimaryMonitor` tell it which one that is.
 *
 * Plug a second monitor in or unplug one while this is running: `Shell_SecondaryTrayWnd` appearing
 * or disappearing adds and removes an instance without a restart.
 */
fun main() = application {
    var showZone by remember { mutableStateOf(false) }

    WindowsTaskBar(
        size = DpSize(width = 132.dp, height = Dp.Unspecified),
        alignment = TaskBarAlignment.End,
        targets = TaskBarTargets.All,
        contextMenu = {
            checkbox("Show time zone", checked = showZone) { showZone = it }
            separator()
            item("Quit") { exitApplication() }
        },
    ) {
        val now by produceState(LocalDateTime.now()) {
            while (true) {
                value = LocalDateTime.now()
                delay(1_000)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp)
                .background(Color(0x99000000), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(
                    text = now.format(TIME),
                    style = TextStyle(color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center),
                )
                BasicText(
                    // Every instance knows which display it landed on.
                    text = if (showZone) {
                        ZoneId.systemDefault().id
                    } else {
                        taskBar.monitorDeviceName.removePrefix("\\\\.\\") +
                            if (taskBar.isPrimaryMonitor) " • primary" else ""
                    },
                    style = TextStyle(color = Color(0xFF9E9E9E), fontSize = 8.sp, textAlign = TextAlign.Center),
                )
            }
        }
    }
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

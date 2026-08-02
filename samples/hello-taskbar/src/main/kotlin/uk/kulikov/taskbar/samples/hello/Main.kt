package uk.kulikov.taskbar.samples.hello

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import uk.kulikov.taskbar.TaskBarDiagnostics
import uk.kulikov.taskbar.WindowsTaskBar
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The smallest possible taskbar widget: a clock, in the taskbar, in about ten lines.
 *
 * Nothing else is running — no main window, no tray icon. `application { }` stays alive as long
 * as the widget is composed.
 */
fun main() {
    // Handy first thing to look at if the widget does not show up where you expected.
    println(TaskBarDiagnostics.report())

    application {
        WindowsTaskBar {
            val time by produceState(LocalTime.now()) {
                while (true) {
                    value = LocalTime.now()
                    delay(1_000)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .background(Color(0xFF0F6CBD), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = time.format(FORMATTER),
                    style = TextStyle(color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center),
                )
            }
        }
    }
}

private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

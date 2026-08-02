package uk.kulikov.taskbar.samples.sysmon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import uk.kulikov.taskbar.TaskBarAlignment
import uk.kulikov.taskbar.WindowsTaskBar
import java.lang.management.ManagementFactory

/**
 * A CPU/RAM read-out in the empty space at the left of a centred Windows 11 taskbar — the
 * Rainmeter-skin genre of taskbar widget, reproduced in Compose.
 *
 * Shows two things worth copying:
 *
 * * `clickThrough = true` makes the widget purely decorative. It sets `WS_EX_TRANSPARENT`, so
 *   clicks land on the taskbar underneath and Windows still shows the normal taskbar tooltips
 *   and context menu through it.
 * * Because a click-through widget cannot offer its own menu, quitting is wired to a Compose
 *   `Tray`. `WindowsTaskBar` and `Tray` coexist happily in one `application { }`.
 */
fun main() = application {
    WindowsTaskBar(
        size = DpSize(width = 128.dp, height = Dp.Unspecified),
        // The left end of a Windows 11 taskbar is empty when the buttons are centred.
        alignment = TaskBarAlignment.Start,
        clickThrough = true,
    ) {
        val sample by produceState(SystemSample.EMPTY) {
            while (true) {
                value = readSystem()
                delay(1_000)
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Gauge("CPU", sample.cpuPercent, Color(0xFF4FC3F7))
            Gauge("RAM", sample.ramPercent, Color(0xFFFFB74D))
        }
    }

    Tray(
        icon = DotPainter(Color(0xFF4FC3F7)),
        tooltip = "System monitor",
        menu = { Item("Quit", onClick = ::exitApplication) },
    )
}

@Composable
private fun Gauge(label: String, percent: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            text = label,
            style = TextStyle(color = Color(0xFFBDBDBD), fontSize = 9.sp),
            modifier = Modifier.width(26.dp),
        )
        Canvas(Modifier.weight(1f).height(6.dp)) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(Color(0x33FFFFFF), size = size, cornerRadius = radius)
            drawRoundRect(
                color = color,
                size = Size(size.width * percent / 100f, size.height),
                cornerRadius = radius,
            )
        }
        Spacer(Modifier.width(5.dp))
        BasicText(
            text = "$percent%",
            style = TextStyle(color = Color.White, fontSize = 9.sp),
            modifier = Modifier.width(26.dp),
        )
    }
}

private class SystemSample(val cpuPercent: Int, val ramPercent: Int) {
    companion object {
        val EMPTY = SystemSample(0, 0)
    }
}

private fun readSystem(): SystemSample {
    val os = ManagementFactory.getOperatingSystemMXBean()
    val cpu = (os as? com.sun.management.OperatingSystemMXBean)?.cpuLoad ?: -1.0
    val total = (os as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize ?: 0L
    val free = (os as? com.sun.management.OperatingSystemMXBean)?.freeMemorySize ?: 0L
    val ram = if (total > 0) (total - free).toDouble() / total else 0.0
    return SystemSample(
        cpuPercent = (cpu.coerceAtLeast(0.0) * 100).toInt(),
        ramPercent = (ram * 100).toInt(),
    )
}

/** A tray icon without shipping an image resource. */
private class DotPainter(private val color: Color) : Painter() {
    override val intrinsicSize: Size = Size(16f, 16f)

    override fun DrawScope.onDraw() {
        drawCircle(color, radius = size.minDimension / 2, center = Offset(size.width / 2, size.height / 2))
    }
}

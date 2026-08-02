package uk.kulikov.taskbar.samples.prayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import uk.kulikov.taskbar.TaskBarAlignment
import uk.kulikov.taskbar.WindowsTaskBar
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * A countdown to the next event, with a settings panel that slides out of the taskbar — the shape
 * of Awqat-Salaat, the WinUI prayer-times widget this library was partly designed against.
 *
 * It exercises the combination that widget needs: a compact always-visible read-out, a click that
 * opens a full panel above the taskbar, a right-click menu, and a status callback so the app can
 * tell whether it is actually injected.
 *
 * The timetable is fixed data. Computing real prayer times needs a location and an astronomical
 * model, which is the widget's business rather than the library's.
 */
fun main() = application {
    var accentedWhenClose by remember { mutableStateOf(true) }
    var showSeconds by remember { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(false) }

    WindowsTaskBar(
        size = DpSize(width = 156.dp, height = Dp.Unspecified),
        alignment = TaskBarAlignment.BeforeTray,
        onClick = { panelOpen = !panelOpen },
        contextMenu = {
            checkbox("Highlight when close", checked = accentedWhenClose) { accentedWhenClose = it }
            checkbox("Show seconds", checked = showSeconds) { showSeconds = it }
            separator()
            item("Open panel") { panelOpen = true }
            separator()
            item("Quit") { exitApplication() }
        },
        onStatusChanged = { println("[prayer-countdown] status: $it") },
    ) {
        val now by produceState(LocalTime.now()) {
            while (true) {
                value = LocalTime.now()
                delay(500)
            }
        }
        val next = TIMETABLE.nextAfter(now)
        val remaining = Duration.between(now, next.second).let {
            if (it.isNegative) it.plusDays(1) else it
        }
        val urgent = accentedWhenClose && remaining.toMinutes() < 15

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
                .background(
                    if (urgent) Color(0xFF8E4B10) else Color(0xB3303030),
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column {
                BasicText(
                    text = next.first,
                    style = TextStyle(color = Color(0xFFBDBDBD), fontSize = 9.sp),
                )
                BasicText(
                    text = remaining.format(showSeconds),
                    style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                )
            }
        }

        Flyout(
            visible = panelOpen,
            onDismissRequest = { panelOpen = false },
            size = DpSize(280.dp, 268.dp),
        ) {
            MaterialTheme(colors = darkColors()) {
                // A Surface rather than a bare Modifier.background: it is what publishes
                // LocalContentColor, so Text picks a readable colour instead of Material's
                // black-on-anything default.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF1E1E1E),
                    contentColor = Color(0xFFF0F0F0),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Today", style = MaterialTheme.typography.subtitle1)
                        TIMETABLE.forEach { (name, at) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    text = name,
                                    color = if (name == next.first) Color(0xFFFFB74D) else Color(0xFFF0F0F0),
                                )
                                Text(at.format(HOUR_MINUTE), color = Color(0xFFBDBDBD))
                            }
                        }
                        Divider(Modifier.padding(vertical = 4.dp), color = Color(0x33FFFFFF))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Show seconds", style = MaterialTheme.typography.body2)
                            Switch(checked = showSeconds, onCheckedChange = { showSeconds = it })
                        }
                    }
                }
            }
        }
    }
}

private val TIMETABLE = listOf(
    "Fajr" to LocalTime.of(5, 12),
    "Dhuhr" to LocalTime.of(12, 38),
    "Asr" to LocalTime.of(15, 51),
    "Maghrib" to LocalTime.of(19, 4),
    "Isha" to LocalTime.of(20, 33),
)

private fun List<Pair<String, LocalTime>>.nextAfter(now: LocalTime): Pair<String, LocalTime> =
    firstOrNull { it.second > now } ?: first()

private fun Duration.format(withSeconds: Boolean): String = if (withSeconds) {
    "%d:%02d:%02d".format(toHours(), toMinutesPart(), toSecondsPart())
} else {
    "%dh %02dm".format(toHours(), toMinutesPart())
}

private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

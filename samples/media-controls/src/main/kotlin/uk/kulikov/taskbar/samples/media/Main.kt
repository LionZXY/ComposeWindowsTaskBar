package uk.kulikov.taskbar.samples.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Slider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import uk.kulikov.taskbar.TaskBarAlignment
import uk.kulikov.taskbar.WindowsTaskBar
import uk.kulikov.taskbar.rememberTaskBarState
import kotlin.time.Duration.Companion.seconds

/**
 * Transport controls in the taskbar, with a scrubber that opens above it.
 *
 * The interesting parts:
 *
 * * `movable = true` — drag the widget along the taskbar. The offset lands in
 *   [uk.kulikov.taskbar.TaskBarState.offset], which the app can persist. Dragging only starts on
 *   the widget's inert areas: the buttons consume the gesture first.
 * * `Flyout` — a widget is clipped to the taskbar's ~48dp, so anything bigger has to live in a
 *   separate window. This one holds the scrubber and volume.
 *
 * The player is simulated. Reading real playback state means talking to WinRT's
 * `GlobalSystemMediaTransportControlsSessionManager`, which is outside what this library does.
 */
fun main() = application {
    val state = rememberTaskBarState()
    var player by remember { mutableStateOf(Player()) }
    var flyoutOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1.seconds)
            if (player.isPlaying) player = player.advanced()
        }
    }

    WindowsTaskBar(
        state = state,
        size = DpSize(width = 208.dp, height = Dp.Unspecified),
        alignment = TaskBarAlignment.BeforeTray,
        movable = true,
        onClick = { flyoutOpen = !flyoutOpen },
        contextMenu = {
            item(if (player.isPlaying) "Pause" else "Play") { player = player.toggled() }
            separator()
            submenu("Position") {
                item("Restart") { player = player.seekTo(0) }
                item("Skip 30s") { player = player.seekTo(player.positionSeconds + 30) }
            }
            separator()
            item("Quit") { exitApplication() }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
                .background(Color(0xE0202020), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TransportButton("⏮") { player = player.seekTo(0) }
            TransportButton(if (player.isPlaying) "⏸" else "▶") { player = player.toggled() }
            TransportButton("⏭") { player = player.next() }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                BasicText(
                    text = player.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Color.White, fontSize = 11.sp),
                )
                BasicText(
                    text = "${player.position} / ${player.duration}",
                    style = TextStyle(color = Color(0xFF9E9E9E), fontSize = 9.sp),
                )
            }
        }

        Flyout(
            visible = flyoutOpen,
            onDismissRequest = { flyoutOpen = false },
            size = DpSize(320.dp, 150.dp),
        ) {
            MaterialTheme(colors = darkColors()) {
                // A Surface rather than a bare Modifier.background: it is what publishes
                // LocalContentColor, so Text picks a readable colour instead of Material's
                // black-on-anything default.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF202020),
                    contentColor = Color(0xFFF0F0F0),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(player.title, style = MaterialTheme.typography.subtitle1)
                        Text(player.artist, style = MaterialTheme.typography.caption, color = Color(0xFF9E9E9E))
                        Slider(
                            value = player.positionSeconds.toFloat(),
                            valueRange = 0f..player.durationSeconds.toFloat(),
                            onValueChange = { player = player.seekTo(it.toInt()) },
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(player.position, style = MaterialTheme.typography.caption)
                            Text(player.duration, style = MaterialTheme.typography.caption)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportButton(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = glyph,
            style = TextStyle(color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center),
        )
    }
}

private class Player(
    val title: String = "Nocturne in E-flat major",
    val artist: String = "Frédéric Chopin",
    val positionSeconds: Int = 42,
    val durationSeconds: Int = 271,
    val isPlaying: Boolean = true,
) {
    val position: String get() = positionSeconds.asClock()
    val duration: String get() = durationSeconds.asClock()

    fun toggled() = copy(isPlaying = !isPlaying)

    fun advanced() = copy(positionSeconds = (positionSeconds + 1) % (durationSeconds + 1))

    fun seekTo(seconds: Int) = copy(positionSeconds = seconds.coerceIn(0, durationSeconds))

    fun next() = Player(title = "Gymnopédie No. 1", artist = "Erik Satie", positionSeconds = 0, durationSeconds = 195)

    private fun copy(
        positionSeconds: Int = this.positionSeconds,
        isPlaying: Boolean = this.isPlaying,
    ) = Player(title, artist, positionSeconds, durationSeconds, isPlaying)
}

private fun Int.asClock(): String = "%d:%02d".format(this / 60, this % 60)

package uk.kulikov.taskbar.samples.playground

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Slider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import uk.kulikov.taskbar.InjectionMode
import uk.kulikov.taskbar.TaskBarAlignment
import uk.kulikov.taskbar.TaskBarDiagnostics
import uk.kulikov.taskbar.TaskBarStatus
import uk.kulikov.taskbar.TaskBarTargets
import uk.kulikov.taskbar.WindowsTaskBar
import uk.kulikov.taskbar.rememberTaskBarState
import androidx.compose.foundation.text.BasicText

/**
 * Every option on [WindowsTaskBar], wired to a control, so the whole surface can be exercised
 * against a live taskbar without editing code.
 *
 * A normal Compose `Window` drives the widget: change an alignment, drag the size slider, flip
 * click-through, switch between reparenting and the overlay fallback, and watch the widget react.
 * The status pane shows what the library reports back.
 */
fun main() = application {
    val state = rememberTaskBarState()

    var visible by remember { mutableStateOf(true) }
    var widthDp by remember { mutableStateOf(220f) }
    var fullHeight by remember { mutableStateOf(true) }
    var heightDp by remember { mutableStateOf(32f) }
    var alignment by remember { mutableStateOf<TaskBarAlignment>(TaskBarAlignment.BeforeTray) }
    var targets by remember { mutableStateOf<TaskBarTargets>(TaskBarTargets.Primary) }
    var movable by remember { mutableStateOf(true) }
    var clickThrough by remember { mutableStateOf(false) }
    var mode by remember {
        mutableStateOf(
            runCatching { InjectionMode.valueOf(System.getProperty("taskbar.mode") ?: "Auto") }
                .getOrDefault(InjectionMode.Auto),
        )
    }
    var clicks by remember { mutableStateOf(0) }
    var rightClicks by remember { mutableStateOf(0) }
    var lastStatus by remember { mutableStateOf<TaskBarStatus>(TaskBarStatus.Initializing) }

    WindowsTaskBar(
        visible = visible,
        state = state,
        size = DpSize(widthDp.dp, if (fullHeight) Dp.Unspecified else heightDp.dp),
        alignment = alignment,
        targets = targets,
        movable = movable,
        clickThrough = clickThrough,
        injectionMode = mode,
        onClick = { clicks++ },
        onRightClick = { rightClicks++ },
        contextMenu = {
            item("Reset offset") { state.offset = DpOffset.Zero }
            checkbox("Movable", checked = movable) { movable = it }
            separator()
            submenu("Alignment") {
                ALIGNMENTS.forEach { (label, value) ->
                    item(label) { alignment = value }
                }
            }
            separator()
            item("Quit") { exitApplication() }
        },
        onStatusChanged = { lastStatus = it },
    ) {
        var hovered by remember { mutableStateOf(false) }
        var innerClicks by remember { mutableStateOf(0) }

        // A visibly bounded widget, so alignment and size changes are easy to see. The counters
        // show the difference between the three ways of handling a click: `I` is an ordinary
        // `Modifier.clickable` inside the widget, `L`/`R` are the widget-level callbacks, which
        // only fire for clicks the content did not consume.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (hovered) Color(0xCCD32F2F) else Color(0xCC1565C0),
                    RoundedCornerShape(6.dp),
                )
                .pointerInputHover { hovered = it }
                .clickableCounter { innerClicks++ },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = "widget ${taskBar.widgetBounds.width}×${taskBar.widgetBounds.height}px  " +
                    "L$clicks R$rightClicks I$innerClicks",
                style = TextStyle(color = Color.White, fontSize = 10.sp),
            )
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(width = 520.dp, height = 720.dp),
        title = "compose-windows-taskbar playground",
    ) {
        MaterialTheme(colors = darkColors()) {
            // Surface, not Modifier.background: it publishes LocalContentColor, without which
            // Material's Text defaults to black and disappears on a dark background.
            Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Widget", style = MaterialTheme.typography.h6)
                    Toggle("Visible", visible) { visible = it }
                    Toggle("Movable (drag the widget)", movable) { movable = it }
                    Toggle("Click-through (inert)", clickThrough) { clickThrough = it }
                    Toggle("Fill taskbar height", fullHeight) { fullHeight = it }

                    Text("Width: ${widthDp.toInt()}dp")
                    Slider(widthDp, { widthDp = it }, valueRange = 60f..600f)
                    if (!fullHeight) {
                        Text("Height: ${heightDp.toInt()}dp")
                        Slider(heightDp, { heightDp = it }, valueRange = 12f..64f)
                    }

                    Text("Alignment", style = MaterialTheme.typography.subtitle2)
                    Choices(ALIGNMENTS, alignment) { alignment = it }

                    Text("Targets", style = MaterialTheme.typography.subtitle2)
                    Choices(TARGETS, targets) { targets = it }

                    Text("Injection mode", style = MaterialTheme.typography.subtitle2)
                    Choices(InjectionMode.entries.map { it.name to it }, mode) { mode = it }

                    Text("Offset: ${state.offset}", style = MaterialTheme.typography.caption)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button({ state.offset = DpOffset.Zero }) { Text("Reset offset") }
                        Button({ println(TaskBarDiagnostics.report()) }) { Text("Print diagnostics") }
                    }

                    Text("Status", style = MaterialTheme.typography.h6)
                    Text(
                        text = lastStatus.describe(),
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        color = Color(0xFFBDBDBD),
                    )
                }
            }
        }
    }
}

/** Hover feedback, to confirm the widget is receiving pointer input at all. */
private fun Modifier.pointerInputHover(onChange: (Boolean) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                when (awaitPointerEvent().type) {
                    PointerEventType.Enter -> onChange(true)
                    PointerEventType.Exit -> onChange(false)
                }
            }
        }
    }

/** An ordinary in-widget click target, separate from `WindowsTaskBar`'s own `onClick`. */
private fun Modifier.clickableCounter(onClick: () -> Unit): Modifier = clickable(onClick = onClick)

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label)
    }
}

@Composable
private fun <T> Choices(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (label, value) ->
                    Button(
                        onClick = { onSelect(value) },
                        colors = androidx.compose.material.ButtonDefaults.buttonColors(
                            backgroundColor = if (value == selected) {
                                MaterialTheme.colors.primary
                            } else {
                                Color(0xFF3A3A3A)
                            },
                        ),
                    ) {
                        Text(label, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

private val ALIGNMENTS: List<Pair<String, TaskBarAlignment>> = listOf(
    "Start" to TaskBarAlignment.Start,
    "AfterStart" to TaskBarAlignment.AfterStart,
    "AfterTaskList" to TaskBarAlignment.AfterTaskList,
    "Center" to TaskBarAlignment.Center,
    "BeforeTray" to TaskBarAlignment.BeforeTray,
    "End" to TaskBarAlignment.End,
    "Absolute 400dp" to TaskBarAlignment.Absolute(400.dp),
)

private val TARGETS: List<Pair<String, TaskBarTargets>> = listOf(
    "Primary" to TaskBarTargets.Primary,
    "All" to TaskBarTargets.All,
    "Secondary" to TaskBarTargets.Secondary,
)

private fun TaskBarStatus.describe(): String = when (this) {
    is TaskBarStatus.Injected -> with(info) {
        """
        injected
          monitor    $monitorDeviceName${if (isPrimaryMonitor) " (primary)" else ""}
          mode       $activeMode
          edge       $edge   dpi $dpi   scale $scale
          taskbar    $taskBarBounds
          widget     $widgetBounds
          tray       ${trayBounds ?: "<none>"}
          task list  ${taskListBounds ?: "<none>"}
          auto-hide  $isAutoHide
        """.trimIndent()
    }

    is TaskBarStatus.Failed -> "failed: ${error.message}"
    TaskBarStatus.Initializing -> "initializing…"
    TaskBarStatus.WaitingForTaskBar -> "waiting for the taskbar to come back…"
    TaskBarStatus.Detached -> "detached"
}

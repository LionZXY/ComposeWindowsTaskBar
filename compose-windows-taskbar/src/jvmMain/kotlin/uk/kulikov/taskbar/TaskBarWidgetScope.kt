package uk.kulikov.taskbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope

/**
 * Receiver for [WindowsTaskBar] content.
 *
 * Extends [FrameWindowScope], so everything Compose gives window content — the underlying
 * [window], `WindowDraggableArea`, and so on — is available here too.
 */
@Stable
public interface TaskBarWidgetScope : FrameWindowScope {

    /**
     * Live geometry of this widget and the taskbar hosting it.
     *
     * Reading it subscribes to changes, so content that lays itself out differently on a
     * top-docked or vertical taskbar updates by itself. When the taskbar disappears — an
     * `explorer.exe` restart — this keeps reporting the last known geometry rather than becoming
     * unavailable.
     */
    public val taskBar: TaskBarInfo

    /**
     * A panel that pops out of the taskbar, anchored to this widget.
     *
     * Shorthand for [TaskBarFlyout] with `anchor = taskBar`. Widget content is clipped to the
     * taskbar, so this is the way to show anything larger — a settings panel, an expanded
     * reading, a media scrubber.
     */
    @Composable
    public fun Flyout(
        visible: Boolean,
        onDismissRequest: () -> Unit,
        size: DpSize,
        alignment: FlyoutAlignment = FlyoutAlignment.Center,
        offset: DpOffset = DpOffset.Zero,
        gap: Dp = 8.dp,
        focusable: Boolean = true,
        dismissOnFocusLoss: Boolean = true,
        content: @Composable () -> Unit,
    )
}

internal class TaskBarWidgetScopeImpl(
    private val frame: FrameWindowScope,
    private val infoState: State<TaskBarInfo>,
) : TaskBarWidgetScope {

    override val window: ComposeWindow get() = frame.window

    override val taskBar: TaskBarInfo get() = infoState.value

    @Composable
    override fun Flyout(
        visible: Boolean,
        onDismissRequest: () -> Unit,
        size: DpSize,
        alignment: FlyoutAlignment,
        offset: DpOffset,
        gap: Dp,
        focusable: Boolean,
        dismissOnFocusLoss: Boolean,
        content: @Composable () -> Unit,
    ) {
        TaskBarFlyout(
            anchor = taskBar,
            visible = visible,
            onDismissRequest = onDismissRequest,
            size = size,
            alignment = alignment,
            offset = offset,
            gap = gap,
            focusable = focusable,
            dismissOnFocusLoss = dismissOnFocusLoss,
            content = content,
        )
    }
}

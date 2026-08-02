package uk.kulikov.taskbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpOffset

/**
 * Observable state of a [WindowsTaskBar] widget: where it sits relative to its resolved
 * alignment, and whether injection is currently working.
 *
 * Hoist it with [rememberTaskBarState] when you want to read the widget's status, persist a
 * user-chosen position, or move the widget from code.
 */
@Stable
public class TaskBarState internal constructor(initialOffset: DpOffset) {

    /**
     * Shift applied on top of the resolved [TaskBarAlignment], in screen space.
     *
     * This is what `movable = true` writes to while the user drags. Persist it yourself if you
     * want the position to survive a restart, and pass it back as `initialOffset`.
     */
    public var offset: DpOffset by mutableStateOf(initialOffset)

    private val perMonitorOffsets = mutableStateMapOf<String, DpOffset>()

    private val statuses = mutableStateMapOf<String, TaskBarStatus>()

    /**
     * Status of every widget instance, keyed by Win32 display device name.
     *
     * Has one entry with [TaskBarTargets.Primary], and one per display with
     * [TaskBarTargets.All].
     */
    public val instances: Map<String, TaskBarStatus> get() = statuses

    /**
     * Status of the widget — the primary display's instance if there is one, otherwise the
     * first instance, otherwise [TaskBarStatus.Initializing].
     */
    public val status: TaskBarStatus
        get() = statuses.values.firstOrNull { it is TaskBarStatus.Injected }
            ?: statuses.values.firstOrNull()
            ?: TaskBarStatus.Initializing

    /** Live geometry of the widget, or `null` while it is not injected. */
    public val info: TaskBarInfo? get() = (status as? TaskBarStatus.Injected)?.info

    /** `true` when at least one instance is live inside a taskbar. */
    public val isInjected: Boolean get() = statuses.values.any { it is TaskBarStatus.Injected }

    /**
     * The offset in force for one display, falling back to [offset].
     *
     * Dragging a widget on a secondary display only moves that instance, so multi-monitor
     * widgets do not jump around in unison.
     */
    public fun offsetFor(monitorDeviceName: String): DpOffset =
        perMonitorOffsets[monitorDeviceName] ?: offset

    /** Moves one display's instance. Pass `null` to make it follow [offset] again. */
    public fun setOffsetFor(monitorDeviceName: String, value: DpOffset?) {
        if (value == null) perMonitorOffsets.remove(monitorDeviceName) else perMonitorOffsets[monitorDeviceName] = value
    }

    internal fun updateStatus(monitorDeviceName: String, status: TaskBarStatus) {
        if (statuses[monitorDeviceName] != status) statuses[monitorDeviceName] = status
    }
}

/**
 * Creates and remembers a [TaskBarState].
 *
 * @param initialOffset starting value of [TaskBarState.offset]; restore a persisted drag
 *   position here.
 */
@Composable
public fun rememberTaskBarState(initialOffset: DpOffset = DpOffset.Zero): TaskBarState =
    remember { TaskBarState(initialOffset) }

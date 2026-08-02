package uk.kulikov.taskbar

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp

/** Which screen edge a taskbar is docked to. */
public enum class TaskBarEdge {
    Bottom,
    Top,
    Left,
    Right,
    ;

    /** `true` for [Bottom] and [Top], where the taskbar's main axis runs horizontally. */
    public val isHorizontal: Boolean get() = this == Bottom || this == Top
}

/**
 * Where along the taskbar's main axis the widget is placed.
 *
 * On a horizontal taskbar "start" is the left edge and "end" the right edge; on a vertical
 * taskbar they are the top and bottom edges respectively. Positions are resolved against the
 * live taskbar geometry on every reconciliation, so a widget stays put when the taskbar is
 * resized, moved to another edge, or when the number of task buttons changes.
 */
@Immutable
public sealed interface TaskBarAlignment {

    /** Flush with the leading edge of the taskbar. Empty space on a centred Windows 11 taskbar. */
    public data object Start : TaskBarAlignment

    /** Immediately after the Start button / search box. */
    public data object AfterStart : TaskBarAlignment

    /**
     * Immediately after the last button of the legacy task band (`ReBarWindow32`).
     *
     * On Windows 10 this tracks the visible task buttons exactly. On Windows 11 the visible
     * buttons are drawn by a XAML island and the legacy band no longer coincides with them, so
     * prefer [BeforeTray] or [Start] there.
     */
    public data object AfterTaskList : TaskBarAlignment

    /** Centred along the taskbar's main axis. */
    public data object Center : TaskBarAlignment

    /**
     * Immediately before the notification area (`TrayNotifyWnd`).
     *
     * The default, and the safest choice: the notification area is the one region whose
     * geometry Windows still reports faithfully on both Windows 10 and Windows 11.
     */
    public data object BeforeTray : TaskBarAlignment

    /** Flush with the trailing edge of the taskbar. */
    public data object End : TaskBarAlignment

    /** A fixed distance from the leading edge of the taskbar. */
    public data class Absolute(public val offset: Dp) : TaskBarAlignment
}

/** Which taskbars a widget is injected into. */
@Immutable
public sealed interface TaskBarTargets {

    /** Only the taskbar on the primary display (`Shell_TrayWnd`). */
    public data object Primary : TaskBarTargets

    /** Every taskbar, primary and secondary alike. One widget instance per taskbar. */
    public data object All : TaskBarTargets

    /** Only secondary-display taskbars (`Shell_SecondaryTrayWnd`). */
    public data object Secondary : TaskBarTargets

    /** Only the taskbar on the display with this Win32 device name, e.g. `\\.\DISPLAY2`. */
    public data class Monitor(public val deviceName: String) : TaskBarTargets
}

/** How the widget window is attached to the taskbar. */
public enum class InjectionMode {
    /** Try [Reparent], fall back to [Overlay] if reparenting is refused. The default. */
    Auto,

    /**
     * Make the widget a `WS_CHILD` of the taskbar via `SetParent`.
     *
     * This is what the WinUI taskbar-widget projects do. It gives correct z-ordering against
     * taskbar content, and the widget follows the taskbar automatically when it auto-hides,
     * moves or changes DPI.
     */
    Reparent,

    /**
     * Keep a borderless top-most window glued over the taskbar rectangle.
     *
     * No `SetParent`, so no cross-process input-queue attachment — but Windows raises the
     * taskbar above it whenever the taskbar is clicked, so the window has to keep pushing
     * itself forward, and it does not follow taskbar auto-hide.
     */
    Overlay,
}

/** Why injection could not happen, or stopped working. */
@Immutable
public sealed interface TaskBarError {

    /** Human-readable description, suitable for logging. */
    public val message: String

    /** The library only does anything on Windows. */
    public data class UnsupportedOs(public val osName: String) : TaskBarError {
        override val message: String get() = "The Windows taskbar is not available on $osName"
    }

    /** No `explorer.exe`-owned taskbar window was found (yet). */
    public data object TaskBarNotFound : TaskBarError {
        override val message: String get() = "No explorer.exe-owned taskbar window found"
    }

    /** `SetParent` into the taskbar failed. [lastError] is the Win32 `GetLastError` value. */
    public data class ReparentFailed(public val lastError: Int) : TaskBarError {
        override val message: String get() = "SetParent into the taskbar failed (GetLastError=$lastError)"
    }

    /** The host Compose window never became displayable, so it has no `HWND`. */
    public data object HostWindowUnavailable : TaskBarError {
        override val message: String get() = "The host Compose window has no native handle yet"
    }

    /** Anything unforeseen; [cause] is preserved for logging. */
    public data class Unexpected(public val cause: Throwable) : TaskBarError {
        override val message: String get() = "Unexpected taskbar failure: $cause"
    }
}

/** Lifecycle of a single widget instance. */
@Immutable
public sealed interface TaskBarStatus {

    /** The host window is being created. */
    public data object Initializing : TaskBarStatus

    /**
     * The taskbar disappeared — usually `explorer.exe` restarting — and the widget is waiting
     * to re-inject itself. It will recover on its own.
     */
    public data object WaitingForTaskBar : TaskBarStatus

    /** The widget is live inside the taskbar. */
    public data class Injected(public val info: TaskBarInfo) : TaskBarStatus

    /** Injection failed. Check [error]; most causes are retried automatically. */
    public data class Failed(public val error: TaskBarError) : TaskBarStatus

    /** The widget left the composition and its window was disposed. */
    public data object Detached : TaskBarStatus
}

/**
 * Live geometry of one taskbar and of the widget hosted in it.
 *
 * All rectangles are in **physical** screen pixels, matching what Win32 reports, because that
 * is the only coordinate space that is unambiguous across a mixed-DPI multi-monitor setup.
 * Divide by [scale] to get Compose/AWT user-space pixels.
 */
@Immutable
public class TaskBarInfo internal constructor(
    /** `true` when this is the taskbar of the primary display. */
    public val isPrimaryMonitor: Boolean,
    /** Win32 display device name, e.g. `\\.\DISPLAY1`. Stable enough to key widgets by. */
    public val monitorDeviceName: String,
    /** Which edge the taskbar is docked to. */
    public val edge: TaskBarEdge,
    /** Effective DPI of the taskbar's monitor; 96 means 100% scaling. */
    public val dpi: Int,
    /** [dpi] / 96, i.e. the scale factor between Compose `dp` and physical pixels. */
    public val scale: Float,
    /** The whole monitor, in physical screen pixels. */
    public val monitorBounds: IntRect,
    /** The whole taskbar, in physical screen pixels. */
    public val taskBarBounds: IntRect,
    /** The widget, in physical screen pixels. */
    public val widgetBounds: IntRect,
    /** The notification area, if Windows reports one for this taskbar. */
    public val trayBounds: IntRect?,
    /** The legacy task-button band, if present. */
    public val taskListBounds: IntRect?,
    /** `true` when the user has the taskbar set to auto-hide. */
    public val isAutoHide: Boolean,
    /** The mechanism actually in use — never [InjectionMode.Auto]. */
    public val activeMode: InjectionMode,
) {
    // Value equality matters: the widget re-measures itself several times a second, and content
    // must only recompose when the geometry genuinely moved.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TaskBarInfo) return false
        return isPrimaryMonitor == other.isPrimaryMonitor &&
            monitorDeviceName == other.monitorDeviceName &&
            edge == other.edge &&
            dpi == other.dpi &&
            scale == other.scale &&
            monitorBounds == other.monitorBounds &&
            taskBarBounds == other.taskBarBounds &&
            widgetBounds == other.widgetBounds &&
            trayBounds == other.trayBounds &&
            taskListBounds == other.taskListBounds &&
            isAutoHide == other.isAutoHide &&
            activeMode == other.activeMode
    }

    override fun hashCode(): Int {
        var result = isPrimaryMonitor.hashCode()
        result = 31 * result + monitorDeviceName.hashCode()
        result = 31 * result + edge.hashCode()
        result = 31 * result + dpi
        result = 31 * result + scale.hashCode()
        result = 31 * result + monitorBounds.hashCode()
        result = 31 * result + taskBarBounds.hashCode()
        result = 31 * result + widgetBounds.hashCode()
        result = 31 * result + (trayBounds?.hashCode() ?: 0)
        result = 31 * result + (taskListBounds?.hashCode() ?: 0)
        result = 31 * result + isAutoHide.hashCode()
        result = 31 * result + activeMode.hashCode()
        return result
    }

    override fun toString(): String = "TaskBarInfo(monitor=$monitorDeviceName, primary=$isPrimaryMonitor, " +
        "edge=$edge, dpi=$dpi, taskBar=$taskBarBounds, widget=$widgetBounds, tray=$trayBounds, " +
        "autoHide=$isAutoHide, mode=$activeMode)"
}

/** Defaults for [WindowsTaskBar]. */
public object TaskBarDefaults {

    /**
     * Default widget size. An unspecified height means "as tall as the taskbar", which is
     * almost always what a taskbar widget wants.
     */
    public val Size: DpSize = DpSize(width = 180.dp, height = Dp.Unspecified)

    /** How often the widget reconciles itself against the live taskbar geometry. */
    public const val ReconcileIntervalMillis: Long = 400L
}

/** A pointer interaction with the widget as a whole. */
@Stable
public class TaskBarClick internal constructor(
    /** Position within the widget, in Compose pixels. */
    public val x: Float,
    /** Position within the widget, in Compose pixels. */
    public val y: Float,
    /** Position on screen, in physical pixels — handy for anchoring your own popup. */
    public val screenX: Int,
    /** Position on screen, in physical pixels. */
    public val screenY: Int,
    /** Geometry of the taskbar the click happened on. */
    public val info: TaskBarInfo,
)

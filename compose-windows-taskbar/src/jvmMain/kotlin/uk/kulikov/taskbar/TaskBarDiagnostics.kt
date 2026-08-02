package uk.kulikov.taskbar

import uk.kulikov.taskbar.win32.TaskBarDiscovery
import uk.kulikov.taskbar.win32.address

/**
 * A snapshot of what the library can see of the shell right now.
 *
 * Taskbar injection depends on undocumented shell internals that differ between Windows builds,
 * so when a widget lands in the wrong place — or nowhere at all — the first useful question is
 * always "what does Windows actually report?". Print [report] and you have the answer, and a bug
 * report worth filing.
 *
 * ```
 * fun main() {
 *     println(TaskBarDiagnostics.report())
 *     application { WindowsTaskBar { … } }
 * }
 * ```
 */
public object TaskBarDiagnostics {

    /** One [TaskBarSummary] per `explorer.exe`-owned taskbar, primary first. */
    public fun taskBars(): List<TaskBarSummary> = TaskBarDiscovery.discover().map { surface ->
        TaskBarSummary(
            nativeHandle = surface.hwnd.address,
            isPrimary = surface.isPrimary,
            monitorDeviceName = surface.monitorDeviceName,
            edge = surface.edge,
            dpi = surface.dpi,
            bounds = surface.bounds.toString(),
            monitorBounds = surface.monitorBounds.toString(),
            trayBounds = surface.trayBounds?.toString(),
            taskListBounds = surface.taskListBounds?.toString(),
            startBounds = surface.startBounds?.toString(),
            isAutoHide = surface.isAutoHide,
            hasXamlIsland = TaskBarDiscovery.contentBridgeOf(surface.hwnd) != null,
        )
    }

    /** Human-readable version of [taskBars], plus the host environment. */
    public fun report(): String = buildString {
        appendLine("compose-windows-taskbar diagnostics")
        appendLine("  os            : ${TaskBarPlatform.osName} (build ${TaskBarPlatform.windowsBuild ?: "?"})")
        appendLine("  jvm           : ${System.getProperty("java.version")} ${System.getProperty("os.arch")}")
        appendLine("  supported     : ${TaskBarPlatform.isSupported}")
        appendLine("  windows 11+   : ${TaskBarPlatform.isWindows11OrNewer}")
        val bars = if (TaskBarPlatform.isSupported) taskBars() else emptyList()
        if (bars.isEmpty()) {
            appendLine("  taskbars      : none found")
            return@buildString
        }
        appendLine("  taskbars      : ${bars.size}")
        for (bar in bars) {
            appendLine("    - ${bar.monitorDeviceName} ${if (bar.isPrimary) "(primary)" else "(secondary)"}")
            appendLine("      hwnd        0x${bar.nativeHandle.toString(16)}")
            appendLine("      edge        ${bar.edge}   dpi ${bar.dpi}   autoHide ${bar.isAutoHide}")
            appendLine("      bounds      ${bar.bounds}")
            appendLine("      monitor     ${bar.monitorBounds}")
            appendLine("      tray        ${bar.trayBounds ?: "<none>"}")
            appendLine("      task list   ${bar.taskListBounds ?: "<none>"}")
            appendLine("      start       ${bar.startBounds ?: "<none>"}")
            appendLine("      xaml island ${bar.hasXamlIsland}")
        }
    }
}

/** What [TaskBarDiagnostics] found for one taskbar. Strings so it is trivially loggable. */
public class TaskBarSummary internal constructor(
    public val nativeHandle: Long,
    public val isPrimary: Boolean,
    public val monitorDeviceName: String,
    public val edge: TaskBarEdge,
    public val dpi: Int,
    public val bounds: String,
    public val monitorBounds: String,
    public val trayBounds: String?,
    public val taskListBounds: String?,
    public val startBounds: String?,
    public val isAutoHide: Boolean,
    /** `true` when the Windows 11 XAML-Islands host is present on this taskbar. */
    public val hasXamlIsland: Boolean,
)

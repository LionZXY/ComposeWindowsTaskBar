package uk.kulikov.taskbar

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import uk.kulikov.taskbar.internal.Placement
import uk.kulikov.taskbar.win32.NativeTaskBar
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Placement is the one part of the library that is pure arithmetic, and the part most likely to
 * be wrong in a way nobody notices until a widget lands on top of the clock. These pin down the
 * rules against a taskbar geometry taken from a real Windows 11 machine.
 */
class PlacementTest {

    private val fullHeight = DpSize(200.dp, Dp.Unspecified)

    @Test
    fun `before tray sits immediately left of the notification area`() {
        val rect = resolve(TaskBarAlignment.BeforeTray)
        assertEquals(IntRect(1500, 1040, 1700, 1080), rect)
    }

    @Test
    fun `start hugs the leading edge`() {
        assertEquals(IntRect(0, 1040, 200, 1080), resolve(TaskBarAlignment.Start))
    }

    @Test
    fun `end hugs the trailing edge`() {
        assertEquals(IntRect(1720, 1040, 1920, 1080), resolve(TaskBarAlignment.End))
    }

    @Test
    fun `center splits the remaining space`() {
        assertEquals(IntRect(860, 1040, 1060, 1080), resolve(TaskBarAlignment.Center))
    }

    @Test
    fun `after start follows the start button`() {
        assertEquals(IntRect(60, 1040, 260, 1080), resolve(TaskBarAlignment.AfterStart))
    }

    @Test
    fun `after task list follows the task band`() {
        assertEquals(IntRect(500, 1040, 700, 1080), resolve(TaskBarAlignment.AfterTaskList))
    }

    @Test
    fun `absolute measures from the leading edge`() {
        assertEquals(IntRect(100, 1040, 300, 1080), resolve(TaskBarAlignment.Absolute(100.dp)))
    }

    @Test
    fun `alignments fall back to the taskbar edges when the shell reports no children`() {
        val bare = taskBar(tray = null, taskList = null, start = null)
        assertEquals(
            IntRect(1720, 1040, 1920, 1080),
            Placement.resolve(bare, TaskBarAlignment.BeforeTray, fullHeight, DpOffset.Zero),
        )
        assertEquals(
            IntRect(0, 1040, 200, 1080),
            Placement.resolve(bare, TaskBarAlignment.AfterTaskList, fullHeight, DpOffset.Zero),
        )
    }

    @Test
    fun `an unspecified height fills the taskbar and a specified one is centred across it`() {
        val short = Placement.resolve(taskBar(), TaskBarAlignment.Start, DpSize(200.dp, 20.dp), DpOffset.Zero)
        assertEquals(IntRect(0, 1050, 200, 1070), short)
    }

    @Test
    fun `dp is scaled by the taskbar's dpi, not the caller's`() {
        // 150% display: the same 200dp widget has to come out 300 physical pixels wide.
        val scaled = NativeTaskBar(
            hwnd = HWND(Pointer.createConstant(1L)),
            isPrimary = true,
            monitorDeviceName = "\\\\.\\DISPLAY1",
            monitorBounds = IntRect(0, 0, 2880, 1620),
            bounds = IntRect(0, 1560, 2880, 1620),
            edge = TaskBarEdge.Bottom,
            dpi = 144,
            trayBounds = IntRect(2550, 1560, 2880, 1620),
            taskListBounds = null,
            startBounds = null,
            isAutoHide = false,
        )
        val rect = Placement.resolve(scaled, TaskBarAlignment.BeforeTray, fullHeight, DpOffset.Zero)
        assertEquals(IntRect(2250, 1560, 2550, 1620), rect)
        assertEquals(300, rect.width)
        assertEquals(60, rect.height)
    }

    @Test
    fun `a drag offset shifts the widget but can never take it off its own taskbar`() {
        val nudged = Placement.resolve(taskBar(), TaskBarAlignment.Start, fullHeight, DpOffset(40.dp, 0.dp))
        assertEquals(40, nudged.left)

        val yanked = Placement.resolve(taskBar(), TaskBarAlignment.Start, fullHeight, DpOffset(9_000.dp, 0.dp))
        assertEquals(IntRect(1720, 1040, 1920, 1080), yanked)

        val dragged = Placement.resolve(taskBar(), TaskBarAlignment.Start, fullHeight, DpOffset((-9_000).dp, 0.dp))
        assertEquals(0, dragged.left)
    }

    @Test
    fun `a widget wider than the taskbar is clamped rather than overflowing`() {
        val huge = Placement.resolve(taskBar(), TaskBarAlignment.Start, DpSize(5_000.dp, Dp.Unspecified), DpOffset.Zero)
        assertEquals(IntRect(0, 1040, 1920, 1080), huge)
    }

    @Test
    fun `on a vertical taskbar height runs along the bar and width across it`() {
        val sideBar = NativeTaskBar(
            hwnd = HWND(Pointer.createConstant(1L)),
            isPrimary = true,
            monitorDeviceName = "\\\\.\\DISPLAY1",
            monitorBounds = IntRect(0, 0, 1920, 1080),
            bounds = IntRect(0, 0, 62, 1080),
            edge = TaskBarEdge.Left,
            dpi = 96,
            trayBounds = IntRect(0, 940, 62, 1080),
            taskListBounds = null,
            startBounds = null,
            isAutoHide = false,
        )
        // Main axis is vertical, so `height` is the size along the bar and BeforeTray means above.
        val rect = Placement.resolve(
            sideBar,
            TaskBarAlignment.BeforeTray,
            DpSize(Dp.Unspecified, 80.dp),
            DpOffset.Zero,
        )
        assertEquals(IntRect(0, 860, 62, 940), rect)
    }

    @Test
    fun `parent-relative coordinates are the screen rectangle minus the taskbar origin`() {
        val bar = taskBar()
        val screen = Placement.resolve(bar, TaskBarAlignment.BeforeTray, fullHeight, DpOffset.Zero)
        assertEquals(IntRect(1500, 0, 1700, 40), Placement.toParentRelative(screen, bar))
    }

    private fun resolve(alignment: TaskBarAlignment) =
        Placement.resolve(taskBar(), alignment, fullHeight, DpOffset.Zero)

    /** A 1920x1080 display at 100% with a 40px bottom taskbar, matching a stock Windows 10 layout. */
    private fun taskBar(
        tray: IntRect? = IntRect(1700, 1040, 1920, 1080),
        taskList: IntRect? = IntRect(60, 1040, 500, 1080),
        start: IntRect? = IntRect(0, 1040, 60, 1080),
    ) = NativeTaskBar(
        hwnd = HWND(Pointer.createConstant(1L)),
        isPrimary = true,
        monitorDeviceName = "\\\\.\\DISPLAY1",
        monitorBounds = IntRect(0, 0, 1920, 1080),
        bounds = IntRect(0, 1040, 1920, 1080),
        edge = TaskBarEdge.Bottom,
        dpi = 96,
        trayBounds = tray,
        taskListBounds = taskList,
        startBounds = start,
        isAutoHide = false,
    )
}

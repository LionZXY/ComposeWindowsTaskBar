package uk.kulikov.taskbar

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A flyout has to grow away from the screen edge the taskbar is docked to, and stay on screen. */
class FlyoutPlacementTest {

    private val size = DpSize(300.dp, 200.dp)

    @Test
    fun `a bottom taskbar puts the flyout above itself`() {
        val rect = resolve(TaskBarEdge.Bottom, widget = IntRect(1500, 1040, 1700, 1080))
        // Vertically: 1040 (taskbar top) - 8dp gap - 200dp tall.
        // Horizontally: a 300dp flyout centred on a 200dp widget overhangs it by 50 each side.
        assertEquals(IntRect(1450, 832, 1750, 1032), rect)
    }

    @Test
    fun `a top taskbar puts the flyout below itself`() {
        val rect = resolve(
            edge = TaskBarEdge.Top,
            widget = IntRect(1500, 0, 1700, 40),
            taskBar = IntRect(0, 0, 1920, 40),
        )
        assertEquals(48, rect.top)
        assertEquals(248, rect.bottom)
    }

    @Test
    fun `alignment lines the flyout up with the widget along the taskbar`() {
        val widget = IntRect(1500, 1040, 1700, 1080)
        assertEquals(1500, resolve(TaskBarEdge.Bottom, widget, FlyoutAlignment.Start).left)
        assertEquals(1700, resolve(TaskBarEdge.Bottom, widget, FlyoutAlignment.End).right)
        assertEquals(1450, resolve(TaskBarEdge.Bottom, widget, FlyoutAlignment.Center).left)
    }

    @Test
    fun `a flyout anchored near the screen edge is pulled back on screen`() {
        val rect = resolve(TaskBarEdge.Bottom, widget = IntRect(1850, 1040, 1920, 1080))
        assertTrue(rect.right <= 1920, "flyout ran off the right of the monitor: $rect")
        assertEquals(1620, rect.left)
    }

    @Test
    fun `offsets are applied on top of the alignment`() {
        val rect = resolve(
            edge = TaskBarEdge.Bottom,
            widget = IntRect(1500, 1040, 1700, 1080),
            offset = DpOffset((-20).dp, 0.dp),
        )
        assertEquals(1430, rect.left)
    }

    private fun resolve(
        edge: TaskBarEdge,
        widget: IntRect,
        alignment: FlyoutAlignment = FlyoutAlignment.Center,
        taskBar: IntRect = IntRect(0, 1040, 1920, 1080),
        offset: DpOffset = DpOffset.Zero,
    ) = FlyoutPlacement.resolve(
        anchor = TaskBarInfo(
            isPrimaryMonitor = true,
            monitorDeviceName = "\\\\.\\DISPLAY1",
            edge = edge,
            dpi = 96,
            scale = 1f,
            monitorBounds = IntRect(0, 0, 1920, 1080),
            taskBarBounds = taskBar,
            widgetBounds = widget,
            trayBounds = null,
            taskListBounds = null,
            isAutoHide = false,
            activeMode = InjectionMode.Reparent,
        ),
        size = size,
        alignment = alignment,
        offset = offset,
        gap = 8.dp,
    )
}

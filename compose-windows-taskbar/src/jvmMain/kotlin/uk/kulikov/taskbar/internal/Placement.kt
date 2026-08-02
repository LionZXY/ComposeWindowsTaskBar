package uk.kulikov.taskbar.internal

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.isSpecified
import uk.kulikov.taskbar.TaskBarAlignment
import uk.kulikov.taskbar.TaskBarDefaults
import uk.kulikov.taskbar.win32.NativeTaskBar
import kotlin.math.roundToInt

/**
 * Turns a [TaskBarAlignment] plus a requested size into a concrete rectangle in physical
 * screen pixels.
 *
 * The taskbar has a *main* axis (along the bar) and a *cross* axis (its thickness). Alignment
 * only ever moves the widget along the main axis; on the cross axis the widget is centred, and
 * an unspecified cross-axis size means "as thick as the taskbar". That keeps a single set of
 * rules working for bottom, top, left and right taskbars alike.
 */
internal object Placement {

    fun resolve(
        taskBar: NativeTaskBar,
        alignment: TaskBarAlignment,
        size: DpSize,
        offset: DpOffset,
    ): IntRect {
        val scale = taskBar.scale
        val bar = taskBar.bounds
        val horizontal = taskBar.edge.isHorizontal

        val mainStart = if (horizontal) bar.left else bar.top
        val mainEnd = if (horizontal) bar.right else bar.bottom
        val crossStart = if (horizontal) bar.top else bar.left
        val crossExtent = if (horizontal) bar.height else bar.width

        val requestedMain = if (horizontal) size.width else size.height
        val requestedCross = if (horizontal) size.height else size.width

        val defaultMain = if (horizontal) TaskBarDefaults.Size.width else TaskBarDefaults.Size.height
        val mainSize = requestedMain.toPx(scale, fallback = defaultMain.toPx(scale, fallback = 180))
            .coerceIn(1, maxOf(1, mainEnd - mainStart))
        val crossSize = requestedCross.toPx(scale, fallback = crossExtent)
            .coerceIn(1, maxOf(1, crossExtent))

        val alignedMain = when (alignment) {
            TaskBarAlignment.Start -> mainStart
            TaskBarAlignment.AfterStart ->
                taskBar.startBounds?.mainEnd(horizontal) ?: mainStart
            TaskBarAlignment.AfterTaskList ->
                taskBar.taskListBounds?.mainEnd(horizontal)
                    ?: taskBar.startBounds?.mainEnd(horizontal)
                    ?: mainStart
            TaskBarAlignment.Center -> mainStart + (mainEnd - mainStart - mainSize) / 2
            TaskBarAlignment.BeforeTray ->
                (taskBar.trayBounds?.mainStart(horizontal) ?: mainEnd) - mainSize
            TaskBarAlignment.End -> mainEnd - mainSize
            is TaskBarAlignment.Absolute -> mainStart + alignment.offset.toPx(scale, fallback = 0)
        }

        // The user offset is applied in screen space, which is what a drag gesture produces.
        val offsetX = offset.x.toPx(scale, fallback = 0)
        val offsetY = offset.y.toPx(scale, fallback = 0)
        val mainOffset = if (horizontal) offsetX else offsetY
        val crossOffset = if (horizontal) offsetY else offsetX

        // Clamped so a widget can never be dragged or aligned off its own taskbar; it is a
        // child window, so anything outside would simply be clipped away and appear lost.
        val main = (alignedMain + mainOffset).coerceIn(mainStart, maxOf(mainStart, mainEnd - mainSize))
        val cross = (crossStart + (crossExtent - crossSize) / 2 + crossOffset)
            .coerceIn(crossStart, maxOf(crossStart, crossStart + crossExtent - crossSize))

        return if (horizontal) {
            IntRect(left = main, top = cross, right = main + mainSize, bottom = cross + crossSize)
        } else {
            IntRect(left = cross, top = main, right = cross + crossSize, bottom = main + mainSize)
        }
    }

    /** Screen rectangle -> coordinates relative to the taskbar's client area. */
    fun toParentRelative(screen: IntRect, taskBar: NativeTaskBar): IntRect =
        IntRect(
            left = screen.left - taskBar.bounds.left,
            top = screen.top - taskBar.bounds.top,
            right = screen.right - taskBar.bounds.left,
            bottom = screen.bottom - taskBar.bounds.top,
        )

    private fun IntRect.mainStart(horizontal: Boolean) = if (horizontal) left else top

    private fun IntRect.mainEnd(horizontal: Boolean) = if (horizontal) right else bottom

    private fun Dp.toPx(scale: Float, fallback: Int): Int =
        if (isSpecified) (value * scale).roundToInt() else fallback
}

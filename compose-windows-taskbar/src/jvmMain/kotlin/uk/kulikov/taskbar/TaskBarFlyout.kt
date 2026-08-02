package uk.kulikov.taskbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import uk.kulikov.taskbar.internal.createWidgetWindow
import uk.kulikov.taskbar.internal.moveNativeWindow
import uk.kulikov.taskbar.internal.showAt
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import kotlin.math.roundToInt

/** Where a flyout lines up with the widget along the taskbar's main axis. */
public enum class FlyoutAlignment {
    /** Leading edges aligned. */
    Start,

    /** Centres aligned. */
    Center,

    /** Trailing edges aligned. */
    End,
}

/**
 * A panel that pops out of the taskbar, anchored to a widget.
 *
 * A taskbar widget is a *child* of the taskbar, so anything it draws is clipped to the taskbar's
 * few dozen pixels — Compose's own `Popup` cannot escape that. A flyout is therefore a separate
 * borderless, always-on-top window, placed just off the taskbar on the side that faces the
 * desktop, and it is how the reference taskbar widgets show settings panels and media controls.
 *
 * Unlike the widget itself, a flyout can take focus, which is what lets it dismiss when the user
 * clicks elsewhere.
 *
 * ```
 * WindowsTaskBar {
 *     var open by remember { mutableStateOf(false) }
 *     Text("Details", Modifier.clickable { open = !open })
 *     Flyout(visible = open, onDismissRequest = { open = false }, size = DpSize(280.dp, 180.dp)) {
 *         // ordinary Compose content
 *     }
 * }
 * ```
 *
 * @param anchor geometry to attach to — normally [TaskBarWidgetScope.taskBar].
 * @param visible whether the flyout window is shown. Toggle this instead of adding or removing
 *   the call, so the window and its composition are reused.
 * @param onDismissRequest invoked when the user clicks away or presses Escape. It is up to the
 *   caller to flip [visible] in response.
 * @param size flyout size. Both dimensions must be specified.
 * @param alignment how the flyout lines up with the widget along the taskbar.
 * @param offset extra shift applied after [alignment].
 * @param gap space left between the taskbar and the flyout.
 * @param focusable whether the flyout takes keyboard focus when it appears. Needed for text
 *   fields, and for [dismissOnFocusLoss] to work at all.
 * @param dismissOnFocusLoss whether losing focus calls [onDismissRequest].
 */
@Composable
public fun TaskBarFlyout(
    anchor: TaskBarInfo,
    visible: Boolean,
    onDismissRequest: () -> Unit,
    size: DpSize,
    alignment: FlyoutAlignment = FlyoutAlignment.Center,
    offset: DpOffset = DpOffset.Zero,
    gap: Dp = 8.dp,
    focusable: Boolean = true,
    dismissOnFocusLoss: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!TaskBarPlatform.isSupported) return

    val currentContent by rememberUpdatedState(content)
    val currentLocals by rememberUpdatedState(currentCompositionLocalContext)
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    val currentAnchor by rememberUpdatedState(anchor)
    val currentDismissOnFocusLoss by rememberUpdatedState(dismissOnFocusLoss)

    val window = remember {
        createWidgetWindow(alwaysOnTop = true, focusable = focusable).also { created ->
            created.setContent {
                CompositionLocalProvider(currentLocals) {
                    currentContent()
                }
            }
        }
    }

    // AWT keeps reporting the window as visible once it has been shown, because hiding goes
    // through `ShowWindow` rather than `setVisible`. This tracks what is actually on screen, so
    // hiding the flyout does not fire a dismissal for the focus it necessarily loses.
    val shown = remember { BooleanHolder() }

    DisposableEffect(window) {
        val focusListener = object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) = Unit

            override fun windowLostFocus(e: WindowEvent?) {
                if (currentDismissOnFocusLoss && shown.value) currentDismiss()
            }
        }
        window.addWindowFocusListener(focusListener)
        onDispose {
            window.removeWindowFocusListener(focusListener)
            shown.value = false
            window.dispose()
        }
    }

    SideEffect {
        window.focusableWindowState = focusable
        if (visible) {
            val bounds = FlyoutPlacement.resolve(
                anchor = currentAnchor,
                size = size,
                alignment = alignment,
                offset = offset,
                gap = gap,
            )
            // AWT places the first frame, Win32 places every frame after it exactly.
            showAt(window, bounds, currentAnchor.scale)
            moveNativeWindow(window, bounds)
            shown.value = true
            window.toFront()
            if (focusable) window.requestFocus()
        } else if (shown.value || window.isVisible) {
            // Cleared before hiding: hiding costs the window its focus, and that must not be
            // mistaken for the user clicking away.
            shown.value = false
            window.isVisible = false
        }
    }
}

private class BooleanHolder(var value: Boolean = false)

/** Positions a flyout just off the taskbar, clamped to the monitor it belongs to. */
internal object FlyoutPlacement {

    fun resolve(
        anchor: TaskBarInfo,
        size: DpSize,
        alignment: FlyoutAlignment,
        offset: DpOffset,
        gap: Dp,
    ): IntRect {
        val scale = anchor.scale
        fun px(dp: Dp) = (dp.value * scale).roundToInt()

        val width = px(size.width).coerceAtLeast(1)
        val height = px(size.height).coerceAtLeast(1)
        val gapPx = px(gap)
        val widget = anchor.widgetBounds
        val bar = anchor.taskBarBounds
        val monitor = anchor.monitorBounds

        val horizontal = anchor.edge.isHorizontal
        val mainSize = if (horizontal) width else height
        val widgetMainStart = if (horizontal) widget.left else widget.top
        val widgetMainEnd = if (horizontal) widget.right else widget.bottom

        val main = when (alignment) {
            FlyoutAlignment.Start -> widgetMainStart
            FlyoutAlignment.Center -> widgetMainStart + (widgetMainEnd - widgetMainStart - mainSize) / 2
            FlyoutAlignment.End -> widgetMainEnd - mainSize
        }

        // The cross axis always grows away from the screen edge the taskbar is docked to.
        val cross = when (anchor.edge) {
            TaskBarEdge.Bottom -> bar.top - gapPx - height
            TaskBarEdge.Top -> bar.bottom + gapPx
            TaskBarEdge.Left -> bar.right + gapPx
            TaskBarEdge.Right -> bar.left - gapPx - width
        }

        var left: Int
        var top: Int
        if (horizontal) {
            left = main + px(offset.x)
            top = cross + px(offset.y)
        } else {
            left = cross + px(offset.x)
            top = main + px(offset.y)
        }
        left = left.coerceIn(monitor.left, maxOf(monitor.left, monitor.right - width))
        top = top.coerceIn(monitor.top, maxOf(monitor.top, monitor.bottom - height))

        return IntRect(left, top, left + width, top + height)
    }
}

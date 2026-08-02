package uk.kulikov.taskbar.internal

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.IntRect
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import uk.kulikov.taskbar.win32.Win32
import javax.swing.WindowConstants
import kotlin.math.roundToInt

/** Initial size — big enough for Skiko to build a real surface, small enough to be cheap. */
private const val INITIAL_WIDTH = 200
private const val INITIAL_HEIGHT = 48

/**
 * Creates the borderless, transparent Compose window used both for widgets and for flyouts.
 *
 * `isUndecorated` and transparency have to be set before the window becomes displayable, which is
 * why this is a factory rather than a set of properties applied afterwards.
 *
 * The window is left hidden and unplaced; callers must bring it up through [showAt]. Parking it
 * somewhere off-desktop first would be worse than it sounds: Compose picks the default position of
 * an application's *other* windows relative to the windows it already knows about, so a host window
 * sitting at (-32000, -32000) drags the app's real windows off the desktop with it.
 */
internal fun createWidgetWindow(alwaysOnTop: Boolean, focusable: Boolean): ComposeWindow =
    ComposeWindow().apply {
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        isUndecorated = true
        isResizable = false
        isTransparent = true
        this.isAlwaysOnTop = alwaysOnTop
        focusableWindowState = focusable
        // Showing a widget must never pull focus away from whatever the user is doing.
        isAutoRequestFocus = focusable
        setSize(INITIAL_WIDTH, INITIAL_HEIGHT)
    }

/**
 * Shows a window for the first time, already in the right place.
 *
 * Placement goes through AWT here rather than Win32 because a hidden window has no `HWND` to move
 * yet. [scale] converts the physical rectangle Win32 reports into the DPI-independent user space
 * AWT expects; this only has to be close, since [moveNativeWindow] follows immediately and is
 * exact.
 */
internal fun showAt(window: ComposeWindow, screen: IntRect, scale: Float) {
    if (window.isVisible) return
    if (screen.width > 0 && screen.height > 0) {
        window.setBounds(
            (screen.left / scale).roundToInt(),
            (screen.top / scale).roundToInt(),
            (screen.width / scale).roundToInt(),
            (screen.height / scale).roundToInt(),
        )
    }
    window.isVisible = true
}

/** The window's native handle, or `null` before AWT has made it displayable. */
internal fun hwndOf(window: ComposeWindow): HWND? {
    val handle = try {
        window.windowHandle
    } catch (_: Throwable) {
        0L
    }
    return if (handle == 0L) null else HWND(Pointer(handle))
}

/**
 * Moves and resizes a window using physical screen pixels.
 *
 * Deliberately not `Window.setBounds`: AWT interprets bounds in DPI-independent user space and
 * rescales them, which makes exact placement against Win32-reported taskbar geometry impossible
 * on a fractional or mixed-DPI setup. AWT still learns the new size from the resulting `WM_SIZE`.
 */
internal fun moveNativeWindow(window: ComposeWindow, screen: IntRect) {
    val hwnd = hwndOf(window) ?: return
    User32.INSTANCE.SetWindowPos(
        hwnd,
        Win32.HWND_TOP,
        screen.left,
        screen.top,
        screen.width,
        screen.height,
        Win32.SWP_NOACTIVATE or Win32.SWP_NOZORDER,
    )
}

/**
 * Shows or hides a window without going through AWT once it exists.
 *
 * `Window.setVisible` re-applies AWT's own idea of the window styles, which would undo the
 * `WS_CHILD` / `WS_EX_NOACTIVATE` work done by [TaskBarInjector].
 */
internal fun setNativeVisible(window: ComposeWindow, visible: Boolean) {
    val hwnd = hwndOf(window)
    if (hwnd == null) {
        window.isVisible = visible
        return
    }
    User32.INSTANCE.ShowWindow(hwnd, if (visible) Win32.SW_SHOWNA else Win32.SW_HIDE)
}

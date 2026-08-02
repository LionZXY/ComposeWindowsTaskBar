package uk.kulikov.taskbar.internal

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.IntRect
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import uk.kulikov.taskbar.InjectionMode
import uk.kulikov.taskbar.TaskBarError
import uk.kulikov.taskbar.win32.NativeTaskBar
import uk.kulikov.taskbar.win32.TaskBarDiscovery
import uk.kulikov.taskbar.win32.Win32
import uk.kulikov.taskbar.win32.address
import uk.kulikov.taskbar.win32.setWindowLong
import uk.kulikov.taskbar.win32.windowLong

/**
 * Owns the native side of one widget: the styles on the host window, whether it is a child of
 * the taskbar, and where it sits.
 *
 * Everything here must run on the AWT event thread, because it manipulates the same `HWND` that
 * AWT's own window procedure services.
 *
 * ### Why the position is driven from Win32, and re-checked every time
 *
 * Once the host window becomes a `WS_CHILD` its coordinates are relative to the taskbar, but AWT
 * still believes it owns a top-level window: it reads the position back with `GetWindowRect`,
 * which reports *screen* coordinates, and later writes it out with `SetWindowPos`, which for a
 * child window means *parent-relative* coordinates. Every such round trip therefore adds the
 * taskbar's origin again, and the widget walks off the bottom of the screen.
 *
 * That cannot be prevented from outside the JDK, so it is corrected instead: the position is set
 * with `SetWindowPos` in physical pixels, and each reconciliation compares where the window
 * *actually* is against where it should be rather than trusting a cached value. Anything that
 * moves the window — AWT's own round trip, a taskbar move, a DPI change — is undone on the next
 * tick. AWT still picks the size up correctly from the resulting `WM_SIZE`, because a size needs
 * no coordinate space.
 */
internal class TaskBarInjector(private val window: ComposeWindow) {

    private val user32 get() = User32.INSTANCE

    private var savedStyle: Int? = null
    private var savedExStyle: Int? = null

    /** The taskbar we believe we are currently a child of, for cheap change detection. */
    private var parentedTo: Long = 0L
    private var appliedMode: InjectionMode? = null
    private var appliedStyleKey: Int = Int.MIN_VALUE
    private var lastVisible: Boolean? = null

    /** Set when styles change, so the next `SetWindowPos` re-applies the frame. */
    private var frameDirty: Boolean = false

    /** `null` until AWT has made the window displayable. */
    fun hwnd(): HWND? {
        val handle = try {
            window.windowHandle
        } catch (_: Throwable) {
            0L
        }
        if (handle == 0L) return null
        return HWND(Pointer(handle))
    }

    val isAttached: Boolean get() = appliedMode != null

    /** The mode actually in force, once [attach] has succeeded. */
    val activeMode: InjectionMode? get() = appliedMode

    /**
     * Brings the native window in line with [taskBar], [requestedMode] and [rect].
     *
     * Idempotent and cheap when nothing changed, so it is safe to call on a timer.
     */
    fun reconcile(
        taskBar: NativeTaskBar,
        requestedMode: InjectionMode,
        rect: IntRect,
        clickThrough: Boolean,
        visible: Boolean,
    ): TaskBarError? {
        val hwnd = hwnd() ?: return TaskBarError.HostWindowUnavailable

        val reparentWanted = requestedMode != InjectionMode.Overlay
        var mode = if (reparentWanted) InjectionMode.Reparent else InjectionMode.Overlay

        applyStyles(hwnd, mode, clickThrough)

        if (mode == InjectionMode.Reparent) {
            val currentParent = user32.GetParent(hwnd)?.address ?: 0L
            if (currentParent != taskBar.hwnd.address) {
                Native.setLastError(0)
                user32.SetParent(hwnd, taskBar.hwnd)
                val lastError = Native.getLastError()
                // Trust GetParent, not the return value: SetParent legitimately returns null
                // when the previous parent was the desktop.
                val settled = user32.GetParent(hwnd)?.address ?: 0L
                if (settled != taskBar.hwnd.address) {
                    if (requestedMode == InjectionMode.Reparent) {
                        return TaskBarError.ReparentFailed(lastError)
                    }
                    // Auto mode: fall back to an overlay and re-style for it.
                    mode = InjectionMode.Overlay
                    applyStyles(hwnd, mode, clickThrough)
                }
            }
        } else if ((user32.GetParent(hwnd)?.address ?: 0L) != 0L) {
            user32.SetParent(hwnd, null)
        }

        parentedTo = if (mode == InjectionMode.Reparent) taskBar.hwnd.address else 0L
        appliedMode = mode

        val target = if (mode == InjectionMode.Reparent) Placement.toParentRelative(rect, taskBar) else rect
        val zOrder = if (mode == InjectionMode.Reparent) Win32.HWND_TOP else Win32.HWND_TOPMOST

        // Compared against where the window really is, not against what we last asked for, so
        // AWT's screen-coordinates-as-parent-relative drift is corrected rather than cached over.
        val misplaced = visible && TaskBarDiscovery.windowRect(hwnd) != rect
        val visibilityChanged = visible != user32.IsWindowVisible(hwnd) || visible != lastVisible

        // An overlay additionally has to keep re-asserting its z-order: Windows raises the
        // taskbar above foreign top-most windows whenever the taskbar itself is clicked.
        if (misplaced || visibilityChanged || frameDirty || mode == InjectionMode.Overlay) {
            var flags = Win32.SWP_NOACTIVATE
            flags = flags or if (visible) Win32.SWP_SHOWWINDOW else Win32.SWP_HIDEWINDOW
            if (frameDirty) flags = flags or Win32.SWP_FRAMECHANGED
            if (!misplaced && !visibilityChanged && !frameDirty) {
                flags = flags or Win32.SWP_NOMOVE or Win32.SWP_NOSIZE
            }
            user32.SetWindowPos(
                hwnd,
                zOrder,
                target.left,
                target.top,
                target.width,
                target.height,
                flags,
            )
            frameDirty = false
            lastVisible = visible
        }
        return null
    }

    /**
     * Restores the window to a normal top-level window and un-parents it.
     *
     * Called before disposal so AWT tears down a window shaped the way it created it, and so a
     * surviving `explorer.exe` is not left holding a child window that is about to vanish.
     */
    fun detach() {
        val hwnd = hwnd() ?: return
        try {
            if (parentedTo != 0L) {
                user32.SetParent(hwnd, null)
                parentedTo = 0L
            }
            savedStyle?.let { hwnd.setWindowLong(Win32.GWL_STYLE, it) }
            savedExStyle?.let { hwnd.setWindowLong(Win32.GWL_EXSTYLE, it) }
            user32.ShowWindow(hwnd, Win32.SW_HIDE)
        } catch (_: Throwable) {
            // Disposal must never throw; a failed restore only affects a window we are about
            // to destroy anyway.
        }
        appliedMode = null
        appliedStyleKey = Int.MIN_VALUE
        frameDirty = false
        lastVisible = null
    }

    private fun applyStyles(hwnd: HWND, mode: InjectionMode, clickThrough: Boolean) {
        val key = mode.ordinal * 2 + if (clickThrough) 1 else 0
        if (appliedStyleKey == key) return

        val style = hwnd.windowLong(Win32.GWL_STYLE)
        val exStyle = hwnd.windowLong(Win32.GWL_EXSTYLE)
        if (savedStyle == null) {
            savedStyle = style
            savedExStyle = exStyle
        }

        val decorations = Win32.WS_CAPTION or Win32.WS_THICKFRAME or Win32.WS_SYSMENU or
            Win32.WS_MINIMIZEBOX or Win32.WS_MAXIMIZEBOX or Win32.WS_MINIMIZE or Win32.WS_MAXIMIZE

        val targetStyle = when (mode) {
            InjectionMode.Reparent ->
                // WS_CHILD has to be in place *before* SetParent when the new parent is not the
                // desktop; WS_CLIPSIBLINGS keeps us from painting over sibling shell windows.
                (style or Win32.WS_CHILD or Win32.WS_CLIPSIBLINGS) and
                    Win32.WS_POPUP.inv() and decorations.inv()

            else ->
                (style or Win32.WS_POPUP or Win32.WS_CLIPSIBLINGS) and
                    Win32.WS_CHILD.inv() and decorations.inv()
        }

        // WS_EX_LAYERED is deliberately left exactly as AWT set it: it is how the JDK
        // implements per-pixel translucency, and both adding and removing it behind AWT's back
        // breaks rendering.
        val chrome = Win32.WS_EX_APPWINDOW or Win32.WS_EX_WINDOWEDGE or Win32.WS_EX_CLIENTEDGE or
            Win32.WS_EX_STATICEDGE or Win32.WS_EX_DLGMODALFRAME

        var targetExStyle = (exStyle or Win32.WS_EX_NOACTIVATE or Win32.WS_EX_TOOLWINDOW) and chrome.inv()
        targetExStyle = when (mode) {
            InjectionMode.Reparent -> targetExStyle and Win32.WS_EX_TOPMOST.inv()
            else -> targetExStyle or Win32.WS_EX_TOPMOST
        }
        targetExStyle = if (clickThrough) {
            targetExStyle or Win32.WS_EX_TRANSPARENT
        } else {
            targetExStyle and Win32.WS_EX_TRANSPARENT.inv()
        }

        if (targetStyle != style) hwnd.setWindowLong(Win32.GWL_STYLE, targetStyle)
        if (targetExStyle != exStyle) hwnd.setWindowLong(Win32.GWL_EXSTYLE, targetExStyle)
        appliedStyleKey = key
        // Style changes only take effect once the frame is recalculated.
        frameDirty = true
    }
}

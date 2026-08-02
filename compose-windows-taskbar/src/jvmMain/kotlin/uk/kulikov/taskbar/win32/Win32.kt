package uk.kulikov.taskbar.win32

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.win32.StdCallLibrary

/**
 * The handful of `user32` entry points that `jna-platform`'s [com.sun.jna.platform.win32.User32]
 * does not declare, or declares in a shape that is awkward for bit-mask work.
 *
 * Function names are spelled exactly as they are exported, so no JNA function mapper is
 * involved — that matters because `GetDpiForWindow` has no `W` variant and would break under
 * the usual [com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS] mapper. None of these take
 * strings, so no type mapper is needed either.
 */
internal interface User32Extra : StdCallLibrary {

    fun GetWindowLongPtrW(hWnd: HWND, nIndex: Int): Long

    fun SetWindowLongPtrW(hWnd: HWND, nIndex: Int, dwNewLong: Long): Long

    fun GetWindowLongW(hWnd: HWND, nIndex: Int): Int

    fun SetWindowLongW(hWnd: HWND, nIndex: Int, dwNewLong: Int): Int

    /** Windows 10 1607+. Callers must tolerate [UnsatisfiedLinkError]. */
    fun GetDpiForWindow(hWnd: HWND): Int

    companion object {
        val INSTANCE: User32Extra = Native.load("user32", User32Extra::class.java)
    }
}

/** Win32 constants used by the taskbar injection, kept in one place so they are auditable. */
internal object Win32 {

    // ---- GetWindowLongPtr / SetWindowLongPtr indices ----
    const val GWL_STYLE: Int = -16
    const val GWL_EXSTYLE: Int = -20

    // ---- Window styles ----
    const val WS_POPUP: Int = 0x80000000.toInt()
    const val WS_CHILD: Int = 0x40000000
    const val WS_MINIMIZE: Int = 0x20000000
    const val WS_CLIPSIBLINGS: Int = 0x04000000
    const val WS_MAXIMIZE: Int = 0x01000000

    /** `WS_BORDER or WS_DLGFRAME`, so clearing it clears both. */
    const val WS_CAPTION: Int = 0x00C00000
    const val WS_SYSMENU: Int = 0x00080000
    const val WS_THICKFRAME: Int = 0x00040000
    const val WS_MINIMIZEBOX: Int = 0x00020000
    const val WS_MAXIMIZEBOX: Int = 0x00010000

    // ---- Extended window styles ----
    const val WS_EX_DLGMODALFRAME: Int = 0x00000001
    const val WS_EX_TOPMOST: Int = 0x00000008
    const val WS_EX_TRANSPARENT: Int = 0x00000020
    const val WS_EX_TOOLWINDOW: Int = 0x00000080
    const val WS_EX_WINDOWEDGE: Int = 0x00000100
    const val WS_EX_CLIENTEDGE: Int = 0x00000200
    const val WS_EX_STATICEDGE: Int = 0x00020000
    const val WS_EX_APPWINDOW: Int = 0x00040000

    /** Set by the JDK for per-pixel translucency. Never touched here; see `TaskBarInjector`. */
    const val WS_EX_LAYERED: Int = 0x00080000
    const val WS_EX_NOACTIVATE: Int = 0x08000000

    // ---- SetWindowPos flags ----
    const val SWP_NOSIZE: Int = 0x0001
    const val SWP_NOMOVE: Int = 0x0002
    const val SWP_NOZORDER: Int = 0x0004
    const val SWP_NOACTIVATE: Int = 0x0010
    const val SWP_FRAMECHANGED: Int = 0x0020
    const val SWP_SHOWWINDOW: Int = 0x0040
    const val SWP_HIDEWINDOW: Int = 0x0080

    // ---- ShowWindow commands ----
    const val SW_HIDE: Int = 0

    /** Show without activating, and without changing the active window. */
    const val SW_SHOWNA: Int = 8

    // ---- SetWindowPos z-order anchors. HWND_TOP is literally 0, i.e. a null HWND. ----
    val HWND_TOP: HWND? = null
    val HWND_TOPMOST: HWND = HWND(Pointer.createConstant(-1L))

    // ---- Window messages we care about ----
    const val WM_DESTROY: Int = 0x0002
    const val WM_CLOSE: Int = 0x0010
    const val WM_SETTINGCHANGE: Int = 0x001A
    const val WM_DISPLAYCHANGE: Int = 0x007E
    const val WM_DPICHANGED: Int = 0x02E0

    // ---- MonitorFromWindow ----
    const val MONITOR_DEFAULTTONEAREST: Int = 2

    // ---- OpenProcess ----
    const val PROCESS_QUERY_LIMITED_INFORMATION: Int = 0x1000

    // ---- SHAppBarMessage ----
    const val ABM_GETSTATE: Int = 0x0004
    const val ABS_AUTOHIDE: Int = 0x0001

    /** Baseline DPI: 96 dpi == 100% scaling. */
    const val USER_DEFAULT_SCREEN_DPI: Int = 96

    val isWindows: Boolean = Platform.isWindows()

    val is64Bit: Boolean = Platform.is64Bit()
}

/** Reads `GWL_STYLE` / `GWL_EXSTYLE`, transparently handling 32-bit Windows. */
internal fun HWND.windowLong(index: Int): Int =
    if (Win32.is64Bit) {
        User32Extra.INSTANCE.GetWindowLongPtrW(this, index).toInt()
    } else {
        User32Extra.INSTANCE.GetWindowLongW(this, index)
    }

/** Writes `GWL_STYLE` / `GWL_EXSTYLE`, transparently handling 32-bit Windows. */
internal fun HWND.setWindowLong(index: Int, value: Int) {
    if (Win32.is64Bit) {
        // Style bits live in the low 32 bits; sign-extension is irrelevant because the
        // upper half is reserved and must be zero for GWL_STYLE / GWL_EXSTYLE.
        User32Extra.INSTANCE.SetWindowLongPtrW(this, index, value.toLong() and 0xFFFFFFFFL)
    } else {
        User32Extra.INSTANCE.SetWindowLongW(this, index, value)
    }
}

/** `GetDpiForWindow`, degrading to 96 dpi on Windows builds that lack it. */
internal fun HWND.dpiOrDefault(): Int =
    try {
        User32Extra.INSTANCE.GetDpiForWindow(this).takeIf { it > 0 } ?: Win32.USER_DEFAULT_SCREEN_DPI
    } catch (_: UnsatisfiedLinkError) {
        Win32.USER_DEFAULT_SCREEN_DPI
    } catch (_: Throwable) {
        Win32.USER_DEFAULT_SCREEN_DPI
    }

internal val HWND.address: Long get() = Pointer.nativeValue(pointer)

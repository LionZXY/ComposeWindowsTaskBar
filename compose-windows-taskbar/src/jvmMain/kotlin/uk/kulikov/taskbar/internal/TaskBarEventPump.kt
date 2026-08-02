package uk.kulikov.taskbar.internal

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import uk.kulikov.taskbar.win32.Win32
import java.util.concurrent.atomic.AtomicInteger

/**
 * A hidden window that listens for the shell events which invalidate an injected widget.
 *
 * `explorer.exe` announces a fresh taskbar by broadcasting the registered `TaskbarCreated`
 * message — that is the documented way to learn that the shell restarted and that anything a
 * process put in the taskbar is gone. Windows re-broadcasts it when the primary display's DPI
 * changes as well.
 *
 * Two details matter:
 *
 * * The listener must be a real top-level window, **not** a message-only (`HWND_MESSAGE`)
 *   window, because `HWND_BROADCAST` posts skip message-only windows entirely.
 * * The message loop needs its own thread. Running it on the AWT event thread is not possible
 *   (AWT owns that loop), so the pump owns a daemon thread and hands events back to the
 *   listener, which re-dispatches them onto the event thread.
 */
internal class TaskBarEventPump(private val onShellEvent: () -> Unit) {

    private val user32 get() = User32.INSTANCE

    @Volatile
    private var hwnd: HWND? = null

    @Volatile
    private var running = false

    private var thread: Thread? = null

    private var taskbarCreatedMessage = 0

    // The callback must stay strongly referenced for as long as Windows can invoke it.
    private val windowProc = object : WinUser.WindowProc {
        override fun callback(hWnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT {
            when (uMsg) {
                Win32.WM_DESTROY -> {
                    user32.PostQuitMessage(0)
                    return LRESULT(0)
                }

                Win32.WM_CLOSE -> {
                    user32.DestroyWindow(hWnd)
                    return LRESULT(0)
                }

                Win32.WM_DISPLAYCHANGE, Win32.WM_SETTINGCHANGE, Win32.WM_DPICHANGED -> notifyShellEvent()
                else -> if (uMsg != 0 && uMsg == taskbarCreatedMessage) notifyShellEvent()
            }
            return user32.DefWindowProc(hWnd, uMsg, wParam, lParam)
        }
    }

    private fun notifyShellEvent() {
        try {
            onShellEvent()
        } catch (_: Throwable) {
            // A listener failure must not take down the message loop.
        }
    }

    fun start() {
        if (!Win32.isWindows || running) return
        running = true
        thread = Thread({ pump() }, "compose-windows-taskbar-shell-events").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        hwnd?.let { user32.PostMessage(it, Win32.WM_CLOSE, WPARAM(0), LPARAM(0)) }
        thread?.join(1_000)
        thread = null
    }

    private fun pump() {
        val className = "uk.kulikov.taskbar.ShellEvents.${INSTANCE_COUNTER.incrementAndGet()}"
        val module = Kernel32.INSTANCE.GetModuleHandle(null)
        val instance = WinDef.HINSTANCE().apply { pointer = module.pointer }

        val windowClass = WinUser.WNDCLASSEX().apply {
            cbSize = size()
            lpfnWndProc = windowProc
            hInstance = instance
            lpszClassName = className
        }
        if (user32.RegisterClassEx(windowClass).toInt() == 0) {
            running = false
            return
        }

        val created = user32.CreateWindowEx(
            0,
            className,
            "compose-windows-taskbar",
            0, // no WS_VISIBLE: a top-level window that is never shown still gets broadcasts
            0, 0, 0, 0,
            null, null, instance, null,
        )
        if (created == null) {
            user32.UnregisterClass(className, instance)
            running = false
            return
        }
        hwnd = created
        taskbarCreatedMessage = user32.RegisterWindowMessage("TaskbarCreated")

        try {
            val message = WinUser.MSG()
            while (running) {
                val result = user32.GetMessage(message, null, 0, 0)
                if (result <= 0) break // 0 == WM_QUIT, -1 == error
                user32.TranslateMessage(message)
                user32.DispatchMessage(message)
            }
        } catch (_: Throwable) {
            // Fall through to cleanup; the reconcile timer keeps the widget alive regardless.
        } finally {
            hwnd = null
            user32.UnregisterClass(className, instance)
            running = false
        }
    }

    private companion object {
        val INSTANCE_COUNTER = AtomicInteger()
    }
}

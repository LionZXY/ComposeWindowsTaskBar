package uk.kulikov.taskbar

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/**
 * What this machine can do.
 *
 * Injecting content into the taskbar is a Windows-only trick, so on any other operating system
 * [WindowsTaskBar] composes nothing and reports [TaskBarError.UnsupportedOs] once. That keeps
 * multiplatform desktop apps compiling and running unchanged.
 */
public object TaskBarPlatform {

    /** Name of the host operating system, as reported by the JVM. */
    public val osName: String = System.getProperty("os.name") ?: "unknown"

    /** `true` on Windows. */
    public val isWindows: Boolean = Platform.isWindows()

    /** `true` when taskbar injection can be attempted at all. */
    public val isSupported: Boolean get() = isWindows

    /**
     * Windows build number, e.g. `26200` for Windows 11 25H2, or `null` when it cannot be
     * determined or the host is not Windows.
     *
     * Useful for gating around shell changes: the taskbar was rewritten as a XAML-Islands
     * surface in Windows 11 (build 22000+), which is where deskbands stopped existing and where
     * the legacy task-band geometry stopped matching what is drawn.
     */
    public val windowsBuild: Int? by lazy {
        if (!isWindows) return@lazy null
        // `os.version` is "10.0" on both Windows 10 and 11 and carries no build, so read the
        // authoritative value from the registry and fall back to parsing only if that fails.
        runCatching {
            Advapi32Util.registryGetStringValue(
                WinReg.HKEY_LOCAL_MACHINE,
                "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion",
                "CurrentBuild",
            ).toInt()
        }.getOrNull()
            ?: System.getProperty("os.version")?.split('.')?.getOrNull(2)?.toIntOrNull()
    }

    /** `true` on Windows 11 or newer, where the taskbar is a XAML-Islands surface. */
    public val isWindows11OrNewer: Boolean
        get() = isWindows && (windowsBuild?.let { it >= 22_000 } ?: osName.contains("11"))
}

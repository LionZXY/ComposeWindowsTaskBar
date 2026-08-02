# Implementation Plan: `compose-windows-taskbar` — A Compose for Desktop Library for Injecting Composable Widgets into the Windows Taskbar

## TL;DR
- **Build a Kotlin Multiplatform (JVM-desktop) library exposing an application-scope `@Composable` `WindowsTaskBar { }` that mirrors Compose's own `Tray`/`Window`/`DialogWindow` pattern; under the hood it hosts a Compose `ComposeWindow` (undecorated + transparent), grabs its native `HWND`, and reparents it into `Shell_TrayWnd` via `SetParent`, exactly as the reference WinUI projects (TaskbarQuota, Awqat-Salaat WinUI) do.**
- **Use JNA (with `jna-platform`) for the Win32 interop in v1** because it ships its own native stubs transitively through Maven and requires no custom DLL, satisfying the "native bindings ship with the dependency" requirement with zero packaging work; keep the native boundary behind an interface so a Java 22+ FFM/Panama backend can replace it later.
- **This is an inherently fragile, undocumented technique** — Windows 11's taskbar is a rewritten XAML-Islands surface, injected widgets overlap when the taskbar fills, and explorer restarts wipe the widget. The plan therefore treats robustness (TaskbarCreated re-injection, DPI/multi-monitor handling, graceful no-op off-Windows) as first-class, and recommends true `SetParent` reparenting over a floating topmost overlay, with the overlay retained as a documented fallback.

## Key Findings

1. **The reference projects all reparent a child window into the taskbar; there is no supported API.** Awqat-Salaat's own README states plainly: *"The widget is injected into the taskbar, thus it's not a part of it. The widget overlaps the taskbar content which can lead to overlapping issue when the taskbar become full."* TaskbarQuota's README describes the identical mechanism: it *"reparents a layered WinUI island into Shell_TrayWnd."* Both make their widget a `WS_CHILD` of `Shell_TrayWnd` via `SetParent`; this is confirmed behaviorally in Awqat-Salaat because its UIAutomation `StructureChangedEvent` bubbles to the taskbar parent. Our library must replicate this — but from a JVM/Compose window rather than a WinUI island.

2. **There are two historically distinct techniques, and the deprecated one is dead on Windows 11.** Awqat-Salaat ships in two forms: a **Deskband** (using the `CSDeskBand` COM library, the "official" `IDeskBand` shell-extension mechanism) which its README says *"takes advantage of a feature called Desk Band which is an old feature and has been deprecated and removed completely from Windows 11. However, the older vesions (Windows 7, 8, 8.1 and 10) still support this feature"*; and a **WinUI** form that does raw `SetParent` injection and works on Windows 11. Because the deskband/`IDeskBand` COM route is gone on Win11, our library must use the raw window-reparenting route.

3. **Compose for Desktop is a Swing/AWT app under the hood and its window HWND is directly reachable.** `androidx.compose.ui.awt.ComposeWindow` extends `java.awt.Window`/`JFrame`; it exposes a `windowHandle` (native pointer) that community code already wraps as `WinDef.HWND(Pointer(window.windowHandle))` to call User32 functions like `SetWindowLongPtr`, `ShowWindow`, etc. Compose supports `undecorated = true` + `transparent = true` windows. Rendering is done by Skiko, which per JetBrains uses a *"new DirectX 12 renderer"* on Windows and *"will gracefully fall back to an OpenGL-based renderer – and, if even that fails, to an all-new software renderer."*

4. **Compose's `Tray`/`Window`/`DialogWindow` are all `@Composable` extension functions on `ApplicationScope`**, made the default window API in Compose Milestone 4/Alpha. They use `remember`/`rememberUpdatedState`/`DisposableEffect`-style lifecycle to create and dispose native resources within the composition. `WindowsTaskBar` should follow this exact idiom: `fun ApplicationScope.WindowsTaskBar(...)`.

5. **Windows 11's taskbar is a from-scratch XAML-Islands rewrite**, which is why the mechanics are undocumented and version-fragile. The top-level window class is still `Shell_TrayWnd` (owned by `explorer.exe`), secondary-monitor taskbars are `Shell_SecondaryTrayWnd`, and modern content is hosted in composition/XAML host child windows (e.g. `Windows.UI.Composition.DesktopWindowContentBridge` / `DesktopWindowXamlSource`). A critical gotcha (documented by the Windhawk project): `FindWindow("Shell_TrayWnd")` can return a spoofed window created by other taskbar tools (YASB, Zebar, Managed Shell), so the library must validate the owning process is `explorer.exe` via `GetWindowThreadProcessId`.

6. **Robustness concerns are well documented and must be designed in.** Explorer broadcasts a registered window message when it (re)creates the taskbar; per Microsoft, *"When the taskbar is created, it registers a message with the TaskbarCreated string and then broadcasts this message to all top-level windows... it should assume that any taskbar icons it added have been removed and add them again"* (also re-broadcast on primary-display DPI changes). DPI must be handled per-monitor — the `SetParent` docs warn that *"Unexpected behavior or errors may occur if hWndNewParent and hWndChild are running in different DPI awareness modes."* On Windows 11 the system tray/clock live only on the primary taskbar, and Skiko renders on the AWT event thread — a performance consideration for an always-on widget.

## Details

### A. Reverse-engineered injection technique (the core spike)

The reference projects and the broader ecosystem converge on this Win32 sequence, which the library's native layer must implement:

1. **Locate the taskbar.** `FindWindow("Shell_TrayWnd", null)` for the primary taskbar; loop `FindWindowEx(null, prev, "Shell_SecondaryTrayWnd", null)` for each secondary-monitor taskbar. **Validate** each handle's owning process is `explorer.exe` (`GetWindowThreadProcessId` → `QueryFullProcessImageName`) to avoid third-party spoof windows.
2. **Optionally drill into children** with `FindWindowEx` to compute placement geometry: on classic/Win10 layouts the hierarchy is `Shell_TrayWnd → ReBarWindow32 → (MSTaskSwWClass → MSTaskListWClass)` for the task-button area, plus `TrayNotifyWnd` (notification area) and `TrayClockWClass` (clock). On Win11 the tray geometry is discovered from the XAML host child windows / via `GetWindowRect` on the tray region. Detecting the Widgets button and the tray lets the widget avoid overlap.
3. **Prepare the Compose child window.** Get the `ComposeWindow` HWND. Set styles with `SetWindowLongPtr(GWL_STYLE, ...)`: clear `WS_POPUP`, set `WS_CHILD` (the `SetParent` docs require setting `WS_CHILD` *before* the call when reparenting into a non-desktop parent). Set `GWL_EXSTYLE` to include `WS_EX_NOACTIVATE` (widget clicks don't steal focus/activate) and `WS_EX_TOOLWINDOW` (no alt-tab entry); optionally `WS_EX_LAYERED` for per-pixel alpha and `WS_EX_TRANSPARENT` when click-through is requested. TaskbarQuota's README explicitly describes its island as **layered** (`WS_EX_LAYERED`).
4. **Reparent:** `SetParent(composeHwnd, taskbarHwnd)`. Synchronize DPI awareness between the two windows.
5. **Position & size:** `SetWindowPos(composeHwnd, HWND_TOP, x, y, w, h, SWP_NOACTIVATE)` positioned relative to the tray/start per the requested alignment, DPI-scaled with `GetDpiForWindow`/monitor DPI.
6. **Survive explorer restarts:** register the broadcast message via `RegisterWindowMessage("TaskbarCreated")`, run a hidden message-pump window (or an AWT-side timer/poll, as TaskbarQuota does with its ~20-attempt retry loop), and re-run steps 1–5 on receipt. Also re-verify placement when the taskbar moves, the alignment changes (center vs. left), or monitor topology changes.

**Windows 10 vs 11 differences the spike must characterize:** Win10's taskbar is classic Win32 child controls (deskband/`IDeskBand` still available); Win11's is XAML Islands (deskband gone, reparenting still works but geometry discovery differs, tray only on primary display). The library should branch on OS build.

**Overlay fallback (documented, not default):** Instead of `SetParent`, create a borderless topmost `WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW` window glued over the taskbar rectangle and continuously repositioned (the Rainmeter "Stay Topmost" approach). Rainmeter's community confirms this is workable but "Windows is going to fight them when you click on the taskbar" — the overlay flickers to the back on taskbar clicks and must force itself forward. **Recommendation: default to true `SetParent` reparenting** (matches both reference WinUI projects, integrates with taskbar z-order and auto-hide, and doesn't fight the shell), and expose the overlay as an opt-in fallback for environments where reparenting fails.

### B. Hosting Compose inside a foreign native window

- Create an `androidx.compose.ui.awt.ComposeWindow` (or `ComposePanel` inside a minimal `JWindow`) configured `isUndecorated = true`, transparent background, `focusableWindowState = false`. Set its content via `setContent { }` and drive it from the composition.
- Obtain `HWND` from `ComposeWindow.windowHandle` and wrap as JNA `WinDef.HWND(Pointer(handle))`.
- **Transparency caveat:** a known Compose issue is that a transparent undecorated window can remain opaque to hit-testing; per-pixel alpha via `WS_EX_LAYERED` semantics or careful `background = Color(0,0,0,0)` handling is required. The spike must verify Skiko renders correctly (with alpha) inside a `WS_CHILD` window; **DirectX/OpenGL context creation inside a reparented child window is the single highest-risk unknown and should be validated first.**
- **Threading:** all AWT window manipulation happens on the EDT (`SwingUtilities.invokeLater`); all Win32 calls that touch the Compose HWND should be marshaled consistently. Skiko renders on the EDT, so keep widget content light.

### C. Public API design

```kotlin
@Composable
fun ApplicationScope.WindowsTaskBar(
    state: TaskBarWidgetState = rememberTaskBarWidgetState(),
    alignment: TaskBarAlignment = TaskBarAlignment.NearTray, // NearStart, NearTray, Custom(offset)
    size: DpSize = DpSize(160.dp, TaskBarDefaults.height),   // height auto-derived from taskbar
    monitors: TaskBarTargets = TaskBarTargets.Primary,       // Primary, All, Specific(id)
    movable: Boolean = false,            // drag to reposition; offset persisted via state
    clickable: Boolean = true,
    clickThrough: Boolean = false,       // WS_EX_TRANSPARENT
    visible: Boolean = true,
    onClick: (() -> Unit)? = null,
    onRightClick: (() -> Unit)? = null,  // context-menu hook
    onInjectionFailed: (TaskBarError) -> Unit = {},
    content: @Composable () -> Unit
)
```

Supporting API: `rememberTaskBarWidgetState()` (holds resolved bounds, per-monitor drag offsets, injection status); a `contextMenu { Item(...) }` DSL mirroring `Tray`'s `MenuScope`; `TaskBarAlignment` with a `Custom(offsetDp)` variant; and a companion overlay/flyout helper so a widget can open a popup above itself (as Awqat-Salaat's settings panel and the media-widget flyouts do).

**Non-Windows behavior:** on macOS/Linux the composable is a graceful no-op that invokes `onInjectionFailed(UnsupportedOs)` once and composes nothing, so cross-platform apps still compile and run.

**Sufficiency check against reference projects:** the parameter set reproduces (a) Awqat-Salaat's prayer-times widget with clickable flyout settings panel (`onClick` + flyout helper + `contextMenu`), (b) the okt/11-taskbar-widgets Rainmeter CPU/RAM system-stats skin (static content, `clickThrough = true`), (c) BMedia-style media controls (interactive content, `movable = true`, `NearStart` alignment to fill the empty space of a centered Win11 taskbar), and (d) ElevenClock-style multi-clock (`monitors = All`, custom click behavior). This confirms the surface is expressive enough to re-implement the existing tech-stack projects on Compose Desktop.

### D. Native interop decision

**Recommendation: JNA + `jna-platform` for v1.** Rationale:
- **Zero custom native packaging.** JNA ships its own platform native stubs inside its own Maven artifacts; declaring `implementation("net.java.dev.jna:jna")` and `jna-platform` means consumers get all needed natives *transitively*, directly satisfying the spec's "native bindings are also provided with the dependency" requirement with no DLL to build, sign, extract, or ship. `jna-platform` already provides `User32`, `WinDef.HWND`, `WinUser` constants, and `WindowUtils`.
- **No C toolchain / no per-arch build matrix.** Custom JNI would require compiling and shipping `x64` + `arm64` DLLs and extracting them at runtime from the JAR — real, ongoing maintenance burden.
- **Performance is a non-issue here.** JNA's dynamic-dispatch overhead (benchmarked at roughly an order of magnitude over JNI) is irrelevant for a handful of window calls per second on a small widget.

**Trade-off & migration path:** JNA is slower than JNI/FFM, and a few calls we need (`SetWindowLongPtr`, custom `WndProc` subclassing to catch `TaskbarCreated`) require care in JNA. **Keep the entire Win32 surface behind an internal `TaskbarNativeBridge` interface** so a **Java 22+ FFM/Panama** backend (finalized under JEP 454 in JDK 22, March 2024 — after preview in JEP 424/434/442 — with ~90% less boilerplate than JNI, benchmarks equal-to-or-faster than JNI, and no shipped glue code when binding to system libraries like `user32.dll`) can be swapped in for a v2 that drops the JNA dependency entirely. FFM is the strategically correct long-term target because `user32.dll` is always present on the target system, so FFM needs no bundled natives either.

### E. Project structure, publishing, CI, validation

**Module layout (Gradle, Kotlin Multiplatform even though only the JVM/desktop target ships initially, to future-proof the API surface and expect/actual boundary):**
- `taskbar-core` — the KMP library: `commonMain` holds the `expect` composable + no-op default; `desktopMain` (jvm) holds the real Compose hosting + `TaskbarNativeBridge` (JNA impl). Depends on `compose.desktop.currentOs`/`compose.runtime` (compileOnly where possible so consumers control the Compose version) and JNA.
- `samples/` — several runnable consumer apps (see below).
- Root config: `libs.versions.toml`, `com.vanniktech.maven.publish` plugin.

**Publishing:** use the `com.vanniktech.maven.publish` plugin to Maven Central via the Central Portal — it handles the KMP publication, POM, sources/javadoc (Dokka) jars, and in-memory GPG signing on CI. Pick permanent coordinates (e.g. `io.github.<org>:compose-windows-taskbar`). Publish the KMP root module (`*.module` Gradle metadata) plus the JVM target so JNA flows transitively. Snapshots to the snapshot repo; use semantic versioning starting `0.1.0` while the injection technique stabilizes across Windows builds.

**CI:** GitHub Actions with a **Windows runner** (`windows-latest`) is mandatory because the only meaningful integration tests require a real `explorer.exe` taskbar. Matrix over Windows 10 and Windows 11 images where available; run the injection smoke test headed. A Linux/mac job verifies the no-op path compiles and runs. Gate releases on the Windows job.

**Validation plan / sample apps** (each consumes the library as an *external* dependency via `mavenLocal()` first, then a published snapshot, to prove the artifact + transitive natives resolve for a real consumer):
1. **Hello-taskbar** — minimal `WindowsTaskBar { Text("Hi") }` smoke test.
2. **Prayer-times clone** — reproduces Awqat-Salaat WinUI: countdown text in the taskbar + clickable flyout settings panel (validates `onClick`, flyout, `contextMenu`, DPI).
3. **System monitor** — CPU/RAM bars (reproduces okt/11-taskbar-widgets), `NearStart` alignment, `clickThrough = true`.
4. **Media controls** — play/pause/seek widget (reproduces BMedia-Taskbar-Widget), `movable = true`.
5. **Multi-monitor clock** — `monitors = All`, exercising `Shell_SecondaryTrayWnd`.

## Recommendations (staged)

**Phase 0 — Research/Spike (complexity: HIGH, highest-risk).** Prove the single riskiest thing first: can a Skiko-rendered `ComposeWindow` (undecorated, transparent, hardware-accelerated) survive being made a `WS_CHILD` of `Shell_TrayWnd` via `SetParent` and render correctly with alpha? Do it in raw Kotlin+JNA before any API design. Characterize Win10 vs Win11 geometry and confirm the exact ex-style bitmask empirically. **Go/no-go gate:** if DirectX/OpenGL context creation fails in a reparented child, fall back to the overlay approach as the default and document it.

**Phase 1 — Core injection PoC (complexity: HIGH).** Harden the native bridge: process-validated taskbar discovery, style manipulation, `SetParent`, `SetWindowPos`, per-monitor DPI, and `TaskbarCreated` re-injection via a message-pump window. Deliverable: a non-Compose demo widget that stays put across explorer restarts, taskbar moves, and DPI changes.

**Phase 2 — Compose integration (complexity: MEDIUM).** Wrap the bridge in the `ApplicationScope.WindowsTaskBar` composable following the `Tray` lifecycle idiom (`remember` the window, `rememberUpdatedState` the callbacks, dispose on leaving composition). Wire recomposition → live property updates (visibility, size, alignment).

**Phase 3 — API surface & fallback (complexity: MEDIUM).** Finalize optional parameters, the context-menu DSL, the flyout helper, movable-drag persistence, click-through, multi-monitor targeting, and the non-Windows no-op. Land the overlay fallback behind a flag.

**Phase 4 — Packaging/publishing (complexity: LOW–MEDIUM).** Wire `vanniktech` publishing, Dokka, Central Portal + GPG on CI, and verify transitive JNA natives resolve from a clean consumer project.

**Phase 5 — Samples/validation (complexity: MEDIUM).** Ship the five sample apps consuming the published snapshot; the prayer-times and system-monitor clones are the acceptance test that "all existing projects on other tech stacks can be implemented."

**Phase 6 (post-1.0, optional) — FFM backend (complexity: MEDIUM).** Swap the JNA bridge for a Java 22+ FFM implementation to drop the dependency; gated on the consumer base being on JDK 22+.

**Thresholds that change the plan:** (a) if Skiko can't render in a child window → overlay becomes default; (b) if a future Windows build blocks `SetParent` into `Shell_TrayWnd` (a plausible Microsoft hardening) → overlay becomes the only path and the library pivots to a "docked topmost strip"; (c) if consumers are predominantly JDK 22+ → prioritize the FFM backend and drop JNA.

## Open Questions
- **Exact ex-style flags** used by the reference projects: inferred (`WS_EX_LAYERED` confirmed from TaskbarQuota's "layered island" wording; `WS_EX_NOACTIVATE`/`WS_EX_TOOLWINDOW`/`WS_EX_TRANSPARENT` are conventional but not line-confirmed) — Phase 0 must nail these down empirically.
- **Can Skiko's hardware-accelerated context initialize inside a `WS_CHILD` window?** The pivotal unknown; drives the SetParent-vs-overlay default.
- **Does the target Windows 11 build still permit `SetParent` into `Shell_TrayWnd`?** Needs per-build verification given Microsoft's ongoing shell hardening.
- **Auto-hide taskbar behavior:** whether a reparented child follows the taskbar's slide-in/out animation cleanly, or needs manual visibility syncing.
- **Elevated/UIPI edge cases:** behavior when the consuming app runs at a different integrity level than `explorer.exe`.

## Caveats
- **This uses an undocumented, unsupported technique.** Microsoft provides no API to inject views into the taskbar; the reference projects all reparent windows into `Shell_TrayWnd`. Windows updates can and do break this (Awqat-Salaat's changelog shows repeated fixes for Start11 interactions, 2-in-1 positioning, and multi-display bugs). Version and test the library defensively per Windows build.
- **The exact interop `.cs` source files** of TaskbarQuota (`TaskBarManager.cs`/`NativeMethods.cs`) and Awqat-Salaat WinUI could not be opened directly (GitHub search/tree/raw fetch restrictions); the precise ex-style bitmask and exact `FindWindowEx` class chain are **inferred** from the projects' own README/changelog wording ("reparents a layered WinUI island into Shell_TrayWnd"), the standard shell hierarchy, and Win32 `SetParent`/`SetWindowPos` documentation — not line-confirmed. Phase 0 must confirm empirically.
- **Widget overlap when the taskbar is full** is an inherent limitation of injection (stated in Awqat-Salaat's README); the library can only mitigate it via tray/Widgets-button geometry detection, not eliminate it.
- **Windows 11 restricts the system tray/clock to the primary taskbar**; secondary-taskbar widgets have less "safe" empty space and behave differently — `monitors = All` must be tested per display. (Note: a late-2025 Windows 11 optional update began enabling notification-center interaction on secondary monitors, so this behavior is itself in flux.)
- **Focus/activation and UIPI:** injecting a foreign process's window as a child of `explorer.exe`'s window can trigger UI Privilege Isolation issues if integrity levels differ (e.g. app run elevated); `WS_EX_NOACTIVATE` mitigates focus stealing but admin/UIPI edge cases need explicit testing.
- **Performance:** Skiko renders on the AWT EDT and by default requests redraws aggressively; an always-on taskbar widget should minimize animation and recomposition to avoid EDT contention.
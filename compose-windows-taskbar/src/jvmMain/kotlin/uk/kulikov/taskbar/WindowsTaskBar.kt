package uk.kulikov.taskbar

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import com.sun.jna.platform.win32.User32
import kotlinx.coroutines.awaitCancellation
import uk.kulikov.taskbar.internal.Placement
import uk.kulikov.taskbar.internal.TaskBarInjector
import uk.kulikov.taskbar.internal.TaskBarRegistry
import uk.kulikov.taskbar.internal.createWidgetWindow
import uk.kulikov.taskbar.internal.hwndOf
import uk.kulikov.taskbar.internal.setNativeVisible
import uk.kulikov.taskbar.internal.showAt
import uk.kulikov.taskbar.win32.NativeTaskBar
import java.awt.MouseInfo
import kotlin.math.roundToInt

/**
 * Hosts Compose content inside the Windows taskbar.
 *
 * Use it exactly like Compose's own `Window`, `DialogWindow` or `Tray` — call it from
 * `application { }` and describe the widget declaratively:
 *
 * ```
 * fun main() = application {
 *     WindowsTaskBar {
 *         Text("Hello, taskbar")
 *     }
 * }
 * ```
 *
 * ### How it works, and what that costs you
 *
 * Windows has no API for putting your own content in the taskbar. The deskband
 * (`IDeskBand`) extension point that used to exist was removed in Windows 11, so this library
 * does what every current taskbar-widget project does: it creates a borderless, transparent
 * Compose window and makes it a `WS_CHILD` of the shell's own taskbar window via `SetParent`.
 *
 * That is an undocumented technique, and it comes with consequences worth knowing about:
 *
 * * **The widget overlaps taskbar content.** It is not laid out by the taskbar, it sits on top
 *   of it. Pick an [alignment] that lands in empty space — [TaskBarAlignment.BeforeTray] is the
 *   default because the notification area's position is the one thing Windows still reports
 *   reliably — and expect overlap once the taskbar fills up.
 * * **Windows updates can break it.** The library re-injects itself after `explorer.exe`
 *   restarts, display changes and DPI changes, and falls back to a top-most overlay if
 *   `SetParent` is ever refused, but a future shell change could close the door entirely. Watch
 *   [onStatusChanged] if your app needs to react.
 * * **`SetParent` across processes attaches input queues.** Your event thread becomes coupled to
 *   `explorer.exe`'s. Use [InjectionMode.Overlay] if you would rather not take that risk.
 *
 * On anything other than Windows this composes nothing and reports
 * [TaskBarError.UnsupportedOs] once through [onStatusChanged], so multiplatform apps keep
 * building and running.
 *
 * ### Application lifetime
 *
 * Like `Tray`, a composed widget keeps `application { }` alive — an app whose only UI is a
 * taskbar widget keeps running until something calls `exitApplication()`. Give the user a way to
 * do that: a [contextMenu] entry is the conventional one.
 *
 * @param visible whether the widget is shown. Prefer toggling this over adding and removing the
 *   call: the host window and its composition are kept alive, so showing it again is instant.
 * @param state hoisted state — read [TaskBarState.status] to observe injection, or
 *   [TaskBarState.offset] to persist a dragged position.
 * @param size widget size. An unspecified height (the default) means "as tall as the taskbar",
 *   which is what a widget normally wants.
 * @param alignment where along the taskbar the widget sits.
 * @param targets which taskbars to inject into. [TaskBarTargets.All] creates one widget instance
 *   per display, each with its own composition.
 * @param movable whether dragging the widget moves it along the taskbar, writing to
 *   [TaskBarState.offset]. On by default: where a widget lands depends on how full the taskbar is,
 *   so being able to nudge it is worth more than the gesture. Interactive content still gets the
 *   gesture first, so only drags starting on inert parts of the widget move it — and hoisting a
 *   [TaskBarState] lets you persist where the user put it.
 * @param clickable whether the widget receives mouse input at all. `false` makes it inert and
 *   passes clicks through to the taskbar underneath.
 * @param clickThrough same as `clickable = false`; kept separate because it reads better
 *   alongside genuinely decorative widgets.
 * @param injectionMode which attachment mechanism to use. Leave it on [InjectionMode.Auto].
 * @param onClick invoked on a primary-button click that the widget's content did not consume —
 *   i.e. clicks on the widget's own empty space.
 * @param onRightClick invoked on an unconsumed secondary-button click. Fires in addition to
 *   [contextMenu], so you can do both.
 * @param contextMenu a menu shown on right-click. `null` means no menu.
 * @param menuStyle appearance of [contextMenu].
 * @param onStatusChanged called whenever this widget's [TaskBarStatus] changes. Runs once per
 *   change per instance, on the AWT event thread.
 * @param content the widget itself. Laid out inside the widget rectangle with the taskbar's own
 *   density, so `dp` values line up with the shell's.
 */
@Composable
public fun ApplicationScope.WindowsTaskBar(
    visible: Boolean = true,
    state: TaskBarState = rememberTaskBarState(),
    size: DpSize = TaskBarDefaults.Size,
    alignment: TaskBarAlignment = TaskBarAlignment.BeforeTray,
    targets: TaskBarTargets = TaskBarTargets.Primary,
    movable: Boolean = true,
    clickable: Boolean = true,
    clickThrough: Boolean = false,
    injectionMode: InjectionMode = InjectionMode.Auto,
    onClick: ((TaskBarClick) -> Unit)? = null,
    onRightClick: ((TaskBarClick) -> Unit)? = null,
    contextMenu: (TaskBarMenuScope.() -> Unit)? = null,
    menuStyle: TaskBarMenuStyle = TaskBarMenuStyle.Default,
    onStatusChanged: (TaskBarStatus) -> Unit = {},
    content: @Composable TaskBarWidgetScope.() -> Unit,
) {
    val currentOnStatusChanged by rememberUpdatedState(onStatusChanged)

    if (!TaskBarPlatform.isSupported) {
        LaunchedEffect(Unit) {
            currentOnStatusChanged(TaskBarStatus.Failed(TaskBarError.UnsupportedOs(TaskBarPlatform.osName)))
        }
        return
    }

    // Keeps `application { }` running for as long as a widget is composed. Compose's application
    // coroutine finishes once no composed effect is still suspended, which is exactly how
    // `Window` and `Tray` hold it open — a taskbar widget has to do the same, or an app whose
    // only UI is a widget would exit the moment it started.
    LaunchedEffect(Unit) { awaitCancellation() }

    // Discovery starts before the first widget exists, so the very first frame already knows
    // where the taskbars are.
    DisposableEffect(Unit) {
        TaskBarRegistry.acquire()
        onDispose { TaskBarRegistry.release() }
    }

    val availableIds by TaskBarRegistry.ids
    val selectedIds = remember(availableIds, targets) { targets.select(TaskBarRegistry.surfaces()) }

    if (selectedIds.isEmpty()) {
        LaunchedEffect(availableIds) {
            currentOnStatusChanged(TaskBarStatus.Failed(TaskBarError.TaskBarNotFound))
        }
    }

    for (id in selectedIds) {
        key(id) {
            TaskBarWidgetInstance(
                surfaceId = id,
                visible = visible,
                state = state,
                size = size,
                alignment = alignment,
                movable = movable,
                clickable = clickable,
                clickThrough = clickThrough,
                injectionMode = injectionMode,
                onClick = onClick,
                onRightClick = onRightClick,
                contextMenu = contextMenu,
                menuStyle = menuStyle,
                onStatusChanged = onStatusChanged,
                content = content,
            )
        }
    }
}

private fun TaskBarTargets.select(surfaces: List<NativeTaskBar>): List<String> = when (this) {
    TaskBarTargets.Primary -> surfaces.filter { it.isPrimary }
    TaskBarTargets.All -> surfaces
    TaskBarTargets.Secondary -> surfaces.filterNot { it.isPrimary }
    is TaskBarTargets.Monitor -> surfaces.filter { it.monitorDeviceName.equals(deviceName, ignoreCase = true) }
}.map { it.id }

/**
 * Mutable mirror of the composable's parameters.
 *
 * Reconciliation runs from a Swing timer, outside any composition, so it reads plain fields
 * rather than snapshot state — that keeps the timer from ever being mistaken for a recomposition
 * dependency, and makes the data flow obvious.
 */
private class WidgetConfig {
    var visible: Boolean = true
    var size: DpSize = TaskBarDefaults.Size
    var alignment: TaskBarAlignment = TaskBarAlignment.BeforeTray
    var clickThrough: Boolean = false
    var injectionMode: InjectionMode = InjectionMode.Auto

    /**
     * Installed by the effect that owns the native window, so a parameter change can be applied
     * straight away instead of waiting up to a reconcile interval for the timer.
     */
    var reconcile: (() -> Unit)? = null
}

@Composable
private fun TaskBarWidgetInstance(
    surfaceId: String,
    visible: Boolean,
    state: TaskBarState,
    size: DpSize,
    alignment: TaskBarAlignment,
    movable: Boolean,
    clickable: Boolean,
    clickThrough: Boolean,
    injectionMode: InjectionMode,
    onClick: ((TaskBarClick) -> Unit)?,
    onRightClick: ((TaskBarClick) -> Unit)?,
    contextMenu: (TaskBarMenuScope.() -> Unit)?,
    menuStyle: TaskBarMenuStyle,
    onStatusChanged: (TaskBarStatus) -> Unit,
    content: @Composable TaskBarWidgetScope.() -> Unit,
) {
    val monitorName = remember(surfaceId) { surfaceId.substringBefore('|') }

    val config = remember { WidgetConfig() }
    config.visible = visible
    config.size = size
    config.alignment = alignment
    config.clickThrough = clickThrough || !clickable
    config.injectionMode = injectionMode

    // Seeded from the surface that caused this instance to exist, so content never has to cope
    // with a null geometry, and keeps the last known values if the taskbar goes away.
    val infoState = remember(surfaceId) {
        mutableStateOf(
            TaskBarRegistry.surface(surfaceId)
                ?.let { buildInfo(it, it.bounds, InjectionMode.Overlay) }
                ?: placeholderInfo(monitorName),
        )
    }

    // Recreated when explorer destroys our window along with its own: a child window does not
    // outlive its parent, so an explorer restart takes the HWND with it.
    var generation by remember(surfaceId) { mutableStateOf(0) }
    val window = remember(surfaceId, generation) { createWidgetWindow(alwaysOnTop = false, focusable = false) }
    val injector = remember(window) { TaskBarInjector(window) }

    val currentContent by rememberUpdatedState(content)
    val currentLocals by rememberUpdatedState(currentCompositionLocalContext)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnRightClick by rememberUpdatedState(onRightClick)
    val currentMenu by rememberUpdatedState(contextMenu)
    val currentMenuStyle by rememberUpdatedState(menuStyle)
    val currentOnStatusChanged by rememberUpdatedState(onStatusChanged)
    val currentMovable by rememberUpdatedState(movable)

    remember(window) {
        window.setContent {
            CompositionLocalProvider(currentLocals) {
                val scope = remember(this) { TaskBarWidgetScopeImpl(this, infoState) }
                WidgetRoot(
                    scope = scope,
                    movable = currentMovable,
                    monitorName = monitorName,
                    state = state,
                    onClick = currentOnClick,
                    onRightClick = currentOnRightClick,
                    contextMenu = currentMenu,
                    menuStyle = currentMenuStyle,
                    content = currentContent,
                )
            }
        }
        window
    }

    DisposableEffect(window) {
        var lastStatus: TaskBarStatus? = null

        fun publish(status: TaskBarStatus) {
            if (status == lastStatus) return
            lastStatus = status
            state.updateStatus(monitorName, status)
            currentOnStatusChanged(status)
        }

        fun reconcile() {
            // The host window's HWND is gone: explorer took it down with its own taskbar, because
            // a child window does not outlive its parent.
            val hwnd = hwndOf(window)
            if (window.isDisplayable && (hwnd == null || !User32.INSTANCE.IsWindow(hwnd))) {
                publish(TaskBarStatus.WaitingForTaskBar)
                generation++
                return
            }

            val surface = TaskBarRegistry.surface(surfaceId)
            if (surface == null) {
                setNativeVisible(window, false)
                publish(TaskBarStatus.WaitingForTaskBar)
                return
            }

            val rect = Placement.resolve(
                taskBar = surface,
                alignment = config.alignment,
                size = config.size,
                offset = state.offsetFor(monitorName),
            )
            // First show happens here rather than eagerly, so the window's very first frame is
            // already over the taskbar instead of somewhere it would be seen.
            showAt(window, rect, surface.scale)
            val error = injector.reconcile(
                taskBar = surface,
                requestedMode = config.injectionMode,
                rect = rect,
                clickThrough = config.clickThrough,
                visible = config.visible,
            )
            val info = buildInfo(surface, rect, injector.activeMode ?: InjectionMode.Overlay)
            if (info != infoState.value) infoState.value = info
            publish(if (error != null) TaskBarStatus.Failed(error) else TaskBarStatus.Injected(info))
        }

        publish(TaskBarStatus.Initializing)
        reconcile()

        val listener = ::reconcile
        config.reconcile = listener
        TaskBarRegistry.addReconcileListener(listener)
        onDispose {
            config.reconcile = null
            TaskBarRegistry.removeReconcileListener(listener)
            injector.detach()
            window.dispose()
            state.updateStatus(monitorName, TaskBarStatus.Detached)
            currentOnStatusChanged(TaskBarStatus.Detached)
        }
    }

    // Parameter changes land on screen in the same frame rather than on the next timer tick.
    SideEffect { config.reconcile?.invoke() }
}

@Composable
private fun WidgetRoot(
    scope: TaskBarWidgetScopeImpl,
    movable: Boolean,
    monitorName: String,
    state: TaskBarState,
    onClick: ((TaskBarClick) -> Unit)?,
    onRightClick: ((TaskBarClick) -> Unit)?,
    contextMenu: (TaskBarMenuScope.() -> Unit)?,
    menuStyle: TaskBarMenuStyle,
    content: @Composable TaskBarWidgetScope.() -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val expandedSubMenus = remember { mutableStateMapOf<String, Boolean>() }
    val entries = remember(contextMenu) { contextMenu?.let { buildMenu(it) }.orEmpty() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .dragToMove(enabled = movable, monitorName = monitorName, state = state)
            .widgetClicks(
                info = { scope.taskBar },
                onClick = onClick,
                onRightClick = { click ->
                    onRightClick?.invoke(click)
                    if (entries.isNotEmpty()) menuOpen = true
                },
            ),
    ) {
        with(scope) { content() }
    }

    if (entries.isNotEmpty()) {
        scope.Flyout(
            visible = menuOpen,
            onDismissRequest = { menuOpen = false },
            size = menuSize(entries, expandedSubMenus.filterValues { it }.keys, menuStyle),
            alignment = FlyoutAlignment.Start,
            gap = 4.dp,
        ) {
            TaskBarMenuContent(entries, expandedSubMenus, menuStyle) { menuOpen = false }
        }
    }
}

/**
 * Drag-to-move along the taskbar.
 *
 * The offset is recomputed from the pointer's absolute screen position on every event rather
 * than accumulated from deltas: the window moves out from under the pointer as it is dragged,
 * which makes delta accumulation drift.
 *
 * Runs on the main pointer pass, so interactive content inside the widget consumes the gesture
 * first and only drags starting on inert parts move the widget.
 */
private fun Modifier.dragToMove(enabled: Boolean, monitorName: String, state: TaskBarState): Modifier {
    if (!enabled) return this
    return pointerInput(monitorName) {
        var pointerOrigin: java.awt.Point? = null
        var offsetOrigin = DpOffset.Zero
        detectDragGestures(
            onDragStart = {
                pointerOrigin = MouseInfo.getPointerInfo()?.location
                offsetOrigin = state.offsetFor(monitorName)
            },
            onDragEnd = { pointerOrigin = null },
            onDragCancel = { pointerOrigin = null },
            onDrag = { _, _ ->
                val origin = pointerOrigin ?: return@detectDragGestures
                val now = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                // AWT reports pointer positions in DPI-independent user space, which is the same
                // scale as Compose dp, so the delta needs no conversion.
                state.setOffsetFor(
                    monitorName,
                    DpOffset(
                        x = offsetOrigin.x + (now.x - origin.x).dp,
                        y = offsetOrigin.y + (now.y - origin.y).dp,
                    ),
                )
            },
        )
    }
}

/**
 * Widget-level click handling.
 *
 * Listens on the final pointer pass and ignores anything the content already consumed, so these
 * callbacks mean "the user clicked the widget, not something in it".
 */
private fun Modifier.widgetClicks(
    info: () -> TaskBarInfo,
    onClick: ((TaskBarClick) -> Unit)?,
    onRightClick: ((TaskBarClick) -> Unit)?,
): Modifier {
    if (onClick == null && onRightClick == null) return this
    return pointerInput(onClick, onRightClick) {
        awaitPointerEventScope {
            var pressedSecondary = false
            var pressPosition: Offset? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull() ?: continue
                when (event.type) {
                    PointerEventType.Press -> if (!change.isConsumed) {
                        pressedSecondary = event.buttons.isSecondaryPressed
                        pressPosition = change.position
                    } else {
                        pressPosition = null
                    }

                    PointerEventType.Release -> {
                        val start = pressPosition
                        pressPosition = null
                        if (start == null || change.isConsumed) continue
                        // Treat it as a click only if the pointer stayed put; a drag is not a click.
                        if ((change.position - start).getDistance() > 8f) continue
                        val click = buildClick(change.position, info())
                        if (pressedSecondary) onRightClick?.invoke(click) else onClick?.invoke(click)
                    }

                    else -> Unit
                }
            }
        }
    }
}

private fun buildClick(position: Offset, info: TaskBarInfo): TaskBarClick = TaskBarClick(
    x = position.x,
    y = position.y,
    screenX = info.widgetBounds.left + (position.x * info.scale).roundToInt(),
    screenY = info.widgetBounds.top + (position.y * info.scale).roundToInt(),
    info = info,
)

private fun buildInfo(surface: NativeTaskBar, widget: IntRect, mode: InjectionMode) = TaskBarInfo(
    isPrimaryMonitor = surface.isPrimary,
    monitorDeviceName = surface.monitorDeviceName,
    edge = surface.edge,
    dpi = surface.dpi,
    scale = surface.scale,
    monitorBounds = surface.monitorBounds,
    taskBarBounds = surface.bounds,
    widgetBounds = widget,
    trayBounds = surface.trayBounds,
    taskListBounds = surface.taskListBounds,
    isAutoHide = surface.isAutoHide,
    activeMode = mode,
)

private fun placeholderInfo(monitorName: String) = TaskBarInfo(
    isPrimaryMonitor = true,
    monitorDeviceName = monitorName,
    edge = TaskBarEdge.Bottom,
    dpi = 96,
    scale = 1f,
    monitorBounds = IntRect.Zero,
    taskBarBounds = IntRect.Zero,
    widgetBounds = IntRect.Zero,
    trayBounds = null,
    taskListBounds = null,
    isAutoHide = false,
    activeMode = InjectionMode.Overlay,
)

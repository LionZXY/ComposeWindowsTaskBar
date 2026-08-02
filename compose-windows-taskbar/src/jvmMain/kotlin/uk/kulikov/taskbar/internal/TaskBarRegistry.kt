package uk.kulikov.taskbar.internal

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import uk.kulikov.taskbar.TaskBarDefaults
import uk.kulikov.taskbar.win32.NativeTaskBar
import uk.kulikov.taskbar.win32.TaskBarDiscovery
import uk.kulikov.taskbar.win32.Win32
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * The single source of truth about which taskbars exist and where they are.
 *
 * Process-wide and reference-counted, because there is exactly one shell to watch no matter how
 * many widgets an application hosts. It owns:
 *
 * * one [TaskBarEventPump], for immediate reaction to `explorer.exe` restarts and display changes;
 * * one [Timer], which re-measures the known taskbars and then asks every widget to reconcile.
 *
 * The timer is a `javax.swing.Timer` on purpose: it fires on the AWT event thread, which is the
 * only thread allowed to touch the host windows' `HWND`s.
 */
internal object TaskBarRegistry {

    /** Full re-enumeration is only needed occasionally; this is how many ticks between them. */
    private const val REDISCOVER_EVERY_TICKS = 10

    private var refCount = 0
    private var timer: Timer? = null
    private var pump: TaskBarEventPump? = null
    private var tickCount = 0

    @Volatile
    private var rediscoveryRequested = true

    private var surfaces: List<NativeTaskBar> = emptyList()
    private val idsState = mutableStateOf<List<String>>(emptyList())
    private val listeners = ArrayList<() -> Unit>()

    /**
     * Ids of the taskbars currently present, primary first. Changes only when a taskbar appears
     * or disappears — never on a mere geometry change — so widgets are not recreated needlessly.
     */
    val ids: State<List<String>> get() = idsState

    /** The latest measurement of a taskbar, or `null` if it is gone. */
    fun surface(id: String): NativeTaskBar? = surfaces.firstOrNull { it.id == id }

    fun surfaces(): List<NativeTaskBar> = surfaces

    fun acquire() {
        assertEventThread()
        refCount++
        if (refCount > 1) return
        rediscoveryRequested = true
        refresh()
        pump = TaskBarEventPump {
            rediscoveryRequested = true
            // The pump runs on its own thread; hop to the event thread before touching state.
            SwingUtilities.invokeLater { if (refCount > 0) refresh(notify = true) }
        }.also { it.start() }
        timer = Timer(TaskBarDefaults.ReconcileIntervalMillis.toInt()) { tick() }.apply {
            isRepeats = true
            isCoalesce = true
            start()
        }
    }

    fun release() {
        assertEventThread()
        if (refCount == 0) return
        refCount--
        if (refCount > 0) return
        timer?.stop()
        timer = null
        pump?.stop()
        pump = null
        surfaces = emptyList()
        idsState.value = emptyList()
        listeners.clear()
    }

    fun addReconcileListener(listener: () -> Unit) {
        assertEventThread()
        listeners.add(listener)
    }

    fun removeReconcileListener(listener: () -> Unit) {
        assertEventThread()
        listeners.remove(listener)
    }

    /** Forces a full re-enumeration on the next tick. */
    fun invalidate() {
        rediscoveryRequested = true
    }

    private fun tick() {
        tickCount++
        refresh(notify = true)
    }

    private fun refresh(notify: Boolean = false) {
        if (!Win32.isWindows) return
        val needsFullScan = rediscoveryRequested ||
            surfaces.isEmpty() ||
            tickCount % REDISCOVER_EVERY_TICKS == 0
        if (needsFullScan) {
            rediscoveryRequested = false
            updateSurfaces(TaskBarDiscovery.discover())
        } else {
            val refreshed = surfaces.mapNotNull(TaskBarDiscovery::refresh)
            if (refreshed.size != surfaces.size) {
                // A taskbar window died under us: explorer is probably restarting.
                updateSurfaces(TaskBarDiscovery.discover())
            } else {
                surfaces = refreshed
            }
        }
        if (notify) {
            // Copy first: a listener may dispose its widget and remove itself while iterating.
            for (listener in listeners.toList()) {
                try {
                    listener()
                } catch (_: Throwable) {
                    // One misbehaving widget must not stall the others.
                }
            }
        }
    }

    private fun updateSurfaces(discovered: List<NativeTaskBar>) {
        surfaces = discovered
        val newIds = discovered.map { it.id }
        if (newIds != idsState.value) idsState.value = newIds
    }

    private fun assertEventThread() {
        check(SwingUtilities.isEventDispatchThread()) {
            "TaskBarRegistry must only be used from the AWT event thread"
        }
    }
}

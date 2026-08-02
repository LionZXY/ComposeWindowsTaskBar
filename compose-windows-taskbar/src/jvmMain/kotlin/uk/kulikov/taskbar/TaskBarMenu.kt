package uk.kulikov.taskbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Marks the [TaskBarMenuScope] builder so menu entries cannot be declared in the wrong place. */
@DslMarker
public annotation class TaskBarMenuDsl

/**
 * Builder for a widget's context menu, mirroring the shape of Compose's own tray `MenuScope`.
 *
 * The menu is described declaratively and then drawn by Compose inside a [TaskBarFlyout], rather
 * than being a native `PopupMenu`. That is deliberate: a taskbar widget is a `WS_EX_NOACTIVATE`
 * child window, and native popup menus depend on the activation and focus such a window never
 * receives. Drawing it ourselves also lets it be themed to match the widget.
 *
 * ```
 * WindowsTaskBar(
 *     contextMenu = {
 *         item("Refresh") { refresh() }
 *         checkbox("Show seconds", checked = showSeconds) { showSeconds = it }
 *         separator()
 *         submenu("Alignment") {
 *             item("Before tray") { alignment = TaskBarAlignment.BeforeTray }
 *             item("Start") { alignment = TaskBarAlignment.Start }
 *         }
 *         separator()
 *         item("Quit") { exitApplication() }
 *     },
 * ) { /* widget content */ }
 * ```
 */
@TaskBarMenuDsl
public interface TaskBarMenuScope {

    /** A plain command. */
    public fun item(label: String, enabled: Boolean = true, onClick: () -> Unit)

    /** A toggle. [onCheckedChange] receives the new value. */
    public fun checkbox(
        label: String,
        checked: Boolean,
        enabled: Boolean = true,
        onCheckedChange: (Boolean) -> Unit,
    )

    /** A horizontal rule between groups. */
    public fun separator()

    /**
     * A nested group.
     *
     * Rendered as an inline expander rather than a side-flyout, so the whole menu stays inside
     * one window and never has to be re-anchored near a screen edge.
     */
    public fun submenu(label: String, enabled: Boolean = true, content: TaskBarMenuScope.() -> Unit)
}

/**
 * Look of the context menu. Defaults approximate the Windows 11 dark flyout; override to match a
 * light theme or a custom widget style.
 */
@Immutable
public class TaskBarMenuStyle(
    public val width: Dp = 220.dp,
    public val background: Color = Color(0xFF2B2B2B),
    public val borderColor: Color = Color(0x33FFFFFF),
    public val contentColor: Color = Color(0xFFF0F0F0),
    public val hoverColor: Color = Color(0x1AFFFFFF),
    public val separatorColor: Color = Color(0x26FFFFFF),
    public val cornerRadius: Dp = 8.dp,
) {
    public companion object {
        /** Height of a command, checkbox or submenu row. */
        public val ItemHeight: Dp = 32.dp

        /** Height a separator occupies, rule plus surrounding space. */
        public val SeparatorHeight: Dp = 9.dp

        /** Padding above the first row and below the last. */
        public val VerticalPadding: Dp = 6.dp

        /** The style [WindowsTaskBar] uses when none is given. */
        public val Default: TaskBarMenuStyle = TaskBarMenuStyle()
    }
}

@Immutable
internal sealed interface MenuEntry {
    data class Item(val label: String, val enabled: Boolean, val onClick: () -> Unit) : MenuEntry

    data class Checkbox(
        val label: String,
        val checked: Boolean,
        val enabled: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
    ) : MenuEntry

    data object Separator : MenuEntry

    data class SubMenu(val label: String, val enabled: Boolean, val entries: List<MenuEntry>) : MenuEntry
}

private class MenuBuilder : TaskBarMenuScope {
    val entries: MutableList<MenuEntry> = mutableListOf()

    override fun item(label: String, enabled: Boolean, onClick: () -> Unit) {
        entries += MenuEntry.Item(label, enabled, onClick)
    }

    override fun checkbox(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
        entries += MenuEntry.Checkbox(label, checked, enabled, onCheckedChange)
    }

    override fun separator() {
        entries += MenuEntry.Separator
    }

    override fun submenu(label: String, enabled: Boolean, content: TaskBarMenuScope.() -> Unit) {
        entries += MenuEntry.SubMenu(label, enabled, MenuBuilder().apply(content).entries)
    }
}

internal fun buildMenu(block: TaskBarMenuScope.() -> Unit): List<MenuEntry> =
    MenuBuilder().apply(block).entries

/**
 * Exact size the menu needs, so the flyout window can be sized without a measuring pass.
 *
 * Every row has a fixed height by construction, which is what makes this exact rather than a
 * guess — and why the menu never flickers at the wrong size when it opens.
 */
internal fun menuSize(entries: List<MenuEntry>, expanded: Set<String>, style: TaskBarMenuStyle): DpSize =
    DpSize(style.width, TaskBarMenuStyle.VerticalPadding * 2 + entriesHeight(entries, "", expanded))

private fun entriesHeight(entries: List<MenuEntry>, path: String, expanded: Set<String>): Dp {
    var height = 0.dp
    entries.forEachIndexed { index, entry ->
        val key = "$path/$index"
        height += when (entry) {
            is MenuEntry.Separator -> TaskBarMenuStyle.SeparatorHeight
            is MenuEntry.SubMenu ->
                TaskBarMenuStyle.ItemHeight +
                    if (key in expanded) entriesHeight(entry.entries, key, expanded) else 0.dp

            else -> TaskBarMenuStyle.ItemHeight
        }
    }
    return height
}

@Composable
internal fun TaskBarMenuContent(
    entries: List<MenuEntry>,
    expanded: MutableMap<String, Boolean>,
    style: TaskBarMenuStyle,
    onDismissRequest: () -> Unit,
) {
    Box(
        Modifier
            .background(style.background, RoundedCornerShape(style.cornerRadius))
            .border(1.dp, style.borderColor, RoundedCornerShape(style.cornerRadius))
            .padding(vertical = TaskBarMenuStyle.VerticalPadding),
    ) {
        Column(verticalArrangement = Arrangement.Top) {
            MenuEntries(entries, path = "", depth = 0, expanded, style, onDismissRequest)
        }
    }
}

@Composable
private fun MenuEntries(
    entries: List<MenuEntry>,
    path: String,
    depth: Int,
    expanded: MutableMap<String, Boolean>,
    style: TaskBarMenuStyle,
    onDismissRequest: () -> Unit,
) {
    entries.forEachIndexed { index, entry ->
        val key = "$path/$index"
        when (entry) {
            is MenuEntry.Separator -> Box(
                Modifier
                    .fillMaxWidth()
                    .height(TaskBarMenuStyle.SeparatorHeight)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .background(style.separatorColor),
            )

            is MenuEntry.Item -> MenuRow(entry.label, entry.enabled, depth, style) {
                entry.onClick()
                onDismissRequest()
            }

            is MenuEntry.Checkbox -> MenuRow(
                label = entry.label,
                enabled = entry.enabled,
                depth = depth,
                style = style,
                leading = if (entry.checked) "✓" else null,
            ) {
                entry.onCheckedChange(!entry.checked)
                onDismissRequest()
            }

            is MenuEntry.SubMenu -> {
                val isOpen = expanded[key] == true
                MenuRow(
                    label = entry.label,
                    enabled = entry.enabled,
                    depth = depth,
                    style = style,
                    trailing = if (isOpen) "⌄" else "›",
                ) {
                    expanded[key] = !isOpen
                }
                if (isOpen) {
                    MenuEntries(entry.entries, key, depth + 1, expanded, style, onDismissRequest)
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    label: String,
    enabled: Boolean,
    depth: Int,
    style: TaskBarMenuStyle,
    leading: String? = null,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TaskBarMenuStyle.ItemHeight)
            .padding(horizontal = 4.dp)
            .background(
                if (hovered && enabled) style.hoverColor else Color.Transparent,
                RoundedCornerShape(4.dp),
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .onHoverChange { hovered = it }
            .padding(start = 8.dp + 14.dp * depth, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(18.dp)) {
            if (leading != null) MenuText(leading, style.contentColor, FontWeight.Bold)
        }
        Box(Modifier.weight(1f)) {
            MenuText(label, style.contentColor, FontWeight.Normal, alpha = if (enabled) 1f else 0.4f)
        }
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            MenuText(trailing, style.contentColor, FontWeight.Normal, alpha = 0.7f)
        }
    }
}

@Composable
private fun MenuText(text: String, color: Color, weight: FontWeight, alpha: Float = 1f) {
    BasicText(
        text = text,
        modifier = Modifier.alpha(alpha),
        style = TextStyle(color = color, fontSize = 13.sp, fontWeight = weight),
    )
}

/** Hover tracking without depending on a material interaction source. */
private fun Modifier.onHoverChange(onChange: (Boolean) -> Unit): Modifier =
    pointerInput(onChange) {
        awaitPointerEventScope {
            while (true) {
                when (awaitPointerEvent(PointerEventPass.Initial).type) {
                    PointerEventType.Enter -> onChange(true)
                    PointerEventType.Exit -> onChange(false)
                }
            }
        }
    }

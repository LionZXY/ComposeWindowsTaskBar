package uk.kulikov.taskbar

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The menu's flyout window is sized from this arithmetic rather than from a measuring pass, so if
 * it is wrong the menu opens clipped or with dead space around it.
 */
class TaskBarMenuTest {

    private val style = TaskBarMenuStyle()

    @Test
    fun `an empty menu is just its padding`() {
        assertEquals(TaskBarMenuStyle.VerticalPadding * 2, menuSize(emptyList(), emptySet(), style).height)
    }

    @Test
    fun `rows and separators each contribute their fixed height`() {
        val entries = buildMenu {
            item("One") {}
            item("Two") {}
            separator()
            checkbox("Three", checked = true) {}
        }
        val expected = TaskBarMenuStyle.VerticalPadding * 2 +
            TaskBarMenuStyle.ItemHeight * 3 +
            TaskBarMenuStyle.SeparatorHeight
        assertEquals(expected, menuSize(entries, emptySet(), style).height)
    }

    @Test
    fun `a collapsed submenu costs one row and an expanded one costs its children too`() {
        val entries = buildMenu {
            item("Top") {}
            submenu("More") {
                item("A") {}
                item("B") {}
            }
        }
        val collapsed = menuSize(entries, emptySet(), style).height
        assertEquals(TaskBarMenuStyle.VerticalPadding * 2 + TaskBarMenuStyle.ItemHeight * 2, collapsed)

        // Keys are positional: "/1" is the second entry of the root menu.
        val expanded = menuSize(entries, setOf("/1"), style).height
        assertEquals(collapsed + TaskBarMenuStyle.ItemHeight * 2, expanded)
    }

    @Test
    fun `nested submenus only count when their whole chain is open`() {
        val entries = buildMenu {
            submenu("Outer") {
                submenu("Inner") {
                    item("Deep") {}
                }
            }
        }
        val outerOnly = menuSize(entries, setOf("/0"), style).height
        val bothOpen = menuSize(entries, setOf("/0", "/0/0"), style).height
        assertEquals(TaskBarMenuStyle.ItemHeight, bothOpen - outerOnly)
    }

    @Test
    fun `the builder records entries in declaration order`() {
        val entries = buildMenu {
            item("First") {}
            separator()
            submenu("Group") { item("Child") {} }
        }
        assertEquals(3, entries.size)
        assertTrue(entries[0] is MenuEntry.Item)
        assertTrue(entries[1] is MenuEntry.Separator)
        val group = entries[2] as MenuEntry.SubMenu
        assertEquals("Group", group.label)
        assertEquals(1, group.entries.size)
    }

    @Test
    fun `the menu is as wide as the style says`() {
        val wide = TaskBarMenuStyle(width = 320.dp)
        assertEquals(320.dp, menuSize(buildMenu { item("x") {} }, emptySet(), wide).width)
    }
}

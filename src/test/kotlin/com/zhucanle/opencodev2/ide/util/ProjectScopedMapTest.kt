package com.zhucanle.opencodev2.ide.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [ProjectScopedMap].
 *
 * The map backs the terminal widget registry, which is shared by every project in the IDE
 * process. Clearing one owner must never touch the entries of another owner.
 */
class ProjectScopedMapTest {

    private fun map() = ProjectScopedMap<String, String, String>()

    @Test
    fun keysAreScopedToTheirOwner() {
        val map = map()
        map.put("projectA", "fileA", "widgetA")
        map.put("projectB", "fileB", "widgetB")

        assertEquals(setOf("fileA"), map.keysOf("projectA"))
        assertEquals(setOf("fileB"), map.keysOf("projectB"))
        assertEquals(setOf("fileA", "fileB"), map.keys())
    }

    @Test
    fun clearingOneOwnerKeepsTheOtherOwnerIntact() {
        val map = map()
        map.put("projectA", "fileA", "widgetA")
        map.put("projectB", "fileB", "widgetB")

        map.keysOf("projectA").forEach { map.remove(it) }

        assertEquals(emptySet<String>(), map.keysOf("projectA"))
        assertEquals("widgetB", map.get("fileB"))
        assertTrue(map.containsKey("fileB"))
    }

    @Test
    fun removeReturnsTheValueAndDropsTheKey() {
        val map = map()
        map.put("projectA", "fileA", "widgetA")

        assertEquals("widgetA", map.remove("fileA"))
        assertNull(map.get("fileA"))
        assertNull(map.remove("fileA"))
    }

    @Test
    fun reRegisteringKeepsTheLatestValueAndOwner() {
        val map = map()
        map.put("projectA", "fileA", "first")
        map.put("projectA", "fileA", "second")

        assertEquals("second", map.get("fileA"))
        assertEquals(setOf("fileA"), map.keysOf("projectA"))
        assertEquals(setOf("fileA"), map.keys())
    }
}

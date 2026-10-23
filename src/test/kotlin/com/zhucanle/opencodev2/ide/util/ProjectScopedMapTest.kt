package com.zhucanle.opencodev2.ide.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [ProjectScopedMap].
 *
 * The map backs the terminal widget registry, which is shared by every project in the IDE
 * process. Clearing one owner must never touch the entries of another owner, and a lookup by the
 * wrong owner must not resolve at all.
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

        map.keysOf("projectA").forEach { map.remove("projectA", it) }

        assertEquals(emptySet<String>(), map.keysOf("projectA"))
        assertEquals("widgetB", map.get("projectB", "fileB"))
        assertTrue(map.containsKey("projectB", "fileB"))
    }

    @Test
    fun lookupsDoNotCrossOwners() {
        val map = map()
        map.put("projectA", "fileA", "widgetA")

        assertNull(map.get("projectB", "fileA"))
        assertFalse(map.containsKey("projectB", "fileA"))
        assertNull(map.remove("projectB", "fileA"))
        assertEquals("widgetA", map.get("projectA", "fileA"))
    }

    @Test
    fun removeReturnsTheValueAndDropsTheKey() {
        val map = map()
        map.put("projectA", "fileA", "widgetA")

        assertEquals("widgetA", map.remove("projectA", "fileA"))
        assertNull(map.get("projectA", "fileA"))
        assertNull(map.remove("projectA", "fileA"))
    }

    @Test
    fun removeAnyIgnoresTheOwner() {
        val map = map()
        map.put("projectA", "fileA", "widgetA")

        assertEquals("widgetA", map.removeAny("fileA"))
        assertNull(map.get("projectA", "fileA"))
    }

    @Test
    fun reRegisteringKeepsTheLatestValueAndOwner() {
        val map = map()
        map.put("projectA", "fileA", "first")
        map.put("projectA", "fileA", "second")

        assertEquals("second", map.get("projectA", "fileA"))
        assertEquals(setOf("fileA"), map.keysOf("projectA"))
        assertEquals(setOf("fileA"), map.keys())
    }
}

package com.zhucanle.opencodev2.ide.util

import java.util.concurrent.ConcurrentHashMap

/**
 * A concurrent key/value map whose entries are owned by a project-like value.
 *
 * The plugin keeps a single registry of terminal widgets for the whole IDE process, while the
 * widgets themselves belong to one project each. Clearing an owner (project disposal) must only
 * drop the entries registered by that owner, otherwise closing one project window kills the
 * terminals of every other project.
 *
 * The owner is part of every lookup, so a stale key from another project can never resolve to
 * another project's value.
 *
 * @param P owner type, e.g. a project
 * @param K key type, e.g. a virtual file
 * @param V value type, e.g. a terminal widget
 */
internal class ProjectScopedMap<P : Any, K : Any, V : Any> {

    private data class Entry<P, V>(val owner: P, val value: V)

    private val entries = ConcurrentHashMap<K, Entry<P, V>>()

    /** Registers or replaces the value for [key], owned by [owner]. */
    fun put(owner: P, key: K, value: V) {
        entries[key] = Entry(owner, value)
    }

    /** Returns the value registered for [key] by [owner], or null when absent or owned by someone else. */
    fun get(owner: P, key: K): V? = entries[key]?.takeIf { it.owner == owner }?.value

    /** True when [key] is registered by [owner]. */
    fun containsKey(owner: P, key: K): Boolean = entries[key]?.owner == owner

    /** Removes [key] when [owner] registered it, and returns its value. */
    fun remove(owner: P, key: K): V? {
        var removed: V? = null
        // Atomic check-and-remove: a plain check followed by remove could delete an entry that
        // another project registered for the same key in between.
        entries.computeIfPresent(key) { _, entry ->
            if (entry.owner == owner) {
                removed = entry.value
                null
            } else {
                entry
            }
        }
        return removed
    }

    /** Removes [key] regardless of its owner; only for teardown paths that already lost the owner. */
    fun removeAny(key: K): V? = entries.remove(key)?.value

    /** Every key owned by [owner]. */
    fun keysOf(owner: P): Set<K> = entries.entries
        .filter { it.value.owner == owner }
        .map { it.key }
        .toSet()

    /** Every registered key, regardless of owner. */
    fun keys(): Set<K> = entries.keys.toSet()
}

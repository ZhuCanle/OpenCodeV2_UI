package com.zhucanle.opencodev2.ide.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil

import java.nio.file.Paths

object PathUtil {
    fun toSystemIndependentPath(path: String): String {
        return FileUtil.toSystemIndependentName(path)
    }

    fun toOpenCodeServerPath(path: String): String {
        val normalized = FileUtil.toSystemIndependentName(path)
        return if (isWindows()) {
            normalized.replace("/", "\\")
        } else {
            normalized
        }
    }

    /**
     * True when [a] and [b] point at the same directory, ignoring separator style and, on Windows,
     * casing. Symlinked paths are compared through their canonical form when both are absolute, so
     * `/tmp/x` and `/private/tmp/x` match on macOS.
     */
    fun sameDirectory(a: String, b: String): Boolean = sameDirectory(a, b, isWindows())

    /** [sameDirectory] with an explicit casing rule, so Windows behaviour is testable anywhere. */
    internal fun sameDirectory(a: String, b: String, caseInsensitive: Boolean): Boolean {
        val left = unifySeparators(a)
        val right = unifySeparators(b)
        // Fast path: the usual case is two identical path strings. Only a mismatch pays for the
        // canonical lookup that resolves symlinks (e.g. /tmp -> /private/tmp on macOS).
        if (left.equals(right, ignoreCase = caseInsensitive)) return true
        val leftCanonical = canonicalize(left) ?: return false
        val rightCanonical = canonicalize(right) ?: return false
        return leftCanonical.equals(rightCanonical, ignoreCase = caseInsensitive)
    }

    /** Normalises separators and drops a trailing separator. */
    private fun unifySeparators(path: String): String {
        val separators = path.trim().replace('\\', '/')
        return separators.trimEnd('/').ifEmpty { separators }
    }

    /** Canonical form of an absolute path, or null when [unified] is relative / cannot be resolved. */
    private fun canonicalize(unified: String): String? = try {
        val file = java.io.File(unified)
        if (!file.isAbsolute) {
            null
        } else {
            val canonical = file.canonicalPath.replace('\\', '/')
            canonical.trimEnd('/').ifEmpty { canonical }
        }
    } catch (_: Exception) {
        null
    }

    /** True on Windows. Single OS check for the plugin (path handling, CLI discovery). */
    fun isWindows(): Boolean {
        val osName = System.getProperty("os.name", "").lowercase()
        return osName.contains("windows")
    }

    /**
     * True when [path] is [basePath] itself or lives inside it.
     *
     * Used to keep VFS events from other projects out of this window's turn. Symlinked paths are
     * compared through their canonical form, so `/tmp/x` and `/private/tmp/x` match on macOS even
     * when the file itself is gone (a deleted file cannot be canonicalised on its own).
     */
    fun isUnder(basePath: String?, path: String): Boolean = isUnder(basePath, path, isWindows())

    /** [isUnder] with an explicit casing rule, so Windows behaviour is testable anywhere. */
    internal fun isUnder(basePath: String?, path: String, caseInsensitive: Boolean): Boolean {
        if (basePath.isNullOrBlank() || path.isBlank()) return false
        val base = unifySeparators(basePath)
        val target = unifySeparators(path)
        if (target.equals(base, ignoreCase = caseInsensitive)) return true
        if (target.startsWith("$base/", ignoreCase = caseInsensitive)) return true

        val baseCanonical = canonicalize(base) ?: return false
        val targetCanonical = canonicalize(target) ?: return false
        return targetCanonical.equals(baseCanonical, ignoreCase = caseInsensitive) ||
            targetCanonical.startsWith("$baseCanonical/", ignoreCase = caseInsensitive)
    }

    fun resolveProjectPath(project: Project, filePath: String): String? {
        return resolveToSystemIndependentPath(project.basePath, filePath)
    }

    fun resolveToSystemIndependentPath(basePath: String?, filePath: String): String? {
        var raw = filePath.trim()
        if (raw.isEmpty()) return null

        if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length - 1)
        }

        val normalizedInput = FileUtil.toSystemIndependentName(raw)
        return try {
            val path = Paths.get(normalizedInput)
            val resolved = when {
                path.isAbsolute -> path
                basePath != null -> Paths.get(basePath).resolve(path)
                else -> return null
            }
            toSystemIndependentPath(resolved.normalize().toString())
        } catch (_: Exception) {
            null
        }
    }

    fun relativizeToProject(project: Project, filePath: String): String {
        return relativizeToProject(project.basePath, filePath)
    }

    fun relativizeToProject(basePath: String?, filePath: String): String {
        if (basePath == null) return toSystemIndependentPath(filePath)

        val normalizedBase = ensureTrailingSlash(toSystemIndependentPath(basePath))
        val normalizedPath = toSystemIndependentPath(filePath)

        // Windows drive letters and path casing are case-insensitive, so a server-reported path may
        // differ in case from the project base path and still be the same file.
        return if (normalizedPath.startsWith(normalizedBase, ignoreCase = isWindows())) {
            normalizedPath.substring(normalizedBase.length)
        } else {
            normalizedPath
        }
    }

    private fun ensureTrailingSlash(path: String): String {
        return if (path.endsWith("/")) path else "$path/"
    }
}

package com.zhucanle.opencodev2.ide

import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the plugin descriptors against duplicate extension registrations.
 *
 * `plugin.xml` contributes the same `fileEditorProvider` that `opencode-web-support.xml` (loaded
 * through the optional JCEF dependency) already contributes. The platform does not de-duplicate
 * providers by implementation class, so every web tab used to get two editors and therefore two
 * JCEF browsers.
 */
class PluginDescriptorTest {

    private fun readDescriptor(name: String) =
        javaClass.getResourceAsStream("/META-INF/$name")
            ?: throw AssertionError("$name is missing from the plugin classpath")

    private fun parse(name: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(readDescriptor(name))

    private fun providers(xmlName: String): List<String> {
        val root = parse(xmlName).documentElement
        val nodes = root.getElementsByTagName("fileEditorProvider")
        return (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("implementation").nodeValue }
    }

    /** Names of the descriptors attached to `<depends optional="true" config-file="...">`. */
    private fun optionalConfigFiles(): List<String> {
        val root = parse("plugin.xml").documentElement
        val depends = root.getElementsByTagName("depends")
        return (0 until depends.length).mapNotNull { index ->
            depends.item(index).attributes.getNamedItem("config-file")?.nodeValue
        }
    }

    @Test
    fun noFileEditorProviderIsContributedTwice() {
        val contributions = buildList {
            addAll(providers("plugin.xml"))
            optionalConfigFiles().forEach { addAll(providers(it)) }
        }

        assertTrue("no fileEditorProvider expected", contributions.isNotEmpty())
        val duplicates = contributions.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(
            "fileEditorProvider contributed more than once: $duplicates",
            duplicates.isEmpty()
        )
    }

    private companion object {
        /** Not used by the assertions; keeps the descriptor layout visible in the report. */
        const val JCEF_MODULE = "com.intellij.modules.jcef"
    }
}

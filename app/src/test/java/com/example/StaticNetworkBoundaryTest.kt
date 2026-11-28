package com.example

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StaticNetworkBoundaryTest {

    private fun resolveMainSrcDir(): File {
        val candidates = listOf(
            File("src/main"),
            File("app/src/main")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate app src/main directory from ${File(".").absolutePath}")
    }

    private fun stripComments(source: String): String {
        return source
            .replace(Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)), "")
            .lines()
            .joinToString("\n") { line ->
                val idx = line.indexOf("//")
                if (idx >= 0) line.substring(0, idx) else line
            }
    }

    @Test
    fun `no network sockets http clients Google SpeechRecognizer or gms exist outside com example search`() {
        val mainDir = resolveMainSrcDir()
        val kotlinRoot = File(mainDir, "java/com/example")
        assertTrue("Expected kotlin root at ${kotlinRoot.absolutePath}", kotlinRoot.exists())

        val forbiddenPatterns = listOf(
            "java.net.URL",
            "HttpURLConnection",
            "okhttp",
            "retrofit",
            "java.net.Socket",
            "DatagramSocket",
            "android.speech.SpeechRecognizer",
            "SpeechRecognizer.",
            "SpeechRecognizer(",
            "RecognitionService",
            "com.google.gms"
        )

        val violations = mutableListOf<String>()
        kotlinRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { file ->
                val rel = file.relativeTo(kotlinRoot).invariantSeparatorsPath
                val isSearchPackage = rel.startsWith("search/")
                if (!isSearchPackage) {
                    val codeOnly = stripComments(file.readText())
                    for (pattern in forbiddenPatterns) {
                        if (codeOnly.contains(pattern)) {
                            violations.add("$rel contains forbidden code reference '$pattern'")
                        }
                    }
                }
            }

        assertTrue(
            "Static boundary check failed:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}

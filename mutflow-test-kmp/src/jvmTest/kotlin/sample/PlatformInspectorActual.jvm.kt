package sample

import java.io.File

internal actual fun currentPlatform(): String = "jvm"

internal actual fun writeResultsFile(platform: String, json: String) {
    val dir = File("build/inspect-results").apply { mkdirs() }
    File(dir, "$platform.json").writeText(json)
}

package sample

import kotlin.js.Json
import kotlin.js.json

internal actual fun currentPlatform(): String = "js"

// Node.js `fs` module, accessed via @JsModule so the nodejs target can write the
// inspector JSON to inspect-results/<platform>.json in the test working directory
// (the JS package dir). inspect-all.sh globs the repo for these files.
@JsModule("fs")
@JsNonModule
private external object fs {
    fun mkdirSync(path: String, options: Any? = definedExternally)
    fun writeFileSync(path: String, data: String, options: Any? = definedExternally)
}

internal actual fun writeResultsFile(platform: String, json: String) {
    val dir = "inspect-results"
    fs.mkdirSync(dir, json("recursive" to true))
    fs.writeFileSync("$dir/$platform.json", json)
}

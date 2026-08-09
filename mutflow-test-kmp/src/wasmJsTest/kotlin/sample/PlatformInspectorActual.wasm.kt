package sample

internal actual fun currentPlatform(): String = "wasmJs"

// Node.js `fs` module, accessed via @JsModule so the wasmJs (nodejs) target can
// write the inspector JSON to inspect-results/<platform>.json in the test working
// directory (the WASM package dir). inspect-all.sh globs the repo for these files.
// Kotlin/Wasm JS interop only allows external/primitive/string/function params,
// so we check existsSync first rather than calling mkdirSync unconditionally and
// swallowing its EEXIST throw — that would also hide genuine mkdir failures.
@JsModule("fs")
private external object fs {
    fun existsSync(path: String): Boolean
    fun mkdirSync(path: String)
    fun writeFileSync(path: String, data: String)
}

internal actual fun writeResultsFile(platform: String, json: String) {
    if (!fs.existsSync("inspect-results")) {
        fs.mkdirSync("inspect-results")
    }
    fs.writeFileSync("inspect-results/$platform.json", json)
}

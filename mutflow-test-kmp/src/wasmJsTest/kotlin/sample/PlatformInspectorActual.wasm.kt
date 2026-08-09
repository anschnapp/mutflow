package sample

internal actual fun currentPlatform(): String = "wasmJs"

// Node.js `fs` module, accessed via @JsModule so the wasmJs (nodejs) target can
// write the inspector JSON to inspect-results/<platform>.json in the test working
// directory (the WASM package dir). inspect-all.sh globs the repo for these files.
// Kotlin/Wasm JS interop only allows external/primitive/string/function params,
// so we use the single-arg mkdirSync (which throws EEXIST if the dir exists) and
// the two-arg writeFileSync.
@JsModule("fs")
private external object fs {
    fun mkdirSync(path: String)
    fun writeFileSync(path: String, data: String)
}

internal actual fun writeResultsFile(platform: String, json: String) {
    try {
        fs.mkdirSync("inspect-results")
    } catch (e: Throwable) {
        // EEXIST is fine — the directory already exists from a prior run.
    }
    fs.writeFileSync("inspect-results/$platform.json", json)
}

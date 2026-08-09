package sample

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.mkdir
import platform.posix.open
import platform.posix.O_CREAT
import platform.posix.O_WRONLY
import platform.posix.O_TRUNC
import platform.posix.write
import platform.posix.close
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.EEXIST
import platform.posix.errno
import platform.posix.strerror

internal actual fun currentPlatform(): String = "linuxX64"

@OptIn(ExperimentalForeignApi::class)
internal actual fun writeResultsFile(platform: String, json: String) {
    if (mkdir("inspect-results", (S_IRUSR or S_IWUSR or S_IXUSR).toUInt()) != 0 && errno != EEXIST) {
        error("mkdir(inspect-results) failed: ${strerror(errno)?.toKString()}")
    }
    val path = "inspect-results/$platform.json"
    val fd = open(path, O_CREAT or O_WRONLY or O_TRUNC, S_IRUSR or S_IWUSR)
    check(fd >= 0) { "open($path) failed: ${strerror(errno)?.toKString()}" }
    val bytes = json.encodeToByteArray()
    bytes.usePinned { pinned ->
        write(fd, pinned.addressOf(0), bytes.size.toULong())
    }
    close(fd)
}

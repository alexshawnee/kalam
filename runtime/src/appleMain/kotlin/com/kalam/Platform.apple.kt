@file:OptIn(ExperimentalForeignApi::class)

package com.kalam

import kalam.cinterop.uds.*
import kotlinx.cinterop.*

actual class UdsConnection(private val fd: Int) : AutoCloseable {
    actual fun write(data: ByteArray) {
        data.usePinned { pinned ->
            var offset = 0
            while (offset < data.size) {
                val written = uds_write(fd, pinned.addressOf(offset), (data.size - offset).convert())
                if (written <= 0) throw IllegalStateException("Write failed")
                offset += written.toInt()
            }
        }
    }

    actual fun read(buffer: ByteArray): Int {
        return buffer.usePinned { pinned ->
            uds_read(fd, pinned.addressOf(0), buffer.size.convert()).toInt()
        }
    }

    actual override fun close() {
        uds_close(fd)
    }
}

actual fun connectUds(path: String): UdsConnection {
    val fd = uds_connect(path)
    if (fd < 0) throw IllegalStateException("uds_connect() failed: $path")
    return UdsConnection(fd)
}

actual class UdsServer(private val fd: Int, private val path: String) : AutoCloseable {
    actual override fun close() {
        uds_close(fd)
        platform.posix.unlink(path)
    }
}

actual fun bindUds(path: String, handler: (UdsConnection) -> Unit): UdsServer {
    val fd = uds_bind(path)
    if (fd < 0) throw IllegalStateException("uds_bind() failed: $path")
    return UdsServer(fd, path)
}

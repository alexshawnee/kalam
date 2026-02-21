package com.kalam

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

actual class UdsConnection(private val channel: SocketChannel) : AutoCloseable {
    actual fun write(data: ByteArray) {
        val buf = ByteBuffer.wrap(data)
        while (buf.hasRemaining()) {
            channel.write(buf)
        }
    }

    actual fun read(buffer: ByteArray): Int {
        val buf = ByteBuffer.wrap(buffer)
        return channel.read(buf)
    }

    actual override fun close() {
        channel.close()
    }
}

actual fun connectUds(path: String): UdsConnection {
    val addr = UnixDomainSocketAddress.of(path)
    val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
    channel.connect(addr)
    return UdsConnection(channel)
}

actual class UdsServer(private val serverChannel: ServerSocketChannel, private val socketPath: Path) : AutoCloseable {
    actual override fun close() {
        serverChannel.close()
        Files.deleteIfExists(socketPath)
    }
}

actual fun bindUds(path: String, handler: (UdsConnection) -> Unit): UdsServer {
    val socketPath = Path.of(path)
    Files.deleteIfExists(socketPath)

    val addr = UnixDomainSocketAddress.of(path)
    val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    serverChannel.bind(addr)

    thread(isDaemon = true) {
        while (serverChannel.isOpen) {
            try {
                val client = serverChannel.accept()
                thread(isDaemon = true) {
                    handler(UdsConnection(client))
                }
            } catch (_: Exception) {
                break
            }
        }
    }

    return UdsServer(serverChannel, socketPath)
}

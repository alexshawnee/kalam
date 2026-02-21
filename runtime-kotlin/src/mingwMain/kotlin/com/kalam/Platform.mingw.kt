package com.kalam

actual class UdsConnection : AutoCloseable {
    actual fun write(data: ByteArray) { TODO("Windows UDS not yet implemented") }
    actual fun read(buffer: ByteArray): Int = TODO("Windows UDS not yet implemented")
    actual override fun close() { TODO("Windows UDS not yet implemented") }
}

actual fun connectUds(path: String): UdsConnection = TODO("Windows UDS not yet implemented")

actual class UdsServer : AutoCloseable {
    actual override fun close() { TODO("Windows UDS not yet implemented") }
}

actual fun bindUds(path: String, handler: (UdsConnection) -> Unit): UdsServer = TODO("Windows UDS not yet implemented")

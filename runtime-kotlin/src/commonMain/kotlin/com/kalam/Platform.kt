package com.kalam

expect class UdsConnection : AutoCloseable {
    fun write(data: ByteArray)
    fun read(buffer: ByteArray): Int
    override fun close()
}

expect fun connectUds(path: String): UdsConnection

expect class UdsServer : AutoCloseable {
    override fun close()
}

expect fun bindUds(path: String, handler: (UdsConnection) -> Unit): UdsServer

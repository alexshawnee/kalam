package com.kalam

import kotlinx.coroutines.*

interface ServiceRouter {
    suspend fun handle(method: String, payload: ByteArray, sink: ResponseSink)
}

class ResponseSink(
    private val connection: UdsConnection,
    private val requestId: Int,
    private val method: String,
) {
    fun sendUnary(payload: ByteArray) {
        connection.write(encodeFrame(requestId, FRAME_TYPE_UNARY, method, payload))
    }

    fun sendChunk(payload: ByteArray) {
        connection.write(encodeFrame(requestId, FRAME_TYPE_STREAM_CHUNK, method, payload))
    }

    fun sendEnd() {
        connection.write(encodeFrame(requestId, FRAME_TYPE_STREAM_END, method, ByteArray(0)))
    }

    fun sendError(message: String) {
        connection.write(encodeFrame(requestId, FRAME_TYPE_ERROR, method, message.encodeToByteArray()))
    }
}

class KalamServer private constructor() {
    companion object {
        val instance = KalamServer()
    }

    private var server: UdsServer? = null

    fun serve(path: String, router: ServiceRouter) {
        server = bindUds(path) { client ->
            CoroutineScope(Dispatchers.Default).launch {
                handleClient(client, router)
            }
        }
        println("Kalam server listening on $path")
    }

    private suspend fun handleClient(client: UdsConnection, router: ServiceRouter) {
        val reader = FrameReader()
        val buf = ByteArray(4096)
        try {
            while (true) {
                val n = client.read(buf)
                if (n <= 0) break
                reader.add(buf.copyOf(n))

                while (true) {
                    val request = reader.tryReadFrame() ?: break
                    val sink = ResponseSink(client, request.requestId, request.method)
                    try {
                        router.handle(request.method, request.payload, sink)
                    } catch (e: Exception) {
                        sink.sendError(e.toString())
                    }
                }
            }
        } finally {
            client.close()
        }
    }

    fun close() {
        server?.close()
        server = null
    }
}

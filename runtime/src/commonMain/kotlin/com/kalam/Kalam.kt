package com.kalam

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Kalam private constructor() {
    companion object {
        val instance = Kalam()
    }

    private var socketPath: String? = null
    private var connection: UdsConnection? = null
    private var reader: FrameReader? = null
    private var nextRequestId = 1
    private val mutex = Mutex()
    private val pendingCalls = mutableMapOf<Int, CompletableDeferred<Frame>>()
    private val pendingStreams = mutableMapOf<Int, Channel<Frame>>()
    private var readJob: Job? = null

    fun useSockets(path: String) {
        socketPath = path
        disconnect()
    }

    private fun ensureConnected() {
        if (connection != null) return

        val path = socketPath ?: throw IllegalStateException("Kalam not initialized. Call useSockets() first.")
        connection = connectUds(path)
        reader = FrameReader()

        readJob = CoroutineScope(Dispatchers.Default).launch {
            val buf = ByteArray(4096)
            try {
                while (isActive) {
                    val n = connection!!.read(buf)
                    if (n <= 0) break
                    reader!!.add(buf.copyOf(n))

                    while (true) {
                        val frame = reader!!.tryReadFrame() ?: break
                        dispatch(frame)
                    }
                }
            } finally {
                disconnect()
            }
        }
    }

    private fun dispatch(frame: Frame) {
        val id = frame.requestId

        val deferred = pendingCalls.remove(id)
        if (deferred != null) {
            deferred.complete(frame)
            return
        }

        val channel = pendingStreams[id]
        if (channel != null) {
            when (frame.frameType) {
                FRAME_TYPE_STREAM_END -> {
                    pendingStreams.remove(id)
                    channel.close()
                }
                FRAME_TYPE_ERROR -> {
                    pendingStreams.remove(id)
                    channel.close(KalamException(frame.method, frame.payload.decodeToString()))
                }
                else -> {
                    channel.trySend(frame)
                }
            }
        }
    }

    private fun disconnect() {
        readJob?.cancel()
        readJob = null
        connection?.close()
        connection = null
        reader = null
        for (d in pendingCalls.values) {
            d.completeExceptionally(IllegalStateException("Connection lost"))
        }
        pendingCalls.clear()
        for (ch in pendingStreams.values) {
            ch.close(IllegalStateException("Connection lost"))
        }
        pendingStreams.clear()
    }

    suspend fun call(method: String, payload: ByteArray): ByteArray {
        mutex.withLock { ensureConnected() }

        val requestId = mutex.withLock { nextRequestId++ }
        val deferred = CompletableDeferred<Frame>()
        mutex.withLock { pendingCalls[requestId] = deferred }

        connection!!.write(encodeFrame(
            requestId = requestId,
            frameType = FRAME_TYPE_UNARY,
            method = method,
            payload = payload,
        ))

        val response = deferred.await()
        checkError(response)
        return response.payload
    }

    fun stream(method: String, payload: ByteArray): Flow<ByteArray> = flow {
        mutex.withLock { ensureConnected() }

        val requestId = mutex.withLock { nextRequestId++ }
        val channel = Channel<Frame>(Channel.UNLIMITED)
        mutex.withLock { pendingStreams[requestId] = channel }

        connection!!.write(encodeFrame(
            requestId = requestId,
            frameType = FRAME_TYPE_UNARY,
            method = method,
            payload = payload,
        ))

        for (frame in channel) {
            checkError(frame)
            emit(frame.payload)
        }
    }

    fun close() {
        disconnect()
    }
}

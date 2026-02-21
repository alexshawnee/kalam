package com.kalam

class FrameReader {
    private var buffer = ByteArray(0)

    fun add(chunk: ByteArray) {
        buffer = buffer + chunk
    }

    fun tryReadFrame(): Frame? {
        val available = buffer.size
        if (available < HEADER_SIZE) return null

        val methodLen = buffer.getInt(6)
        val neededForPayloadLen = HEADER_SIZE + methodLen
        if (available < neededForPayloadLen) return null

        val payloadLenOffset = 10 + methodLen
        val payloadLen = buffer.getInt(payloadLenOffset)
        val totalLen = neededForPayloadLen + payloadLen
        if (available < totalLen) return null

        val frame = Frame(
            version = buffer[0],
            requestId = buffer.getInt(1),
            frameType = buffer[5],
            method = buffer.decodeToString(10, 10 + methodLen),
            payload = buffer.copyOfRange(neededForPayloadLen, totalLen),
        )

        buffer = buffer.copyOfRange(totalLen, buffer.size)
        return frame
    }
}

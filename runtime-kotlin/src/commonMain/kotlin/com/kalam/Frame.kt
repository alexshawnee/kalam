package com.kalam

const val PROTOCOL_VERSION: Byte = 1
const val FRAME_TYPE_UNARY: Byte = 0
const val FRAME_TYPE_STREAM_CHUNK: Byte = 1
const val FRAME_TYPE_STREAM_END: Byte = 2
const val FRAME_TYPE_ERROR: Byte = 3

// Header: version(1) + requestId(4) + frameType(1) + methodLen(4) + payloadLen(4) = 14
const val HEADER_SIZE = 14

class Frame(
    val version: Byte,
    val requestId: Int,
    val frameType: Byte,
    val method: String,
    val payload: ByteArray,
)

fun encodeFrame(
    requestId: Int,
    frameType: Byte,
    method: String,
    payload: ByteArray,
): ByteArray {
    val methodBytes = method.encodeToByteArray()
    val total = HEADER_SIZE + methodBytes.size + payload.size
    val buf = ByteArray(total)
    var offset = 0

    buf[offset] = PROTOCOL_VERSION
    offset += 1

    buf.putInt(offset, requestId)
    offset += 4

    buf[offset] = frameType
    offset += 1

    buf.putInt(offset, methodBytes.size)
    offset += 4

    methodBytes.copyInto(buf, offset)
    offset += methodBytes.size

    buf.putInt(offset, payload.size)
    offset += 4

    payload.copyInto(buf, offset)

    return buf
}

internal fun ByteArray.putInt(offset: Int, value: Int) {
    this[offset] = (value shr 24).toByte()
    this[offset + 1] = (value shr 16).toByte()
    this[offset + 2] = (value shr 8).toByte()
    this[offset + 3] = value.toByte()
}

internal fun ByteArray.getInt(offset: Int): Int =
    (this[offset].toInt() and 0xFF shl 24) or
    (this[offset + 1].toInt() and 0xFF shl 16) or
    (this[offset + 2].toInt() and 0xFF shl 8) or
    (this[offset + 3].toInt() and 0xFF)

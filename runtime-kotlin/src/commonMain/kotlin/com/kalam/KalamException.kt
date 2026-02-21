package com.kalam

class KalamException(
    val method: String,
    override val message: String,
) : Exception("KalamException($method): $message")

internal fun checkError(frame: Frame) {
    if (frame.frameType == FRAME_TYPE_ERROR) {
        throw KalamException(frame.method, frame.payload.decodeToString())
    }
}

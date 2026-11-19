package com.quizai.mobile

/** One instance per press. Late callbacks from a cancelled press cannot submit an image. */
internal class HoldCapture {
    enum class State { OPENING, READY, CAPTURING, FINISHED, CANCELLED }
    var state = State.OPENING
        private set

    fun previewReady(): Boolean {
        if (state != State.OPENING) return false
        state = State.READY
        return true
    }

    fun release(): Boolean {
        if (state != State.READY) { if (state == State.OPENING) cancel(); return false }
        state = State.CAPTURING
        return true
    }

    fun acceptImage(): Boolean {
        if (state != State.CAPTURING) return false
        state = State.FINISHED
        return true
    }

    fun cancel() { if (state != State.FINISHED) state = State.CANCELLED }
}

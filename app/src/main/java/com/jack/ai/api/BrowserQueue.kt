package com.jack.ai.api

/** 下载队列的状态与并发名额，独立于手机界面。 */
object BrowserQueue {
    enum class State { QUEUED, RUNNING, PAUSED, COMPLETE, FAILED, CANCELLED }
    fun starts(states: List<State>): List<Int> = states.indices.filter { states[it] == State.QUEUED }
        .take((3 - states.count { it == State.RUNNING }).coerceAtLeast(0))
    fun pause(state: State) = if (state == State.RUNNING || state == State.QUEUED) State.PAUSED else state
    fun resume(state: State) = if (state == State.PAUSED || state == State.FAILED) State.QUEUED else state
}

package com.jack.ai

import kotlinx.coroutines.flow.update

/*
 * 排队：回答进行中发的话有两种。插话（默认）是插进这一轮，Kavi 当场停下带上重想；
 * 排队是不打扰这一轮，等它结束再当新消息发，适合「顺便再算一下 9 月」这种补充。
 * 思路参考 DSH Go（只借思路，代码自己写）。
 */

/** 把输入框里的话排进当前对话的队；对话没在回答就不排（直接发就行），返回 false。 */
fun ChatViewModel.queue(text: String): Boolean {
    val here = _state.value.currentId ?: return false
    val t = text.trim()
    if (t.isEmpty() || !busy(here)) return false
    _state.update { it.copy(queued = it.queued + (here to (it.queued[here].orEmpty() + t))) }
    return true
}

/** 撤回排着的第 [index] 条，返回它的字（放回输入框改了再发）。 */
fun ChatViewModel.unqueue(convId: String, index: Int): String? {
    var taken: String? = null
    _state.update { st ->
        val list = st.queued[convId].orEmpty()
        if (index !in list.indices) return@update st
        taken = list[index]
        val rest = list.filterIndexed { i, _ -> i != index }
        st.copy(queued = if (rest.isEmpty()) st.queued - convId else st.queued + (convId to rest))
    }
    return taken
}

/** 排着的改成插话：这一轮还在就插进去；已经结束了就直接发。 */
fun ChatViewModel.queuedToSteer(convId: String, index: Int) {
    val text = unqueue(convId, index) ?: return
    // send() 只管眼前这个对话：回答进行中走插话，没在回答就是一条新消息
    if (_state.value.currentId == convId && send(text)) return
    resendLeftover(convId, text)
}

/** 回答收尾时取走排在最前面的那条。 */
internal fun ChatViewModel.takeQueued(convId: String): String? = unqueue(convId, 0)

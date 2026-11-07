package com.jack.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jack.ai.data.AskCard

/** 「其他」选项的字：点了在卡片里出一个输入框，自己写。 */
internal const val ASK_OTHER = "其他（我自己说）"

/**
 * Kavi 的问题卡片（ask_user）：每个问题一张，选项点着选。只有一个单选问题时点一下就发；
 * 几个问题（或能多选）时选好再点「发送」。[active] = false（已经答过、不是最后一条）时只显示，不能点。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskCards(msgId: String, cards: List<AskCard>, active: Boolean, onAnswer: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val picks = remember(msgId) { mutableStateListOf<List<String>>().apply { repeat(cards.size) { add(emptyList()) } } }
    val others = remember(msgId) { mutableStateListOf<String>().apply { repeat(cards.size) { add("") } } }
    val otherOpen = remember(msgId) { mutableStateListOf<Boolean>().apply { repeat(cards.size) { add(false) } } }
    var sent by remember(msgId) { mutableStateOf(false) }
    val enabled = active && !sent
    // 一个单选问题：点一下就发，不用再点「发送」
    val instant = cards.size == 1 && !cards[0].multi

    fun answers(): List<List<String>> = cards.indices.map { i ->
        picks[i] + listOfNotNull(others[i].trim().takeIf { otherOpen[i] && it.isNotEmpty() })
    }
    fun send() {
        val text = com.jack.ai.tools.askAnswer(cards, answers())
        if (text.isBlank()) return
        sent = true
        onAnswer(text)
    }

    Column(Modifier.fillMaxWidth().testTag("ask-cards"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cards.forEachIndexed { i, card ->
            Surface(color = colors.secondaryContainer.copy(alpha = if (enabled) 0.6f else 0.3f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(card.question + if (card.multi) "（可以选几个）" else "", style = MaterialTheme.typography.titleSmall,
                        color = colors.onSecondaryContainer.copy(alpha = if (enabled) 1f else 0.6f))
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (card.options + ASK_OTHER).forEach { opt ->
                            val isOther = opt == ASK_OTHER
                            val selected = if (isOther) otherOpen[i] else opt in picks[i]
                            FilterChip(
                                selected = selected,
                                enabled = enabled,
                                onClick = {
                                    if (isOther) {
                                        otherOpen[i] = !otherOpen[i]
                                        // 单选：自己写就不要上面选的了
                                        if (otherOpen[i] && !card.multi) picks[i] = emptyList()
                                    } else {
                                        picks[i] = when {
                                            card.multi -> if (opt in picks[i]) picks[i] - opt else picks[i] + opt
                                            opt in picks[i] -> emptyList()
                                            else -> listOf(opt)
                                        }
                                        if (!card.multi) otherOpen[i] = false
                                        if (instant && picks[i].isNotEmpty()) send()
                                    }
                                },
                                label = { Text(opt) },
                            )
                        }
                    }
                    if (otherOpen[i] && enabled) {
                        OutlinedTextField(
                            value = others[i], onValueChange = { others[i] = it },
                            placeholder = { Text("想怎么说都行") },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("ask-other-$i"),
                            keyboardOptions = KeyboardOptions(imeAction = if (instant) ImeAction.Send else ImeAction.Done),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                            singleLine = false, maxLines = 4,
                        )
                    }
                }
            }
        }
        val ready = answers().any { it.isNotEmpty() }
        // 「发送」：几个问题、能多选，或者自己写了的时候
        if (enabled && (!instant || otherOpen[0])) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (cards.size > 1 && !answers().all { it.isNotEmpty() }) {
                    Text("没选的可以空着", style = MaterialTheme.typography.labelSmall, color = colors.outline, modifier = Modifier.padding(end = 8.dp))
                }
                Button(onClick = { send() }, enabled = ready, modifier = Modifier.testTag("ask-send")) { Text("发送") }
            }
        }
    }
}

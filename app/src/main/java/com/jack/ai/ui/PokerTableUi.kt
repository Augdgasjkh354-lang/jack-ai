package com.jack.ai.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jack.ai.data.games.Act
import com.jack.ai.data.games.HandEval
import com.jack.ai.data.games.PokerTable
import com.jack.ai.data.games.Card as PlayingCard

// 牌桌的样子照主流扑克 App 的通用做法：深色底、绿色桌布配木质桌边，玩家围着椭圆桌坐，
// 头像下面一块名牌（名字、筹码），下的注摆在座位和桌心之间，轮到谁谁的头像外面亮一圈。

/** 牌桌固定用这一套颜色，不跟着浅色、深色主题变（桌布本来就是深的）。 */
internal object Felt {
    val Backdrop = Brush.verticalGradient(listOf(Color(0xFF0E1F18), Color(0xFF07120D)))
    val Cloth = Brush.radialGradient(listOf(Color(0xFF2B8A57), Color(0xFF1B6B42), Color(0xFF0F4A2D)))
    val Rail = Brush.verticalGradient(listOf(Color(0xFF4A3424), Color(0xFF2A1C12)))
    val Gold = Color(0xFFE8C15A)
    val Text = Color.White
    val Dim = Color.White.copy(alpha = 0.62f)
    val Plate = Color.Black.copy(alpha = 0.55f)
    val Fold = Color(0xFF3B4045)
    val Call = Color(0xFF1E9E5A)
    val Raise = Color(0xFFE0A526)
}

/** 每个座位一种头像底色。 */
private val SEAT_HUES = listOf(Color(0xFF5C6BC0), Color(0xFFEF6C6C), Color(0xFF26A69A), Color(0xFFAB47BC), Color(0xFFFFA726))

/** 把这个东西的中心摆在父布局的 (fx, fy) 处（0～1）。 */
private fun Modifier.centerAt(fx: Float, fy: Float) = layout { m, c ->
    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
    layout(c.maxWidth, c.maxHeight) { p.place((fx * c.maxWidth - p.width / 2f).toInt(), (fy * c.maxHeight - p.height / 2f).toInt()) }
}

/** 对手 1～4 坐哪（左中、左上、右上、右中），你坐下面正中。 */
private val SEAT_POS = listOf(0.5f to 0.88f, 0.1f to 0.55f, 0.27f to 0.15f, 0.73f to 0.15f, 0.9f to 0.55f)
private const val CX = 0.5f
private const val CY = 0.47f

@Composable
internal fun PokerBoard(t: PokerTable, modifier: Modifier) {
    val tick by t.tick.collectAsState()
    tick.hashCode()
    val g = t.game
    val me = g.seats.first { it.human }
    Column(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp)) {
            val textW = maxWidth * 0.62f
            // 桌子：竖着的跑道形，木边加一圈细金线
            Box(
                Modifier.centerAt(0.5f, 0.5f).size(maxWidth * 0.8f, maxHeight * 0.84f)
                    .shadow(18.dp, RoundedCornerShape(50), ambientColor = Color.Black, spotColor = Color.Black)
                    .background(Felt.Rail, RoundedCornerShape(50)).padding(11.dp)
                    .border(1.dp, Felt.Gold.copy(alpha = 0.35f), RoundedCornerShape(50))
                    .background(Felt.Cloth, RoundedCornerShape(50)),
            )
            // 桌心：底池、公共牌、结果
            Column(Modifier.centerAt(CX, CY), horizontalAlignment = Alignment.CenterHorizontally) {
                val result = g.result
                Pill {
                    ChipDot(Felt.Gold, 12.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(if (result == null) "底池 ${fmt(g.pot)}" else "本手结束", color = Felt.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0 until 5).forEach { i ->
                        val c = g.board.getOrNull(i)
                        if (c != null) androidx.compose.runtime.key(g.hand, i) { Dealt { CardFace(c, 38.dp) } } else CardSlot(38.dp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    result?.text ?: g.log.lastOrNull().orEmpty().removePrefix("—— "),
                    color = if (result != null) Felt.Gold else Felt.Dim,
                    fontSize = if (result != null) 14.sp else 12.sp, fontWeight = if (result != null) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(textW),
                )
            }
            // 每个人下的注：摆在座位和桌心之间
            g.seats.forEach { s ->
                val (sx, sy) = SEAT_POS[s.id]
                if (s.bet > 0) BetChips(s.bet, Modifier.centerAt(sx + (CX - sx) * 0.42f, sy + (CY - sy) * 0.4f))
            }
            g.seats.forEach { s ->
                val (sx, sy) = SEAT_POS[s.id]
                SeatView(t, s.id, Modifier.centerAt(sx, sy))
            }
        }
        // 你的两张牌和牌型
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
            if (me.hole.isNotEmpty() && !me.out) {
                Box(Modifier.alpha(if (me.folded) 0.45f else 1f)) {
                    androidx.compose.runtime.key(g.hand) {
                        Row {
                            Dealt { CardFace(me.hole[0], 58.dp, Modifier.rotate(-6f)) }
                            Spacer(Modifier.width(2.dp))
                            Dealt(delayMs = 90) { CardFace(me.hole[1], 58.dp, Modifier.rotate(6f)) }
                        }
                    }
                }
                if (g.board.size >= 3 && !me.folded) {
                    Spacer(Modifier.width(12.dp))
                    Pill(Modifier.padding(bottom = 10.dp)) { Text(HandEval.name(HandEval.best(me.hole + g.board)), color = Felt.Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        Box(Modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp).heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
            when {
                g.over -> Hint(if (me.out) "筹码输光了。点右上角「结束牌局」重新开一局" else "整桌都被你赢下来了！点「结束牌局」再来一局")
                g.result != null -> TableButton("下一手", Felt.Call, Modifier.fillMaxWidth()) { t.startHand() }
                g.toAct == me.id -> ActionBar(t)
                else -> Hint(if (t.thinking >= 0) "${g.seats[t.thinking].name} 在想……" else "等别人出牌……")
            }
        }
    }
}

private fun fmt(n: Int) = "%,d".format(n)

@Composable
private fun Hint(text: String) {
    Text(text, color = Felt.Dim, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp))
}

@Composable
private fun Pill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.background(Felt.Plate, CircleShape).padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

/** 一个座位：头像（轮到时外面一圈金光转着）、身后两张牌、名牌、动作、台词。 */
@Composable
private fun SeatView(t: PokerTable, id: Int, modifier: Modifier) {
    val g = t.game
    val s = g.seats[id]
    val acting = g.toAct == id || t.thinking == id
    val shown = g.result?.shown?.containsKey(id) == true
    val won = g.result?.won?.get(id)
    Column(modifier.alpha(if (s.out) 0.35f else if (s.folded) 0.55f else 1f), horizontalAlignment = Alignment.CenterHorizontally) {
        t.says[id]?.takeIf { !s.human }?.let { Bubble(it) }
        Box(contentAlignment = Alignment.Center) {
            // 对手的两张牌斜插在头像后面；亮牌时翻过来
            if (!s.human && s.hole.isNotEmpty() && !s.out && !s.folded) {
                Row(Modifier.padding(start = 34.dp, bottom = 14.dp)) {
                    if (shown) { CardFace(s.hole[0], 26.dp, Modifier.rotate(-8f)); CardFace(s.hole[1], 26.dp, Modifier.rotate(8f)) }
                    else { CardBack(22.dp, Modifier.rotate(-8f)); CardBack(22.dp, Modifier.rotate(8f).padding(start = 0.dp)) }
                }
            }
            Avatar(s.name, SEAT_HUES[id % SEAT_HUES.size], acting, won != null && won > 0)
            if (g.dealer == id) {
                Box(Modifier.padding(start = 40.dp, top = 36.dp).size(18.dp).shadow(3.dp, CircleShape).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    Text("D", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Column(
            Modifier.background(Felt.Plate, RoundedCornerShape(10.dp)).border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 你的头像上已经写着「你」，名牌上只放筹码
            if (!s.human) Text(s.name, color = Felt.Text, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(if (s.out) "出局" else fmt(s.chips), color = Felt.Gold, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold)
        }
        val tag = when {
            won != null && won > 0 -> "+${fmt(won)}" to Felt.Gold
            t.thinking == id -> "思考中" to Color.White.copy(alpha = 0.3f)
            s.folded && !s.out -> "弃牌" to Felt.Fold
            s.last.startsWith("全下") -> "全下" to Color(0xFFD84343)
            s.last.startsWith("加注") -> "加注" to Felt.Raise
            s.last.startsWith("下注") -> "下注" to Felt.Raise
            s.last.startsWith("跟注") -> "跟注" to Felt.Call
            s.last.startsWith("过牌") -> "过牌" to Color(0xFF3D7BD9)
            else -> null
        }
        // 你自己的动作不用标（下面就是你的牌和按钮），省得压到手牌上
        if (tag != null && !s.human) {
            Spacer(Modifier.height(3.dp))
            Text(tag.first, color = if (tag.second == Felt.Gold || tag.second == Felt.Raise) Color.Black else Color.White, fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(tag.second, CircleShape).padding(horizontal = 8.dp, vertical = 1.dp))
        }
        if (!s.human) Text(t.modelName(id), color = Color.White.copy(alpha = 0.38f), fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, modifier = Modifier.width(84.dp), textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Avatar(name: String, hue: Color, acting: Boolean, winner: Boolean) {
    val spin = rememberInfiniteTransition(label = "seat")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "ring")
    val glow by spin.animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
        if (acting || winner) Canvas(Modifier.size(56.dp).graphicsLayer { rotationZ = if (acting) angle else 0f; alpha = if (winner) glow else 1f }) {
            drawCircle(
                Brush.sweepGradient(listOf(Felt.Gold.copy(alpha = 0f), Felt.Gold, Color(0xFFFFF3C4), Felt.Gold.copy(alpha = 0f))),
                style = Stroke(3.dp.toPx()),
            )
        }
        Box(
            Modifier.size(46.dp).shadow(6.dp, CircleShape)
                .background(Brush.linearGradient(listOf(hue, hue.copy(red = hue.red * 0.65f, green = hue.green * 0.65f, blue = hue.blue * 0.65f))), CircleShape)
                .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Bubble(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = Color(0xFF1B1B1F), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(IntrinsicWidthCap).background(Color.White, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
        Canvas(Modifier.size(10.dp, 5.dp)) {
            drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close() }, Color.White)
        }
    }
}

private val IntrinsicWidthCap = 104.dp

/** 下注：一小摞筹码加数字。 */
@Composable
private fun BetChips(amount: Int, modifier: Modifier) {
    Row(modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape).padding(start = 3.dp, end = 8.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        val color = when {
            amount >= 500 -> Color(0xFF7E57C2)
            amount >= 100 -> Color(0xFF212121)
            amount >= 25 -> Color(0xFF2E7D32)
            else -> Color(0xFFC62828)
        }
        ChipDot(color, 16.dp)
        Spacer(Modifier.width(4.dp))
        Text(fmt(amount), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** 一枚筹码：彩色圆，边上一圈白色短块，中间一个浅圈。 */
@Composable
private fun ChipDot(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        drawCircle(color)
        for (k in 0 until 6) {
            val a = Math.toRadians(k * 60.0).toFloat()
            drawLine(Color.White, Offset(center.x + r * 0.62f * kotlin.math.cos(a), center.y + r * 0.62f * kotlin.math.sin(a)),
                Offset(center.x + r * 0.98f * kotlin.math.cos(a), center.y + r * 0.98f * kotlin.math.sin(a)), strokeWidth = r * 0.32f)
        }
        drawCircle(Color.White.copy(alpha = 0.35f), r * 0.45f, style = Stroke(r * 0.1f))
    }
}

/** 发牌：从上面一点翻进来。 */
@Composable
private fun Dealt(delayMs: Int = 0, content: @Composable () -> Unit) {
    val still = reduceMotion()
    val p = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { if (p.value < 1f) { kotlinx.coroutines.delay(delayMs.toLong()); p.animateTo(1f, tween(260)) } }
    Box(Modifier.graphicsLayer { alpha = p.value; scaleX = 0.4f + 0.6f * p.value; translationY = (1f - p.value) * -18.dp.toPx() }) { content() }
}

/** 牌面：白底圆角，左上角点数和小花色，中间一个大花色。[w] 是宽，高按 1.42 倍。 */
@Composable
private fun CardFace(c: PlayingCard, w: Dp, modifier: Modifier = Modifier) {
    val h = w * 1.42f
    val tint = if (c.red) Color(0xFFD32F2F) else Color(0xFF1B1B1F)
    val small = w < 34.dp
    Box(
        modifier.size(w, h).shadow(4.dp, RoundedCornerShape(w * 0.14f))
            .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFF2F2F2))), RoundedCornerShape(w * 0.14f)),
    ) {
        Column(Modifier.padding(start = w * 0.1f, top = w * 0.06f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(c.rankText, color = tint, fontWeight = FontWeight.Bold, fontSize = (w.value * 0.36f).sp, lineHeight = (w.value * 0.38f).sp,
                letterSpacing = if (c.rank == 10) (-1).sp else 0.sp)
            if (!small) Text(c.suitText, color = tint, fontSize = (w.value * 0.26f).sp, lineHeight = (w.value * 0.27f).sp)
        }
        Text(c.suitText, color = tint, fontSize = (w.value * (if (small) 0.5f else 0.62f)).sp,
            modifier = Modifier.align(if (small) Alignment.BottomCenter else Alignment.BottomEnd).padding(end = if (small) 0.dp else w * 0.1f, bottom = w * 0.02f))
    }
}

/** 牌背：深红底、白边，斜格纹。 */
@Composable
private fun CardBack(w: Dp, modifier: Modifier = Modifier) {
    val h = w * 1.42f
    Box(modifier.size(w, h).shadow(3.dp, RoundedCornerShape(w * 0.14f)).background(Color.White, RoundedCornerShape(w * 0.14f)).padding(w * 0.08f)) {
        Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(w * 0.1f)).background(Brush.linearGradient(listOf(Color(0xFFC62828), Color(0xFF7F1414))))) {
            val step = this.size.width / 3
            var x = -this.size.height
            while (x < this.size.width + this.size.height) {
                drawLine(Color.White.copy(alpha = 0.18f), Offset(x, 0f), Offset(x + this.size.height, this.size.height), strokeWidth = 1.2f)
                drawLine(Color.White.copy(alpha = 0.18f), Offset(x + this.size.height, 0f), Offset(x, this.size.height), strokeWidth = 1.2f)
                x += step
            }
        }
    }
}

@Composable
private fun CardSlot(w: Dp) {
    Box(Modifier.size(w, w * 1.42f).border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(w * 0.14f)))
}

@Composable
private fun TableButton(text: String, color: Color, modifier: Modifier = Modifier, dark: Boolean = false, onClick: () -> Unit) {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val haptics = rememberHaptics()
    Box(
        modifier.height(52.dp).pressScale(press, 0.96f).shadow(6.dp, CircleShape).clip(CircleShape)
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 1f), color.copy(red = color.red * 0.82f, green = color.green * 0.82f, blue = color.blue * 0.82f))))
            .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current) { haptics.tick(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (dark) Color(0xFF1B1300) else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun ActionBar(t: PokerTable) {
    val g = t.game
    val me = g.seats.first { it.human }
    val l = g.legal(me.id)
    var raising by remember(g.hand, g.street, g.log.size) { mutableStateOf(false) }
    var to by remember(g.hand, g.street, g.log.size) { mutableStateOf(l.minRaiseTo.toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (raising && l.canRaise) {
            Column(Modifier.fillMaxWidth().background(Felt.Plate, RoundedCornerShape(18.dp)).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("加注到", color = Felt.Dim, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(fmt(to.toInt()), color = Felt.Gold, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = to, onValueChange = { to = it }, valueRange = l.minRaiseTo.toFloat()..l.maxRaiseTo.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = Felt.Gold, activeTrackColor = Felt.Gold, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("最小" to l.minRaiseTo, "½ 池" to me.bet + l.toCall + g.pot / 2, "1 池" to me.bet + l.toCall + g.pot, "全下" to l.maxRaiseTo).forEach { (label, v) ->
                        val value = v.coerceIn(l.minRaiseTo, l.maxRaiseTo)
                        Text(label, color = Felt.Text, fontSize = 13.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).clickable { to = value.toFloat() }.padding(vertical = 7.dp))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TableButton("弃牌", Felt.Fold, Modifier.weight(1f)) { t.play(Act.Fold) }
            TableButton(if (l.canCheck) "过牌" else if (l.toCall >= me.chips) "全下 ${fmt(me.chips)}" else "跟注 ${fmt(l.toCall)}", Felt.Call, Modifier.weight(1.25f)) {
                t.play(if (l.canCheck) Act.Check else Act.Call)
            }
            when {
                raising && l.canRaise -> TableButton("确定", Felt.Raise, Modifier.weight(1f), dark = true) { t.play(Act.Raise(to.toInt())) }
                l.canRaise -> TableButton(if (l.canCheck) "下注" else "加注", Felt.Raise, Modifier.weight(1f), dark = true) { raising = true }
                l.toCall < me.chips -> TableButton("全下", Felt.Raise, Modifier.weight(1f), dark = true) { t.play(Act.AllIn) }
            }
        }
    }
}

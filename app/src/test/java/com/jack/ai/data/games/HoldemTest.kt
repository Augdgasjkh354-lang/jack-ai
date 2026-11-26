package com.jack.ai.data.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HoldemTest {
    private fun c(s: String): Card {
        val rank = when (val r = s.dropLast(1)) { "A" -> 14; "K" -> 13; "Q" -> 12; "J" -> 11; else -> r.toInt() }
        return Card(rank, "shdc".indexOf(s.last()))
    }
    private fun cs(s: String) = s.split(" ").map(::c)

    @Test fun handRanksInTheRightOrder() {
        val hands = listOf(
            "2s 7h 9d Jc Kh", "2s 2h 9d Jc Kh", "2s 2h 9d 9c Kh", "2s 2h 2d 9c Kh", "As 2h 3d 4c 5h",
            "6s 7h 8d 9c 10h", "2s 7s 9s Js Ks", "2s 2h 2d 9c 9h", "2s 2h 2d 2c Kh", "9s 10s Js Qs Ks",
        ).map { HandEval.best(cs(it)) }
        assertEquals(hands.sorted(), hands)
        assertEquals("顺子", HandEval.name(HandEval.best(cs("As 2h 3d 4c 5h"))))
        // 七张里挑最好的：公共牌上的同花
        assertEquals("同花", HandEval.name(HandEval.best(cs("2s 3d 4s 7s 9s Ks Qh"))))
        // 一样大就平分
        assertEquals(HandEval.best(cs("As Kd 2c 2h 7s 8d 9c")), HandEval.best(cs("Ah Kc 2c 2h 7s 8d 9c")))
    }

    @Test fun chipsAreConservedOverManyRandomHands() {
        val rnd = Random(7)
        repeat(30) { round ->
            val g = Holdem(List(5) { "p$it" to (it == 0) }, 1000, rnd = Random(round))
            var hands = 0
            while (!g.over && hands < 60) {
                g.startHand(); hands++
                var steps = 0
                while (g.toAct >= 0 && steps < 200) { g.act(PokerBot.decide(g, g.toAct, rnd)); steps++ }
                assertTrue("一手没打完", g.result != null)
                assertEquals(5000, g.seats.sumOf { it.chips })
                assertTrue(g.seats.all { it.chips >= 0 })
            }
        }
    }

    @Test fun everyoneFoldsToTheBigBlind() {
        val g = Holdem(List(3) { "p$it" to false }, 1000, rnd = Random(1))
        g.startHand()
        // 庄家 0，小盲 1，大盲 2：0 先说话
        assertEquals(0, g.toAct)
        g.act(Act.Fold); g.act(Act.Fold)
        assertEquals(mapOf(2 to 30), g.result!!.won)
        assertEquals(1010, g.seats[2].chips)
    }

    @Test fun shortAllInMakesASidePot() {
        val g = Holdem(List(3) { "p$it" to false }, 1000, rnd = Random(3))
        g.seats[0].chips = 100
        g.startHand()
        g.act(Act.AllIn) // 0 全下 100
        g.act(Act.Raise(400)) // 1 加到 400
        g.act(Act.Call) // 2 跟 400
        // 之后两个人过牌到底
        while (g.toAct >= 0) g.act(Act.Check)
        val r = g.result!!
        assertEquals(900, r.won.values.sum())
        // 0 最多只能赢主池 300
        assertTrue((r.won[0] ?: 0) <= 300)
        assertEquals(2100, g.seats.sumOf { it.chips })
    }

    @Test fun headsUpDealerPostsTheSmallBlind() {
        val g = Holdem(List(2) { "p$it" to false }, 1000, rnd = Random(2))
        g.startHand()
        assertEquals(g.dealer, g.toAct)
        assertEquals(10, g.seats[g.dealer].total)
    }
}

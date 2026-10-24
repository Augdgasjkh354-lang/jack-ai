package com.jack.ai.tools

import com.jack.ai.data.PricePoint
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PriceChartTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun drawsPngForTwoOrMorePoints() {
        val pts = listOf(129.0, 99.0, 109.0).mapIndexed { i, p -> PricePoint("保温杯", p, 1_700_000_000_000L + i * 86_400_000L) }
        val f = PriceChart.draw(pts, java.io.File(tmp.root, "c.png"))
        assertTrue(f != null && f.length() > 0)
        assertNull(PriceChart.draw(pts.take(1), java.io.File(tmp.root, "d.png")))
    }
}

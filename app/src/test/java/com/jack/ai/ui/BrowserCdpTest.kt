package com.jack.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserCdpTest {
    @Test fun screenshotCoordinatesFollowCssViewport() {
        assertEquals(250.0, BrowserCdpCoords.fromScreenshot(180f, 1440, 1000f), 0.001)
        assertEquals(150.0, BrowserCdpCoords.fromScreenshot(150f, 300, 300f), 0.001)
    }

    @Test fun navigationKeysHaveRealKeyCodes() {
        assertEquals(13, BrowserCdpKeys.named("ENTER")?.codePoint)
        assertEquals(8, BrowserCdpKeys.named("backspace")?.codePoint)
        assertEquals(9, BrowserCdpKeys.named("tab")?.codePoint)
        assertNull(BrowserCdpKeys.named("not-a-key"))
    }

    @Test fun failedInputOnlyFallsBackBeforePageMayChange() {
        assertEquals(BrowserCdp.Outcome.FALLBACK, BrowserCdp.failureOutcome(false))
        assertEquals(BrowserCdp.Outcome.UNCERTAIN, BrowserCdp.failureOutcome(true))
    }
}

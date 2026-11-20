package com.jack.ai.ui

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LegalTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun devNeverAsksAndDocsAreBundled() {
        assertFalse(Legal.mustAsk(ctx))
        assertTrue(Legal.text(ctx, Legal.Doc.PRIVACY).contains("隐私政策"))
        assertTrue(Legal.text(ctx, Legal.Doc.TERMS).contains("用户协议"))
    }

    @Test fun agreeingIsRemembered() {
        assertFalse(Legal.agreed(ctx))
        Legal.agree(ctx)
        assertTrue(Legal.agreed(ctx))
    }
}

package com.jack.ai.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {
    @Test fun overnightWindowCoversLateEveningAndEarlyMorning() {
        assertTrue(Routines.quietNow(22, 8, 23))
        assertTrue(Routines.quietNow(22, 8, 0))
        assertTrue(Routines.quietNow(22, 8, 7))
        assertFalse(Routines.quietNow(22, 8, 8))
        assertFalse(Routines.quietNow(22, 8, 21))
    }

    @Test fun daytimeWindowAndOffSettings() {
        assertTrue(Routines.quietNow(13, 15, 14))
        assertFalse(Routines.quietNow(13, 15, 15))
        assertFalse(Routines.quietNow(-1, 8, 3))
        assertFalse(Routines.quietNow(5, 5, 5))
    }
}

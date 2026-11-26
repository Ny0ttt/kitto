package com.ny0t.kitto

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakableTest {
    @Test
    fun plainTextIsUnchanged() {
        assertEquals("Food has RM72 left.", speakable("Food has RM72 left."))
    }

    @Test
    fun boldAndCodeMarksAreRemoved() {
        assertEquals("Food has RM72 left in food_budget.", speakable("**Food** has RM72 left in `food_budget`."))
    }

    @Test
    fun headingsAndBulletsAreRemoved() {
        assertEquals("Due today\nsingki dapur\ntoilet", speakable("## Due today\n- singki dapur\n• toilet"))
    }

    @Test
    fun blankAfterCleaningIsEmpty() {
        assertEquals("", speakable("  ** ``  "))
    }
}

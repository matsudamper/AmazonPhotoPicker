package net.matsudamper.amazonphotopicker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeTypeMatcherTest {
    @Test
    fun wildcardMatchesAnyImage() {
        assertTrue(MimeTypeMatcher.matches("image/heic", listOf("image/*")))
        assertTrue(MimeTypeMatcher.matches("image/jpeg", listOf("*/*")))
        assertTrue(MimeTypeMatcher.matches("image/jpeg", emptyList()))
    }

    @Test
    fun specificSubtypeIsRespected() {
        assertTrue(MimeTypeMatcher.matches("image/png", listOf("image/png")))
        assertTrue(MimeTypeMatcher.matches("IMAGE/PNG", listOf("image/png", "image/gif")))
        assertFalse(MimeTypeMatcher.matches("image/jpeg", listOf("image/png")))
        assertFalse(MimeTypeMatcher.matches("image/jpeg", listOf("video/*")))
    }
}

package gearfinder.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NormaliseTest {
    @Test fun sizes() {
        assertEquals("m", sizeKey("Medium")); assertEquals("m", sizeKey("Mens M"))
        assertEquals("9.5", sizeKey("UK 9.5")); assertEquals("9.5", sizeKey("9½")); assertEquals("9.5", sizeKey("9 1/2"))
        assertEquals("xxl", sizeKey("2XL")); assertEquals("os", sizeKey("One Size"))
        assertEquals("eu42", sizeKey("EU 42")); assertNotEquals(sizeKey("EU 42"), sizeKey("UK 42"))
        assertEquals("m/l", sizeKey("M/L")); assertNotEquals(sizeKey("M/L"), sizeKey("M"))
        assertEquals("32r", sizeKey("32 R")); assertNotEquals(sizeKey("32R"), sizeKey("32L"))
        assertEquals("7", sizeKey("UK 7 | EU 40")); assertEquals("7.5", sizeKey("EU 41 (UK 7.5)"))
        assertEquals("xl", sizeKey("X-Large")); assertEquals("xl", sizeKey("Extra Large")); assertEquals("", sizeKey("  "))
    }

    @Test fun colours() {
        assertEquals(colourKey("Black/Grey"), colourKey("Grey & Black"))
        assertEquals("black", colourKey("TNF Black")); assertEquals("", colourKey("No Colour")); assertEquals("", colourKey(null))
        assertEquals("", colourKey("Default Title")); assertNotEquals(colourKey("Black"), colourKey("Blue"))
    }
}

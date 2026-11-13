package gearfinder.merge

import gearfinder.model.Listing
import gearfinder.model.SizeStock
import gearfinder.model.Variant
import gearfinder.model.colourKey
import gearfinder.model.sizeKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

fun v(colour: String?, pence: Int, vararg sizes: Pair<String, Boolean>, inStock: Boolean = true, url: String = "u") =
    Variant(colour, colourKey(colour), "https://img/x.jpg", pence, null, url, sizes.map { SizeStock(it.first, sizeKey(it.first), it.second, pence) }, if (sizes.isEmpty()) inStock else sizes.any { it.second })

fun listing(shop: String, title: String, brand: String = "Rab", vararg variants: Variant, hint: String? = null) =
    Listing(shop, title, brand, hint, null, "https://$shop/${title.hashCode()}", "desc", variants.toList().ifEmpty { listOf(v(null, 1000)) })

class MergeTest {
    @Test fun classifies() {
        assertEquals(Placement("Clothing", "Insulated & Down Jackets"), classify("Rab Microlight Alpine Down Jacket"))
        assertEquals(Placement("Footwear", "Trail Running Shoes"), classify("Speedgoat 6 Trail Running Shoes"))
        assertEquals(Placement("Camping", "Tents"), classify("Copper Spur UL2 Tent"))
        assertNull(classify("Carp Fishing Reel 5000")); assertNull(classify("Caravan Awning Poles"))
        assertNull(classify("Race Goggles", brand = "Speedo"))
        assertEquals(Placement("Other", "Other"), classify("Zzyzx Qwerty"))
        assertEquals("Tents", classify("Icarus Air 700", brand = "Vango")!!.category, "brand default when the name says nothing")
        assertEquals("Trousers & Leggings", classify("Kinetic 2.0", "Mens Trousers")!!.category, "shop category used when the name says nothing")
        assertTrue("Clothing" in TAXONOMY && "Fishing" !in TAXONOMY)
        assertEquals(listOf("Clothing", "Footwear", "Packs & Bags", "Camping"), TAXONOMY.keys.take(4))
        for (p in RULE_PLACEMENTS.filter { it.group !in EXCLUDED_GROUPS }) assertTrue(p.category in TAXONOMY[p.group].orEmpty(), "$p is missing from the menu order")
    }

    @Test fun theShopsOwnCategorySettlesAmbiguousTitles() {
        val c = { title: String, hint: String -> classify(title, hint)?.category }
        assertEquals("Waterproof Jackets", c("Womens Signal 2.5L Dryvent Jacket", "Womens Jackets Waterproof Jackets"))
        assertEquals("Insulated & Down Jackets", c("Mens Highland Winter Parka V", "Mens Jackets Insulated Jackets"))
        assertEquals("Waterproof Jackets", c("Kids Pack-It III Jacket", "Childrens Outdoor Jackets Waterproof Jackets"))
        assertEquals("Food & Nutrition", c("Energy Gel - Orange (Pack of 20)", "Equipment Food & Drinks Sports Nutrition"))
        assertEquals("Food & Nutrition", c("Pasta Bolognese", "")); assertEquals("Food & Nutrition", c("Morning Oats with Raspberry Camping Food", ""))
        assertEquals("Lighting", c("Recharge Magnet Light", "Camping Lighting"))
        assertEquals("Fleece & Midlayers", c("Womens K-2 Ski Half Zip Knitted Sweater", "Womens Midlayers Fleeces + Midlayers"))
        assertEquals("Shorts & Skirts", c("Womens Trail Shorts", "Womens Legwear"), "the title is more exact than the shop's shelf")
        assertEquals("Skis & Snowboards", c("Mens Cartel X Snowboard Bindings", "Snowboard Clothing"))
        assertEquals("Clothing", classify("Capilene Thermal Weight Tights", "Clothing Base Layers")?.group, "'weight' is not fishing tackle here")
        assertEquals("Tops & T-Shirts", c("Mens Wild Cat T-Shirt", "Mens Tops T-Shirts"))
        assertNull(classify("Womens Kelli Bandeau One Piece Swimsuit", "Womens Beach & Watersports Swimwear"))
        assertNull(classify("Saddle Bag - 4.1L", "Activities Cycling Rucksacks & Luggage"))
        assertEquals("Care & Repair", c("Footwear + Gear Cleaner Spray-On - 275ml", "Outdoor Footwear Footwear Accessories"))
        assertEquals("Travel & Towels", c("SoftFibre Recycled Towel - Pocket", "")); assertEquals("Travel & Towels", c("Open and Close 11 Umbrella", "Equipment"))
        assertEquals("First Aid & Safety", c("Rechargeable Hand Warmer + Light 10K", "")); assertEquals("Maps & Books", c("Peak District Climbing Guidebook", ""))
        assertEquals("Electronics & Optics", c("Nature-Trek 8x42 Binoculars", "")); assertEquals("Belts & Accessories", c("Stretch Web Belt", ""))
    }

    @Test fun sameItemFromTwoShopsBecomesOneProduct() {
        val out = merge(listOf(
            listing("cotswold", "Men's Microlight Alpine Jacket", "Rab", v("Black", 18000, "M" to true)),
            listing("alpine", "Rab - Microlight Alpine Jacket - Mens", "Rab", v("Black", 17000, "M" to true)),
            listing("cotswold", "Women's Microlight Alpine Jacket", "Rab", v("Blue", 18000, "10" to true)),
        ))
        assertEquals(2, out.size)
        val men = out.single { it.gender == "Men" }
        assertEquals(listOf("alpine", "cotswold"), men.offers.map { it.retailerId }, "cheapest shop first")
        assertEquals("Rab", men.brand); assertEquals("Clothing", men.group)
    }

    @Test fun coloursFromOneShopFoldIntoOneOffer() {
        val out = merge(listOf(
            listing("eb", "Latok Jacket - Black", "Rab", v(null, 30000)), listing("eb", "Latok Jacket - Blue", "Rab", v(null, 28000)),
        ))
        assertEquals(1, out.size); assertEquals("Latok Jacket", out[0].name)
    }

    @Test fun packSuffixIsPartOfTheName() {
        val out = merge(listOf(listing("eb", "Tent Pegs - Pack of 28", "Vango"), listing("eb", "Tent Pegs - Pack of 10", "Vango")))
        assertEquals(setOf("Tent Pegs - Pack of 28", "Tent Pegs - Pack of 10"), out.map { it.name }.toSet())
    }

    @Test fun brandAndColourAreReadFromTitlesThatCarryThem() {
        val out = merge(listOf(
            listing("trekitt", "Rab Women's Phantom Jacket Hibiscus", "", v(null, 14000)),
            listing("joebrown", "La Sportiva Women's Lumina 200 Jacket in Limestone/Night Sky", "", v(null, 29500)),
            listing("cotswold", "Women's Phantom Jacket", "Rab", v("Hibiscus", 15000, "10" to true)),
        ))
        val phantom = out.single { "Phantom" in it.name }
        assertEquals("Rab", phantom.brand); assertEquals(2, phantom.offers.size)
        assertEquals("hibiscus", phantom.offers.single { it.retailerId == "trekitt" }.variants[0].colourKey)
        val lumina = out.single { "Lumina" in it.name }
        assertEquals("La Sportiva", lumina.brand); assertEquals("Women's Lumina 200 Jacket", lumina.name)
        assertEquals(colourKey("Limestone/Night Sky"), lumina.offers[0].variants[0].colourKey)
    }

    @Test fun outOfScopeItemsAreDropped() {
        assertTrue(merge(listOf(listing("eb", "Carp Fishing Reel", "Shimano"))).isEmpty())
    }

    @Test fun idsAreUniqueAndStable() {
        val ls = listOf(listing("eb", "Latok Jacket", "Rab"), listing("eb", "Latok-Jacket", "Rab"))
        val a = merge(ls).map { it.id }; assertEquals(a.size, a.toSet().size); assertEquals(a, merge(ls).map { it.id })
    }
}

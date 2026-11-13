package gearfinder.recommend

import gearfinder.catalogue.Catalogue
import gearfinder.catalogue.CatalogueWriter
import gearfinder.merge.listing
import gearfinder.merge.merge
import gearfinder.merge.v
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RecommendTest {
    private fun jacket(shop: String, name: String, pence: Int, desc: String, vararg sizes: Pair<String, Boolean>) =
        listing(shop, name, "Rab", v("Black", pence, *sizes)).copy(description = desc)

    private val catalogue: Catalogue = Files.createTempDirectory("rec").toFile().let { dir ->
        CatalogueWriter.write(dir, merge(listOf(
            jacket("cotswold", "Men's Storm Shell Waterproof Jacket", 18000, "Gore-Tex hardshell with insulated lining and a helmet-compatible hood for winter.", "M" to true),
            jacket("alpine", "Rab - Storm Shell Waterproof Jacket - Mens", 17500, "Waterproof.", "M" to true),
            jacket("cotswold", "Men's Drizzle Waterproof Jacket", 9000, "A simple waterproof jacket.", "M" to true),
            jacket("cotswold", "Men's Summit Waterproof Jacket", 45000, "Gore-Tex Pro hardshell, insulated, hood, built for winter.", "M" to true),
            jacket("cotswold", "Men's Gone Waterproof Jacket", 10000, "Waterproof insulated hood winter hardshell.", "M" to false, "L" to true),
            jacket("cotswold", "Women's Storm Shell Waterproof Jacket", 18000, "Gore-Tex hardshell insulated hood winter.", "10" to true),
            listing("cotswold", "Copper Spur UL2 Tent", "Big Agnes", v(null, 45000, "os" to true)),
        ) + (1..12).map { jacket("cotswold", "Men's Filler $it Waterproof Jacket", 5000 + it, "plain", "M" to true) }), emptyList(), 1)
        Catalogue.open(dir)
    }

    @Test fun parsesAPlainRequest() {
        val r = parseRequest("waterproof jacket for Scottish winter under £200, men's medium")
        assertEquals("Waterproof Jackets", r.category?.category); assertEquals("Men", r.gender); assertEquals("m", r.sizeKey)
        assertEquals(20000, r.maxPence); assertTrue("winter" in r.uses && "wet" in r.uses)
        for (t in listOf("jacket under 200", "jacket £200", "jacket 200 quid", "jacket, budget of 200 pounds")) assertEquals(20000, parseRequest(t).maxPence, t)
        assertEquals("9.5", parseRequest("trail running shoes size 9.5").sizeKey); assertEquals("xl", parseRequest("fleece in XL").sizeKey)
        assertEquals("Rab", parseRequest("a rab down jacket", listOf("Rab", "Montane")).brand)
        assertEquals("black", parseRequest("black trousers").colourKey)
    }

    @Test fun vagueRequestsGetOneQuestionNotACrash() {
        for (t in listOf("", "   ", "asdf qwer", "something under £50", "£££", "!!!", "x".repeat(5000))) assertIs<Advice.Ask>(recommend(t, catalogue), "'${t.take(20)}'")
        assertTrue(assertIs<Advice.Ask>(recommend("something under £50", catalogue)).options.isNotEmpty())
    }

    @Test fun ranksByMatchedFeaturesWithinBudgetAndSize() {
        val a = assertIs<Advice.Picks>(recommend("waterproof jacket for Scottish winter under £200, men's medium", catalogue))
        assertEquals(10, a.picks.size, "never more than ten")
        assertEquals("Men's Storm Shell Waterproof Jacket", a.picks[0].summary.name)
        assertTrue(a.picks[0].matched.containsAll(listOf("insulated", "hood", "hardshell", "waterproof")), a.picks[0].matched.toString())
        assertTrue(a.picks[0].reason.startsWith("matches: ") && "2 shops" in a.picks[0].reason && "in stock in M" in a.picks[0].reason, a.picks[0].reason)
        val names = a.picks.map { it.summary.name }
        assertTrue(names.none { "Summit" in it }, "over budget"); assertTrue(names.none { "Gone" in it }, "medium is out of stock")
        assertTrue(names.none { "Women" in it } && names.none { "Tent" in it })
        assertTrue(a.picks.all { it.summary.fromPence <= 20000 })
        assertTrue("up to £200" in a.understood && "size M" in a.understood)
    }

    @Test fun saysWhichLimitExcludedEverything() {
        assertTrue("£50" in assertIs<Advice.Nothing>(recommend("men's waterproof jacket under £10", catalogue)).why)
        assertTrue("size XXL" in assertIs<Advice.Nothing>(recommend("men's waterproof jacket size xxl", catalogue)).why)
        assertTrue("purple" in assertIs<Advice.Nothing>(recommend("purple waterproof jacket", catalogue)).why)
    }
}

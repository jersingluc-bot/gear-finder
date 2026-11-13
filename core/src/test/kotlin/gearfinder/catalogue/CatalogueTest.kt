package gearfinder.catalogue

import gearfinder.merge.listing
import gearfinder.merge.merge
import gearfinder.merge.v
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogueTest {
    private val products = merge(listOf(
        listing("cotswold", "Men's Microlight Alpine Down Jacket", "Rab", v("Black", 18000, "M" to false, "L" to true), v("Blue", 20000, "M" to true)),
        listing("alpine", "Rab - Microlight Alpine Down Jacket - Mens", "Rab", v("Black", 15000, "M" to false, "L" to true)),
        listing("eb", "Men's Microlight Alpine Down Jacket", "Rab", v(null, 14000)),
        listing("cotswold", "Women's Speedgoat 6 Trail Running Shoes", "Hoka", v("Pink", 9000, "5" to true).copy(wasPence = 14000)),
        listing("alpine", "Copper Spur UL2 Tent", "Big Agnes", v(null, 45000, inStock = false)),
    ))
    private fun open(): Catalogue {
        val dir = Files.createTempDirectory("cat").toFile()
        CatalogueWriter.write(dir, products, listOf(RetailerHealth("eb", "Ellis Brigham", 1, 99)), builtAt = 1234)
        return Catalogue.open(dir)
    }

    @Test fun roundTrips() {
        val c = open()
        assertEquals(1234, c.meta.builtAt); assertEquals(3, c.meta.products); assertEquals("eb", c.meta.retailers[0].id)
        for (p in products) assertEquals(p, c.product(p.id))
        assertNull(c.product("nope"))
    }

    @Test fun textAndFilters() {
        val c = open()
        assertEquals(listOf("Men's Microlight Alpine Down Jacket"), c.search(Query(text = "microlight rab")).items.map { it.name })
        assertEquals(3, c.search(Query()).total)
        assertEquals(14000, c.search(Query(text = "microlight")).items[0].fromPence, "overall cheapest with no filters")
        assertEquals(20000, c.search(Query(text = "microlight", sizeKey = "m")).items[0].fromPence, "cheapest shop that confirms a medium in stock")
        assertEquals(15000, c.search(Query(text = "microlight", sizeKey = "l")).items[0].fromPence)
        assertEquals(0, c.search(Query(text = "microlight", sizeKey = "xl")).total)
        assertEquals(18000, c.search(Query(text = "microlight", retailers = setOf("cotswold"))).items[0].fromPence)
        assertEquals(20000, c.search(Query(text = "microlight", colourKey = "blue")).items[0].fromPence)
        assertEquals(0, c.search(Query(text = "microlight", maxPence = 13999)).total)
        assertEquals(listOf("Hoka"), c.search(Query(onSaleOnly = true)).items.map { it.brand })
        assertEquals(2, c.search(Query(inStockOnly = true)).total, "the out-of-stock tent is excluded")
        assertEquals(listOf("Hoka"), c.search(Query(gender = "Women")).items.map { it.brand }.filter { it == "Hoka" })
        assertEquals(listOf(45000, 14000, 9000), c.search(Query(sort = Sort.PriceHigh)).items.map { it.fromPence })
        assertEquals(1, c.search(Query(limit = 1, offset = 1, sort = Sort.PriceLow)).items.size)
    }

    @Test fun facetsAndTags() {
        val c = open()
        val f = c.facets()
        assertTrue("Rab" in f.brands && "m" in f.sizes && "black" in f.colours); assertTrue("Camping" in f.groups)
        assertTrue("down" in c.search(Query(text = "microlight")).items[0].tags)
    }

    @Test fun damagedCatalogueIsRejected() {
        val dir = Files.createTempDirectory("cat").toFile()
        CatalogueWriter.write(dir, products, emptyList(), 1)
        File(dir, "index.jsonl.gz").writeBytes(File(dir, "index.jsonl.gz").readBytes().copyOf(20))
        assertFailsWith<Exception> { Catalogue.open(dir) }
    }
}

package gearfinder.retail

/** Every shop the app covers. Add a shop by adding a line here and a saved page under src/test/resources/pages. */
val RETAILERS: List<RetailerAdapter> = listOf(
    SitemapAdapter("cotswold", "Cotswold Outdoor", "https://www.cotswoldoutdoor.com",
        listOf("https://www.cotswoldoutdoor.com/sitemap/index.xml"), Regex("""product-\d+\.xml"""), Regex("/p/"), parse = ::parseAsAdventure),
    SitemapAdapter("snowrock", "Snow+Rock", "https://www.snowandrock.com",
        listOf("https://www.snowandrock.com/sitemap/index.xml"), Regex("""product-\d+\.xml"""), Regex("/p/"), parse = ::parseAsAdventure),
    SitemapAdapter("runnersneed", "Runners Need", "https://www.runnersneed.com",
        listOf("https://www.runnersneed.com/sitemap/index.xml"), Regex("""product-\d+\.xml"""), Regex("/p/"), parse = ::parseAsAdventure),
    SitemapAdapter("alpine", "Alpine Trek", "https://www.alpinetrek.co.uk",
        listOf("https://www.alpinetrek.co.uk/sitemap/sitemap.xml.gz"), Regex("sitemap_products"), sliceOf = 7),
    SitemapAdapter("eb", "Ellis Brigham", "https://www.ellis-brigham.com",
        listOf("https://www.ellis-brigham.com/sitemap.xml"), productUrl = Regex("""-\d{7,}$""")),
    SitemapAdapter("absolutesnow", "Absolute Snow", "https://www.absolute-snow.co.uk",
        listOf("https://www.absolute-snow.co.uk/sitemap.xml"), Regex("sitemap-products")),
    SitemapAdapter("ultralight", "Ultralight Outdoor Gear", "https://ultralightoutdoorgear.co.uk",
        listOf("https://ultralightoutdoorgear.co.uk/xmlsitemap.php"), Regex("type=products")),
    SitemapAdapter("trekitt", "Trekitt", "https://www.trekitt.co.uk",
        listOf("https://www.trekitt.co.uk/sitemap.xml"), productUrl = Regex("""__\d+$"""), parse = ::parseTrekitt),
    SitemapAdapter("joebrown", "Joe Brown / The Climbers Shop", "https://www.climbers-shop.com",
        listOf("https://www.joe-brown.com/sitemap.xml"), productUrl = Regex("""__\d+$"""), parse = ::parseTrekitt),
    SitemapAdapter("taunton", "Taunton Leisure", "https://www.tauntonleisure.com",
        listOf("https://www.tauntonleisure.com/sitemap.xml"), productUrl = Regex("""__\d+$"""), parse = ::parseTrekitt),
)

fun retailer(id: String): RetailerAdapter? = RETAILERS.firstOrNull { it.id == id }

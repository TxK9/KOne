package cc.txwd.kone.core

/** A category list a rule can name: SagerNet's list ([name]), what people call it, and its group on the Rules page. */
data class Category(val name: String, val title: String, val group: String)

/**
 * The category lists Rules offers by name. Any other geosite- or geoip- list SagerNet publishes
 * can be typed; these are the common ones. Lists of names (geosite) also steer DNS; lists of
 * addresses (geoip, the regions) match the address a connection goes to.
 */
object Categories {
    const val SERVICES = "Services"
    const val WEBSITES = "Websites"
    const val REGIONS = "Regions"

    val ALL = listOf(
        Category("geosite-netflix", "Netflix", SERVICES), Category("geosite-youtube", "YouTube", SERVICES),
        Category("geosite-disney", "Disney+", SERVICES), Category("geosite-hbo", "HBO", SERVICES),
        Category("geosite-primevideo", "Prime Video", SERVICES), Category("geosite-spotify", "Spotify", SERVICES),
        Category("geosite-twitch", "Twitch", SERVICES), Category("geosite-tiktok", "TikTok", SERVICES),
        Category("geosite-telegram", "Telegram", SERVICES), Category("geosite-whatsapp", "WhatsApp", SERVICES),
        Category("geosite-discord", "Discord", SERVICES), Category("geosite-twitter", "X (Twitter)", SERVICES),
        Category("geosite-facebook", "Facebook", SERVICES), Category("geosite-instagram", "Instagram", SERVICES),
        Category("geosite-openai", "OpenAI", SERVICES), Category("geosite-category-ai-!cn", "AI services", SERVICES),
        Category("geosite-google", "Google", WEBSITES), Category("geosite-github", "GitHub", WEBSITES),
        Category("geosite-microsoft", "Microsoft", WEBSITES), Category("geosite-apple", "Apple", WEBSITES),
        Category("geosite-amazon", "Amazon", WEBSITES), Category("geosite-paypal", "PayPal", WEBSITES),
        Category("geosite-steam", "Steam", WEBSITES), Category("geosite-category-games", "Games", WEBSITES),
        Category("geosite-category-dev", "Developer sites", WEBSITES), Category("geosite-category-scholar-!cn", "Scholarly sites", WEBSITES),
        Category("geosite-category-media", "News and media", WEBSITES), Category("geosite-category-social-media-!cn", "Social media", WEBSITES),
        Category("geosite-bilibili", "Bilibili", WEBSITES), Category("geosite-geolocation-!cn", "Sites outside China", WEBSITES),
        Category("geoip-us", "United States", REGIONS), Category("geoip-jp", "Japan", REGIONS),
        Category("geoip-hk", "Hong Kong", REGIONS), Category("geoip-sg", "Singapore", REGIONS),
        Category("geoip-tw", "Taiwan", REGIONS), Category("geoip-kr", "South Korea", REGIONS),
        Category("geoip-gb", "United Kingdom", REGIONS), Category("geoip-de", "Germany", REGIONS),
    )

    fun title(name: String): String? = ALL.firstOrNull { it.name == name }?.title

    /** Where SagerNet publishes [name]: a fixed address, only the list's name varies. */
    fun url(name: String): String {
        val repository = if (name.startsWith("geoip-")) "sing-geoip" else "sing-geosite"
        return "https://raw.githubusercontent.com/SagerNet/$repository/rule-set/$name.srs"
    }
}

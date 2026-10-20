package vn.vumap.feature.search

/**
 * Vietnamese administrative aliases: old/new names and nicknames
 * (project plan section 5: "ten cu/ten moi, biet danh").
 *
 * All keys are stored in [VietnameseNormalizer.matchingForm] so lookups work
 * with or without diacritics. Boundaries/versions noted per entry; the table is
 * versioned as [TABLE_VERSION] and must be reviewed when admin units change.
 *
 * Pure Kotlin, no Android dependency, covered by JVM unit tests.
 */
object AdminAliases {

    const val TABLE_VERSION = "2026.10"

    /**
     * alias (normalized) -> canonical display name.
     *
     * Sources: Thu Duc city merger (2021), Ha Tay merged into Ha Noi (2008),
     * 2025 provincial mergers (Quang Nam -> Da Nang; Binh Thuan + Dak Nong ->
     * Lam Dong). Common nicknames included for autocomplete recall.
     */
    private val aliasToCanonical: Map<String, String> = mapOf(
        // TP.HCM nicknames / old names
        "sai gon" to "TP. Hồ Chí Minh",
        "saigon" to "TP. Hồ Chí Minh",
        "thanh pho ho chi minh" to "TP. Hồ Chí Minh",
        "tp hcm" to "TP. Hồ Chí Minh",
        "tphcm" to "TP. Hồ Chí Minh",
        "tp. ho chi minh" to "TP. Hồ Chí Minh",
        // Thu Duc city (merger of Quan 2 + Quan 9 + Quan Thu Duc, 2021)
        "quan 2" to "TP. Thủ Đức",
        "quan 9" to "TP. Thủ Đức",
        "quan thu duc" to "TP. Thủ Đức",
        "q2" to "TP. Thủ Đức",
        "q9" to "TP. Thủ Đức",
        // Ha Noi: Ha Tay merged in 2008
        "ha tay" to "Hà Nội",
        "son tay" to "Hà Nội",
        // Da Nang expansion (Quang Nam merged into Da Nang, 2025)
        "quang nam" to "Đà Nẵng",
        "hoi an" to "Hội An, Đà Nẵng",
        "tam ky" to "Tam Kỳ, Đà Nẵng",
        // Lam Dong expansion (Binh Thuan + Dak Nong merged into Lam Dong, 2025)
        "binh thuan" to "Lâm Đồng",
        "phan thiet" to "Phan Thiết, Lâm Đồng",
        "mui ne" to "Mũi Né, Lâm Đồng",
        "dak nong" to "Lâm Đồng",
        "gia nghia" to "Gia Nghĩa, Lâm Đồng",
        // Can Tho / Mekong nicknames
        "can tho" to "Cần Thơ",
        "tay do" to "Cần Thơ",
        // Khanh Hoa (2025 context kept minimal; well-known resort names)
        "nha trang" to "Nha Trang, Khánh Hòa",
        "da lat" to "Đà Lạt, Lâm Đồng",
    )

    /**
     * Canonical display name for a normalized alias, or null when unknown.
     */
    fun canonicalFor(rawAlias: String): String? =
        aliasToCanonical[VietnameseNormalizer.matchingForm(rawAlias)]

    /**
     * Normalized tokens of every alias key and canonical locality. These are
     * locality vocabulary only: an overlap on them proves nothing about whether
     * a result answers the query, so [MatchEvidence] strips them from the
     * query before demanding match evidence for a VERIFIED badge.
     */
    val localityTokens: Set<String> by lazy {
        val out = HashSet<String>()
        fun addTokens(text: String) {
            VietnameseNormalizer.matchingForm(text)
                .split(' ')
                .filter { it.isNotEmpty() }
                // "TP." and "TP" are the same word for matching purposes.
                .forEach { out.add(it.trim('.')) }
        }
        aliasToCanonical.forEach { (alias, canonical) ->
            addTokens(alias)
            addTokens(canonical)
        }
        out
    }

    /**
     * Query variants for Photon: the original query plus one variant per known
     * alias token found inside it. Variants are tried in order; Photon itself
     * is the source of truth for coordinates — aliases only widen recall.
     *
     * Honesty guard: a bare canonical locality (e.g. "TP. Hồ Chí Minh") is
     * emitted ONLY when the whole input is that alias. Address queries keep
     * their street/POI terms in every variant — otherwise a retry could return
     * the city as a successful address hit, skip the street-only fallback,
     * and even badge a `place` POI as VERIFIED for a street that was never
     * found.
     */
    fun variants(query: String): List<String> {
        val norm = VietnameseNormalizer.matchingForm(query)
        if (norm.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        out.add(query.trim())
        // Longest aliases first so "quan thu duc" wins over shorter overlaps.
        val sorted = aliasToCanonical.keys.sortedByDescending { it.length }
        for (alias in sorted) {
            if (norm.contains(alias)) {
                val canonical = aliasToCanonical.getValue(alias)
                val expanded = norm.replace(alias, VietnameseNormalizer.matchingForm(canonical))
                if (expanded != norm) out.add(expanded)
                // Bare canonical only when the alias IS the whole query.
                if (norm == alias) out.add(canonical)
            }
        }
        return out.toList()
    }
}

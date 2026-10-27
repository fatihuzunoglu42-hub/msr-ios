package com.msrcevirici.msr.pipeline

/** Aynı metni tekrar tekrar çevirmemek için basit LRU önbellek. Tek iş parçacığından kullanılır. */
class TranslationCache(private val maxSize: Int = 500) {
    private val map = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > maxSize
    }

    operator fun get(source: String): String? = map[source]

    operator fun set(source: String, translated: String) {
        map[source] = translated
    }

    val size: Int get() = map.size
}

package com.medremind.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One registered product from the bundled list (`assets/drugs.tsv`). */
data class DrugProduct(
    val brand: String,
    /** As listed, e.g. `50 mg+500 mg`. */
    val strength: String,
    val generic: String,
    val form: String,
) {
    /** How a prescription would write it: `Janmet 50/500`, `D-Rise 40000 IU`. */
    val displayName: String get() = listOf(brand, compactStrength(strength)).filter { it.isNotBlank() }.joinToString(" ")

    companion object {
        /** `50 mg+500 mg` → `50/500` and `10 mg` → `10`, the way doctors write them; other units stay. */
        fun compactStrength(strength: String): String {
            val parts = strength.split("+").map { it.trim() }
            return if (parts.isNotEmpty() && parts.all { MG.matches(it) }) {
                parts.joinToString("/") { MG.find(it)!!.groupValues[1] }
            } else {
                strength.trim()
            }
        }

        private val MG = Regex("""^(\d+(?:\.\d+)?)\s*mg$""", RegexOption.IGNORE_CASE)
    }
}

/** A suggested product for a name as read or typed. [exact] means the brand is spelled the same. */
data class DrugMatch(val product: DrugProduct, val score: Double, val exact: Boolean)

/**
 * Fuzzy brand-name matching against Bangladesh's registered medicines. It can catch an AI misreading
 * one or two letters (`Janner 50/500` → Janmet), and gives the generic name so the family can tell
 * what a medicine is. It only ever suggests: nothing is renamed without the user tapping it.
 *
 * The list is the "Medicinal Products in Bangladesh" dataset (University of Dhaka, Mendeley Data,
 * CC BY 4.0). Credit is shown in সেটিংস.
 */
class DrugMatcher(products: List<DrugProduct>) {

    private class Brand(val key: String, val products: List<Pair<DrugProduct, List<Double>>>)

    private val brands: List<Brand> = products
        .groupBy { normalize(it.brand) }
        .filterKeys { it.isNotEmpty() }
        .map { (key, list) -> Brand(key, list.map { it to numbers(it.strength) }) }

    /**
     * Up to [limit] products, one per brand, best first. [text] may include the strength
     * (`Janmet 50/500`); [form] is the prescription's form word, if any.
     */
    fun suggest(text: String, form: String? = null, limit: Int = 3, minScore: Double = 0.75): List<DrugMatch> {
        val (brandText, strengthText) = split(text)
        val wanted = normalize(brandText)
        if (wanted.length < 3) return emptyList()
        val wantedNumbers = numbers(strengthText)
        val formPrefix = form?.lowercase()?.take(3)

        return brands.asSequence()
            .mapNotNull { brand ->
                val similarity = 1.0 - levenshtein(wanted, brand.key).toDouble() / maxOf(wanted.length, brand.key.length)
                if (similarity < 0.5) return@mapNotNull null
                val prefixBonus = if (brand.key.startsWith(wanted.take(3))) 0.1 else 0.0
                brand.products
                    .map { (product, have) ->
                        val strengthBonus =
                            if (wantedNumbers.isNotEmpty() && wantedNumbers.all { it in have }) 0.15 else 0.0
                        val formBonus =
                            if (formPrefix != null && product.form.lowercase().startsWith(formPrefix)) 0.05 else 0.0
                        DrugMatch(product, similarity + prefixBonus + strengthBonus + formBonus, brand.key == wanted)
                    }
                    .maxBy { it.score }
            }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    /** Brand names starting with what has been typed, for autocomplete. One product per brand + strength. */
    fun complete(prefix: String, limit: Int = 6): List<DrugProduct> {
        val wanted = normalize(split(prefix).first)
        if (wanted.length < 2) return emptyList()
        return brands.asSequence()
            .filter { it.key.startsWith(wanted) }
            .sortedBy { it.key.length }
            .flatMap { brand -> brand.products.asSequence().map { it.first } }
            .distinctBy { it.displayName.lowercase() }
            .take(limit)
            .toList()
    }

    companion object {
        private val PREFIX = Regex("""^(tab|tablet|cap|capsule|syp|syrup|inj|susp)\.?\s+""", RegexOption.IGNORE_CASE)

        /** `Tab. Janmet 50/500` → (`Janmet`, `50/500`): the brand ends where the first number starts. */
        fun split(text: String): Pair<String, String> {
            val cleaned = text.trim().replace(PREFIX, "")
            val firstDigit = cleaned.indexOfFirst { it.isDigit() }
            // Brands like "D-Rise" or "3-C" keep a leading number with the name.
            return if (firstDigit <= 0) cleaned to "" else cleaned.take(firstDigit).trim() to cleaned.drop(firstDigit)
        }

        fun normalize(text: String): String = text.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

        fun numbers(text: String): List<Double> =
            Regex("""\d+(?:\.\d+)?""").findAll(text).map { it.value.toDouble() }.toList()

        fun levenshtein(a: String, b: String): Int {
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                }
                val swap = previous
                previous = current
                current = swap
            }
            return previous[b.length]
        }

        /** Parses the asset: brand, strength, generic name, form, tab-separated. */
        fun parse(lines: Sequence<String>): List<DrugProduct> = lines.mapNotNull { line ->
            val cells = line.split('\t')
            if (cells.size < 4 || cells[0].isBlank()) null else DrugProduct(cells[0], cells[1], cells[2], cells[3])
        }.toList()
    }
}

/** Loads the bundled list once, off the main thread, and keeps it for the process. */
object DrugDirectory {

    private val lock = Mutex()

    @Volatile
    private var matcher: DrugMatcher? = null

    suspend fun get(context: Context): DrugMatcher = matcher ?: lock.withLock {
        matcher ?: withContext(Dispatchers.IO) {
            context.applicationContext.assets.open("drugs.tsv").bufferedReader().useLines { DrugMatcher.parse(it) }
        }.let { products -> withContext(Dispatchers.Default) { DrugMatcher(products) } }.also { matcher = it }
    }
}

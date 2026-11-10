package com.example.voiceexpense

import java.util.Locale

/** Turns "spent 200 on lunch" into amount=200, category=Food, note=lunch. */
object ExpenseParser {
    data class Result(val amount: Double, val category: String, val note: String)

    // Add your own words here. First matching category wins.
    private val categories = linkedMapOf(
        "Food" to listOf("food", "lunch", "dinner", "breakfast", "tea", "chai", "coffee", "snack", "snacks",
            "pizza", "burger", "canteen", "mess", "tiffin", "juice", "biryani", "dosa", "vada pav",
            "restaurant", "cafe", "grocery", "groceries"),
        "Travel" to listOf("petrol", "fuel", "diesel", "bus", "auto", "rickshaw", "uber", "ola", "rapido",
            "metro", "train", "ticket", "cab", "taxi", "parking", "toll", "travel"),
        "Shopping" to listOf("shopping", "clothes", "shirt", "shoes", "jeans", "amazon", "flipkart", "myntra"),
        "Bills" to listOf("recharge", "electricity", "rent", "wifi", "internet", "bill", "bills", "subscription"),
        "Study" to listOf("book", "books", "xerox", "print", "printing", "stationery", "pen", "notebook",
            "fees", "fee", "course", "workshop", "project", "components"),
        "Health" to listOf("medicine", "medicines", "doctor", "hospital", "gym", "protein", "supplement", "supplements"),
        "Entertainment" to listOf("movie", "movies", "netflix", "game", "games", "party", "outing", "spotify")
    )

    fun parse(raw: String): Result? {
        val t = raw.lowercase(Locale.ROOT).replace(",", "")
        val m = Regex("""\d+(\.\d+)?""").find(t) ?: return null
        val amount = m.value.toDouble()
        if (amount <= 0) return null

        val rest = t.removeRange(m.range)
            .replace(Regex("""₹|\brs\.?|\brupees?\b|\binr\b"""), " ")
            .replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val padded = " $rest "

        var category: String? = null
        // Explicit: "... category travel"
        Regex("""\bcategory (?:is )?([a-z]+)""").find(rest)?.let {
            category = it.groupValues[1].replaceFirstChar { c -> c.uppercase() }
        }
        if (category == null) {
            for ((cat, words) in categories) {
                if (words.any { padded.contains(" $it ") }) { category = cat; break }
            }
        }
        val note = Regex("""\b(?:on|for)\s+(.+?)(?:\s+category.*)?$""")
            .find(rest)?.groupValues?.get(1)?.trim().orEmpty()
        return Result(amount, category ?: "Other", note.ifEmpty { rest })
    }
}

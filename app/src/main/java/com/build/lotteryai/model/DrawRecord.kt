package com.build.lotteryai.model

data class DrawRecord(
    val issue: String,
    val drawDate: String,
    val reds: List<Int>,
    val blue: Int,
    val sourceUrl: String,
    val fetchedAt: Long = System.currentTimeMillis()
) {
    fun isValid(): Boolean =
        issue.matches(Regex("\\d{5,10}")) &&
            drawDate.isNotBlank() &&
            reds.size == 6 &&
            reds.all { it in 1..33 } &&
            reds.distinct().size == 6 &&
            reds == reds.sorted() &&
            blue in 1..16

    fun displayNumbers(): String = reds.joinToString(" ") { it.toString().padStart(2, '0') } +
        " + " + blue.toString().padStart(2, '0')
}

data class Candidate(
    val reds: List<Int>,
    val blue: Int,
    val strategy: String,
    val score: Double,
    val features: Map<String, String> = emptyMap()
) {
    fun isValid(): Boolean =
        reds.size == 6 && reds.all { it in 1..33 } && reds.distinct().size == 6 &&
            reds == reds.sorted() && blue in 1..16

    fun displayNumbers(): String = reds.joinToString(" ") { it.toString().padStart(2, '0') } +
        " + " + blue.toString().padStart(2, '0')
}

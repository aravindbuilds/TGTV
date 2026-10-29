package com.tgtv.mobile.model

/** Country dial codes for the phone-login picker (popular first, then A→Z). */
object CountryCodes {

    data class Country(val name: String, val dial: String)

    val entries: List<Country> = listOf(
        Country("India", "+91"),
        Country("United States", "+1"),
        Country("United Kingdom", "+44"),
        Country("United Arab Emirates", "+971"),
        Country("Australia", "+61"),
        Country("Bangladesh", "+880"),
        Country("Belgium", "+32"),
        Country("Brazil", "+55"),
        Country("Canada", "+1"),
        Country("China", "+86"),
        Country("Egypt", "+20"),
        Country("France", "+33"),
        Country("Germany", "+49"),
        Country("Hong Kong", "+852"),
        Country("Indonesia", "+62"),
        Country("Italy", "+39"),
        Country("Japan", "+81"),
        Country("Kenya", "+254"),
        Country("Kuwait", "+965"),
        Country("Malaysia", "+60"),
        Country("Maldives", "+960"),
        Country("Mexico", "+52"),
        Country("Nepal", "+977"),
        Country("Netherlands", "+31"),
        Country("New Zealand", "+64"),
        Country("Nigeria", "+234"),
        Country("Norway", "+47"),
        Country("Oman", "+968"),
        Country("Pakistan", "+92"),
        Country("Philippines", "+63"),
        Country("Poland", "+48"),
        Country("Portugal", "+351"),
        Country("Qatar", "+974"),
        Country("Russia", "+7"),
        Country("Saudi Arabia", "+966"),
        Country("Singapore", "+65"),
        Country("South Africa", "+27"),
        Country("South Korea", "+82"),
        Country("Spain", "+34"),
        Country("Sri Lanka", "+94"),
        Country("Sweden", "+46"),
        Country("Switzerland", "+41"),
        Country("Thailand", "+66"),
        Country("Turkey", "+90"),
        Country("Ukraine", "+380"),
        Country("Vietnam", "+84"),
    )

    /**
     * Splits a saved full number like "+919876543210" into (dial, rest) using
     * longest-prefix matching against the known codes; falls back to a short
     * leading digit run or the default "+91".
     */
    fun split(raw: String): Pair<String, String> {
        val plus = raw.trim()
        if (plus.startsWith("+")) {
            val digits = plus.drop(1).takeWhile { it.isDigit() }
            for (len in 4 downTo 1) {
                if (digits.length < len) continue
                val candidate = "+" + digits.take(len)
                val hit = entries.firstOrNull { it.dial == candidate }
                if (hit != null) return hit.dial to digits.drop(len)
            }
            // Unknown prefix: take a conservative 2-digit code.
            if (digits.length > 2) return "+${digits.take(2)}" to digits.drop(2)
        }
        return "+91" to plus.dropWhile { it == '+' }.filter { it.isDigit() }
    }
}

package io.github.phantom.gps.utils

import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Minimal Open Location Code (Plus Codes) decoder (offline).
 *
 * Notes:
 * - Full codes decode directly.
 * - Short codes require a reference location (recoverNearest).
 *
 * This is intentionally self-contained to avoid adding new dependencies.
 */
object OpenLocationCode {

    private const val CODE_ALPHABET = "23456789CFGHJMPQRVWX"
    private const val SEPARATOR = '+'
    private const val SEPARATOR_POSITION = 8
    private const val PADDING_CHAR = '0'

    private const val PAIR_CODE_LENGTH = 10
    private const val GRID_COLUMNS = 4
    private const val GRID_ROWS = 5

    private const val LATITUDE_MAX = 90.0
    private const val LONGITUDE_MAX = 180.0

    private val PAIR_RESOLUTIONS = doubleArrayOf(
        20.0,
        1.0,
        0.05,
        0.0025,
        0.000125
    )

    data class CodeArea(
        val latitudeLo: Double,
        val longitudeLo: Double,
        val latitudeHi: Double,
        val longitudeHi: Double,
    ) {
        val latitudeCenter: Double
            get() = (latitudeLo + latitudeHi) / 2.0
        val longitudeCenter: Double
            get() = normalizeLongitude((longitudeLo + longitudeHi) / 2.0)
    }

    fun extractFromText(text: String): String? {
        val s = text.uppercase(Locale.US)
        var idx = s.indexOf(SEPARATOR)
        while (idx >= 0) {
            var start = idx - 1
            while (start >= 0 && isAllowedBeforeSeparator(s[start])) start--
            var end = idx + 1
            while (end < s.length && isAllowedAfterSeparator(s[end])) end++
            val candidate = s.substring(start + 1, end).replace(" ", "")
            if (isValid(candidate)) return candidate
            idx = s.indexOf(SEPARATOR, idx + 1)
        }
        return null
    }

    fun decodeToCenter(
        code: String,
        referenceLatitude: Double? = null,
        referenceLongitude: Double? = null,
    ): Pair<Double, Double>? {
        val cleaned = sanitize(code)
        if (!isValid(cleaned)) return null
        return if (isShort(cleaned)) {
            if (referenceLatitude == null || referenceLongitude == null) return null
            recoverNearestCenter(cleaned, referenceLatitude, referenceLongitude)
        } else {
            val area = decodeFull(cleaned) ?: return null
            area.latitudeCenter to area.longitudeCenter
        }
    }

    fun isValid(code: String): Boolean {
        val cleaned = sanitize(code)
        val sep = cleaned.indexOf(SEPARATOR)
        if (sep < 0 || cleaned.lastIndexOf(SEPARATOR) != sep) return false
        if (sep < 2 || sep > SEPARATOR_POSITION) return false
        if ((sep % 2) == 1) return false // must be an even number of chars before separator (pairs)
        val after = cleaned.length - sep - 1
        if (after < 2) return false // typical plus codes have at least 2 chars after '+'

        val padIndex = cleaned.indexOf(PADDING_CHAR)
        if (padIndex >= 0) {
            if (padIndex >= sep) return false
            // Padding must be continuous and only before the separator.
            for (i in padIndex until sep) {
                if (cleaned[i] != PADDING_CHAR) return false
            }
            if (((sep - padIndex) % 2) == 1) return false
        }

        for (i in cleaned.indices) {
            val c = cleaned[i]
            if (c == SEPARATOR) continue
            if (c == PADDING_CHAR) {
                if (i >= sep) return false
                continue
            }
            if (CODE_ALPHABET.indexOf(c) < 0) return false
        }
        return true
    }

    fun isShort(code: String): Boolean {
        val cleaned = sanitize(code)
        val sep = cleaned.indexOf(SEPARATOR)
        if (sep < 0) return false
        return sep < SEPARATOR_POSITION
    }

    private fun sanitize(code: String): String {
        return code.trim()
            .uppercase(Locale.US)
            .replace(Regex("\\s+"), "")
    }

    private fun isAllowedBeforeSeparator(c: Char): Boolean {
        return c == PADDING_CHAR || CODE_ALPHABET.indexOf(c) >= 0
    }

    private fun isAllowedAfterSeparator(c: Char): Boolean {
        return CODE_ALPHABET.indexOf(c) >= 0
    }

    private fun recoverNearestCenter(shortCode: String, refLat: Double, refLng: Double): Pair<Double, Double>? {
        val cleaned = sanitize(shortCode)
        val sep = cleaned.indexOf(SEPARATOR)
        if (sep < 0) return null
        val paddingLength = SEPARATOR_POSITION - sep
        if (paddingLength <= 0) {
            val area = decodeFull(cleaned) ?: return null
            return area.latitudeCenter to area.longitudeCenter
        }

        val referenceCode = encode(refLat, refLng, PAIR_CODE_LENGTH)
        val prefix = referenceCode.substring(0, paddingLength)
        val fullCode = prefix + cleaned
        val area = decodeFull(fullCode) ?: return null

        val resolutionIndex = (paddingLength / 2) - 1
        val resolution = PAIR_RESOLUTIONS.getOrNull(resolutionIndex) ?: return null

        var lat = area.latitudeCenter
        var lng = area.longitudeCenter

        val latDiff = lat - refLat
        if (latDiff > (resolution / 2.0)) lat -= resolution
        else if (latDiff < -(resolution / 2.0)) lat += resolution

        val lngDiff = normalizeLongitude(lng - refLng)
        if (lngDiff > (resolution / 2.0)) lng -= resolution
        else if (lngDiff < -(resolution / 2.0)) lng += resolution

        lat = clipLatitude(lat)
        lng = normalizeLongitude(lng)
        return lat to lng
    }

    private fun decodeFull(fullCode: String): CodeArea? {
        val cleaned = sanitize(fullCode)
        if (!isValid(cleaned)) return null
        if (isShort(cleaned)) return null

        val digits = cleaned
            .replace(SEPARATOR.toString(), "")
            .replace(PADDING_CHAR.toString(), "")

        if (digits.length < 2) return null

        val pairLengthRaw = min(PAIR_CODE_LENGTH, digits.length)
        val pairLength = if ((pairLengthRaw % 2) == 0) pairLengthRaw else pairLengthRaw - 1

        var lat = -LATITUDE_MAX
        var lng = -LONGITUDE_MAX
        var latPlace = 0.0
        var lngPlace = 0.0

        var i = 0
        while (i < pairLength) {
            val latDigit = CODE_ALPHABET.indexOf(digits[i])
            val lngDigit = CODE_ALPHABET.indexOf(digits[i + 1])
            if (latDigit < 0 || lngDigit < 0) return null

            val pairIndex = i / 2
            val placeValue = PAIR_RESOLUTIONS.getOrNull(pairIndex) ?: return null

            lat += latDigit * placeValue
            lng += lngDigit * placeValue
            latPlace = placeValue
            lngPlace = placeValue

            i += 2
        }

        // Grid refinement.
        var gridLatPlace = latPlace
        var gridLngPlace = lngPlace
        if (digits.length > pairLength) {
            gridLatPlace /= GRID_ROWS.toDouble()
            gridLngPlace /= GRID_COLUMNS.toDouble()
            for (j in pairLength until digits.length) {
                val digit = CODE_ALPHABET.indexOf(digits[j])
                if (digit < 0) return null
                val row = digit / GRID_COLUMNS
                val col = digit % GRID_COLUMNS
                lat += row * gridLatPlace
                lng += col * gridLngPlace
                gridLatPlace /= GRID_ROWS.toDouble()
                gridLngPlace /= GRID_COLUMNS.toDouble()
            }
        }

        val latHi = lat + gridLatPlace
        val lngHi = lng + gridLngPlace
        return CodeArea(
            latitudeLo = clipLatitude(lat),
            longitudeLo = normalizeLongitude(lng),
            latitudeHi = clipLatitude(latHi),
            longitudeHi = normalizeLongitude(lngHi),
        )
    }

    private fun encode(latitude: Double, longitude: Double, codeLength: Int): String {
        val lat = clipLatitude(latitude)
        val lng = normalizeLongitude(longitude)

        var adjustedLat = lat + LATITUDE_MAX
        var adjustedLng = lng + LONGITUDE_MAX

        val digitsToGenerate = max(SEPARATOR_POSITION, min(codeLength, PAIR_CODE_LENGTH))
        val sb = StringBuilder(digitsToGenerate + 1)

        for (pairIndex in 0 until (digitsToGenerate / 2)) {
            val placeValue = PAIR_RESOLUTIONS[pairIndex]

            val latDigit = floor(adjustedLat / placeValue).toInt().coerceIn(0, CODE_ALPHABET.length - 1)
            adjustedLat -= latDigit * placeValue
            sb.append(CODE_ALPHABET[latDigit])

            val lngDigit = floor(adjustedLng / placeValue).toInt().coerceIn(0, CODE_ALPHABET.length - 1)
            adjustedLng -= lngDigit * placeValue
            sb.append(CODE_ALPHABET[lngDigit])

            if (sb.length == SEPARATOR_POSITION) {
                sb.append(SEPARATOR)
            }
        }

        // Ensure separator exists.
        if (!sb.contains(SEPARATOR)) {
            while (sb.length < SEPARATOR_POSITION) sb.append(PADDING_CHAR)
            sb.append(SEPARATOR)
        }

        // If asked for more precision than the separator position, add two more digits (normal precision).
        // This isn't needed for our short-code recovery prefix logic, but keeps output well-formed.
        while ((sb.length - 1) < (codeLength + 1) && (sb.length - 1) < (PAIR_CODE_LENGTH + 1)) {
            val pairIndex = ((sb.length - 1).coerceAtLeast(0)) / 2
            if (pairIndex >= PAIR_RESOLUTIONS.size) break
            val placeValue = PAIR_RESOLUTIONS[pairIndex]
            val latDigit = floor(adjustedLat / placeValue).toInt().coerceIn(0, CODE_ALPHABET.length - 1)
            adjustedLat -= latDigit * placeValue
            sb.append(CODE_ALPHABET[latDigit])
            if ((sb.length - 1) >= (codeLength + 1)) break
            val lngDigit = floor(adjustedLng / placeValue).toInt().coerceIn(0, CODE_ALPHABET.length - 1)
            adjustedLng -= lngDigit * placeValue
            sb.append(CODE_ALPHABET[lngDigit])
        }

        return sb.toString()
    }

    private fun clipLatitude(latitude: Double): Double {
        val clipped = min(max(latitude, -LATITUDE_MAX), LATITUDE_MAX)
        // Plus codes can't represent exactly 90.0, so nudge down very slightly.
        return if (clipped >= LATITUDE_MAX) LATITUDE_MAX - 1e-12 else clipped
    }

    private fun normalizeLongitude(longitude: Double): Double {
        var lng = longitude
        while (lng < -LONGITUDE_MAX) lng += 360.0
        while (lng >= LONGITUDE_MAX) lng -= 360.0
        return lng
    }
}


package io.github.phantom.gps.utils

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import io.github.phantom.gps.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.Locale
import java.util.regex.Pattern

class ClipboardLocationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (!AppIntegrity.isSelfValid(context)) return
        if (!LicenseGuard.isLocallyAllowedForCriticalAction(context)) return
        if (intent?.action != LocationActions.ACTION_CLIPBOARD_TEXT) return
        val forceFromCommand = intent.getBooleanExtra(LocationActions.EXTRA_FORCE, false)
        if (!PrefManager.isClipboardMonitorEnabled && !forceFromCommand) return

        val text = intent.getStringExtra(LocationActions.EXTRA_CLIPBOARD_TEXT)?.trim()
            ?: return
        if (text.isEmpty()) return
        if (isDuplicate(text)) return

        val generation = synchronized(lock) {
            activeProcessingJob?.cancel()
            latestGeneration += 1
            latestGeneration
        }

        val pendingResult = goAsync()
        val job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                processClipboard(context.applicationContext, text, generation)
            } finally {
                pendingResult.finish()
            }
        }
        synchronized(lock) {
            activeProcessingJob = job
        }
    }

    private suspend fun processClipboard(context: Context, text: String, generation: Long) {
        if (!isLatestGeneration(generation)) return

        // Fast-path: coordinates/URLs that already contain coordinates.
        resolveLocationNoGeocode(text)?.let { latLng ->
            if (!isLatestGeneration(generation)) return
            val label = text
            if (!isInThailandBounds(latLng.first, latLng.second)) {
                showOutsideThailandNotification(context, label, latLng.first, latLng.second)
                return
            }
            if (PrefManager.isClipboardAutoStart) {
                showFoundStatusNotification(context, label, latLng.first, latLng.second, autoStart = true)
                LicenseGuard.runCriticalActionIfAllowed(context) {
                    LocationController.start(context, latLng.first, latLng.second, label)
                }
            } else {
                showFoundStatusNotification(context, label, latLng.first, latLng.second, autoStart = false)
                showPendingNotification(context, latLng.first, latLng.second, label)
            }
            return
        }

        val previewQuery = bestQueryForGeocoder(text)
        showSearchingNotification(context, previewQuery.ifBlank { text })

        val latLng = resolveLocationFast(context, text) ?: run {
            if (isLatestGeneration(generation)) {
                showNotFoundStatusNotification(context, previewQuery.ifBlank { text })
            }
            return
        }
        if (!isLatestGeneration(generation)) return
        if (!isInThailandBounds(latLng.first, latLng.second)) {
            showOutsideThailandNotification(context, previewQuery.ifBlank { text }, latLng.first, latLng.second)
            return
        }
        val label = text

        if (PrefManager.isClipboardAutoStart) {
            showFoundStatusNotification(context, label, latLng.first, latLng.second, autoStart = true)
            LicenseGuard.runCriticalActionIfAllowed(context) {
                LocationController.start(context, latLng.first, latLng.second, label)
            }
        } else {
            showFoundStatusNotification(context, label, latLng.first, latLng.second, autoStart = false)
            showPendingNotification(context, latLng.first, latLng.second, label)
        }
    }

    private fun resolveLocationNoGeocode(text: String): Pair<Double, Double>? {
        val normalized = text.trim()
        if (normalized.isEmpty()) return null

        getCachedLocation(normalized)?.let { return it }

        // Offline: Plus Codes (Open Location Code), full/short.
        OpenLocationCode.extractFromText(normalized)?.let { plusCode ->
            val ref = resolveReferenceForShortPlusCode(normalized)
            val latLng = OpenLocationCode.decodeToCenter(plusCode, ref?.first, ref?.second)
            if (latLng != null) {
                cacheLocation(normalized, latLng)
                return latLng
            }
        }

        parseLatLng(normalized)?.let {
            cacheLocation(normalized, it)
            return it
        }

        parseGoogleBangCoords(normalized)?.let {
            cacheLocation(normalized, it)
            return it
        }

        parseFromMapsUrl(normalized)?.let {
            cacheLocation(normalized, it)
            return it
        }

        val urls = extractUrls(normalized)
        urls.forEach { urlText ->
            parseFromMapsUrl(urlText)?.let {
                cacheLocation(normalized, it)
                return it
            }
        }

        return null
    }

    private fun resolveReferenceForShortPlusCode(text: String): Pair<Double, Double>? {
        // Prefer the user's current configured location (if any), so short codes resolve "near here".
        if (PrefManager.isStarted || PrefManager.hasSavedLocation()) {
            return PrefManager.getLat to PrefManager.getLng
        }
        synchronized(lock) {
            lastResolvedLatLng?.let { return it }
        }

        // If the clipboard text looks Thai (or device is in Thailand), use Bangkok as a reasonable default.
        val looksThai = THAI_CHAR_PATTERN.matcher(text).find() ||
            text.contains("ประเทศไทย") ||
            Locale.getDefault().country.equals("TH", ignoreCase = true) ||
            java.util.TimeZone.getDefault().id.equals("Asia/Bangkok", ignoreCase = true)
        return if (looksThai) {
            13.7563 to 100.5018 // Bangkok
        } else {
            null
        }
    }

    private fun showSearchingNotification(context: Context, query: String) {
        NotificationsChannel().showNotification(
            context,
            NotificationsChannel.NOTIFICATION_ID_CLIPBOARD_STATUS,
            NotificationsChannel.CHANNEL_ID_CLIPBOARD_STATUS
        ) { builder ->
            builder.setSmallIcon(R.drawable.ic_baseline_search_24)
            builder.setContentTitle(context.getString(R.string.clipboard_searching_title))
            builder.setContentText(context.getString(R.string.clipboard_searching_desc, query.take(120)))
            builder.setAutoCancel(true)
            builder.setOnlyAlertOnce(true)
            builder.setCategory(Notification.CATEGORY_STATUS)
            builder.priority = NotificationCompat.PRIORITY_LOW
        }
    }

    private fun showNotFoundStatusNotification(context: Context, query: String) {
        NotificationsChannel().showNotification(
            context,
            NotificationsChannel.NOTIFICATION_ID_CLIPBOARD_STATUS,
            NotificationsChannel.CHANNEL_ID_CLIPBOARD_STATUS
        ) { builder ->
            builder.setSmallIcon(R.drawable.ic_baseline_search_24)
            builder.setContentTitle(context.getString(R.string.clipboard_not_found_title))
            builder.setContentText(context.getString(R.string.clipboard_not_found_desc, query.take(120)))
            builder.setAutoCancel(true)
            builder.setOnlyAlertOnce(true)
            builder.setCategory(Notification.CATEGORY_STATUS)
            builder.priority = NotificationCompat.PRIORITY_DEFAULT
        }
    }

    private fun showFoundStatusNotification(context: Context, query: String, lat: Double, lon: Double, autoStart: Boolean) {
        val coords = "%.6f, %.6f".format(Locale.US, lat, lon)
        NotificationsChannel().showNotification(
            context,
            NotificationsChannel.NOTIFICATION_ID_CLIPBOARD_STATUS,
            NotificationsChannel.CHANNEL_ID_CLIPBOARD_STATUS
        ) { builder ->
            builder.setSmallIcon(R.drawable.ic_baseline_search_24)
            builder.setContentTitle(context.getString(R.string.clipboard_found_title))
            builder.setContentText(context.getString(R.string.clipboard_found_desc, query.take(80), coords))
            builder.setAutoCancel(true)
            builder.setOnlyAlertOnce(true)
            builder.setCategory(Notification.CATEGORY_STATUS)
            builder.priority = NotificationCompat.PRIORITY_DEFAULT
            if (!autoStart) {
                builder.addAction(
                    R.drawable.ic_play,
                    context.getString(R.string.action_start),
                    NotificationIntents.startLocationPendingIntent(context, lat, lon, query)
                )
            }
        }
    }

    private fun showOutsideThailandNotification(context: Context, query: String, lat: Double, lon: Double) {
        val coords = "%.6f, %.6f".format(Locale.US, lat, lon)
        NotificationsChannel().showNotification(
            context,
            NotificationsChannel.NOTIFICATION_ID_CLIPBOARD_STATUS,
            NotificationsChannel.CHANNEL_ID_CLIPBOARD_STATUS
        ) { builder ->
            builder.setSmallIcon(R.drawable.ic_baseline_search_24)
            builder.setContentTitle(context.getString(R.string.clipboard_outside_th_title))
            builder.setContentText(context.getString(R.string.clipboard_outside_th_desc, query.take(80), coords))
            builder.setAutoCancel(true)
            builder.setOnlyAlertOnce(true)
            builder.setCategory(Notification.CATEGORY_STATUS)
            builder.priority = NotificationCompat.PRIORITY_DEFAULT
        }
    }

    private fun showPendingNotification(context: Context, lat: Double, lon: Double, address: String) {
        NotificationsChannel().showNotification(
            context,
            NotificationsChannel.NOTIFICATION_ID_LOCATION,
            NotificationsChannel.CHANNEL_ID_LOCATION
        ) { builder ->
            builder.setSmallIcon(R.drawable.ic_play)
            builder.setContentTitle(context.getString(R.string.location_ready))
            builder.setContentText(address)
            builder.setAutoCancel(true)
            builder.setCategory(Notification.CATEGORY_EVENT)
            builder.priority = NotificationCompat.PRIORITY_HIGH
            builder.addAction(
                R.drawable.ic_play,
                context.getString(R.string.action_start),
                NotificationIntents.startLocationPendingIntent(context, lat, lon, address)
            )
        }
    }

    private suspend fun resolveLocationFast(context: Context, text: String): Pair<Double, Double>? {
        val normalized = text.trim()
        if (normalized.isEmpty()) return null

        getCachedLocation(normalized)?.let { return it }

        parseLatLng(normalized)?.let {
            cacheLocation(normalized, it)
            return it
        }

        parseGoogleBangCoords(normalized)?.let {
            cacheLocation(normalized, it)
            return it
        }

        parseFromMapsUrl(normalized)?.let {
            cacheLocation(normalized, it)
            return it
        }

        // If the clipboard contains multiple lines (e.g., address + maps URL), scan embedded URLs.
        val urls = extractUrls(normalized)
        urls.forEach { urlText ->
            parseFromMapsUrl(urlText)?.let {
                cacheLocation(normalized, it)
                return it
            }
        }

        extractSearchQueryFromUrl(normalized)?.let { query ->
            val q = bestQueryForGeocoder(query)
            geocodeUsingAppMethod(context, q)?.let {
                cacheLocation(normalized, it)
                return it
            }
        }

        urls.forEach { urlText ->
            extractSearchQueryFromUrl(urlText)?.let { query ->
                val q = bestQueryForGeocoder(query)
                geocodeUsingAppMethod(context, q)?.let {
                    cacheLocation(normalized, it)
                    return it
                }
            }
        }

        val geocodeQuery = bestQueryForGeocoder(normalized)
        geocodeUsingAppMethod(context, geocodeQuery)?.let {
            cacheLocation(normalized, it)
            return it
        }

        return null
    }

    private fun bestQueryForGeocoder(text: String): String {
        val cleaned = normalizeSpaces(text.replace('\r', '\n')).trim()
        if (cleaned.isBlank()) return ""
        val lines = cleaned.split('\n')
            .map { normalizeSpaces(it).trim() }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""
        if (lines.size == 1) return stripTrailingNoise(lines[0])

        return lines
            .asSequence()
            .map { it to scoreLineForAddress(it) }
            .sortedByDescending { it.second }
            .first()
            .first
            .let { stripTrailingNoise(it) }
    }

    private fun normalizeSpaces(text: String): String {
        return text
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
    }

    private fun scoreLineForAddress(line: String): Int {
        val s = line.trim()
        if (s.isEmpty()) return Int.MIN_VALUE
        if (LATIN_ONLY_LINE.matches(s) && s.length <= 80) return -1000 // e.g. "Stang Surakiat"
        var score = 0
        if (THAI_CHAR_PATTERN.matcher(s).find()) score += 10
        if (DIGIT_PATTERN.matcher(s).find()) score += 5
        if (POSTCODE_PATTERN.matcher(s).find()) score += 8
        if (s.contains("/")) score += 2
        if (ADDRESS_KEYWORDS.any { s.contains(it) }) score += 8
        if (s.contains("ประเทศไทย") || s.contains("Thailand", ignoreCase = true)) score += 2
        if (s.length >= 15) score += 2
        return score
    }

    private fun stripTrailingNoise(text: String): String {
        var s = normalizeSpaces(text).trim()
        // Common trailing noise from sharing apps.
        s = s.removeSuffix("ประเทศไทย").trim()
        s = s.removeSuffix("Thailand").trim()
        if (s.isBlank()) return ""
        // Keep the country if it was the only thing removed and the result looks too short.
        if (s.length < 8 && (text.contains("ประเทศไทย") || text.contains("Thailand", ignoreCase = true))) {
            s = normalizeSpaces(text).trim()
        }
        return s
    }

    private fun parseLatLng(text: String): Pair<Double, Double>? {
        val matcher = LAT_LNG_PATTERN.matcher(text.replace(';', ','))
        if (!matcher.find()) return null
        val values = matcher.group().split(",")
        if (values.size < 2) return null
        val lat = values[0].trim().toDoubleOrNull() ?: return null
        val lon = values[1].trim().toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    private fun parseFromMapsUrl(text: String): Pair<Double, Double>? {
        val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme == "geo") {
            val direct = uri.schemeSpecificPart.substringBefore('?')
            parseLatLng(direct)?.let { return it }
        }
        if (scheme != "http" && scheme != "https" && scheme != "geo") return null

        URL_COORD_QUERY_KEYS.forEach { key ->
            uri.getQueryParameter(key)?.let { value ->
                parseLatLng(decode(value))?.let { return it }
            }
        }

        val lat = uri.getQueryParameter("lat")?.toDoubleOrNull()
        val lon = (uri.getQueryParameter("lng")
            ?: uri.getQueryParameter("lon")
            ?: uri.getQueryParameter("long")
            ?: uri.getQueryParameter("longitude"))?.toDoubleOrNull()
        if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
            return lat to lon
        }

        val decodedText = decode(text)
        val bangMatch = GOOGLE_BANG_COORDS_PATTERN.matcher(decodedText)
        if (bangMatch.find()) {
            val dLat = bangMatch.group(1)?.toDoubleOrNull()
            val dLon = bangMatch.group(2)?.toDoubleOrNull()
            if (dLat != null && dLon != null && dLat in -90.0..90.0 && dLon in -180.0..180.0) {
                return dLat to dLon
            }
        }

        val atMatch = AT_COORDS_PATTERN.matcher(decodedText)
        if (atMatch.find()) {
            parseLatLng(atMatch.group(1).orEmpty())?.let { return it }
        }

        val pathMatch = PATH_COORDS_PATTERN.matcher(decodedText)
        if (pathMatch.find()) {
            parseLatLng(pathMatch.group(1).orEmpty())?.let { return it }
        }

        return null
    }

    private fun parseGoogleBangCoords(text: String): Pair<Double, Double>? {
        val decodedText = decode(text)
        val bangMatch = GOOGLE_BANG_COORDS_PATTERN.matcher(decodedText)
        if (!bangMatch.find()) return null
        val dLat = bangMatch.group(1)?.toDoubleOrNull()
        val dLon = bangMatch.group(2)?.toDoubleOrNull()
        if (dLat != null && dLon != null && dLat in -90.0..90.0 && dLon in -180.0..180.0) {
            return dLat to dLon
        }
        return null
    }

    private fun extractUrls(text: String): List<String> {
        val m = URL_PATTERN.matcher(text)
        if (!m.find()) return emptyList()
        val urls = LinkedHashSet<String>(4)
        do {
            val raw = m.group().trim()
            if (raw.isNotEmpty()) urls.add(raw)
        } while (m.find())
        return urls.toList()
    }

    private fun extractSearchQueryFromUrl(text: String): String? {
        val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        if (scheme != "http" && scheme != "https" && scheme != "geo") return null

        URL_SEARCH_QUERY_KEYS.forEach { key ->
            val value = uri.getQueryParameter(key)?.trim().orEmpty()
            if (value.isNotEmpty() && parseLatLng(value) == null) {
                return decode(value)
            }
        }

        val path = uri.path.orEmpty()
        val placeSegment = PLACE_SEGMENT_PATTERN.matcher(path)
        if (placeSegment.find()) {
            val raw = decode(placeSegment.group(1).orEmpty())
                .replace('+', ' ')
                .trim()
            if (raw.isNotEmpty() && parseLatLng(raw) == null) {
                return raw
            }
        }

        return null
    }

    private suspend fun geocodeUsingAppMethod(context: Context, query: String): Pair<Double, Double>? {
        val normalized = query.trim()
        if (normalized.length < MIN_QUERY_LENGTH) return null
        getCachedLocation(normalized)?.let { return it }

        val startMs = SystemClock.elapsedRealtime()
        val isThai = THAI_CHAR_PATTERN.matcher(normalized).find()
        val geocoderPresent = runCatching { Geocoder.isPresent() }.getOrDefault(true)

        return coroutineScope {
            // Use the same method as the in-app search (Android Geocoder).
            // We run it once, then wait with a "fast budget" and continue waiting up to a total budget.
            val androidDeferred = async(Dispatchers.IO) {
                geocodeViaAndroidLikeApp(context, normalized)
            }

            val fast = withTimeoutOrNull(GEOCODE_FAST_TIMEOUT_MS) {
                androidDeferred.await()
            }
            if (fast != null) {
                return@coroutineScope fast.also { cacheLocation(normalized, it) }
            }

            if (!androidDeferred.isCompleted) {
                val elapsed = SystemClock.elapsedRealtime() - startMs
                val remaining = (GEOCODE_TOTAL_TIMEOUT_MS - elapsed).coerceAtLeast(0L)
                val slow = withTimeoutOrNull(remaining) {
                    androidDeferred.await()
                }
                if (slow != null) {
                    return@coroutineScope slow.also { cacheLocation(normalized, it) }
                }
            }

            val completed = androidDeferred.isCompleted
            androidDeferred.cancel()

            // If Android Geocoder is just slow/timed-out, do NOT fallback to a different provider.
            // That tends to reduce accuracy vs. the in-app search result.
            if (!completed) return@coroutineScope null

            // Only fallback when Android Geocoder isn't available (common on some foss ROMs),
            // or when the query isn't Thai (to support English place names).
            if (geocoderPresent && isThai) return@coroutineScope null

            val fallback = withTimeoutOrNull(NOMINATIM_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { geocodeViaNominatim(normalized) }
            }
            fallback?.also { cacheLocation(normalized, it) }
        }
    }

    private fun geocodeViaAndroidLikeApp(context: Context, query: String): Pair<Double, Double>? {
        return try {
            // Match the in-app search behaviour as closely as possible:
            // - use default locale/device geocoder
            // - request multiple candidates and pick the best match for the query
            val geocoder = Geocoder(context)
            @Suppress("DEPRECATION")
            val addresses = runCatching { geocoder.getFromLocationName(query, 3) }.getOrNull()
                ?: return null
            val first = selectBestAddress(addresses, query) ?: return null
            val lat = first.latitude
            val lon = first.longitude
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            lat to lon
        } catch (_: Exception) {
            null
        }
    }

    private fun selectBestAddress(addresses: List<Address>?, query: String): Address? {
        if (addresses.isNullOrEmpty()) return null
        if (addresses.size == 1) return addresses[0]

        val q = query.trim()
        if (q.isEmpty()) return addresses.firstOrNull()

        val qLower = q.lowercase(Locale.ROOT)
        val postcode = POSTCODE_PATTERN.matcher(q).run { if (find()) group() else null }

        fun containsPart(part: String?): Boolean {
            val p = part?.trim().orEmpty()
            if (p.isEmpty()) return false
            // Thai doesn't have case, but normalize anyway for mixed queries.
            return qLower.contains(p.lowercase(Locale.ROOT))
        }

        var best: Address? = null
        var bestScore = Int.MIN_VALUE
        for (a in addresses) {
            var score = 0

            // Prefer Thailand if possible.
            if (a.countryCode.equals("TH", ignoreCase = true) || containsPart(a.countryName)) score += 10

            // If clipboard contains a postcode, prefer exact match.
            if (!postcode.isNullOrBlank()) {
                score += if (a.postalCode?.trim() == postcode) 12 else -2
            }

            if (containsPart(a.adminArea)) score += 5
            if (containsPart(a.subAdminArea)) score += 4
            if (containsPart(a.locality)) score += 3
            if (containsPart(a.subLocality)) score += 3
            if (containsPart(a.thoroughfare)) score += 4
            if (containsPart(a.subThoroughfare)) score += 2
            if (containsPart(a.featureName)) score += 1

            // De-prioritize results outside Thailand bounds when query looks Thai.
            if (THAI_CHAR_PATTERN.matcher(q).find()) {
                val inThailand = a.latitude in THAILAND_LOWER_LEFT_LAT..THAILAND_UPPER_RIGHT_LAT &&
                    a.longitude in THAILAND_LOWER_LEFT_LON..THAILAND_UPPER_RIGHT_LON
                score += if (inThailand) 3 else -8
            }

            if (score > bestScore) {
                bestScore = score
                best = a
            }
        }
        return best ?: addresses.firstOrNull()
    }

    private fun geocodeViaNominatim(query: String): Pair<Double, Double>? {
        return try {
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            val url = URL("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&addressdetails=0&q=$encoded")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 250
                readTimeout = 500
                setRequestProperty("Accept-Language", "th,en")
                setRequestProperty("User-Agent", "PHANToM-GPS/1.1")
                setRequestProperty("Connection", "close")
            }
            conn.inputStream.bufferedReader().use { reader ->
                val raw = reader.readText()
                val arr = JSONArray(raw)
                if (arr.length() == 0) return null
                val first = arr.getJSONObject(0)
                val lat = first.optString("lat").toDoubleOrNull() ?: return null
                val lon = first.optString("lon").toDoubleOrNull() ?: return null
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
                lat to lon
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getCachedLocation(text: String): Pair<Double, Double>? {
        val key = cacheKey(text)
        synchronized(lock) {
            return locationCache[key]
        }
    }

    private fun cacheLocation(text: String, latLng: Pair<Double, Double>) {
        val key = cacheKey(text)
        synchronized(lock) {
            locationCache[key] = latLng
            lastResolvedLatLng = latLng
        }
    }

    private fun cacheKey(text: String): String {
        return text.trim().lowercase(Locale.US)
    }

    private fun decode(value: String): String {
        return runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrElse { value }
    }

    private fun isLatestGeneration(generation: Long): Boolean {
        synchronized(lock) {
            return generation == latestGeneration
        }
    }

    private fun isInThailandBounds(lat: Double, lon: Double): Boolean {
        return lat in THAILAND_LOWER_LEFT_LAT..THAILAND_UPPER_RIGHT_LAT &&
            lon in THAILAND_LOWER_LEFT_LON..THAILAND_UPPER_RIGHT_LON
    }

    private fun isDuplicate(text: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (text == lastText && (now - lastTimeMs) <= DUPLICATE_WINDOW_MS) {
                return true
            }
            lastText = text
            lastTimeMs = now
        }
        return false
    }

    companion object {
        private val LAT_LNG_PATTERN =
            Pattern.compile("[-+]?\\d{1,3}([.]\\d+)?\\s*,\\s*[-+]?\\d{1,3}([.]\\d+)?")
        private val AT_COORDS_PATTERN =
            Pattern.compile("@([-+]?\\d{1,3}(?:[.]\\d+)?\\s*,\\s*[-+]?\\d{1,3}(?:[.]\\d+)?)")
        private val PATH_COORDS_PATTERN =
            Pattern.compile("/([-+]?\\d{1,3}(?:[.]\\d+)?\\s*,\\s*[-+]?\\d{1,3}(?:[.]\\d+)?)")
        private val GOOGLE_BANG_COORDS_PATTERN =
            Pattern.compile("!3d([-+]?\\d{1,3}(?:[.]\\d+)?)!4d([-+]?\\d{1,3}(?:[.]\\d+)?)")
        private val PLACE_SEGMENT_PATTERN = Pattern.compile("/place/([^/]+)")
        private val URL_COORD_QUERY_KEYS = arrayOf(
            "q",
            "query",
            "ll",
            "sll",
            "center",
            "destination",
            "origin",
            "daddr",
            "saddr"
        )
        private val URL_SEARCH_QUERY_KEYS = arrayOf(
            "q",
            "query",
            "destination",
            "origin",
            "daddr",
            "saddr",
            "text",
            "where",
            "address"
        )

        private val THAI_CHAR_PATTERN = Pattern.compile("[\\u0E00-\\u0E7F]")
        private val DIGIT_PATTERN = Pattern.compile("\\d")
        private val POSTCODE_PATTERN = Pattern.compile("(?<!\\d)\\d{5}(?!\\d)")
        private val LATIN_ONLY_LINE = Regex("^[A-Za-z .,'\"\\-_/()]+$")
        private val ADDRESS_KEYWORDS = arrayOf(
            "แขวง",
            "เขต",
            "ตำบล",
            "อำเภอ",
            "จังหวัด",
            "กรุงเทพ",
            "นนทบุรี",
            "นครปฐม",
            "ถนน",
            "ซ.",
            "ซอย",
            "ม.",
            "หมู่"
        )

        private val locationCache = object : LinkedHashMap<String, Pair<Double, Double>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Double, Double>>?): Boolean {
                return size > 64
            }
        }
        private const val MIN_QUERY_LENGTH = 2
        // Tight timeout budget for clipboard flow: prioritize speed over long geocoder waits.
        private const val GEOCODE_FAST_TIMEOUT_MS = 650L
        private const val GEOCODE_TOTAL_TIMEOUT_MS = 1800L
        private const val NOMINATIM_TIMEOUT_MS = 700L
        private const val DUPLICATE_WINDOW_MS = 1500L
        private const val THAILAND_LOWER_LEFT_LAT = 5.0
        private const val THAILAND_LOWER_LEFT_LON = 96.0
        private const val THAILAND_UPPER_RIGHT_LAT = 21.0
        private const val THAILAND_UPPER_RIGHT_LON = 106.0
        private val URL_PATTERN = Pattern.compile("(?i)\\b(?:https?://|geo:)[^\\s]+")
        private val lock = Any()
        private var activeProcessingJob: Job? = null
        private var latestGeneration: Long = 0
        private var lastText: String? = null
        private var lastTimeMs: Long = 0
        private var lastResolvedLatLng: Pair<Double, Double>? = null
    }
}

package tv.projectivy.plugin.wallpaperprovider.daynight

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

private const val TAG = "DayNightWallpaper"

/** Time-of-day buckets the app picks images for. */
enum class DayPhase { DAY, EVENING, NIGHT }

/** Image-folder name for this phase ("day" / "evening" / "night"). */
val DayPhase.folderName: String
    get() = name.lowercase()

/**
 * Local sunrise/sunset computation (no network, no API key) using the standard
 * "sunrise equation" (NOAA). Accuracy is a few minutes, which is plenty for day/night.
 */
object SunCalc {
    private fun sind(deg: Double) = sin(Math.toRadians(deg))
    private fun cosd(deg: Double) = cos(Math.toRadians(deg))

    /**
     * Which phase [nowMillis] falls in at the given location. Evening is the window from
     * [eveningLeadMillis] before sunset to [eveningTrailMillis] after sunset; daytime before
     * that, night after it. Polar day -> always DAY, polar night -> always NIGHT.
     */
    fun phaseAt(
        latDeg: Double,
        lonEastDeg: Double,
        nowMillis: Long,
        eveningLeadMillis: Long,
        eveningTrailMillis: Long
    ): DayPhase {
        val julian = nowMillis / 86400000.0 + 2440587.5            // Julian date of "now"
        val n = Math.round(julian - 2451545.0 + 0.0008).toDouble()  // day number since J2000
        val lw = -lonEastDeg                                        // west longitude positive
        val meanSolar = n + lw / 360.0                              // western locations: solar noon later in UTC
        val m = (357.5291 + 0.98560028 * meanSolar).mod(360.0)      // solar mean anomaly
        val c = 1.9148 * sind(m) + 0.0200 * sind(2 * m) + 0.0003 * sind(3 * m)
        val lambda = (m + c + 282.9372).mod(360.0)                  // ecliptic longitude (180 + 102.9372)
        val transit = 2451545.0 + meanSolar + 0.0053 * sind(m) - 0.0069 * sind(2 * lambda)
        val declination = asin(sind(lambda) * sind(23.44))         // radians
        val latR = Math.toRadians(latDeg)
        val cosOmega = (sind(-0.833) - sin(latR) * sin(declination)) / (cos(latR) * cos(declination))
        if (cosOmega <= -1.0) return DayPhase.DAY     // polar day: sun never sets
        if (cosOmega >= 1.0) return DayPhase.NIGHT    // polar night: sun never rises
        val omega = Math.toDegrees(acos(cosOmega))
        val riseMillis = ((transit - omega / 360.0 - 2440587.5) * 86400000.0).toLong()
        val setMillis = ((transit + omega / 360.0 - 2440587.5) * 86400000.0).toLong()
        val eveningStart = setMillis - eveningLeadMillis
        val eveningEnd = setMillis + eveningTrailMillis
        return when {
            nowMillis in eveningStart until eveningEnd -> DayPhase.EVENING
            nowMillis in riseMillis until eveningStart -> DayPhase.DAY
            else -> DayPhase.NIGHT
        }
    }
}

/**
 * Resolves a ZIP code to coordinates (api.zippopotam.us) and fetches current weather
 * (Open-Meteo). Both are free and key-less. Results are cached in [PreferencesManager];
 * refreshes run on a background thread so the Projectivy binder call is never blocked.
 */
object WeatherProvider {
    private const val WEATHER_MAX_AGE_MS = 3L * 60 * 60 * 1000   // 3 hours

    @Volatile private var refreshing = false

    /** The cached weather bucket, or "clear" when weather is disabled. */
    fun conditionOrClear(): String =
        if (PreferencesManager.useWeather) PreferencesManager.weatherCondition else "clear"

    /** Kick off a background geocode/weather refresh if anything is stale. Non-blocking. */
    fun maybeRefresh() {
        if (refreshing) return
        val zip = PreferencesManager.zipCode.trim()
        val wantLocation = (PreferencesManager.useWeather || PreferencesManager.useSunriseSunset) && zip.isNotEmpty()
        val needGeocode = wantLocation && PreferencesManager.geocodedZip != zip
        val needWeather = PreferencesManager.useWeather &&
            System.currentTimeMillis() - PreferencesManager.weatherFetchedAt > WEATHER_MAX_AGE_MS
        if (!needGeocode && !needWeather) return

        refreshing = true
        Thread {
            try {
                if (needGeocode) {
                    geocodeZip(zip)?.let { (lat, lon) ->
                        PreferencesManager.latitude = lat.toFloat()
                        PreferencesManager.longitude = lon.toFloat()
                        PreferencesManager.geocodedZip = zip
                        Log.i(TAG, "Geocoded $zip -> $lat, $lon")
                    } ?: Log.w(TAG, "Geocode failed for ZIP '$zip'")
                }
                if (PreferencesManager.useWeather && zip.isNotEmpty() && PreferencesManager.geocodedZip == zip) {
                    val code = fetchWeatherCode(
                        PreferencesManager.latitude.toDouble(),
                        PreferencesManager.longitude.toDouble()
                    )
                    if (code != null) {
                        val condition = codeToCondition(code)
                        PreferencesManager.weatherCondition = condition
                        PreferencesManager.weatherFetchedAt = System.currentTimeMillis()
                        Log.i(TAG, "Weather code $code -> '$condition'")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "weather/geocode refresh failed", e)
            } finally {
                refreshing = false
            }
        }.start()
    }

    /**
     * Synchronously geocode (if needed) and fetch weather, returning a short human-readable
     * status. MUST be called off the main thread (it does network I/O). Used by the "Check
     * weather now" button in settings.
     */
    fun refreshNowBlocking(): String {
        val zip = PreferencesManager.zipCode.trim()
        if (zip.isEmpty()) return "Enter a ZIP code first"
        return try {
            if (!PreferencesManager.hasLocation) {
                val geo = geocodeZip(zip) ?: return "Couldn't find ZIP $zip"
                PreferencesManager.latitude = geo.first.toFloat()
                PreferencesManager.longitude = geo.second.toFloat()
                PreferencesManager.geocodedZip = zip
            }
            val code = fetchWeatherCode(
                PreferencesManager.latitude.toDouble(),
                PreferencesManager.longitude.toDouble()
            ) ?: return "Weather service unavailable"
            val condition = codeToCondition(code)
            PreferencesManager.weatherCondition = condition
            PreferencesManager.weatherFetchedAt = System.currentTimeMillis()
            "$condition (WMO code $code)"
        } catch (e: Exception) {
            Log.w(TAG, "manual weather check failed", e)
            "Check failed: ${e.message}"
        }
    }

    private fun geocodeZip(zip: String): Pair<Double, Double>? {
        val body = httpGet("https://api.zippopotam.us/us/$zip") ?: return null
        val places = JSONObject(body).optJSONArray("places") ?: return null
        if (places.length() == 0) return null
        val place = places.getJSONObject(0)
        val lat = place.getString("latitude").toDoubleOrNull() ?: return null
        val lon = place.getString("longitude").toDoubleOrNull() ?: return null
        return lat to lon
    }

    private fun fetchWeatherCode(lat: Double, lon: Double): Int? {
        val body = httpGet("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=weather_code")
            ?: return null
        val code = JSONObject(body).optJSONObject("current")?.optInt("weather_code", -1) ?: -1
        return code.takeIf { it >= 0 }
    }

    /** Map WMO weather codes to our image buckets. */
    private fun codeToCondition(code: Int): String = when (code) {
        in 71..77 -> "snow"     // snow fall / grains
        in 85..86 -> "snow"     // snow showers
        in 51..67 -> "rain"     // drizzle + rain (incl. freezing)
        in 80..82 -> "rain"     // rain showers
        in 95..99 -> "rain"     // thunderstorm
        else -> "clear"          // 0-3 clear/cloudy, 45/48 fog
    }

    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.w(TAG, "HTTP ${conn.responseCode} for $urlStr")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET failed: $urlStr", e)
            null
        } finally {
            conn?.disconnect()
        }
    }
}

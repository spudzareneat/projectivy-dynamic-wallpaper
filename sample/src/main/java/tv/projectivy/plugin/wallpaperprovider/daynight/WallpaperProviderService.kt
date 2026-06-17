package tv.projectivy.plugin.wallpaperprovider.daynight

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.content.FileProvider
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType
import java.io.File
import java.util.Calendar

class WallpaperProviderService : Service() {

    override fun onCreate() {
        super.onCreate()
        PreferencesManager.init(this)
        // Create all condition folders so the user has somewhere to drop images.
        val base = getExternalFilesDir(null)
        if (base != null) {
            for (name in ALL_FOLDERS) File(base, name).mkdirs()
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val binder = object : IWallpaperProviderService.Stub() {
        override fun getWallpapers(event: Event?): List<Wallpaper> {
            return when (event) {
                is Event.TimeElapsed -> currentWallpapers()
                else -> emptyList() // empty == "keep showing whatever is on screen"
            }
        }

        override fun getPreferences(): String = PreferencesManager.export()

        override fun setPreferences(params: String) {
            PreferencesManager.import(params)
        }
    }

    /** Builds the wallpaper list for the current time of day and weather. */
    private fun currentWallpapers(): List<Wallpaper> {
        // Refresh geocode/weather in the background if stale; uses the cached value for now.
        WeatherProvider.maybeRefresh()

        val now = System.currentTimeMillis()
        val condition = WeatherProvider.conditionOrClear()      // clear | rain | snow
        val timeKey = currentPhase(now)                         // day | evening | night
        val base = getExternalFilesDir(null)

        // Prefer the weather-specific folder (e.g. evening_rain), fall back to the plain time-of-day folder.
        val primaryName = if (condition == "clear") timeKey else "${timeKey}_$condition"
        var folderName = primaryName
        var images = imagesIn(base, primaryName)
        if (images.isEmpty() && primaryName != timeKey) {
            folderName = timeKey
            images = imagesIn(base, timeKey)
        }

        if (images.isEmpty()) {
            // No user images for this bucket: fall back to the bundled placeholder default.
            val defaultRes = defaultDrawableFor(timeKey, condition)
            Log.i(TAG, "No user images for '$primaryName' (phase=$timeKey, weather=$condition) - using bundled default")
            return listOf(Wallpaper(drawableUri(defaultRes).toString(), WallpaperType.DRAWABLE))
        }

        Log.i(TAG, "phase=$timeKey weather=$condition -> folder '$folderName', ${images.size} image(s): ${images.joinToString { it.name }}")
        return images.mapNotNull { file -> toWallpaper(file) }
    }

    /** Bundled placeholder used when the user hasn't supplied an image for this bucket. */
    private fun defaultDrawableFor(timeKey: String, condition: String): Int =
        when (if (condition == "clear") timeKey else "${timeKey}_$condition") {
            "night" -> R.drawable.def_night
            "evening" -> R.drawable.def_evening
            "day_rain" -> R.drawable.def_day_rain
            "night_rain" -> R.drawable.def_night_rain
            "evening_rain" -> R.drawable.def_evening_rain
            "day_snow" -> R.drawable.def_day_snow
            "night_snow" -> R.drawable.def_night_snow
            "evening_snow" -> R.drawable.def_evening_snow
            else -> R.drawable.def_day
        }

    /** android.resource:// URI for one of our bundled drawables. */
    private fun drawableUri(resId: Int): Uri = Uri.Builder()
        .scheme(android.content.ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(packageName)
        .appendPath(resources.getResourceTypeName(resId))
        .appendPath(resources.getResourceEntryName(resId))
        .build()

    /** Decide day/evening/night: sunrise/sunset when configured & located, otherwise fixed clock times. */
    private fun currentPhase(now: Long): String {
        if (PreferencesManager.useSunriseSunset && PreferencesManager.hasLocation) {
            val phase = SunCalc.phaseAt(
                PreferencesManager.latitude.toDouble(),
                PreferencesManager.longitude.toDouble(),
                now,
                PreferencesManager.eveningLeadMinutes * 60_000L,
                PreferencesManager.eveningTrailMinutes * 60_000L
            )
            return phase.folderName
        }
        val nowMin = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        return phaseForTime(
            nowMin,
            PreferencesManager.dayStartMinutes,
            PreferencesManager.eveningStartMinutes,
            PreferencesManager.nightStartMinutes
        )
    }

    private fun imagesIn(base: File?, name: String): List<File> =
        base?.let { File(it, name) }
            ?.listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_EXTENSIONS }
            ?.sortedBy { it.name }
            .orEmpty()

    /** Wraps a local file in a content:// URI Projectivy is allowed to read. */
    private fun toWallpaper(file: File): Wallpaper? {
        return try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            // Hand temporary read access to Projectivy so it can load the image from our private folder.
            grantUriPermission(PROJECTIVY_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Wallpaper(uri.toString(), WallpaperType.IMAGE)
        } catch (e: Exception) {
            Log.e(TAG, "Could not share ${file.name}", e)
            null
        }
    }

    companion object {
        private const val TAG = "DayNightWallpaper"
        private const val PROJECTIVY_PACKAGE = "com.spocky.projengmenu"
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

        // Folders the app creates for the user. day/evening/night plus weather variants.
        val ALL_FOLDERS = listOf(
            "day", "evening", "night",
            "day_rain", "evening_rain", "night_rain",
            "day_snow", "evening_snow", "night_snow"
        )

        /** True if [nowMin] is in the circular window [start, end) on a 24h clock (handles wrap-around). */
        private fun inWindow(nowMin: Int, start: Int, end: Int): Boolean =
            if (start <= end) nowMin in start until end else nowMin >= start || nowMin < end

        /**
         * Which fixed-time phase [nowMin] falls in, given the day/evening/night start times.
         * The three windows tile the clock circularly: day [dayStart, eveningStart),
         * evening [eveningStart, nightStart), night [nightStart, dayStart).
         */
        fun phaseForTime(nowMin: Int, dayStart: Int, eveningStart: Int, nightStart: Int): String = when {
            inWindow(nowMin, eveningStart, nightStart) -> "evening"
            inWindow(nowMin, dayStart, eveningStart) -> "day"
            else -> "night"
        }
    }
}

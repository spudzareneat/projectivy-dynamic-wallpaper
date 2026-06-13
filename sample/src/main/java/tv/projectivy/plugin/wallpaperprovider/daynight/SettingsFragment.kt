package tv.projectivy.plugin.wallpaperprovider.daynight


import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsFragment : GuidedStepSupportFragment() {

    // Folder picker (Storage Access Framework). Importing copies images into the app's folders.
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val ctx = context ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { /* not all providers allow persisting; import still works now */ }
        Toast.makeText(ctx, R.string.import_running, Toast.LENGTH_SHORT).show()
        Thread {
            val result = ImageImporter.importFromTree(ctx.applicationContext, uri)
            activity?.runOnUiThread { Toast.makeText(ctx, result, Toast.LENGTH_LONG).show() }
        }.start()
    }
    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance {
        return Guidance(
            getString(R.string.plugin_name),
            "v${BuildConfig.VERSION_NAME}\n\n${getString(R.string.plugin_description)}",
            getString(R.string.settings),
            AppCompatResources.getDrawable(requireActivity(), R.drawable.ic_plugin)
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        PreferencesManager.init(requireContext())
        val sunrise = PreferencesManager.useSunriseSunset

        // 1. Day/night source: tap to toggle between sunrise/sunset and fixed times.
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_ID_SOURCE)
                .title(R.string.setting_source_title)
                .description(sourceLabel())
                .build()
        )
        // 2. ZIP code (used for sunrise/sunset and weather).
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_ID_ZIP)
                .title(R.string.setting_zip_title)
                .description(zipLabel())
                .editDescription(PreferencesManager.zipCode)
                .descriptionEditable(true)
                .build()
        )
        // 3+4. Fixed-time fields — disabled while sunrise/sunset is selected.
        actions.add(timeAction(ACTION_ID_DAY_START, R.string.setting_day_start_title, PreferencesManager.dayStartMinutes, !sunrise))
        actions.add(timeAction(ACTION_ID_NIGHT_START, R.string.setting_night_start_title, PreferencesManager.nightStartMinutes, !sunrise))

        // 5. Weather on/off: tap to toggle.
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_ID_WEATHER)
                .title(R.string.setting_weather_title)
                .description(onOff(PreferencesManager.useWeather))
                .build()
        )
        // 6. Check weather now.
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_ID_CHECK_WEATHER)
                .title(R.string.setting_check_weather_title)
                .description(weatherStatusLabel())
                .build()
        )
        // 7. Sources note (read-only).
        actions.add(
            infoRow(ACTION_ID_WEATHER_SOURCES, getString(R.string.setting_weather_sources_title), getString(R.string.weather_sources_note))
        )
        // 8. Import images from a USB drive / folder (Storage Access Framework).
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_ID_PICK_FOLDER)
                .title(R.string.setting_import_title)
                .description(R.string.setting_import_desc)
                .build()
        )
        // 9. Where the images live (read-only).
        val base = requireContext().getExternalFilesDir(null)
        actions.add(
            infoRow(ACTION_ID_FOLDERS, getString(R.string.setting_folders_title), "$base\n${WallpaperProviderService.ALL_FOLDERS.joinToString(", ")}")
        )
    }

    private fun timeAction(id: Long, titleRes: Int, minutes: Int, enabled: Boolean): GuidedAction {
        val text = formatTime(minutes)
        return GuidedAction.Builder(context)
            .id(id)
            .title(titleRes)
            .description(text)
            .editDescription(text)
            .descriptionEditable(true)
            .enabled(enabled)
            .build()
    }

    private fun infoRow(id: Long, title: String, desc: String): GuidedAction =
        GuidedAction.Builder(context)
            .id(id)
            .title(title)
            .description(desc)
            .infoOnly(true)
            .focusable(false)
            .build()

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_ID_SOURCE -> {
                val sunrise = !PreferencesManager.useSunriseSunset
                PreferencesManager.useSunriseSunset = sunrise
                action.description = sourceLabel()
                notifyActionChanged(findActionPositionById(ACTION_ID_SOURCE))
                // Grey out / re-enable the fixed-time fields.
                setEnabled(ACTION_ID_DAY_START, !sunrise)
                setEnabled(ACTION_ID_NIGHT_START, !sunrise)
            }
            ACTION_ID_WEATHER -> {
                PreferencesManager.useWeather = !PreferencesManager.useWeather
                action.description = onOff(PreferencesManager.useWeather)
                notifyActionChanged(findActionPositionById(ACTION_ID_WEATHER))
            }
            ACTION_ID_CHECK_WEATHER -> {
                action.description = getString(R.string.weather_checking)
                notifyActionChanged(findActionPositionById(ACTION_ID_CHECK_WEATHER))
                Thread {
                    val result = WeatherProvider.refreshNowBlocking()
                    activity?.runOnUiThread {
                        findActionById(ACTION_ID_CHECK_WEATHER)?.let {
                            it.description = result
                            notifyActionChanged(findActionPositionById(ACTION_ID_CHECK_WEATHER))
                        }
                    }
                }.start()
            }
            ACTION_ID_PICK_FOLDER -> {
                try {
                    pickFolder.launch(null)
                } catch (e: Exception) {
                    Toast.makeText(context, R.string.import_no_picker, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        when (action.id) {
            ACTION_ID_ZIP -> {
                val zip = action.editDescription?.toString()?.trim().orEmpty()
                PreferencesManager.zipCode = zip
                action.description = zipLabel()
                action.editDescription = zip
            }
            ACTION_ID_DAY_START, ACTION_ID_NIGHT_START -> {
                val minutes = parseTime(action.editDescription?.toString())
                val current = if (action.id == ACTION_ID_DAY_START)
                    PreferencesManager.dayStartMinutes else PreferencesManager.nightStartMinutes
                val value = minutes ?: current  // invalid input -> keep previous
                if (action.id == ACTION_ID_DAY_START) PreferencesManager.dayStartMinutes = value
                else PreferencesManager.nightStartMinutes = value
                action.description = formatTime(value)
                action.editDescription = formatTime(value)
            }
        }
        return GuidedAction.ACTION_ID_CURRENT
    }

    private fun setEnabled(id: Long, enabled: Boolean) {
        findActionById(id)?.let {
            it.isEnabled = enabled
            notifyActionChanged(findActionPositionById(id))
        }
    }

    private fun sourceLabel(): String = getString(
        if (PreferencesManager.useSunriseSunset) R.string.source_sunrise else R.string.source_fixed
    )

    private fun zipLabel(): String =
        PreferencesManager.zipCode.ifBlank { getString(R.string.zip_unset) }

    private fun onOff(value: Boolean): String =
        getString(if (value) R.string.on else R.string.off)

    private fun weatherStatusLabel(): String {
        val at = PreferencesManager.weatherFetchedAt
        if (at <= 0L) return getString(R.string.weather_never)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
        return "${PreferencesManager.weatherCondition} (checked $time)"
    }

    companion object {
        private const val ACTION_ID_DAY_START = 1L
        private const val ACTION_ID_NIGHT_START = 2L
        private const val ACTION_ID_FOLDERS = 3L
        private const val ACTION_ID_SOURCE = 4L
        private const val ACTION_ID_ZIP = 5L
        private const val ACTION_ID_WEATHER = 6L
        private const val ACTION_ID_CHECK_WEATHER = 7L
        private const val ACTION_ID_WEATHER_SOURCES = 8L
        private const val ACTION_ID_PICK_FOLDER = 9L

        private fun formatTime(minutes: Int): String =
            String.format("%02d:%02d", minutes / 60, minutes % 60)

        /** Parses "HH:mm" (or "H:mm") into minutes past midnight, or null if invalid. */
        private fun parseTime(input: String?): Int? {
            val parts = input?.trim()?.split(":") ?: return null
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return h * 60 + m
        }
    }
}

package tv.projectivy.plugin.wallpaperprovider.daynight

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Imports images from a user-picked folder (Storage Access Framework tree) into the app's
 * private condition folders. The picked folder should contain subfolders named like our
 * condition folders (day, night, day_rain, …); their images are copied in.
 */
object ImageImporter {
    private const val TAG = "DayNightWallpaper"
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    /** Copies images from matching subfolders of [treeUri]. Returns a short summary for the UI. */
    fun importFromTree(context: Context, treeUri: Uri): String {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return "Couldn't open that folder"
        val base = context.getExternalFilesDir(null) ?: return "App storage unavailable"
        val known = WallpaperProviderService.ALL_FOLDERS.toSet()

        var imported = 0
        var matchedFolders = 0
        for (child in root.listFiles()) {
            val folder = child.name
            if (!child.isDirectory || folder == null || folder.lowercase() !in known) continue
            matchedFolders++
            val destDir = File(base, folder.lowercase()).apply { mkdirs() }
            for (file in child.listFiles()) {
                val fname = file.name ?: continue
                if (!file.isFile || fname.substringAfterLast('.', "").lowercase() !in IMAGE_EXTENSIONS) continue
                try {
                    context.contentResolver.openInputStream(file.uri)?.use { input ->
                        File(destDir, fname).outputStream().use { output -> input.copyTo(output) }
                    }
                    imported++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import $fname", e)
                }
            }
        }

        return when {
            imported > 0 -> "Imported $imported image(s) from $matchedFolders folder(s)"
            matchedFolders == 0 -> "No matching subfolders (day, night, day_rain, …) found"
            else -> "Matching folders had no images"
        }
    }
}

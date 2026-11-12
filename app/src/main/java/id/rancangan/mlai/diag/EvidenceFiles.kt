package id.rancangan.mlai.diag

import android.content.Context
import java.io.File

/**
 * Where Fase 0 evidence files live — always TWO copies of everything:
 *
 *  1. Internal filesDir — the app always has it, survives nothing special.
 *  2. External app-specific files dir (Android/data/id.rancangan.mlai/files/)
 *     — this copy exists so evidence can be pulled off the device with
 *     `adb pull` WITHOUT root during direct ADB testing; internal filesDir
 *     cannot be read that way.
 *
 * Both copies are written from the same functions here (and in CaptureService
 * via [targets]) so they can never drift apart.
 */
object EvidenceFiles {

    const val DIAGNOSTICS_NAME = "diagnostics.txt"

    /** All writable locations for [name]: internal first, external second (if available). */
    fun targets(context: Context, name: String): List<File> {
        val result = mutableListOf<File>()
        result.add(File(context.filesDir, name))
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir != null) {
            if (!externalDir.exists()) externalDir.mkdirs()
            result.add(File(externalDir, name))
        }
        return result
    }

    /** Overwrite [name] in every location with the same bytes. Returns the paths written. */
    fun writeBoth(context: Context, name: String, bytes: ByteArray): List<File> {
        val written = mutableListOf<File>()
        for (file in targets(context, name)) {
            try {
                file.writeBytes(bytes)
                written.add(file)
            } catch (e: Exception) {
                // A failed copy must not hide the other one; callers see which paths succeeded.
            }
        }
        return written
    }

    /** Append text to [name] in every location (keeps the copies in sync). */
    fun appendBoth(context: Context, name: String, text: String) {
        for (file in targets(context, name)) {
            try {
                file.appendText(text)
            } catch (e: Exception) {
                // Same reasoning as writeBoth.
            }
        }
    }
}

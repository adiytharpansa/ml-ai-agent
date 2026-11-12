package id.rancangan.mlai.touch

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * The four honest states of the Shizuku bridge (design doc §3):
 * the manager app may be absent, installed but its server not running
 * (it dies on every reboot), running without our permission, or ready.
 */
enum class ShizukuState {
    NOT_INSTALLED,
    INSTALLED_NOT_RUNNING,
    RUNNING_NO_PERMISSION,
    READY;

    fun label(): String = when (this) {
        NOT_INSTALLED -> "belum terpasang"
        INSTALLED_NOT_RUNNING -> "terpasang, binder belum aktif (hidupkan lewat wireless debugging)"
        RUNNING_NO_PERMISSION -> "binder aktif, izin untuk aplikasi ini belum diberikan"
        READY -> "siap (binder aktif, izin diberikan)"
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        fun isPackageInstalled(context: Context): Boolean = try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

        /** Full check. Safe to call when the binder is absent — Shizuku.pingBinder() just returns false. */
        fun check(context: Context): ShizukuState {
            if (!isPackageInstalled(context)) return NOT_INSTALLED
            val binderAlive = try {
                Shizuku.pingBinder()
            } catch (e: Exception) {
                false
            }
            if (!binderAlive) return INSTALLED_NOT_RUNNING
            val granted = try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                false
            }
            return if (granted) READY else RUNNING_NO_PERMISSION
        }

        /** Ask the user for Shizuku permission. Only meaningful when the binder is alive. */
        fun requestPermission(requestCode: Int) {
            try {
                if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                    Shizuku.requestPermission(requestCode)
                }
            } catch (e: Exception) {
                // Binder died between check and request — the state check will show it.
            }
        }
    }
}

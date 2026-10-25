// SPDX-License-Identifier: GPL-3.0-only

package moe.nyaneko.kirisaki

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.provider.Settings

const val STATE_REQUEST_ACTION = "moe.nyaneko.kirisaki.REQUEST_STATE"
const val STATE_REQUEST_PERMISSION = "moe.nyaneko.kirisaki.REQUEST_STATE"
const val TARGET_PACKAGE = "com.oplus.location"
val STATE_URI: Uri = Uri.parse("content://moe.nyaneko.kirisaki.state")

object BootSession {
    fun id(context: Context): String = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        .takeIf { it >= 0 }?.toString() ?: runCatching {
            java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
        }.getOrDefault("unknown")
}

/** Private persistence. StateBridge checks the remote Binder UID before accepting any snapshot. */
class StateProvider : ContentProvider() {
    override fun onCreate() = true
    @Synchronized override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireNotNull(context)
        val own = Binder.getCallingUid() == Process.myUid()
        if (!own) throw SecurityException("Private state storage")
        val boot = BootSession.id(ctx)
        val prefs = ctx.getSharedPreferences("hook_state", Context.MODE_PRIVATE)
        if (prefs.getString("boot", null) != boot) prefs.edit().clear().putString("boot", boot).commit()
        // v1 recorded a claim as completion. No completion evidence exists for those records.
        // Migrate once to a pending real animation; subsequent updates preserve completed state.
        if (!prefs.contains("animation_complete")) {
            prefs.edit().putBoolean("animation_complete", false).putBoolean("animated", false)
                .putInt("animation_elapsed", 0).commit()
        }
        fun playback() = AnimationPlayback(prefs.getInt("animation_elapsed", 0),
            prefs.getBoolean("animation_complete", false), prefs.getString("animation_owner", "").orEmpty(),
            prefs.getLong("animation_lease", 0))
        fun persistPlayback(state: AnimationPlayback) {
            check(prefs.edit().putInt("animation_elapsed", state.elapsedMs)
                .putBoolean("animation_complete", state.completed).putBoolean("animated", state.completed)
                .putString("animation_owner", state.owner).putLong("animation_lease", state.leaseUntil).commit()) {
                "Could not persist animation progress"
            }
        }
        when (method) {
            "report" -> error("Direct reports are not accepted; use the UID-checked Binder reply")
            "acceptSnapshot" -> {
                require(extras?.getString("boot") == boot && boot != "unknown") { "Boot mismatch: reporter=${extras?.getString("boot")}, provider=$boot" }
                val data = requireNotNull(extras)
                prefs.edit().apply {
                    putBoolean("active", true)
                    putBoolean("precise", data.getBoolean("precise"))
                    putBoolean("other_entry", prefs.getBoolean("other_entry", false) || data.getBoolean("other_entry"))
                    putString("class", data.getString("class").orEmpty().take(256))
                    putString("method", data.getString("method").orEmpty().take(256))
                    putString("error", data.getString("error").orEmpty().take(512))
                    putLong("blocked", data.getLong("blocked").takeIf { it > 0 } ?: prefs.getLong("blocked", 0))
                }.commit()
            }
            "beginAnimation" -> {
                val requester = extras?.getString("owner").orEmpty()
                val claim = playback().claim(requester, android.os.SystemClock.elapsedRealtime(),
                    boot != "unknown" && prefs.getBoolean("precise", false) && prefs.getString("error", "").isNullOrEmpty())
                if (claim != null) persistPlayback(claim)
                return Bundle().apply {
                    putBoolean("play", claim != null)
                    putInt("elapsed", claim?.elapsedMs ?: 0)
                    putString("boot", boot)
                }
            }
            "checkpointAnimation" -> {
                requireNotNull(extras)
                // An old Activity must not change a new boot's playback record.
                if (extras.getString("boot") == boot) persistPlayback(playback().checkpoint(
                    extras.getString("owner").orEmpty(), extras.getInt("elapsed"), android.os.SystemClock.elapsedRealtime(),
                    extras.getBoolean("finish"), extras.getBoolean("release")))
            }
            "read" -> check(own)
            else -> throw IllegalArgumentException("Unknown operation")
        }
        return Bundle().apply {
            putString("boot", boot)
            putBoolean("active", prefs.getBoolean("active", false))
            putBoolean("precise", prefs.getBoolean("precise", false))
            putBoolean("other_entry", prefs.getBoolean("other_entry", false))
            putString("class", prefs.getString("class", ""))
            putString("method", prefs.getString("method", ""))
            putString("error", prefs.getString("error", ""))
            putLong("blocked", prefs.getLong("blocked", 0))
            putBoolean("animation_complete", prefs.getBoolean("animation_complete", false))
            putInt("animation_elapsed", prefs.getInt("animation_elapsed", 0))
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = throw SecurityException("Use guarded call")
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("Use guarded call")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Use guarded call")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Use guarded call")
    override fun getType(uri: Uri): String? = null
}

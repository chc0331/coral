package com.korailauto.reader

import android.content.Context
import java.util.concurrent.CopyOnWriteArraySet

object ScreenSnapshotStore {
    @Volatile
    private var latestSnapshot = ScreenSnapshot.empty()

    private val listeners = CopyOnWriteArraySet<(ScreenSnapshot) -> Unit>()

    fun latest(): ScreenSnapshot = latestSnapshot

    fun publish(snapshot: ScreenSnapshot) {
        latestSnapshot = snapshot
        listeners.forEach { listener -> listener(snapshot) }
    }

    fun addListener(listener: (ScreenSnapshot) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (ScreenSnapshot) -> Unit) {
        listeners.remove(listener)
    }
}

object AutomationSettings {
    private const val PREFS_NAME = "automation_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MANUALLY_STOPPED = "manually_stopped"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putBoolean(KEY_MANUALLY_STOPPED, !enabled)
            .apply()
    }

    /** Accessibility service activation always starts a new automation run. */
    fun enableWhenServiceConnected(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, true)
            .putBoolean(KEY_MANUALLY_STOPPED, false)
            .apply()
    }
}

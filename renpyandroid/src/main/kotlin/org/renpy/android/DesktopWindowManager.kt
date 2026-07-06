package org.renpy.android

import android.content.Context
import android.content.Intent

object ActiveActivityRegistry {
    val activeActivities: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile
    var currentActivity: android.app.Activity? = null
}

object DesktopWindowManager {
    const val ACTION_WINDOW_STATE_CHANGED = "org.renpy.android.ACTION_WINDOW_STATE_CHANGED"
    const val ACTION_WINDOW_COMMAND = "org.renpy.android.ACTION_WINDOW_COMMAND"

    const val EXTRA_ACTIVITY_ID = "activity_id"
    const val EXTRA_ACTIVITY_NAME = "activity_name"
    const val EXTRA_STATE = "state" // "RUNNING", "MINIMIZED", "DESTROYED"
    const val EXTRA_COMMAND = "command" // "RESTORE", "MINIMIZE"

    fun notifyStateChanged(context: Context, activityId: String, activityName: String, state: String) {
        val intent = Intent(ACTION_WINDOW_STATE_CHANGED).apply {
            putExtra(EXTRA_ACTIVITY_ID, activityId)
            putExtra(EXTRA_ACTIVITY_NAME, activityName)
            putExtra(EXTRA_STATE, state)
        }
        context.sendBroadcast(intent)
    }

    fun sendCommand(context: Context, activityId: String, command: String) {
        val intent = Intent(ACTION_WINDOW_COMMAND).apply {
            putExtra(EXTRA_ACTIVITY_ID, activityId)
            putExtra(EXTRA_COMMAND, command)
        }
        context.sendBroadcast(intent)
    }
}

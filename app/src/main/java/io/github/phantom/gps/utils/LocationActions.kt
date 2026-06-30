package io.github.phantom.gps.utils

import io.github.phantom.gps.BuildConfig

object LocationActions {
    private val PREFIX = BuildConfig.APPLICATION_ID
    val ACTION_LOCATION_STARTED = "$PREFIX.action.LOCATION_STARTED"
    val ACTION_LOCATION_STOPPED = "$PREFIX.action.LOCATION_STOPPED"
    val ACTION_START_LOCATION = "$PREFIX.action.START_LOCATION"
    val ACTION_STOP_LOCATION = "$PREFIX.action.STOP_LOCATION"
    val ACTION_CLIPBOARD_TEXT = "$PREFIX.action.CLIPBOARD_TEXT"
    val ACTION_SHELL_COMMAND = "$PREFIX.action.SHELL_COMMAND"
    const val EXTRA_LAT = "extra_lat"
    const val EXTRA_LON = "extra_lon"
    const val EXTRA_LABEL = "extra_label"
    const val EXTRA_CLIPBOARD_TEXT = "extra_clipboard_text"
    const val EXTRA_COMMAND = "extra_command"
    const val EXTRA_TEXT = "extra_text"
    const val EXTRA_FORCE = "extra_force"
}


package io.nekohasekai.sagernet.utils

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app

object Theme {

    // Ids are stored in the settings. 1..21 were the old saturated colors and 22 the old
    // black; old ids fall back to the default.
    const val MONOCHROME = 22
    const val SYSTEM = 23
    const val GRAPHITE = 24
    const val MIST_BLUE = 25
    const val SAGE = 26
    const val OAT = 27
    const val INK = 28
    const val DUSTY_ROSE = 29

    // the system palette before Android 12 is mist blue, see values-v31/colors.xml
    private fun defaultTheme() = SYSTEM

    fun apply(context: Context) {
        context.setTheme(getTheme())
    }

    fun applyDialog(context: Context) {
        context.setTheme(getDialogTheme())
    }

    fun getTheme(): Int {
        return getTheme(DataStore.appTheme)
    }

    fun getDialogTheme(): Int {
        return getDialogTheme(DataStore.appTheme)
    }

    fun getTheme(theme: Int): Int {
        return when (theme) {
            MONOCHROME -> R.style.Theme_SagerNet_Monochrome
            SYSTEM -> R.style.Theme_SagerNet_System
            GRAPHITE -> R.style.Theme_SagerNet_Graphite
            MIST_BLUE -> R.style.Theme_SagerNet_MistBlue
            SAGE -> R.style.Theme_SagerNet_Sage
            OAT -> R.style.Theme_SagerNet_Oat
            INK -> R.style.Theme_SagerNet_Ink
            DUSTY_ROSE -> R.style.Theme_SagerNet_DustyRose
            else -> getTheme(defaultTheme())
        }
    }

    fun getDialogTheme(theme: Int): Int {
        return when (theme) {
            MONOCHROME -> R.style.Theme_SagerNet_Dialog_Monochrome
            SYSTEM -> R.style.Theme_SagerNet_Dialog_System
            GRAPHITE -> R.style.Theme_SagerNet_Dialog_Graphite
            MIST_BLUE -> R.style.Theme_SagerNet_Dialog_MistBlue
            SAGE -> R.style.Theme_SagerNet_Dialog_Sage
            OAT -> R.style.Theme_SagerNet_Dialog_Oat
            INK -> R.style.Theme_SagerNet_Dialog_Ink
            DUSTY_ROSE -> R.style.Theme_SagerNet_Dialog_DustyRose
            else -> getDialogTheme(defaultTheme())
        }
    }

    var currentNightMode = -1
    fun getNightMode(): Int {
        if (currentNightMode == -1) {
            currentNightMode = DataStore.nightTheme
        }
        return getNightMode(currentNightMode)
    }

    fun getNightMode(mode: Int): Int {
        return when (mode) {
            0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            1 -> AppCompatDelegate.MODE_NIGHT_YES
            2 -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
        }
    }

    fun usingNightMode(): Boolean {
        return when (DataStore.nightTheme) {
            1 -> true
            2 -> false
            else -> (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun applyNightTheme() {
        AppCompatDelegate.setDefaultNightMode(getNightMode())
    }

}
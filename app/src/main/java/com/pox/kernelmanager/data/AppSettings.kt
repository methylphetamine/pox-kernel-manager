package com.pox.kernelmanager.data

import android.content.Context
import android.content.SharedPreferences
import com.pox.kernelmanager.PoxApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the app should pick its color scheme.
 *
 * [SYSTEM] is the default: it follows the device's light/dark appearance and
 * stays monochrome in both. [MATERIAL_YOU] opts into wallpaper-sampled
 * dynamic colors on Android 12+, which can introduce any hue (including
 * green); older releases fall back to the monochrome palette.
 */
enum class ThemeMode(val id: String, val label: String, val description: String) {
    MATERIAL_YOU(
        "material_you",
        "Material You",
        "Dynamic colors sampled from your wallpaper (Android 12+). Not monochrome - the wallpaper decides the hue."
    ),
    SYSTEM(
        "system",
        "System default",
        "Follow the device's light or dark appearance, monochrome either way"
    ),
    LIGHT(
        "light",
        "Light",
        "Always use the monochrome light color scheme"
    ),
    DARK(
        "dark",
        "Dark",
        "Always use the monochrome dark color scheme"
    );

    companion object {
        fun fromId(id: String?): ThemeMode =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * Small user preferences that are not tuning state: appearance and which
 * GitHub repository the kernel updater talks to.
 *
 * Stored in its own SharedPreferences file and written with `commit()` so a
 * force stop right after a change cannot lose it.
 */
object AppSettings {

    private const val PREFS_NAME = "pox_app_settings"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_UPDATER_REPOSITORY = "updater_repository"

    const val DEFAULT_UPDATER_REPOSITORY = "POX-project/pox-begonia-kernel"

    /**
     * Suggested repositories for the updater. The first entry is the default,
     * the others exist so switching between the official channel and a personal
     * fork is one tap instead of typing.
     */
    val UPDATER_REPOSITORY_PRESETS = listOf(
        DEFAULT_UPDATER_REPOSITORY,
        "methylphetamine/pox-begonia-kernel",
        "txorav/pox-kernel-begonia"
    )

    private val prefs: SharedPreferences by lazy {
        PoxApplication.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _themeMode = MutableStateFlow(ThemeMode.fromId(prefs.getString(KEY_THEME_MODE, null)))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _updaterRepository = MutableStateFlow(
        normalizeRepository(prefs.getString(KEY_UPDATER_REPOSITORY, null)) ?: DEFAULT_UPDATER_REPOSITORY
    )
    val updaterRepository: StateFlow<String> = _updaterRepository.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.id).commit()
        _themeMode.value = mode
    }

    /** Stores a new updater repository. Returns false when [value] is not owner/repo. */
    fun setUpdaterRepository(value: String): Boolean {
        val normalized = normalizeRepository(value) ?: return false
        prefs.edit().putString(KEY_UPDATER_REPOSITORY, normalized).commit()
        _updaterRepository.value = normalized
        return true
    }

    /**
     * Accepts what users actually paste: a bare `owner/repo`, a full GitHub URL,
     * the API URL, or either with a trailing `.git` / `/releases`.
     */
    fun normalizeRepository(value: String?): String? {
        var candidate = value?.trim().orEmpty()
        if (candidate.isEmpty()) return null
        candidate = candidate
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .removePrefix("github.com/")
            .removePrefix("api.github.com/repos/")
        candidate = candidate
            .removeSuffix("/")
            .removeSuffix(".git")
            .removeSuffix("/")
            .removeSuffix("/releases")
        val match = REPOSITORY_PATTERN.matchEntire(candidate) ?: return null
        return "${match.groupValues[1]}/${match.groupValues[2]}"
    }

    private val REPOSITORY_PATTERN = Regex("^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$")
}

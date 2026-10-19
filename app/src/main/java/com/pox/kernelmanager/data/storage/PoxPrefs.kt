package com.pox.kernelmanager.data.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.pox.kernelmanager.PoxApplication
import com.pox.kernelmanager.data.root.ShellUtils
import com.topjohnwu.superuser.Shell
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.security.MessageDigest

/**
 * SharedPreferences-shaped config store backed by a plain JSON file in
 * `/sdcard/PoxManager/`.
 *
 * Why not app-private `shared_prefs`: this file is meant to be visible and to
 * last. It survives an app reinstall or a wipe of app data, can be inspected
 * or hand-edited from a file manager, TWRP or a PC, and nothing prunes it -
 * the app rewrites it only when a setting actually changes.
 *
 * Reads and writes go through a root shell because the app targets API 35
 * without all-files access, so scoped storage refuses a direct `/sdcard`
 * write on most devices; a direct `java.io.File` attempt is still tried first
 * (it wins on older devices), and the old SharedPreferences file is kept in
 * sync as a last-resort mirror so a device without a working root shell
 * loses nothing.
 */
class PoxPrefs private constructor(
    private val fileName: String,
    private val legacyPrefsName: String
) {

    companion object {
        /** Folder on internal storage that holds every config file. */
        const val DIR = "/sdcard/PoxManager"

        private const val ROOT_MISSING_MARKER = "__POX_CONFIG_MISSING__"

        private val json = Json { prettyPrint = true }

        private val instances = HashMap<String, PoxPrefs>()

        /** One shared instance per file: two stores must never race write-back. */
        @Synchronized
        fun of(fileName: String, legacyPrefsName: String): PoxPrefs =
            instances.getOrPut(fileName) { PoxPrefs(fileName, legacyPrefsName) }
    }

    private val values = LinkedHashMap<String, Any>()
    private var loaded = false

    /** Last-resort copy, also the upgrade path from builds before the move. */
    private val legacy: SharedPreferences by lazy {
        PoxApplication.instance.getSharedPreferences(legacyPrefsName, Context.MODE_PRIVATE)
    }

    // ---------------------------------------------------------------- reading

    /** Loads the file once per process; safe to call from any thread. */
    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            load()
            loaded = true
        }
    }

    @Synchronized
    fun getInt(key: String, def: Int): Int {
        ensureLoaded()
        return (values[key] as? Number)?.toInt() ?: def
    }

    @Synchronized
    fun getLong(key: String, def: Long): Long {
        ensureLoaded()
        return (values[key] as? Number)?.toLong() ?: def
    }

    @Synchronized
    fun getBoolean(key: String, def: Boolean): Boolean {
        ensureLoaded()
        return when (val value = values[key]) {
            is Boolean -> value
            is Number -> value.toLong() != 0L
            else -> def
        }
    }

    @Synchronized
    fun getString(key: String, def: String?): String? {
        ensureLoaded()
        return values[key] as? String ?: def
    }

    // ---------------------------------------------------------------- writing

    fun edit(): Editor = Editor(this)

    class Editor internal constructor(private val store: PoxPrefs) {

        internal val puts = LinkedHashMap<String, Any>()
        internal val removals = LinkedHashSet<String>()

        fun putInt(key: String, value: Int) = also { puts[key] = value }

        fun putLong(key: String, value: Long) = also { puts[key] = value }

        fun putBoolean(key: String, value: Boolean) = also { puts[key] = value }

        fun putString(key: String, value: String?) = also {
            if (value == null) removals.add(key) else puts[key] = value
        }

        fun remove(key: String) = also {
            removals.add(key)
            puts.remove(key)
        }

        /** Writes immediately; returns whether the file write landed. */
        fun commit(): Boolean = store.commit(this)

        /** Same as [commit]: the file is tiny and durability beats the async hop. */
        fun apply() {
            commit()
        }
    }

    @Synchronized
    internal fun commit(editor: Editor): Boolean {
        ensureLoaded()
        editor.removals.forEach { values.remove(it) }
        values.putAll(editor.puts)
        return flush()
    }

    // ------------------------------------------------------------ file access

    private fun load() {
        val path = "$DIR/$fileName"
        val text = readDirect(path) ?: readViaRoot(path)
        if (text != null) {
            if (parse(text)) return
            // File exists but is not readable JSON: keep the old prefs values
            // and leave the file alone for the user to fix.
            adoptLegacy()
            return
        }
        // Missing file: adopt the old prefs values, then seed the file so the
        // config location is discoverable (and the migration is complete).
        adoptLegacy()
        flush()
    }

    private fun adoptLegacy(): Boolean {
        val all = runCatching { legacy.all }.getOrNull().orEmpty()
        if (all.isEmpty()) return false
        for ((key, value) in all) {
            when (value) {
                is String -> values[key] = value
                is Boolean -> values[key] = value
                is Number -> values[key] = value.toLong()
            }
        }
        return true
    }

    private fun parse(text: String): Boolean = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        val parsed = LinkedHashMap<String, Any>()
        for ((key, element) in root) {
            val primitive = element as? JsonPrimitive ?: continue
            parsed[key] = if (primitive.isString) {
                // Hand edits often quote numbers or booleans; accept both.
                val text = primitive.content
                when {
                    text.equals("true", ignoreCase = true) -> true
                    text.equals("false", ignoreCase = true) -> false
                    text.toLongOrNull() != null -> text.toLong()
                    else -> text
                }
            } else {
                val boolean = primitive.booleanOrNull
                if (boolean != null) boolean else primitive.longOrNull ?: primitive.content
            }
        }
        values.clear()
        values.putAll(parsed)
        true
    }.getOrDefault(false)

    private fun encode(): String {
        val root = buildJsonObject {
            values.toSortedMap().forEach { (key, value) ->
                when (value) {
                    is Boolean -> put(key, JsonPrimitive(value))
                    is Number -> put(key, JsonPrimitive(value.toLong()))
                    else -> put(key, JsonPrimitive(value.toString()))
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), root) + "\n"
    }

    @Synchronized
    private fun flush(): Boolean {
        val text = encode()
        val path = "$DIR/$fileName"
        val written = writeDirect(path, text) || writeViaRoot(path, text)
        // Mirror into the app-private copy: that is what loads when the file
        // is missing or a root shell is momentarily unavailable.
        val mirrored = writeLegacy()
        return written || mirrored
    }

    private fun readDirect(path: String): String? = runCatching {
        val file = File(path)
        if (file.isFile) file.readText() else null
    }.getOrNull()

    private fun readViaRoot(path: String): String? {
        val quoted = ShellUtils.shellQuote(path)
        val result = runCatching {
            Shell.cmd("if [ -f $quoted ]; then cat $quoted; else echo $ROOT_MISSING_MARKER; fi").exec()
        }.getOrNull() ?: return null
        if (!result.isSuccess) return null
        val text = result.out.joinToString("\n")
        return if (text.trim() == ROOT_MISSING_MARKER) null else text
    }

    private fun writeDirect(path: String, text: String): Boolean = runCatching {
        val file = File(path)
        file.parentFile?.mkdirs()
        val tmp = File("$path.tmp")
        tmp.writeText(text)
        tmp.renameTo(file) || run {
            file.writeText(text)
            tmp.delete()
            true
        }
    }.getOrDefault(false)

    /**
     * Writes through the shell. The payload travels base64-encoded so no
     * quoting layer ever sees the JSON, and the command only reports success
     * after the file is moved into place and its md5 read back - the mirror
     * write must not claim a file it never wrote.
     */
    private fun writeViaRoot(path: String, text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val expected = md5(bytes)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val dir = ShellUtils.shellQuote(DIR)
        val target = ShellUtils.shellQuote(path)
        val tmp = ShellUtils.shellQuote("$path.tmp")
        val command = "mkdir -p $dir && " +
            "printf '%s' '$encoded' | base64 -d > $tmp && " +
            "mv -f $tmp $target && " +
            // chmod/sync are best effort: a shell-user write (no root) still
            // lands the file, it just cannot change ownership.
            "(chmod 0644 $target 2>/dev/null; sync $target 2>/dev/null; md5sum $target)"

        val result = runCatching { Shell.cmd(command).exec() }.getOrNull() ?: return false
        if (!result.isSuccess) return false
        return result.out.any { it.trim().startsWith(expected) }
    }

    private fun writeLegacy(): Boolean = runCatching {
        val editor = legacy.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Number -> editor.putLong(key, value.toLong())
                is String -> editor.putString(key, value)
            }
        }
        editor.commit()
    }.getOrDefault(false)

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}

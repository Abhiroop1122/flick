package app.flick

import android.content.Context
import android.os.Build
import android.provider.Settings
import app.flick.core.Flick
import app.flick.core.PhoneAuth
import app.flick.core.Receiver
import app.flick.core.SenderInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/** A computer this phone has seen or sent to before. */
@Serializable
data class Known(
    val id: String,
    val name: String,
    val os: String,
    val host: String,
    val port: Int,
    val fp: String,
    val lastSeen: Long = 0,
    val lastUsed: Long = 0,
    val trustKey: String? = null,
    val manual: Boolean = false,
) {
    fun toReceiver(via: String = "known") = Receiver(id, name, os, host, port, fp, lastSeen, via)
}

/** Small persistent state, kept in SharedPreferences. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("flick", Context.MODE_PRIVATE)
    private val listSer = ListSerializer(Known.serializer())
    private val _known = MutableStateFlow(load())
    val known: StateFlow<List<Known>> = _known.asStateFlow()

    val phoneId: String = prefs.getString("phone_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("phone_id", it).apply()
    }

    private val defaultName: String = run {
        val dev = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        dev?.takeIf { it.isNotBlank() } ?: modelName()
    }

    private val _phoneName = MutableStateFlow(prefs.getString("phone_name", null) ?: defaultName)
    val phoneName: StateFlow<String> = _phoneName.asStateFlow()

    fun setPhoneName(name: String) {
        val n = name.trim().take(40).ifBlank { defaultName }
        prefs.edit().putString("phone_name", n).apply()
        _phoneName.value = n
    }

    // ---------------------------------------------------------------- staying connected

    private fun flag(key: String, def: Boolean) = MutableStateFlow(prefs.getBoolean(key, def))
    private val _stayConnected = flag("stay_connected", true)
    private val _clipboardSync = flag("clipboard_sync", true)
    private val _autoAccept = flag("auto_accept_files", true)
    private val _sendClipboard = flag("send_clipboard", true)
    /** Send what's copied on this phone to connected computers (automatic with the helper). */
    val sendClipboard: StateFlow<Boolean> = _sendClipboard.asStateFlow()
    fun setSendClipboard(v: Boolean) { prefs.edit().putBoolean("send_clipboard", v).apply(); _sendClipboard.value = v }
    /** Keep a live link to paired computers (needed for clipboard and computer-to-phone files). */
    val stayConnected: StateFlow<Boolean> = _stayConnected.asStateFlow()
    /** Put text copied on a connected computer onto this phone's clipboard. */
    val clipboardSync: StateFlow<Boolean> = _clipboardSync.asStateFlow()
    /** Accept files from paired computers without asking. */
    val autoAcceptFiles: StateFlow<Boolean> = _autoAccept.asStateFlow()

    fun setStayConnected(v: Boolean) { prefs.edit().putBoolean("stay_connected", v).apply(); _stayConnected.value = v }
    fun setClipboardSync(v: Boolean) { prefs.edit().putBoolean("clipboard_sync", v).apply(); _clipboardSync.value = v }
    fun setAutoAcceptFiles(v: Boolean) { prefs.edit().putBoolean("auto_accept_files", v).apply(); _autoAccept.value = v }

    /** Computers this phone is paired with (they trust it). */
    fun paired(): List<Known> = _known.value.filter { it.trustKey != null }

    fun auth(computerId: String): PhoneAuth? = get(computerId)?.trustKey?.let {
        PhoneAuth(phoneId, it, _phoneName.value, modelName())
    }

    @Synchronized
    fun clearTrust(id: String) = save(_known.value.map { if (it.id == id) it.copy(trustKey = null) else it })

    fun sender(receiverId: String) = SenderInfo(
        id = phoneId,
        name = _phoneName.value,
        model = modelName(),
        platform = "android",
        trustKey = _known.value.firstOrNull { it.id == receiverId }?.trustKey,
    )

    @Synchronized
    fun remember(r: Receiver, used: Boolean = false, manual: Boolean = false) {
        val now = System.currentTimeMillis()
        val list = _known.value.toMutableList()
        val i = list.indexOfFirst { it.id == r.id }
        val old = list.getOrNull(i)
        val k = Known(
            id = r.id, name = r.name, os = r.os, host = r.host, port = r.port, fp = r.fingerprint,
            lastSeen = now,
            lastUsed = if (used) now else old?.lastUsed ?: 0,
            trustKey = if (old?.fp == r.fingerprint) old.trustKey else null, // new certificate = new install: drop trust
            manual = manual || old?.manual == true,
        )
        // nothing but the timestamp changed: avoid rewriting prefs on every probe reply
        if (old != null && old == k.copy(lastSeen = old.lastSeen) && now - old.lastSeen < 60_000) return
        if (i >= 0) list[i] = k else list.add(k)
        save(list)
    }

    @Synchronized
    fun setTrustKey(id: String, key: String) {
        save(_known.value.map { if (it.id == id) it.copy(trustKey = key) else it })
    }

    @Synchronized
    fun forget(id: String) = save(_known.value.filterNot { it.id == id })

    fun get(id: String) = _known.value.firstOrNull { it.id == id }

    private fun load(): List<Known> = runCatching {
        Flick.json.decodeFromString(listSer, prefs.getString("known", "[]")!!)
    }.getOrDefault(emptyList())

    private fun save(list: List<Known>) {
        val trimmed = list.sortedByDescending { maxOf(it.lastUsed, it.lastSeen) }.take(40)
        prefs.edit().putString("known", Flick.json.encodeToString(listSer, trimmed)).apply()
        _known.value = trimmed
    }

    companion object {
        fun modelName(): String {
            val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            val model = Build.MODEL
            return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
        }
    }
}

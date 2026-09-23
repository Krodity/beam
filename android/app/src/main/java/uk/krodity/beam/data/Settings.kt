package uk.krodity.beam.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("beam")

/** Broker pairing plus the few browser preferences worth keeping. */
class SettingsRepo(private val context: Context) {

    private val kBroker = stringPreferencesKey("broker")
    private val kToken = stringPreferencesKey("token")
    private val kHost = stringPreferencesKey("host")
    private val kAutoSend = booleanPreferencesKey("auto_send")
    private val kBookmarks = stringPreferencesKey("bookmarks")

    data class Config(
        val broker: String = "",
        val token: String = "",
        val host: String = "",
        /** Hand a video to the PC the moment it starts playing on the phone. */
        val autoSend: Boolean = true,
        val bookmarks: List<Bookmark> = emptyList(),
    ) {
        val paired: Boolean get() = broker.isNotBlank() && token.isNotBlank()
    }

    val config: Flow<Config> = context.dataStore.data.map { p ->
        Config(
            broker = p[kBroker] ?: "",
            token = p[kToken] ?: "",
            host = p[kHost] ?: "",
            autoSend = p[kAutoSend] ?: true,
            bookmarks = Bookmark.decode(p[kBookmarks]),
        )
    }

    suspend fun pair(broker: String, token: String) {
        context.dataStore.edit {
            it[kBroker] = normalise(broker)
            it[kToken] = token
        }
    }

    suspend fun selectHost(hostId: String) {
        context.dataStore.edit { it[kHost] = hostId }
    }

    suspend fun setAutoSend(on: Boolean) {
        context.dataStore.edit { it[kAutoSend] = on }
    }

    suspend fun setBookmarks(list: List<Bookmark>) {
        context.dataStore.edit { it[kBookmarks] = Bookmark.encode(list) }
    }

    /** Forget the pairing, but keep the user's bookmarks and preferences. */
    suspend fun unpair() {
        context.dataStore.edit {
            it.remove(kBroker)
            it.remove(kToken)
            it.remove(kHost)
        }
    }

    /** Accept "hostname", "hostname:8780" or a full URL and always end up with a base URL. */
    private fun normalise(raw: String): String {
        var s = raw.trim().removeSuffix("/")
        if (s.isEmpty()) return s
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        val afterScheme = s.substringAfter("://")
        if (!afterScheme.contains(":")) s = "$s:8780"
        return s
    }
}

package com.example.data

import android.content.Context
import com.example.core.model.ActiveAppContext
import com.example.core.model.ConversationContextState
import com.example.core.model.ConversationMemoryEntry
import com.example.core.model.PersonalShortcut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persistent local conversation memory & personal shortcut store.
 * Stores only structured text context (never raw audio recordings) in app-private filesDir,
 * excluded from cloud/ADB backups via backup_rules.xml & data_extraction_rules.xml.
 */
class ConversationMemoryStore(context: Context) {

    private val memoryFile = File(context.filesDir, "cheetah_memory.json")
    private val _state = MutableStateFlow(loadFromDisk())
    val state: StateFlow<ConversationContextState> = _state.asStateFlow()

    fun current(): ConversationContextState = _state.value

    suspend fun recordTurn(
        userText: String,
        assistantReply: String,
        intentCategory: String,
        newAppContext: ActiveAppContext? = null,
        newSearchQuery: String? = null,
        newOpenedApp: String? = null
    ) = withContext(Dispatchers.IO) {
        val current = _state.value
        val resolvedAppCtx = newAppContext ?: current.activeAppContext
        val entry = ConversationMemoryEntry(
            id = System.currentTimeMillis(),
            userText = userText.take(220),
            assistantReply = assistantReply.take(260),
            intentCategory = intentCategory,
            activeContext = resolvedAppCtx,
            timestampMs = System.currentTimeMillis()
        )
        val updated = current.copy(
            activeAppContext = resolvedAppCtx,
            lastSearchQuery = newSearchQuery ?: current.lastSearchQuery,
            lastOpenedApp = newOpenedApp ?: current.lastOpenedApp,
            recentEntries = (listOf(entry) + current.recentEntries).take(MAX_HISTORY)
        )
        saveToDisk(updated)
        _state.value = updated
    }

    suspend fun addShortcut(triggerPhrase: String, mappedCommand: String) = withContext(Dispatchers.IO) {
        val cleanTrigger = triggerPhrase.trim()
        val cleanCommand = mappedCommand.trim()
        if (cleanTrigger.isEmpty() || cleanCommand.isEmpty()) return@withContext
        val current = _state.value
        val shortcut = PersonalShortcut(
            id = UUID.randomUUID().toString(),
            triggerPhrase = cleanTrigger,
            mappedCommand = cleanCommand
        )
        val updated = current.copy(
            shortcuts = (current.shortcuts.filterNot { it.triggerPhrase == cleanTrigger } + shortcut)
                .takeLast(MAX_SHORTCUTS)
        )
        saveToDisk(updated)
        _state.value = updated
    }

    suspend fun deleteShortcut(id: String) = withContext(Dispatchers.IO) {
        val current = _state.value
        val updated = current.copy(shortcuts = current.shortcuts.filterNot { it.id == id })
        saveToDisk(updated)
        _state.value = updated
    }

    suspend fun deleteEntry(id: Long) = withContext(Dispatchers.IO) {
        val current = _state.value
        val updated = current.copy(recentEntries = current.recentEntries.filterNot { it.id == id })
        saveToDisk(updated)
        _state.value = updated
    }

    suspend fun clearAllMemory() = withContext(Dispatchers.IO) {
        val current = _state.value
        val updated = ConversationContextState(shortcuts = current.shortcuts)
        saveToDisk(updated)
        _state.value = updated
    }

    private fun loadFromDisk(): ConversationContextState {
        if (!memoryFile.exists()) return ConversationContextState()
        return runCatching {
            val root = JSONObject(memoryFile.readText())
            val ctxName = root.optString("activeAppContext", ActiveAppContext.NONE.name)
            val appCtx = runCatching { ActiveAppContext.valueOf(ctxName) }.getOrDefault(ActiveAppContext.NONE)
            val lastSearch = root.optString("lastSearchQuery", "").ifBlank { null }
            val lastApp = root.optString("lastOpenedApp", "").ifBlank { null }

            val entriesJson = root.optJSONArray("entries") ?: JSONArray()
            val entries = buildList {
                for (i in 0 until entriesJson.length()) {
                    val o = entriesJson.getJSONObject(i)
                    val ec = runCatching {
                        ActiveAppContext.valueOf(o.optString("activeContext", ActiveAppContext.NONE.name))
                    }.getOrDefault(ActiveAppContext.NONE)
                    add(
                        ConversationMemoryEntry(
                            id = o.getLong("id"),
                            userText = o.getString("userText"),
                            assistantReply = o.getString("assistantReply"),
                            intentCategory = o.optString("intentCategory", "UNKNOWN"),
                            activeContext = ec,
                            timestampMs = o.optLong("timestampMs", 0L)
                        )
                    )
                }
            }

            val shortcutsJson = root.optJSONArray("shortcuts") ?: JSONArray()
            val shortcuts = buildList {
                for (i in 0 until shortcutsJson.length()) {
                    val o = shortcutsJson.getJSONObject(i)
                    add(
                        PersonalShortcut(
                            id = o.getString("id"),
                            triggerPhrase = o.getString("triggerPhrase"),
                            mappedCommand = o.getString("mappedCommand")
                        )
                    )
                }
            }

            ConversationContextState(
                activeAppContext = appCtx,
                lastSearchQuery = lastSearch,
                lastOpenedApp = lastApp,
                recentEntries = entries,
                shortcuts = shortcuts
            )
        }.getOrDefault(ConversationContextState())
    }

    private fun saveToDisk(state: ConversationContextState) {
        val entriesArr = JSONArray()
        state.recentEntries.forEach { e ->
            entriesArr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("userText", e.userText)
                    put("assistantReply", e.assistantReply)
                    put("intentCategory", e.intentCategory)
                    put("activeContext", e.activeContext.name)
                    put("timestampMs", e.timestampMs)
                }
            )
        }
        val shortcutsArr = JSONArray()
        state.shortcuts.forEach { s ->
            shortcutsArr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("triggerPhrase", s.triggerPhrase)
                    put("mappedCommand", s.mappedCommand)
                }
            )
        }
        val root = JSONObject().apply {
            put("activeAppContext", state.activeAppContext.name)
            put("lastSearchQuery", state.lastSearchQuery.orEmpty())
            put("lastOpenedApp", state.lastOpenedApp.orEmpty())
            put("entries", entriesArr)
            put("shortcuts", shortcutsArr)
        }
        memoryFile.writeText(root.toString())
    }

    companion object {
        private const val MAX_HISTORY = 20
        private const val MAX_SHORTCUTS = 15
    }
}

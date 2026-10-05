package com.aliothmoon.maahotta.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.dataStore by preferencesDataStore("maa_hotta")

class ConfigStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("app_config")

    val config: Flow<AppConfig> = context.dataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<AppConfig>(it) }.getOrNull() }
            ?: AppConfig()
    }

    suspend fun update(transform: (AppConfig) -> AppConfig) {
        context.dataStore.edit { prefs ->
            val current = prefs[key]?.let {
                runCatching { json.decodeFromString<AppConfig>(it) }.getOrNull()
            } ?: AppConfig()
            prefs[key] = json.encodeToString(AppConfig.serializer(), transform(current))
        }
    }

    suspend fun upsertAccount(account: GameAccount) {
        update { config ->
            val id = account.id.ifBlank { UUID.randomUUID().toString() }
            val saved = account.copy(id = id)
            val accounts = config.accounts.toMutableList()
            val index = accounts.indexOfFirst { it.id == id }
            if (index >= 0) accounts[index] = saved else accounts += saved
            config.copy(accounts = accounts)
        }
    }

    suspend fun removeAccount(id: String) {
        update { it.copy(accounts = it.accounts.filterNot { account -> account.id == id }) }
    }

    suspend fun updateAccount(id: String, transform: (GameAccount) -> GameAccount) {
        update { config ->
            config.copy(
                accounts = config.accounts.map { account ->
                    if (account.id == id) transform(account) else account
                },
            )
        }
    }

    suspend fun setAllAccountsEnabled(enabled: Boolean) {
        update { config ->
            config.copy(accounts = config.accounts.map { it.copy(enabled = enabled) })
        }
    }

    /** Adds the accounts packaged with the app once, without overwriting saved edits. */
    suspend fun importBundledAccounts() {
        val bundled = loadBundledAccounts()
        if (bundled.isEmpty()) return
        update { config ->
            if (config.bundledAccountsVersion >= BUNDLED_ACCOUNTS_VERSION) {
                config
            } else {
                val bundledByUsername = bundled.associateBy { it.username.trim() }
                val correctedAccounts = config.accounts.map { saved ->
                    val corrected = bundledByUsername[saved.username.trim()]
                    if (corrected != null && saved.id == corrected.id) {
                        saved.copy(label = corrected.label, password = corrected.password)
                    } else {
                        saved
                    }
                }
                val savedUsernames = correctedAccounts.mapTo(mutableSetOf()) { it.username.trim() }
                val missing = bundled.filter { savedUsernames.add(it.username.trim()) }
                config.copy(
                    accounts = correctedAccounts + missing,
                    bundledAccountsVersion = BUNDLED_ACCOUNTS_VERSION,
                )
            }
        }
    }

    private fun loadBundledAccounts(): List<GameAccount> = runCatching {
        context.assets.open(BUNDLED_ACCOUNTS_FILE).bufferedReader().useLines { lines ->
            lines.mapNotNull(::parseBundledAccount).distinctBy { it.username }.toList()
        }
    }.getOrDefault(emptyList())

    private fun parseBundledAccount(rawLine: String): GameAccount? {
        val line = rawLine.replace("\u200B", "").trim()
        val match = BUNDLED_ACCOUNT_PATTERN.matchEntire(line) ?: return null
        val username = match.groupValues[1]
        val password = match.groupValues[2]
        val label = match.groupValues[3].trim()
        if (label.isEmpty()) return null

        val id = UUID.nameUUIDFromBytes("bundled:$username".toByteArray()).toString()
        return GameAccount(
            id = id,
            label = label,
            username = username,
            password = password,
            enabled = true,
        )
    }

    private companion object {
        const val BUNDLED_ACCOUNTS_VERSION = 3
        const val BUNDLED_ACCOUNTS_FILE = "default_accounts.txt"
        val BUNDLED_ACCOUNT_PATTERN = Regex("^账号：(\\d{11})----([A-Za-z0-9]+)-+\\s*(.+)$")
    }

}

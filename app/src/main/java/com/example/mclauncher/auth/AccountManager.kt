package com.example.mclauncher.auth

import android.content.Context
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
sealed class Account {
    abstract val id: String
    abstract val username: String
    abstract val uuid: String

    /** Cuenta Microsoft con licencia verificada. Tokens cifrados con Keystore. */
    @Serializable @SerialName("microsoft")
    data class Microsoft(
        override val id: String,
        override val username: String,
        override val uuid: String,
        val xuid: String? = null,
        val encMcToken: String,
        val mcExpiresAt: Long,
        val encRefreshToken: String
    ) : Account()

    /** Perfil local para un jugador / LAN. Nunca se autentica contra servidores de Mojang. */
    @Serializable @SerialName("offline")
    data class Offline(override val id: String, override val username: String, override val uuid: String) : Account()
}

/** Datos que se inyectan en los argumentos del juego. */
data class LaunchSession(val username: String, val uuid: String, val accessToken: String, val userType: String, val xuid: String, val offline: Boolean = false)

class AccountManager(context: Context, http: HttpClient, msaClientId: () -> String) {

    /**
     * Política legal recomendada: solo permitir perfiles offline si en este dispositivo
     * ya se inició sesión alguna vez con una cuenta Microsoft que posee el juego.
     */
    var requireOwnershipForOffline: Boolean = true

    private val file = File(context.filesDir, "accounts.json")
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
    private val lock = Mutex()
    private val msa = MicrosoftAuth(http, msaClientId)

    private val _accounts = MutableStateFlow(load())
    val accounts: StateFlow<List<Account>> = _accounts

    private fun load(): List<Account> = runCatching {
        json.decodeFromString(ListSerializer(Account.serializer()), file.readText())
    }.getOrDefault(emptyList())

    private fun persist(list: List<Account>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(Account.serializer()), list))
        tmp.renameTo(file)
        _accounts.value = list
    }

    // ---------- Microsoft ----------
    suspend fun beginMicrosoftLogin() = msa.requestDeviceCode()

    /** Flujo automático (WebView): prepara la URL de login con PKCE. */
    fun newWebLogin(): Pair<MicrosoftAuth.Pkce, String> = msa.newPkce().let { it to msa.authorizeUrl(it) }

    /** Recibe el ?code= que devolvió Microsoft y completa toda la cadena hasta Minecraft. */
    suspend fun completeWebLogin(code: String, pkce: MicrosoftAuth.Pkce): Account.Microsoft = withContext(Dispatchers.IO) {
        saveMicrosoft(msa.loginMinecraft(msa.exchangeCode(code, pkce)))
    }

    private suspend fun saveMicrosoft(r: MicrosoftAuth.Result): Account.Microsoft {
        val acc = Account.Microsoft(
            id = "ms-" + r.profile.id, username = r.profile.name, uuid = dashed(r.profile.id), xuid = r.xuid,
            encMcToken = TokenVault.encrypt(r.mcAccessToken), mcExpiresAt = r.mcExpiresAt,
            encRefreshToken = TokenVault.encrypt(r.msaRefreshToken)
        )
        lock.withLock { persist(_accounts.value.filterNot { it.id == acc.id } + acc) }
        return acc
    }

    /** Refresca en segundo plano al abrir la app (sin pedir nada al usuario). Ignora fallos de red. */
    suspend fun refreshAllQuietly() = withContext(Dispatchers.IO) {
        _accounts.value.filterIsInstance<Account.Microsoft>().forEach { acc ->
            if (acc.mcExpiresAt - System.currentTimeMillis() < 60 * 60_000) runCatching { refresh(acc) }
        }
    }

    suspend fun completeMicrosoftLogin(dc: MicrosoftAuth.DeviceCode): Account.Microsoft = withContext(Dispatchers.IO) {
        val token = msa.pollDeviceCode(dc)
        val r = msa.loginMinecraft(token)
        val acc = Account.Microsoft(
            id = "ms-" + r.profile.id, username = r.profile.name, uuid = dashed(r.profile.id), xuid = r.xuid,
            encMcToken = TokenVault.encrypt(r.mcAccessToken), mcExpiresAt = r.mcExpiresAt,
            encRefreshToken = TokenVault.encrypt(r.msaRefreshToken)
        )
        lock.withLock { persist(_accounts.value.filterNot { it.id == acc.id } + acc) }
        acc
    }

    // ---------- Offline ----------
    private val nameRegex = Regex("^[A-Za-z0-9_]{3,16}$")

    suspend fun addOffline(username: String): Account.Offline = lock.withLock {
        require(nameRegex.matches(username)) { "Nombre inválido: 3–16 caracteres, letras, números o _" }
        if (requireOwnershipForOffline) {
            check(_accounts.value.any { it is Account.Microsoft }) {
                "Inicia sesión una vez con una cuenta Microsoft que tenga Minecraft para habilitar perfiles offline."
            }
        }
        // UUID v3 idéntico al que usan los servidores en modo offline: MD5("OfflinePlayer:<nombre>")
        val uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray()).toString()
        val acc = Account.Offline("off-$uuid", username, uuid)
        persist(_accounts.value.filterNot { it.id == acc.id } + acc)
        acc
    }

    suspend fun remove(id: String) = lock.withLock { persist(_accounts.value.filterNot { it.id == id }) }

    /** Devuelve una sesión válida, refrescando el token de Minecraft si expira en < 5 min. */
    suspend fun sessionFor(id: String): LaunchSession = withContext(Dispatchers.IO) {
        when (val acc = _accounts.value.first { it.id == id }) {
            is Account.Offline -> LaunchSession(acc.username, acc.uuid, "0", "legacy", "0")
            is Account.Microsoft -> {
                val expiring = acc.mcExpiresAt - System.currentTimeMillis() < 5 * 60_000
                val fresh = if (!expiring) acc else try {
                    refresh(acc)
                } catch (e: Exception) {
                    if (e is MicrosoftAuth.AuthException) throw e
                    // Sin internet: la licencia ya se verificó antes → modo un jugador/LAN con el mismo perfil
                    return@withContext LaunchSession(acc.username, acc.uuid, "0", "msa", acc.xuid ?: "0", offline = true)
                }
                LaunchSession(fresh.username, fresh.uuid, TokenVault.decrypt(fresh.encMcToken), "msa", fresh.xuid ?: "0")
            }
        }
    }

    private suspend fun refresh(acc: Account.Microsoft): Account.Microsoft {
        val msaTok = msa.refreshMsa(TokenVault.decrypt(acc.encRefreshToken))
        val r = msa.loginMinecraft(msaTok)
        val updated = acc.copy(
            username = r.profile.name,
            encMcToken = TokenVault.encrypt(r.mcAccessToken), mcExpiresAt = r.mcExpiresAt,
            encRefreshToken = TokenVault.encrypt(r.msaRefreshToken)
        )
        lock.withLock { persist(_accounts.value.map { if (it.id == acc.id) updated else it }) }
        return updated
    }

    private fun dashed(id: String) = if (id.length == 32)
        "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}" else id
}

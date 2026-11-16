package com.example.mclauncher.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Cadena oficial: Microsoft (OAuth2 Device Code) → Xbox Live → XSTS → Minecraft Services.
 *
 * Requisitos legales/técnicos:
 *  - Registrar tu propia app en Azure (Entra ID), cuentas personales Microsoft, "Allow public client flows".
 *  - Solicitar a Mojang acceso de tu client_id a api.minecraftservices.com (formulario oficial);
 *    sin aprobación, login_with_xbox responde 403.
 */
class MicrosoftAuth(private val http: HttpClient, private val clientIdProvider: () -> String) {
    private val clientId: String get() = clientIdProvider()

    // ---------------- Flujo automático: Authorization Code + PKCE en WebView ----------------
    /** PKCE (RFC 7636): el "verifier" nunca sale del teléfono. */
    data class Pkce(val verifier: String, val challenge: String, val state: String)

    fun newPkce(): Pkce {
        val rnd = java.security.SecureRandom()
        fun b64(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        val verifier = b64(ByteArray(48).also(rnd::nextBytes))
        val challenge = b64(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        return Pkce(verifier, challenge, b64(ByteArray(16).also(rnd::nextBytes)))
    }

    fun authorizeUrl(p: Pkce): String {
        require(clientId.isNotBlank()) { "Falta el Client ID de Azure (Ajustes ⚙)" }
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        return "$MSA/authorize?client_id=${enc(clientId)}&response_type=code&response_mode=query" +
            "&redirect_uri=${enc(REDIRECT_URI)}&scope=${enc(SCOPE)}&prompt=select_account" +
            "&code_challenge=${p.challenge}&code_challenge_method=S256&state=${p.state}"
    }

    suspend fun exchangeCode(code: String, p: Pkce): MsaToken {
        val resp = http.submitForm("$MSA/token", parameters {
            append("client_id", clientId); append("grant_type", "authorization_code")
            append("code", code); append("redirect_uri", REDIRECT_URI)
            append("code_verifier", p.verifier); append("scope", SCOPE)
        })
        if (!resp.status.isSuccess()) {
            val err: OAuthError = resp.body()
            throw AuthException(err.description ?: err.error)
        }
        return resp.body()
    }


    @Serializable data class DeviceCode(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_uri") val verificationUri: String,
        @SerialName("expires_in") val expiresIn: Int,
        val interval: Int = 5,
        val message: String = ""
    )

    @Serializable data class MsaToken(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Int = 3600
    )

    @Serializable private data class OAuthError(val error: String, @SerialName("error_description") val description: String? = null)

    @Serializable data class McLogin(@SerialName("access_token") val accessToken: String, @SerialName("expires_in") val expiresIn: Int)
    @Serializable data class McProfile(val id: String, val name: String)

    data class Result(val profile: McProfile, val mcAccessToken: String, val mcExpiresAt: Long, val msaRefreshToken: String, val xuid: String?)

    class AuthException(msg: String) : Exception(msg)

    suspend fun requestDeviceCode(): DeviceCode {
        require(clientId.isNotBlank()) { "Falta el Client ID de Azure (Ajustes ⚙)" }
        return http.submitForm(
            "$MSA/devicecode",
            parameters { append("client_id", clientId); append("scope", SCOPE) }
        ).body()
    }

    /** Hace polling hasta que el usuario ingrese el código en microsoft.com/link. */
    suspend fun pollDeviceCode(dc: DeviceCode): MsaToken {
        var interval = dc.interval.toLong()
        val deadline = System.currentTimeMillis() + dc.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000)
            val resp = http.submitForm("$MSA/token", parameters {
                append("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                append("client_id", clientId)
                append("device_code", dc.deviceCode)
            })
            if (resp.status.isSuccess()) return resp.body()
            val err: OAuthError = resp.body()
            when (err.error) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "authorization_declined" -> throw AuthException("Inicio de sesión cancelado")
                "expired_token" -> throw AuthException("El código expiró")
                else -> throw AuthException(err.description ?: err.error)
            }
        }
        throw AuthException("El código expiró")
    }

    suspend fun refreshMsa(refreshToken: String): MsaToken {
        val resp = http.submitForm("$MSA/token", parameters {
            append("grant_type", "refresh_token"); append("client_id", clientId)
            append("refresh_token", refreshToken); append("scope", SCOPE)
        })
        if (!resp.status.isSuccess()) throw AuthException("Sesión Microsoft expirada; vuelve a iniciar sesión")
        return resp.body()
    }

    /** MSA → XBL → XSTS → Minecraft. Verifica que la cuenta tenga la licencia del juego. */
    suspend fun loginMinecraft(msa: MsaToken): Result {
        // 1) Xbox Live
        val xbl: JsonObject = http.post("https://user.auth.xboxlive.com/user/authenticate") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                putJsonObject("Properties") {
                    put("AuthMethod", "RPS"); put("SiteName", "user.auth.xboxlive.com"); put("RpsTicket", "d=${msa.accessToken}")
                }
                put("RelyingParty", "http://auth.xboxlive.com"); put("TokenType", "JWT")
            })
        }.body()
        val xblToken = xbl["Token"]!!.jsonPrimitive.content
        val uhs = xbl["DisplayClaims"]!!.jsonObject["xui"]!!.jsonArray[0].jsonObject["uhs"]!!.jsonPrimitive.content

        // 2) XSTS para Minecraft
        val xstsResp = http.post("https://xsts.auth.xboxlive.com/xsts/authorize") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                putJsonObject("Properties") { put("SandboxId", "RETAIL"); putJsonArray("UserTokens") { add(kotlinx.serialization.json.JsonPrimitive(xblToken)) } }
                put("RelyingParty", "rp://api.minecraftservices.com/"); put("TokenType", "JWT")
            })
        }
        val xsts: JsonObject = xstsResp.body()
        if (!xstsResp.status.isSuccess()) {
            val code = xsts["XErr"]?.jsonPrimitive?.content
            throw AuthException(when (code) {
                "2148916233" -> "Esta cuenta Microsoft no tiene perfil de Xbox. Créalo en xbox.com."
                "2148916235" -> "Xbox Live no está disponible en tu país."
                "2148916236", "2148916237" -> "La cuenta requiere verificación de adulto (Corea del Sur)."
                "2148916238" -> "Cuenta de menor: debe agregarse a una familia Microsoft."
                else -> "Error XSTS ${code ?: xstsResp.status.value}"
            })
        }
        val xstsToken = xsts["Token"]!!.jsonPrimitive.content
        val xuid = runCatching { xsts["DisplayClaims"]!!.jsonObject["xui"]!!.jsonArray[0].jsonObject["xid"]!!.jsonPrimitive.content }.getOrNull()

        // 3) Minecraft Services
        val mcResp = http.post("$MCS/authentication/login_with_xbox") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("identityToken", "XBL3.0 x=$uhs;$xstsToken") })
        }
        if (!mcResp.status.isSuccess()) throw AuthException(
            if (mcResp.status.value == 403) "Tu client_id de Azure aún no está aprobado por Mojang para la API de Minecraft."
            else "Minecraft Services HTTP ${mcResp.status.value}")
        val mc: McLogin = mcResp.body()

        // 4) Licencia + perfil (falla con 404 si no compró Java Edition)
        val profResp = http.get("$MCS/minecraft/profile") { bearerAuth(mc.accessToken) }
        if (profResp.status.value == 404) throw AuthException("Esta cuenta no tiene Minecraft: Java Edition.")
        val profile: McProfile = profResp.body()

        return Result(profile, mc.accessToken, System.currentTimeMillis() + mc.expiresIn * 1000L,
            msa.refreshToken ?: error("Microsoft no devolvió refresh_token (falta offline_access)"), xuid)
    }

    companion object {
        private const val MSA = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
        private const val MCS = "https://api.minecraftservices.com"
        private const val SCOPE = "XboxLive.signin offline_access"
        /** Redirect estándar de Microsoft para apps nativas (agrégalo en Azure → Autenticación). */
        const val REDIRECT_URI = "https://login.microsoftonline.com/common/oauth2/nativeclient"
    }
}

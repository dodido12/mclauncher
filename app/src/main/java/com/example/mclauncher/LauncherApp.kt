package com.example.mclauncher

import android.app.Application
import com.example.mclauncher.auth.AccountManager
import com.example.mclauncher.instance.InstanceManager
import com.example.mclauncher.jre.JreManager
import com.example.mclauncher.net.Http
import com.example.mclauncher.net.ModrinthClient
import com.example.mclauncher.net.MojangDownloader

/** Contenedor de dependencias simple (sin DI framework para mantenerlo legible). */
class LauncherApp : Application() {
    lateinit var instances: InstanceManager; private set
    lateinit var jre: JreManager; private set
    lateinit var mojang: MojangDownloader; private set
    lateinit var modrinth: ModrinthClient; private set
    lateinit var accounts: AccountManager; private set
    private lateinit var prefs: android.content.SharedPreferences

    /** Client ID de Azure: el que pongas en Ajustes ⚙, o el de gradle.properties como valor inicial. */
    var msaClientId: String
        get() = BuildConfig.MSA_CLIENT_ID.ifBlank { prefs.getString("msa_client_id", null).orEmpty() }
        set(v) { prefs.edit().putString("msa_client_id", v.trim()).apply() }

    override fun onCreate() {
        super.onCreate()
        instances = InstanceManager(this)
        jre = JreManager(this, Http.client)
        mojang = MojangDownloader(Http.client, instances.sharedDir)
        modrinth = ModrinthClient(Http.client, BuildConfig.MODRINTH_UA)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        accounts = AccountManager(this, Http.client) { msaClientId }
    }
}

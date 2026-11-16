package com.example.mclauncher.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.example.mclauncher.auth.MicrosoftAuth

/**
 * Página oficial de inicio de sesión de Microsoft dentro de la app. Cuando Microsoft redirige
 * a REDIRECT_URI con ?code=…, la capturamos y devolvemos el código: el usuario no copia nada.
 */
class MicrosoftLoginActivity : Activity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val state = intent.getStringExtra(EXTRA_STATE)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true          // la página de Microsoft lo necesita
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = intercept(request.url, state)
                override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) {
                    progress.visibility = android.view.View.VISIBLE
                    intercept(Uri.parse(u), state)
                }
                override fun onPageFinished(view: WebView, u: String) { progress.visibility = android.view.View.GONE }
            }
        }
        setContentView(FrameLayout(this).apply {
            addView(web, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(progress, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        web.loadUrl(url)
    }

    private var done = false

    private fun intercept(uri: Uri, state: String?): Boolean {
        if (done || !uri.toString().startsWith(MicrosoftAuth.REDIRECT_URI)) return false
        done = true
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error")
        val data = Intent()
        when {
            uri.getQueryParameter("state") != state -> data.putExtra(RESULT_ERROR, "Respuesta de login inválida (state)")
            code != null -> data.putExtra(RESULT_CODE, code)
            else -> data.putExtra(RESULT_ERROR, error ?: "Inicio de sesión cancelado")
        }
        setResult(if (code != null) RESULT_OK else RESULT_CANCELED, data)
        finish()
        return true
    }

    companion object {
        const val EXTRA_URL = "url"; const val EXTRA_STATE = "state"
        const val RESULT_CODE = "code"; const val RESULT_ERROR = "error"
    }
}

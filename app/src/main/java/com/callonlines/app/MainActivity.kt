package com.callonlines.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.callonlines.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var credentialStore: SavedCreds

    private var pendingCredentials: Pair<String, String>? = null

    private var pendingPermissionRequest: PermissionRequest? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val req = pendingPermissionRequest ?: return@registerForActivityResult
        val granted = mutableListOf<String>()
        req.resources.forEach { res ->
            when (res) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                    if (results[Manifest.permission.RECORD_AUDIO] == true) granted += res
                }
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                    if (results[Manifest.permission.CAMERA] == true) granted += res
                }
                else -> granted += res
            }
        }
        if (granted.isEmpty()) req.deny() else req.grant(granted.toTypedArray())
        pendingPermissionRequest = null
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = filePathCallback
        filePathCallback = null
        if (cb == null) return@registerForActivityResult
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        cb.onReceiveValue(uris)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setTheme(R.style.Theme_CallOnLines)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        webView = binding.webview
        swipe = binding.swipe
        credentialStore = SavedCreds(this)

        configureWebView()

        swipe.setColorSchemeResources(
            R.color.primary_cyan,
            R.color.primary_purple
        )
        swipe.setOnRefreshListener { webView.reload() }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(START_URL)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = userAgentString + " CallOnLines/1.0"
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setGeolocationEnabled(false)
            @Suppress("DEPRECATION")
            saveFormData = true
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            val mode = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val forceDark = if (mode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                WebSettingsCompat.FORCE_DARK_ON
            } else {
                WebSettingsCompat.FORCE_DARK_OFF
            }
            WebSettingsCompat.setForceDark(webView.settings, forceDark)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(JsBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val host = url.host?.lowercase() ?: return false

                return if (isAllowedHost(host)) {
                    false
                } else {
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, url)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    }.onFailure {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.cannot_open_link),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    true
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.cancel()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.ssl_error),
                    Toast.LENGTH_LONG
                ).show()
            }

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                binding.progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progress.visibility = View.GONE
                swipe.isRefreshing = false

                val urlStr = url ?: ""
                val isLogin = isLoginUrl(urlStr)

                if (isLogin) {
                    view?.evaluateJavascript(AUTOFILL_JS, null)
                } else {
                    val pending = pendingCredentials
                    if (pending != null) {
                        pendingCredentials = null
                        val sameAsSaved =
                            credentialStore.getUser() == pending.first &&
                            credentialStore.getPass() == pending.second
                        if (!sameAsSaved) {
                            showSaveCredentialsDialog(pending.first, pending.second)
                        }
                    }
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val androidPerms = mutableListOf<String>()
                    request.resources.forEach { res ->
                        when (res) {
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                                androidPerms += Manifest.permission.RECORD_AUDIO
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                                androidPerms += Manifest.permission.CAMERA
                        }
                    }
                    val needRequest = androidPerms.any {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) !=
                            PackageManager.PERMISSION_GRANTED
                    }
                    if (needRequest) {
                        pendingPermissionRequest = request
                        permissionLauncher.launch(androidPerms.toTypedArray())
                    } else {
                        request.grant(request.resources)
                    }
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                cb: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = cb
                val intent = fileChooserParams?.createIntent() ?: return false
                runCatching { fileChooserLauncher.launch(intent) }.onFailure {
                    filePathCallback = null
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.cannot_open_file_picker),
                        Toast.LENGTH_SHORT
                    ).show()
                    return false
                }
                return true
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progress.progress = newProgress
                if (newProgress >= 100) binding.progress.visibility = View.GONE
            }
        }
    }

    inner class JsBridge {
        @JavascriptInterface
        fun onLoginSubmit(user: String, pass: String) {
            pendingCredentials = Pair(user, pass)
        }

        @JavascriptInterface
        fun getSavedUser(): String = credentialStore.getUser()

        @JavascriptInterface
        fun getSavedPass(): String = credentialStore.getPass()

        @JavascriptInterface
        fun hasCredentials(): Boolean = credentialStore.has()

        @JavascriptInterface
        fun requestClear() {
            runOnUiThread {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Borrar credenciales guardadas")
                    .setMessage("¿Quieres eliminar el usuario y contraseña guardados en este dispositivo?")
                    .setPositiveButton("Borrar") { _, _ ->
                        credentialStore.clear()
                        Toast.makeText(
                            this@MainActivity,
                            "Credenciales borradas",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
    }

    private fun showSaveCredentialsDialog(user: String, pass: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("¿Guardar credenciales?")
                .setMessage("Guarda tu usuario y contraseña en este dispositivo para no tener que escribirlos cada vez.")
                .setPositiveButton("Guardar") { _, _ ->
                    credentialStore.save(user, pass)
                    Toast.makeText(
                        this,
                        "Credenciales guardadas",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton("Ahora no", null)
                .show()
        }
    }

    private fun isAllowedHost(host: String): Boolean {
        return ALLOWED_HOSTS.any { allowed ->
            host == allowed || host.endsWith(".$allowed")
        }
    }

    private fun isLoginUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/login.php") && !lower.contains("/root_login.php")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val START_URL = "https://callonlines.com/login.php"

        private val ALLOWED_HOSTS = listOf(
            "callonlines.com",
            "www.callonlines.com"
        )

        private val AUTOFILL_JS = """
        (function () {
          if (window.__cb_autofill_ready) return;
          window.__cb_autofill_ready = true;

          function findFields() {
            var passInput = document.querySelector('input[type="password"]:not([disabled])');
            if (!passInput) return null;
            var form = passInput.closest('form');
            if (!form) return null;
            var userInput = null;
            var inputs = form.querySelectorAll('input');
            for (var i = 0; i < inputs.length; i++) {
              var inp = inputs[i];
              if (inp === passInput) continue;
              var t = (inp.type || 'text').toLowerCase();
              if (t === 'hidden' || t === 'submit' || t === 'button' || t === 'checkbox' || t === 'radio') continue;
              userInput = inp;
              break;
            }
            return { form: form, userInput: userInput, passInput: passInput };
          }

          var fields = findFields();
          if (!fields) return;

          try {
            var alreadyTried = sessionStorage.getItem('cb_autofill_done') === '1';
            if (!alreadyTried && AndroidBridge.hasCredentials() && fields.userInput && fields.passInput) {
              if (!fields.userInput.value) {
                fields.userInput.value = AndroidBridge.getSavedUser();
                fields.userInput.dispatchEvent(new Event('input', { bubbles: true }));
                fields.userInput.dispatchEvent(new Event('change', { bubbles: true }));
              }
              if (!fields.passInput.value) {
                fields.passInput.value = AndroidBridge.getSavedPass();
                fields.passInput.dispatchEvent(new Event('input', { bubbles: true }));
                fields.passInput.dispatchEvent(new Event('change', { bubbles: true }));
              }
              sessionStorage.setItem('cb_autofill_done', '1');
            }
          } catch (e) {}

          fields.form.addEventListener('submit', function () {
            try {
              var u = fields.userInput ? fields.userInput.value : '';
              var p = fields.passInput.value;
              if (u && p) {
                AndroidBridge.onLoginSubmit(u, p);
              }
            } catch (e) {}
          }, true);

          try {
            if (AndroidBridge.hasCredentials() && !document.getElementById('cb_clear_saved_btn')) {
              var btn = document.createElement('div');
              btn.id = 'cb_clear_saved_btn';
              btn.textContent = 'Borrar credenciales guardadas';
              btn.style.cssText = 'position:fixed;bottom:14px;left:50%;transform:translateX(-50%);padding:9px 14px;background:rgba(0,0,0,.62);color:#fff;font-family:system-ui,-apple-system,sans-serif;font-size:12px;border-radius:999px;cursor:pointer;z-index:99999;border:1px solid rgba(255,255,255,.18);';
              btn.onclick = function () { AndroidBridge.requestClear(); };
              document.body.appendChild(btn);
            }
          } catch (e) {}
        })();
        """.trimIndent()
    }
}

class SavedCreds(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREF_FILE, Context.MODE_PRIVATE
    )

    fun save(user: String, pass: String) {
        prefs.edit()
            .putString(KEY_USER, user)
            .putString(KEY_PASS, pass)
            .apply()
    }

    fun getUser(): String = prefs.getString(KEY_USER, "") ?: ""
    fun getPass(): String = prefs.getString(KEY_PASS, "") ?: ""
    fun has(): Boolean = getUser().isNotEmpty() && getPass().isNotEmpty()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREF_FILE = "callonlines_app_creds"
        const val KEY_USER = "user"
        const val KEY_PASS = "pass"
    }
}

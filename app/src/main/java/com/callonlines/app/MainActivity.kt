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
                view?.evaluateJavascript(AUTOFILL_JS, null)
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
        fun saveCredsManual(user: String, pass: String) {
            runOnUiThread {
                if (user.isBlank() || pass.isBlank()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Escribe usuario y contraseña primero",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("¿Guardar credenciales?")
                    .setMessage("Usuario: $user\n\nSe guardarán en este dispositivo para autocompletar la próxima vez.")
                    .setPositiveButton("Guardar") { _, _ ->
                        credentialStore.save(user, pass)
                        Toast.makeText(
                            this@MainActivity,
                            "✓ Credenciales guardadas",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
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

    private fun isAllowedHost(host: String): Boolean {
        return ALLOWED_HOSTS.any { allowed ->
            host == allowed || host.endsWith(".$allowed")
        }
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
          if (window.__cb_v3_installed) return;

          function findFields() {
            var passInput = document.querySelector('input[type="password"]:not([disabled])');
            if (!passInput) return null;
            var form = passInput.closest('form');
            var userInput = null;
            if (form) {
              var inputs = form.querySelectorAll('input');
              for (var i = 0; i < inputs.length; i++) {
                var inp = inputs[i];
                if (inp === passInput) continue;
                var t = (inp.type || 'text').toLowerCase();
                if (t === 'hidden' || t === 'submit' || t === 'button' || t === 'checkbox' || t === 'radio' || t === 'password' || t === 'file') continue;
                userInput = inp;
                break;
              }
            }
            if (!userInput) {
              var allInputs = document.querySelectorAll('input');
              for (var i = 0; i < allInputs.length; i++) {
                var inp = allInputs[i];
                if (inp === passInput) continue;
                var t = (inp.type || 'text').toLowerCase();
                if (t === 'hidden' || t === 'submit' || t === 'button' || t === 'checkbox' || t === 'radio' || t === 'password' || t === 'file') continue;
                userInput = inp;
                break;
              }
            }
            return { passInput: passInput, userInput: userInput, form: form };
          }

          function autofill(f) {
            try {
              if (sessionStor

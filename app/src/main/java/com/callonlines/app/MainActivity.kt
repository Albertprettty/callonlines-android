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
    private lateinit var creds: SavedCreds

    private var pendingPermissionRequest: PermissionRequest? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val req = pendingPermissionRequest ?: return@registerForActivityResult
        val granted = mutableListOf<String>()
        req.resources.forEach { res ->
            when (res) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                    if (results[Manifest.permission.RECORD_AUDIO] == true) granted += res
                PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                    if (results[Manifest.permission.CAMERA] == true) granted += res
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
        cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
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
        creds = SavedCreds(this)
        configureWebView()
        swipe.setColorSchemeResources(R.color.primary_cyan, R.color.primary_purple)
        swipe.setOnRefreshListener { webView.reload() }
        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        else webView.loadUrl(START_URL)
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
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString CallOnLines/1.0"
            useWideViewPort = true
            loadWithOverviewMode = true
            setGeolocationEnabled(false)
            @Suppress("DEPRECATION")
            saveFormData = true
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            val mode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            WebSettingsCompat.setForceDark(
                webView.settings,
                if (mode == android.content.res.Configuration.UI_MODE_NIGHT_YES)
                    WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
            )
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.addJavascriptInterface(JsBridge(), "AndroidBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host?.lowercase() ?: return false
                if (isAllowed(host)) return false
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                return true
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                binding.progress.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progress.visibility = View.GONE
                swipe.isRefreshing = false
                view?.evaluateJavascript(JS, null)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val perms = mutableListOf<String>()
                    request.resources.forEach {
                        when (it) {
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> perms += Manifest.permission.RECORD_AUDIO
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> perms += Manifest.permission.CAMERA
                        }
                    }
                    val need = perms.any { ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED }
                    if (need) {
                        pendingPermissionRequest = request
                        permissionLauncher.launch(perms.toTypedArray())
                    } else request.grant(request.resources)
                }
            }
            override fun onShowFileChooser(
                webView: WebView?, cb: ValueCallback<Array<Uri>>?, params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = cb
                val intent = params?.createIntent() ?: return false
                return runCatching { fileChooserLauncher.launch(intent); true }.getOrElse {
                    filePathCallback = null; false
                }
            }
            override fun onProgressChanged(view: WebView?, p: Int) {
                binding.progress.progress = p
                if (p >= 100) binding.progress.visibility = View.GONE
            }
        }
    }

    inner class JsBridge {
        @JavascriptInterface
        fun saveCredsManual(user: String, pass: String) {
            runOnUiThread {
                if (user.isBlank() || pass.isBlank()) {
                    Toast.makeText(this@MainActivity, "Escribe usuario y contraseña primero", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("¿Guardar credenciales?")
                    .setMessage("Usuario: $user")
                    .setPositiveButton("Guardar") { _, _ ->
                        creds.save(user, pass)
                        Toast.makeText(this@MainActivity, "Credenciales guardadas", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancelar", null).show()
            }
        }
        @JavascriptInterface fun getSavedUser() = creds.getUser()
        @JavascriptInterface fun getSavedPass() = creds.getPass()
        @JavascriptInterface fun hasCredentials() = creds.has()
        @JavascriptInterface
        fun requestClear() {
            runOnUiThread {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Borrar credenciales")
                    .setMessage("¿Eliminar las credenciales guardadas?")
                    .setPositiveButton("Borrar") { _, _ ->
                        creds.clear()
                        Toast.makeText(this@MainActivity, "Borradas", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancelar", null).show()
            }
        }
    }

    private fun isAllowed(host: String) = ALLOWED.any { host == it || host.endsWith(".$it") }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); webView.saveState(outState) }
    override fun onPause() { super.onPause(); webView.onPause(); CookieManager.getInstance().flush() }
    override fun onResume() { super.onResume(); webView.onResume() }
    override fun onDestroy() {
        CookieManager.getInstance().flush(); webView.stopLoading(); webView.destroy(); super.onDestroy()
    }

    companion object {
        private const val START_URL = "https://callonlines.com/login.php"
        private val ALLOWED = listOf("callonlines.com", "www.callonlines.com")

        private val JS = """
(function(){
  if(window.__cb)return;
  function find(){
    var p=document.querySelector('input[type="password"]:not([disabled])');
    if(!p)return null;
    var ins=document.querySelectorAll('input'),u=null,bad=['hidden','submit','button','checkbox','radio','password','file'];
    for(var i=0;i<ins.length;i++){var x=ins[i];if(x===p)continue;if(bad.indexOf((x.type||'text').toLowerCase())>=0)continue;u=x;break;}
    return{u:u,p:p};
  }
  function tick(){
    var f=find();if(!f)return;
    window.__cb=true;
    try{
      if(AndroidBridge.hasCredentials()&&f.u&&!f.u.value){
        f.u.value=AndroidBridge.getSavedUser();
        f.p.value=AndroidBridge.getSavedPass();
        f.u.dispatchEvent(new Event('input',{bubbles:true}));
        f.p.dispatchEvent(new Event('input',{bubbles:true}));
      }
    }catch(e){}
    if(!document.getElementById('cbsave')){
      var b=document.createElement('button');
      b.id='cbsave';b.type='button';b.textContent='Guardar contrasena';
      b.style.cssText='position:fixed!important;top:14px!important;right:14px!important;padding:11px 18px!important;background:linear-gradient(135deg,#22D3EE,#A855F7)!important;color:#fff!important;font:700 13px system-ui,sans-serif!important;border:0!important;border-radius:10px!important;z-index:2147483647!important;box-shadow:0 4px 14px rgba(34,211,238,.5)!important;';
      b.onclick=function(e){e.preventDefault();e.stopPropagation();var f=find();AndroidBridge.saveCredsManual(f&&f.u?f.u.value:'',f&&f.p?f.p.value:'');};
      document.body.appendChild(b);
    }
    try{
      if(AndroidBridge.hasCredentials()&&!document.getElementById('cbclr')){
        var c=document.createElement('div');
        c.id='cbclr';c.textContent='Borrar credenciales guardadas';
        c.style.cssText='position:fixed!important;bottom:14px!important;left:50%!important;transform:translateX(-50%)!important;padding:9px 14px!important;background:rgba(0,0,0,.62)!important;color:#fff!important;font:12px system-ui,sans-serif!important;border-radius:999px!important;z-index:2147483647!important;border:1px solid rgba(255,255,255,.18)!important;';
        c.onclick=function(){AndroidBridge.requestClear();};
        document.body.appendChild(c);
      }
    }catch(e){}
  }
  tick();
  var n=0;var t=setInterval(function(){n++;tick();if(window.__cb||n>40)clearInterval(t);},500);
})();
""".trimIndent()
    }
}

class SavedCreds(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("callonlines_app_creds", Context.MODE_PRIVATE)
    fun save(user: String, pass: String) = prefs.edit().putString("u", user).putString("p", pass).apply()
    fun getUser(): String = prefs.getString("u", "") ?: ""
    fun getPass(): String = prefs.getString("p", "") ?: ""
    fun has(): Boolean = getUser().isNotEmpty() && getPass().isNotEmpty()
    fun clear() = prefs.edit().clear().apply()
}

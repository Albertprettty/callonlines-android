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
import android.text.InputType
import android.view.View
import android.view.ViewGroup
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
import android.widget.EditText
import android.widget.LinearLayout
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
        fun openSaveDialog(prefillUser: String, prefillPass: String) {
            runOnUiThread { showCredsDialog(prefillUser, prefillPass) }
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

    private fun showCredsDialog(initU: String, initP: String) {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val userEdit = EditText(this).apply {
            hint = "Usuario"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(if (initU.isNotEmpty()) initU else creds.getUser())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val passEdit = EditText(this).apply {
            hint = "Contraseña"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(if (initP.isNotEmpty()) initP else creds.getPass())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(userEdit)
            addView(passEdit)
        }
        AlertDialog.Builder(this)
            .setTitle("Guardar credenciales")
            .setMessage("Escribe o confirma tus datos. Se guardarán en este dispositivo para autocompletar la próxima vez.")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val u = userEdit.text.toString().trim()
                val p = passEdit.text.toString()
                if (u.isNotBlank() && p.isNotBlank()) {
                    creds.save(u, p)
                    Toast.makeText(this, "✓ Credenciales guardadas", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Escribe usuario y contraseña", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null).show()
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
  if(window.__cbReady)return;
  window.__cbReady=true;
  function findPass(){
    var ps=document.querySelectorAll('input[type="password"]:not([disabled])');
    if(ps.length)return ps[0];
    var all=document.querySelectorAll('input:not([disabled])');
    for(var i=0;i<all.length;i++){
      var c=all[i],t=(c.type||'').toLowerCase();
      if(['hidden','submit','button','checkbox','radio','file'].indexOf(t)>=0)continue;
      var a=((c.placeholder||'')+' '+(c.name||'')+' '+(c.id||'')+' '+(c.className||'')+' '+(c.getAttribute('aria-label')||'')).toLowerCase();
      if(a.indexOf('pass')>=0||a.indexOf('clave')>=0||a.indexOf('contras')>=0)return c;
    }
    return null;
  }
  function findUser(pass){
    var all=document.querySelectorAll('input:not([disabled])'),prev=null;
    for(var i=0;i<all.length;i++){
      var c=all[i];if(c===pass)return prev;
      var t=(c.type||'').toLowerCase();
      if(['hidden','submit','button','checkbox','radio','password','file'].indexOf(t)>=0)continue;
      prev=c;
    }
    for(var i=0;i<all.length;i++){
      var c=all[i];if(c===pass)continue;
      var t=(c.type||'').toLowerCase();
      if(['hidden','submit','button','checkbox','radio','password','file'].indexOf(t)>=0)continue;
      var a=((c.placeholder||'')+' '+(c.name||'')+' '+(c.id||'')+' '+(c.className||'')+' '+(c.getAttribute('aria-label')||'')).toLowerCase();
      if(a.indexOf('user')>=0||a.indexOf('usuari')>=0||a.indexOf('email')>=0||a.indexOf('login')>=0)return c;
    }
    return null;
  }
  function autofill(){
    try{
      if(!AndroidBridge.hasCredentials())return;
      var p=findPass();if(!p)return;
      var u=findUser(p);
      if(u&&!u.value){u.value=AndroidBridge.getSavedUser();u.dispatchEvent(new Event('input',{bubbles:true}));u.dispatchEvent(new Event('change',{bubbles:true}));}
      if(!p.value){p.value=AndroidBridge.getSavedPass();p.dispatchEvent(new Event('input',{bubbles:true}));p.dispatchEvent(new Event('change',{bubbles:true}));}
    }catch(e){}
  }
  function addButtons(){
    if(!document.getElementById('cbsave')){
      var b=document.createElement('button');
      b.id='cbsave';b.type='button';b.textContent='Guardar contrasena';
      b.style.cssText='position:fixed!important;top:14px!important;right:14px!important;padding:11px 18px!important;background:linear-gradient(135deg,#22D3EE,#A855F7)!important;color:#fff!important;font:700 13px system-ui,sans-serif!important;border:0!important;border-radius:10px!important;z-index:2147483647!important;box-shadow:0 4px 14px rgba(34,211,238,.5)!important;cursor:pointer!important;';
      b.onclick=function(e){
        e.preventDefault();e.stopPropagation();
        var p=findPass(),u=p?findUser(p):null;
        AndroidBridge.openSaveDialog(u&&u.value?u.value:'',p&&p.value?p.value:'');
      };
      document.body.appendChild(b);
    }
    if(AndroidBridge.hasCredentials()&&!document.getElementById('cbclr')){
      var c=document.createElement('div');
      c.id='cbclr';c.textContent='Borrar credenciales guardadas';
      c.style.cssText='position:fixed!important;bottom:14px!important;left:50%!important;transform:translateX(-50%)!important;padding:9px 14px!important;background:rgba(0,0,0,.62)!important;color:#fff!important;font:12px system-ui,sans-serif!important;border-radius:999px!important;z-index:2147483647!important;border:1px solid rgba(255,255,255,.18)!important;cursor:pointer!important;';
      c.onclick=function(){AndroidBridge.requestClear();};
      document.body.appendChild(c);
    }
  }
  autofill();addButtons();
  var n=0;var t=setInterval(function(){n++;autofill();addButtons();if(n>30)clearInterval(t);},600);
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

package com.estebangomez.asignaciones

import android.Manifest
import android.app.AlarmManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var pendingText = ""

    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                val ok = try {
                    contentResolver.openOutputStream(uri)?.use { it.write(pendingText.toByteArray()) }
                    true
                } catch (e: Exception) {
                    false
                }
                js("window.onSaved&&window.onSaved($ok)")
            }
        }

    private val openLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val text = try {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) {
                    null
                }
                val arg = if (text == null) "null" else JSONObject.quote(text)
                js("window.onFilePicked&&window.onFilePicked($arg)")
            }
        }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun js(code: String) {
        runOnUiThread { web.evaluateJavascript(code, null) }
    }

    private fun isDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(if (isDark()) 0xFF211812.toInt() else 0xFFFFFAF5.toInt())
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? = request?.url?.let { loader.shouldInterceptRequest(it) }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = true
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.onBack&&window.onBack()") {
                    if (it != "true") finish()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        Reminders.rescheduleAll(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        js("window.applyTheme&&window.applyTheme()")
    }

    private fun ensurePermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
            if (!am.canScheduleExactAlarms() && !prefs.getBoolean("askedExact", false)) {
                prefs.edit().putBoolean("askedExact", true).apply()
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                )
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun version(): String = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }

        @JavascriptInterface
        fun isDark(): Boolean = this@MainActivity.isDark()

        @JavascriptInterface
        fun haptic(kind: Int) {
            runOnUiThread {
                val c = if (kind == 1 && Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.KEYBOARD_TAP
                web.performHapticFeedback(c)
            }
        }

        @JavascriptInterface
        fun copy(text: String) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("asignaciones", text))
        }

        @JavascriptInterface
        fun whatsapp(text: String) {
            runOnUiThread {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                try {
                    startActivity(Intent(i).setPackage("com.whatsapp"))
                } catch (e: Exception) {
                    startActivity(Intent.createChooser(i, null))
                }
            }
        }

        @JavascriptInterface
        fun saveFile(name: String, text: String) {
            pendingText = text
            runOnUiThread { saveLauncher.launch(name) }
        }

        @JavascriptInterface
        fun pickFile() {
            runOnUiThread { openLauncher.launch(arrayOf("*/*")) }
        }

        @JavascriptInterface
        fun setReminders(json: String, user: String) {
            Reminders.save(this@MainActivity, json, user)
            runOnUiThread { ensurePermissions() }
        }

        @JavascriptInterface
        fun batterySettings() {
            runOnUiThread {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }

        @JavascriptInterface
        fun setBars(status: String, nav: String, navLight: Boolean) {
            runOnUiThread {
                try {
                    window.statusBarColor = Color.parseColor(status)
                    window.navigationBarColor = Color.parseColor(nav)
                    web.setBackgroundColor(Color.parseColor(nav))
                    val ic = WindowCompat.getInsetsController(window, window.decorView)
                    ic.isAppearanceLightStatusBars = false
                    ic.isAppearanceLightNavigationBars = navLight
                } catch (e: Exception) {
                }
            }
        }
    }
}

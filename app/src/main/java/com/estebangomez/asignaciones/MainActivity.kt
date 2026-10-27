package com.estebangomez.asignaciones

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewAssetLoader
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import org.json.JSONObject
import java.io.File

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

    /** Pantalla de permiso de Google (solo la primera vez que se conecta una cuenta). */
    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            if (res.resultCode != RESULT_OK) {
                jsConnect(false, "cancel", "")
            } else {
                try {
                    val r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data ?: Intent())
                    finishConnect(r.accessToken)
                } catch (e: Exception) {
                    jsConnect(false, whyOf(e), codeOf(e))
                }
            }
        }

    private val pdfLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                Thread {
                    val ok = PdfStore.save(this, uri, false)
                    js("window.onPdfSaved&&window.onPdfSaved($ok)")
                }.start()
            }
        }

    private val authLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val ok = r.resultCode == RESULT_OK
            js("window.onAuth&&window.onAuth($ok)")
        }

    private fun codeOf(e: Exception): String = (e as? ApiException)?.statusCode?.toString() ?: ""

    private fun whyOf(e: Exception): String = when ((e as? ApiException)?.statusCode) {
        10 -> "config"
        7 -> "nonet"
        1, 2, 16, 17 -> "nogms"
        else -> "error"
    }

    private fun jsConnect(ok: Boolean, why: String, extra: String) {
        js("window.onDriveConnect&&window.onDriveConnect($ok,${JSONObject.quote(why)},${JSONObject.quote(extra)})")
    }

    private fun startConnect() {
        Identity.getAuthorizationClient(this).authorize(Drive.request())
            .addOnSuccessListener { r ->
                if (r.hasResolution()) {
                    val pi = r.pendingIntent
                    if (pi == null) jsConnect(false, "error", "")
                    else consentLauncher.launch(IntentSenderRequest.Builder(pi).build())
                } else {
                    finishConnect(r.accessToken)
                }
            }
            .addOnFailureListener { e -> jsConnect(false, whyOf(e), codeOf(e)) }
    }

    /** Crea (o encuentra) la carpeta en Drive y avisa si ya hay copias de una conexión anterior. */
    private fun finishConnect(token: String?) {
        if (token == null) {
            jsConnect(false, "error", "")
            return
        }
        Thread {
            try {
                val folder = Drive.folderId(this, token)
                val backups = Drive.list(token, folder)
                val email = Drive.email(token)
                Backup.markConnected(this, email)
                val info = JSONObject().put("email", email).put("backups", backups)
                jsConnect(true, "ok", info.toString())
            } catch (e: Drive.Http) {
                jsConnect(false, if (e.code == 403) "api" else "error", e.code.toString())
            } catch (e: java.io.IOException) {
                jsConnect(false, "nonet", "")
            } catch (e: Exception) {
                jsConnect(false, "error", "")
            }
        }.start()
    }

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
            .addPathHandler("/pdf/", WebViewAssetLoader.InternalStoragePathHandler(this, File(filesDir, "pdf").also { it.mkdirs() }))
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
        handleShare(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.onBack&&window.onBack()") {
                    if (it != "true") finish()
                }
            }
        })
    }

    /** Recibe un PDF compartido desde otra app y reemplaza la planilla guardada. */
    private fun handleShare(i: Intent?) {
        if (i == null || i.action != Intent.ACTION_SEND) return
        val type = i.type ?: return
        if (!type.contains("pdf")) return
        val uri = IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        i.action = null
        Thread {
            val ok = PdfStore.save(this, uri, true)
            js("window.onPdfShared&&window.onPdfShared($ok)")
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
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
        fun deviceSecure(): Boolean {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            return km.isDeviceSecure
        }

        @JavascriptInterface
        fun authenticate() {
            runOnUiThread {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                val i = if (km.isDeviceSecure)
                    km.createConfirmDeviceCredentialIntent("Asignaciones", "Confirmá tu identidad para continuar")
                else null
                if (i == null) js("window.onAuth&&window.onAuth('none')") else authLauncher.launch(i)
            }
        }

        @JavascriptInterface
        fun driveConnect() {
            runOnUiThread {
                try {
                    startConnect()
                } catch (e: Exception) {
                    jsConnect(false, "nogms", "")
                }
            }
        }

        @JavascriptInterface
        fun driveRestore(id: String) {
            Thread {
                try {
                    val text = Drive.download(Drive.silentToken(this@MainActivity), id)
                    js("window.onDriveRestored&&window.onDriveRestored(true,${JSONObject.quote(text)})")
                } catch (e: Exception) {
                    js("window.onDriveRestored&&window.onDriveRestored(false,'')")
                }
            }.start()
        }

        @JavascriptInterface
        fun setSnapshot(text: String) {
            try {
                Backup.saveSnapshot(this@MainActivity, text)
            } catch (e: Exception) {
            }
        }

        @JavascriptInterface
        fun setBackupSchedule(freq: String) {
            Backup.schedule(this@MainActivity, freq)
        }

        @JavascriptInterface
        fun clearBackup() {
            Backup.clear(this@MainActivity)
        }

        @JavascriptInterface
        fun backupStatus(): String = Backup.status(this@MainActivity)

        @JavascriptInterface
        fun backupNow() {
            Thread {
                val r = Backup.run(this@MainActivity)
                js("window.onBackupDone&&window.onBackupDone($r)")
            }.start()
        }

        @JavascriptInterface
        fun pdfInfo(): String = PdfStore.info(this@MainActivity)

        @JavascriptInterface
        fun pickPdf() {
            runOnUiThread { pdfLauncher.launch(arrayOf("application/pdf")) }
        }

        @JavascriptInterface
        fun pdfDelete() {
            PdfStore.delete(this@MainActivity)
        }

        @JavascriptInterface
        fun pdfAck() {
            PdfStore.ack(this@MainActivity)
        }

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
        fun share(text: String) {
            runOnUiThread {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(i, "Compartir mensaje"))
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
        fun setPdfDue(at: String) {
            val ms = at.toLongOrNull() ?: 0L
            Reminders.setPdfDue(this@MainActivity, ms)
            if (ms > 0L) runOnUiThread { ensurePermissions() }
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

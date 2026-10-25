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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewAssetLoader
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

    private val folderLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val uri = r.data?.data
            if (r.resultCode != RESULT_OK || uri == null) {
                js("window.onBackupFolder&&window.onBackupFolder(false,'cancel','')")
            } else if (uri.authority?.startsWith("com.google.android.apps.docs") != true) {
                js("window.onBackupFolder&&window.onBackupFolder(false,'notdrive','')")
            } else {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    val name = Backup.folderName(this, uri)
                    Backup.setFolder(this, uri, name)
                    js("window.onBackupFolder&&window.onBackupFolder(true,'ok',${JSONObject.quote(name)})")
                } catch (e: Exception) {
                    js("window.onBackupFolder&&window.onBackupFolder(false,'error','')")
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
        fun pickBackupFolder() {
            runOnUiThread {
                try {
                    folderLauncher.launch(
                        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                        )
                    )
                } catch (e: Exception) {
                    js("window.onBackupFolder&&window.onBackupFolder(false,'error','')")
                }
            }
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

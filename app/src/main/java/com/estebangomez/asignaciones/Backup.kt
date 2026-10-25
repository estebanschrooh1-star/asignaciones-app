package com.estebangomez.asignaciones

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Copia de seguridad automática en una carpeta de Google Drive elegida por la persona
 * (selector de carpetas de Android). La app no necesita permiso de internet: quien sube
 * el archivo a la nube es la propia app de Google Drive.
 */
object Backup {
    const val OK = 0
    const val RETRY = 1
    const val LOST = 2
    private const val PREFIX = "asignaciones-respaldo-"
    private const val WORK = "auto_backup"

    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("bk", Context.MODE_PRIVATE)
    private fun snapshot(c: Context) = File(c.filesDir, "snapshot.txt")

    fun saveSnapshot(c: Context, text: String) {
        snapshot(c).writeText(text)
    }

    fun setFolder(c: Context, uri: Uri, name: String) {
        prefs(c).edit().putString("uri", uri.toString()).putString("name", name)
            .remove("err").remove("last").putBoolean("lost", false).apply()
    }

    fun folderName(c: Context, tree: Uri): String = try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        c.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Google Drive"
    } catch (e: Exception) {
        "Google Drive"
    }

    /** La cuenta cuenta el tiempo desde que se activa: la primera copia es dentro de un período completo. */
    fun schedule(c: Context, freq: String) {
        val hours = when (freq) {
            "day" -> 24L
            "week" -> 24L * 7
            else -> 24L * 30
        }
        val req = PeriodicWorkRequestBuilder<BackupWorker>(hours, TimeUnit.HOURS)
            .setInitialDelay(hours, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(c)
            .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, req)
        prefs(c).edit().putString("freq", freq).apply()
    }

    fun clear(c: Context) {
        WorkManager.getInstance(c).cancelUniqueWork(WORK)
        val uri = prefs(c).getString("uri", null)
        if (uri != null) {
            try {
                c.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
            }
        }
        prefs(c).edit().clear().apply()
        snapshot(c).delete()
    }

    fun status(c: Context): String {
        val p = prefs(c)
        val uriS = p.getString("uri", null)
        var lost = p.getBoolean("lost", false) || uriS == null
        if (!lost) {
            val t = Uri.parse(uriS ?: "")
            lost = c.contentResolver.persistedUriPermissions.none { it.uri == t && it.isWritePermission }
        }
        val o = JSONObject()
        o.put("lost", lost)
        o.put("last", p.getLong("last", 0L))
        o.put("err", p.getString("err", "") ?: "")
        o.put("folder", p.getString("name", "") ?: "")
        return o.toString()
    }

    fun run(c: Context): Int {
        val p = prefs(c)
        val uriS = p.getString("uri", null) ?: return LOST
        val tree = Uri.parse(uriS)
        if (c.contentResolver.persistedUriPermissions.none { it.uri == tree && it.isWritePermission }) {
            p.edit().putBoolean("lost", true).apply()
            WorkManager.getInstance(c).cancelUniqueWork(WORK)
            return LOST
        }
        val f = snapshot(c)
        if (!f.exists()) {
            p.edit().putString("err", "Todavía no hay datos para respaldar").apply()
            return OK
        }
        return try {
            val cr = c.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val name = PREFIX + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date()) + ".txt"
            val doc = DocumentsContract.createDocument(cr, parent, "text/plain", name)
            if (doc == null) {
                p.edit().putString("err", "No se pudo crear el archivo").apply()
                RETRY
            } else {
                val out = cr.openOutputStream(doc, "w")
                if (out == null) {
                    RETRY
                } else {
                    out.use { it.write(f.readBytes()) }
                    p.edit().putLong("last", System.currentTimeMillis()).remove("err").apply()
                    prune(c, tree)
                    OK
                }
            }
        } catch (e: SecurityException) {
            p.edit().putBoolean("lost", true).apply()
            LOST
        } catch (e: Exception) {
            p.edit().putString("err", e.message ?: "Error").apply()
            RETRY
        }
    }

    /** Conserva solo las últimas 12 copias de la app en la carpeta. */
    private fun prune(c: Context, tree: Uri) {
        try {
            val cr = c.contentResolver
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val found = mutableListOf<Pair<String, String>>()
            cr.query(
                kids,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    val n = it.getString(1) ?: continue
                    if (n.startsWith(PREFIX) && n.endsWith(".txt")) found.add(Pair(n, id))
                }
            }
            found.sortByDescending { it.first }
            found.drop(12).forEach {
                DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, it.second))
            }
        } catch (e: Exception) {
        }
    }
}

class BackupWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result =
        if (Backup.run(applicationContext) == Backup.RETRY) Result.retry() else Result.success()
}

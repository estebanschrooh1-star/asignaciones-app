package com.estebangomez.asignaciones

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Acceso mínimo a Google Drive (API REST). Usa el permiso "drive.file": la app solo ve
 * los archivos y carpetas que ella misma creó, nunca el resto del Drive.
 */
object Drive {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val FOLDER = "Asignaciones"
    const val PREFIX = "asignaciones-respaldo-"

    class NeedsConsent : Exception()

    class Http(val code: Int, val body: String) : Exception("HTTP $code") {
        fun brief(): String = try {
            JSONObject(body).getJSONObject("error").getString("message")
        } catch (e: Exception) {
            "Error $code"
        }
    }

    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Token sin pantallas: solo funciona si la persona ya dio permiso. Se usa en segundo plano. */
    fun silentToken(c: Context): String {
        val r = Tasks.await(Identity.getAuthorizationClient(c).authorize(request()), 30, TimeUnit.SECONDS)
        if (r.hasResolution()) throw NeedsConsent()
        return r.accessToken ?: throw NeedsConsent()
    }

    private fun call(method: String, url: String, token: String, body: ByteArray? = null, type: String? = null): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20000
            conn.readTimeout = 40000
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", type ?: "application/json")
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val data = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) throw Http(code, String(data))
            return data
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Busca la carpeta "Asignaciones" y, si no existe, la crea. Sin ninguna pantalla. */
    fun folderId(c: Context, token: String): String {
        val p = c.getSharedPreferences("bk", Context.MODE_PRIVATE)
        val cached = p.getString("folderId", null)
        if (cached != null) {
            try {
                val o = JSONObject(String(call("GET", "https://www.googleapis.com/drive/v3/files/$cached?fields=id,trashed", token)))
                if (!o.optBoolean("trashed", false)) return cached
            } catch (e: Http) {
                if (e.code != 404) throw e
            }
        }
        val q = enc("name='$FOLDER' and mimeType='application/vnd.google-apps.folder' and trashed=false")
        val found = JSONObject(String(call("GET", "https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id)&pageSize=1", token))).getJSONArray("files")
        val id = if (found.length() > 0) {
            found.getJSONObject(0).getString("id")
        } else {
            val meta = JSONObject().put("name", FOLDER).put("mimeType", "application/vnd.google-apps.folder").toString().toByteArray()
            JSONObject(String(call("POST", "https://www.googleapis.com/drive/v3/files?fields=id", token, meta, "application/json"))).getString("id")
        }
        p.edit().putString("folderId", id).apply()
        return id
    }

    /** Copias que ya hay en la carpeta, de la más nueva a la más vieja: [{id, name, t}]. */
    fun list(token: String, folder: String): JSONArray {
        val q = enc("'$folder' in parents and trashed=false and name contains '$PREFIX'")
        val url = "https://www.googleapis.com/drive/v3/files?q=$q&orderBy=createdTime%20desc&fields=files(id,name,createdTime)&pageSize=50"
        val arr = JSONObject(String(call("GET", url, token))).getJSONArray("files")
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = try { Instant.parse(o.getString("createdTime")).toEpochMilli() } catch (e: Exception) { 0L }
            out.put(JSONObject().put("id", o.getString("id")).put("name", o.getString("name")).put("t", t))
        }
        return out
    }

    fun upload(token: String, folder: String, name: String, text: String) {
        val b = "asig" + System.currentTimeMillis()
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(folder)).toString()
        val body = ("--$b\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$b\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n$text\r\n--$b--").toByteArray()
        call("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", token, body, "multipart/related; boundary=$b")
    }

    fun download(token: String, id: String): String =
        String(call("GET", "https://www.googleapis.com/drive/v3/files/$id?alt=media", token))

    fun delete(token: String, id: String) {
        call("DELETE", "https://www.googleapis.com/drive/v3/files/$id", token)
    }

    fun email(token: String): String = try {
        JSONObject(String(call("GET", "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)", token)))
            .getJSONObject("user").optString("emailAddress", "")
    } catch (e: Exception) {
        ""
    }
}

object Backup {
    const val OK = 0
    const val RETRY = 1
    const val LOST = 2
    private const val WORK = "auto_backup"

    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("bk", Context.MODE_PRIVATE)
    private fun snapshot(c: Context) = File(c.filesDir, "snapshot.txt")

    fun saveSnapshot(c: Context, text: String) {
        snapshot(c).writeText(text)
    }

    fun markConnected(c: Context, email: String) {
        prefs(c).edit().putBoolean("on", true).putBoolean("lost", false).putString("email", email)
            .putString("name", Drive.FOLDER).remove("err").remove("last").apply()
    }

    /** Se cuenta el tiempo desde que se activa: la primera copia es dentro de un período completo. */
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
        prefs(c).edit().clear().apply()
        snapshot(c).delete()
    }

    fun status(c: Context): String {
        val p = prefs(c)
        val o = JSONObject()
        o.put("lost", !p.getBoolean("on", false) || p.getBoolean("lost", false))
        o.put("last", p.getLong("last", 0L))
        o.put("err", p.getString("err", "") ?: "")
        o.put("folder", p.getString("name", "") ?: "")
        o.put("email", p.getString("email", "") ?: "")
        return o.toString()
    }

    private fun lost(c: Context): Int {
        prefs(c).edit().putBoolean("lost", true).apply()
        WorkManager.getInstance(c).cancelUniqueWork(WORK)
        return LOST
    }

    fun run(c: Context): Int {
        val p = prefs(c)
        if (!p.getBoolean("on", false)) return LOST
        val f = snapshot(c)
        if (!f.exists()) {
            p.edit().putString("err", "Todavía no hay datos para respaldar").apply()
            return OK
        }
        return try {
            val token = Drive.silentToken(c)
            val folder = Drive.folderId(c, token)
            val name = Drive.PREFIX + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date()) + ".txt"
            Drive.upload(token, folder, name, f.readText())
            p.edit().putLong("last", System.currentTimeMillis()).remove("err").apply()
            prune(token, folder)
            OK
        } catch (e: Drive.NeedsConsent) {
            lost(c)
        } catch (e: Drive.Http) {
            p.edit().putString("err", e.brief()).apply()
            RETRY
        } catch (e: ExecutionException) {
            p.edit().putString("err", "Sin conexión con Google").apply()
            RETRY
        } catch (e: IOException) {
            p.edit().putString("err", "Sin conexión a internet").apply()
            RETRY
        } catch (e: Exception) {
            p.edit().putString("err", e.message ?: "Error").apply()
            RETRY
        }
    }

    /** Conserva solo las últimas 12 copias de la app en la carpeta. */
    private fun prune(token: String, folder: String) {
        try {
            val all = Drive.list(token, folder)
            for (i in 12 until all.length()) {
                try { Drive.delete(token, all.getJSONObject(i).getString("id")) } catch (e: Exception) { }
            }
        } catch (e: Exception) {
        }
    }
}

class BackupWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result =
        if (Backup.run(applicationContext) == Backup.RETRY) Result.retry() else Result.success()
}

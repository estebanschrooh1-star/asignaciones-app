package com.estebangomez.asignaciones

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File

/** Guarda una sola planilla PDF dentro de la app; subir otra reemplaza la anterior. */
object PdfStore {
    private fun dir(c: Context) = File(c.filesDir, "pdf").also { it.mkdirs() }
    fun file(c: Context) = File(dir(c), "planilla.pdf")
    private fun prefs(c: Context) = c.getSharedPreferences("pdf", Context.MODE_PRIVATE)

    private fun displayName(c: Context, uri: Uri): String = try {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "planilla.pdf"
    } catch (e: Exception) {
        "planilla.pdf"
    }

    fun save(c: Context, uri: Uri, fresh: Boolean): Boolean {
        return try {
            val tmp = File(dir(c), "tmp.pdf")
            val input = c.contentResolver.openInputStream(uri) ?: return false
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            val head = ByteArray(5)
            val n = tmp.inputStream().use { it.read(head) }
            if (n != 5 || String(head, Charsets.ISO_8859_1) != "%PDF-") {
                tmp.delete()
                return false
            }
            val dest = file(c)
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return false
            }
            prefs(c).edit().putString("name", displayName(c, uri)).putBoolean("fresh", fresh).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun info(c: Context): String {
        val f = file(c)
        val o = JSONObject()
        o.put("has", f.exists())
        o.put("name", prefs(c).getString("name", "planilla.pdf") ?: "planilla.pdf")
        o.put("t", if (f.exists()) f.lastModified() else 0L)
        o.put("fresh", prefs(c).getBoolean("fresh", false))
        return o.toString()
    }

    fun ack(c: Context) {
        prefs(c).edit().putBoolean("fresh", false).apply()
    }

    fun delete(c: Context) {
        file(c).delete()
        prefs(c).edit().clear().apply()
    }
}

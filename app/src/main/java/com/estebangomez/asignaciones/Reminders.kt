package com.estebangomez.asignaciones

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import org.json.JSONArray
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

object Reminders {
    private const val PREFS = "rem"
    const val DUE = "pdfdue"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pending(c: Context, key: String, d: Int, h: Int, m: Int): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java)
            .putExtra("key", key).putExtra("d", d).putExtra("h", h).putExtra("m", m)
        return PendingIntent.getBroadcast(
            c, key.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Guarda la lista de recordatorios (JSON) y el nombre de usuario, y reprograma las alarmas. */
    fun save(c: Context, json: String, user: String) {
        prefs(c).edit().putString("json", json).putString("user", user).apply()
        rescheduleAll(c)
    }

    fun rescheduleAll(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val p = prefs(c)
        p.getStringSet("keys", emptySet())?.forEach { am.cancel(pending(c, it, 0, 0, 0)) }
        val keys = mutableSetOf<String>()
        try {
            val arr = JSONArray(p.getString("json", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = o.getString("time").split(":")
                val h = t[0].toInt()
                val m = t[1].toInt()
                val ds = o.getJSONArray("days")
                for (j in 0 until ds.length()) {
                    val d = ds.getInt(j)
                    val key = o.getString("id") + "|" + d + "|" + h + ":" + m
                    keys.add(key)
                    schedule(c, key, d, h, m)
                }
            }
        } catch (e: Exception) {
            // JSON inválido: no se programa nada
        }
        p.edit().putStringSet("keys", keys).apply()
        scheduleDue(c)
    }

    private fun duePending(c: Context): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java).putExtra("key", DUE)
        return PendingIntent.getBroadcast(
            c, DUE.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Aviso único para cambiar la planilla PDF (at = momento exacto en milisegundos; 0 = sin aviso). */
    fun setPdfDue(c: Context, at: Long) {
        prefs(c).edit().putLong("due", at).apply()
        scheduleDue(c)
    }

    fun scheduleDue(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = duePending(c)
        am.cancel(pi)
        val at = prefs(c).getLong("due", 0L)
        if (at <= System.currentTimeMillis()) return
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun notifyDue(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("asig", "Recordatorios", NotificationManager.IMPORTANCE_HIGH)
        )
        val user = prefs(c).getString("user", "") ?: ""
        val title = SpannableStringBuilder()
        if (user.isNotBlank()) {
            title.append(user)
            title.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, 0)
            title.append(", recuerda cambiar la planilla PDF ⏰")
        } else {
            title.append("Recuerda cambiar la planilla PDF ⏰")
        }
        val open = PendingIntent.getActivity(
            c, 0,
            Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(c, "asig")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText("Hoy termina la última semana de la planilla. Tocá para subir una nueva.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(2, n)
    }

    fun schedule(c: Context, key: String, d: Int, h: Int, m: Int) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = ZonedDateTime.now()
        var t = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(d))).with(LocalTime.of(h, m))
        if (!t.isAfter(now)) t = t.plusWeeks(1)
        val at = t.toInstant().toEpochMilli()
        val pi = pending(c, key, d, h, m)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun notifyNow(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("asig", "Recordatorios", NotificationManager.IMPORTANCE_HIGH)
        )
        val user = prefs(c).getString("user", "") ?: ""
        val title = SpannableStringBuilder()
        if (user.isNotBlank()) {
            title.append(user)
            title.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, 0)
            title.append(", recuerda enviar las asignaciones ⏰")
        } else {
            title.append("Recuerda enviar las asignaciones ⏰")
        }
        val open = PendingIntent.getActivity(
            c, 0,
            Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(c, "asig")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText("Tocá para armar el mensaje")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(1, n)
    }

    fun hasKey(c: Context, key: String): Boolean =
        prefs(c).getStringSet("keys", emptySet())?.contains(key) == true
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        if (key == Reminders.DUE) {
            Reminders.notifyDue(context)
            return
        }
        if (!Reminders.hasKey(context, key)) return
        Reminders.schedule(
            context, key,
            intent.getIntExtra("d", 1), intent.getIntExtra("h", 0), intent.getIntExtra("m", 0)
        )
        Reminders.notifyNow(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.rescheduleAll(context)
    }
}

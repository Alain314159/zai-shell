package com.zai.shell.plugin

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.zai.shell.MainActivity
import com.zai.shell.R

/**
 * ZaiWidgetProvider - widget de pantalla de inicio con input directo a Z.ai
 *
 * Muestra un campo de texto y un boton "Enviar". Al pulsar, abre la app
 * con el texto precargado y lo envia automaticamente a Z.ai.
 */
class ZaiWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_SEND_PROMPT = "com.zai.shell.action.SEND_PROMPT"
        const val EXTRA_PROMPT = "extra_prompt"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_zai)

        // Click en el widget abre la app
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context, widgetId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openPending)
        views.setOnClickPendingIntent(R.id.widget_input, openPending)
        views.setOnClickPendingIntent(R.id.widget_send_button, openPending)

        appWidgetManager.updateAppWidget(widgetId, views)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_SEND_PROMPT) {
            val prompt = intent.getStringExtra(EXTRA_PROMPT) ?: ""
            if (prompt.isNotEmpty()) {
                // Abrir MainActivity con el prompt
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    action = Intent.ACTION_SEND
                    putExtra(EXTRA_PROMPT, prompt)
                }
                context.startActivity(launchIntent)
            }
        }
    }
}

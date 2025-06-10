package com.forensics.digitalinvestigationagent.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.forensics.digitalinvestigationagent.R

class ForensicCollectionService : Service() {
    private val channelId = "ForensicCollectionChannel"
    private val notificationId = 101
    private val tag = "ForensicService"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "Service créé")
        createNotificationChannel()
        startForeground(notificationId, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "Commande de démarrage reçue")

        Thread {
            try {
                collectData()
            } catch (e: Exception) {
                Log.e(tag, "Erreur lors de la collecte", e)
            }
        }.start()

        return START_STICKY
    }

    private fun collectData() {
        Log.d(tag, "Début de la collecte des données")
        // Implémentez ici la logique de collecte
        // Exemple : collectSms(), collectCallLogs(), etc.
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Collecte Forensique",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification pour la collecte de données forensiques"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Collecte en cours")
            .setContentText("Collecte des données forensiques")
            .setSmallIcon(R.drawable.ic_forensic_icon)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
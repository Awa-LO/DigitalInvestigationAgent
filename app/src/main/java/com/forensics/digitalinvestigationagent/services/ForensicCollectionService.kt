package com.forensics.digitalinvestigationagent.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.io.IOException
import java.io.FileOutputStream
import java.io.InputStream

class ForensicCollectionService : Service() {
    private val channelId = "ForensicCollectionChannel"
    private val notificationId = 102

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var collectionJob: Job? = null

    // État de la collecte
    private var isCollecting = false
    private var shouldStop = false

    // Résultats de collecte
    private var smsCount = 0
    private var callLogCount = 0
    private var contactsCount = 0
    private var imagesCount = 0
    private var videosCount = 0
    private var audioCount = 0

    // Données collectées
    private var smsData = JSONArray()
    private var callLogData = JSONArray()
    private var contactsData = JSONArray()
    private var imagesData = JSONArray()
    private var videosData = JSONArray()
    private var audioData = JSONArray()

    // Chemin de sauvegarde
    private var saveDirectory: File? = null
    private var sessionFolder: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupSaveDirectory()
        startForeground(notificationId, createNotification("Service de collecte démarré"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_COLLECTION" -> {
                if (!isCollecting) {
                    startDataCollection()
                }
            }
            "STOP_COLLECTION" -> {
                stopDataCollection()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun setupSaveDirectory() {
        try {
            // Essayer d'abord le dossier Downloads public
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            saveDirectory = File(downloadsDir, "ForensicData")

            if (!saveDirectory!!.exists()) {
                val created = saveDirectory!!.mkdirs()
                Log.d("ForensicService", "Dossier Downloads créé: $created - ${saveDirectory!!.absolutePath}")
            }

            // Vérifier l'accès en écriture
            if (saveDirectory!!.canWrite()) {
                Log.d("ForensicService", "Dossier Downloads accessible: ${saveDirectory!!.absolutePath}")
            } else {
                Log.w("ForensicService", "Dossier Downloads non accessible, utilisation du stockage interne")
                // Fallback vers le stockage interne
                saveDirectory = File(filesDir, "ForensicData")
                if (!saveDirectory!!.exists()) {
                    saveDirectory!!.mkdirs()
                }
            }

        } catch (e: Exception) {
            Log.e("ForensicService", "Erreur setup dossier: ${e.message}")
            // Fallback final vers le cache interne
            saveDirectory = File(cacheDir, "ForensicData")
            if (!saveDirectory!!.exists()) {
                saveDirectory!!.mkdirs()
            }
        }
    }

    private fun startDataCollection() {
        if (isCollecting) return

        isCollecting = true
        shouldStop = false

        // Réinitialiser les compteurs et données
        resetCollectionData()

        // Créer un nouveau dossier de session
        createSessionFolder()

        collectionJob = serviceScope.launch {
            try {
                updateNotification("Collecte en cours...")
                sendBroadcast("Démarrage de la collecte de données")

                // Collecte des données en parallèle
                val jobs = listOf(
                    async { collectSMS() },
                    async { collectCallLogs() },
                    async { collectContacts() },
                    async { collectImages() },
                    async { collectVideos() },
                    async { collectAudio() }
                )

                // Attendre la fin de toutes les collectes
                jobs.awaitAll()

                if (!shouldStop) {
                    // Sauvegarder toutes les données
                    saveAllData()

                    sendBroadcastComplete("Collecte terminée avec succès", sessionFolder?.absolutePath)
                    updateNotification("Collecte terminée - ${getTotalCount()} éléments collectés")
                } else {
                    sendBroadcastStopped("Collecte arrêtée par l'utilisateur", sessionFolder?.absolutePath)
                    updateNotification("Collecte arrêtée")
                }

            } catch (e: Exception) {
                Log.e("ForensicService", "Erreur lors de la collecte: ${e.message}")
                sendBroadcast("Erreur lors de la collecte: ${e.message}")
            } finally {
                isCollecting = false
            }
        }
    }

    private fun stopDataCollection() {
        shouldStop = true
        collectionJob?.cancel()
        isCollecting = false

        // Sauvegarder les données partielles
        if (getTotalCount() > 0) {
            serviceScope.launch {
                saveAllData()
            }
        }

        stopSelf()
    }

    private fun resetCollectionData() {
        smsCount = 0
        callLogCount = 0
        contactsCount = 0
        imagesCount = 0
        videosCount = 0
        audioCount = 0

        smsData = JSONArray()
        callLogData = JSONArray()
        contactsData = JSONArray()
        imagesData = JSONArray()
        videosData = JSONArray()
        audioData = JSONArray()
    }

    private fun createSessionFolder() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        sessionFolder = File(saveDirectory, "Session_$timestamp")

        if (!sessionFolder!!.exists()) {
            sessionFolder!!.mkdirs()
        }

        // Créer les sous-dossiers
        File(sessionFolder, "SMS").mkdirs()
        File(sessionFolder, "CallLogs").mkdirs()
        File(sessionFolder, "Contacts").mkdirs()
        File(sessionFolder, "Images").mkdirs()
        File(sessionFolder, "Videos").mkdirs()
        File(sessionFolder, "Audio").mkdirs()
    }

    private suspend fun collectSMS() {
        if (!hasPermission(android.Manifest.permission.READ_SMS)) return

        try {
            val cursor = contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                null, null, null,
                "${Telephony.Sms.DATE} DESC"
            )

            cursor?.use {
                while (it.moveToNext() && !shouldStop) {
                    val sms = JSONObject().apply {
                        put("id", it.getLong(it.getColumnIndexOrThrow(Telephony.Sms._ID)))
                        put("address", it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "")
                        put("body", it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: "")
                        put("date", it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE)))
                        put("type", it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.TYPE)))
                        put("read", it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.READ)))
                        put("date_formatted", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(Date(it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE)))))
                    }

                    smsData.put(sms)
                    smsCount++

                    // Envoyer mise à jour toutes les 10 entrées
                    if (smsCount % 10 == 0) {
                        sendBroadcastUpdate("Collecte SMS: $smsCount")
                        delay(10) // Petite pause pour éviter la surcharge
                    }
                }
            }

            Log.d("ForensicService", "SMS collectés: $smsCount")

        } catch (e: Exception) {
            Log.e("ForensicService", "Erreur collecte SMS: ${e.message}")
        }
    }

    private suspend fun collectCallLogs() {
        if (!hasPermission(android.Manifest.permission.READ_CALL_LOG)) return

        try {
            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                null, null, null,
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use {
                while (it.moveToNext() && !shouldStop) {
                    val call = JSONObject().apply {
                        put("id", it.getLong(it.getColumnIndexOrThrow(CallLog.Calls._ID)))
                        put("number", it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: "")
                        put("name", it.getString(it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)) ?: "")
                        put("date", it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE)))
                        put("duration", it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION)))
                        put("type", it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE)))
                        put("date_formatted", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(Date(it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE)))))
                    }

                    callLogData.put(call)
                    callLogCount++

                    if (callLogCount % 10 == 0) {
                        sendBroadcastUpdate("Collecte Appels: $callLogCount")
                        delay(10)
                    }
                }
            }

            Log.d("ForensicService", "Appels collectés: $callLogCount")

        } catch (e: Exception) {
            Log.e("ForensicService", "Erreur collecte appels: ${e.message}")
        }
    }

    private suspend fun collectContacts() {
        if (!hasPermission(android.Manifest.permission.READ_CONTACTS)) return

        try {
            val cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null, null
            )

            cursor?.use {
                while (it.moveToNext() && !shouldStop) {
                    val contact = JSONObject().apply {
                        put("id", it.getLong(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)))
                        put("name", it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)) ?: "")
                        put("phone", it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: "")
                        put("type", it.getInt(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)))
                    }

                    contactsData.put(contact)
                    contactsCount++

                    if (contactsCount % 10 == 0) {
                        sendBroadcastUpdate("Collecte Contacts: $contactsCount")
                        delay(10)
                    }
                }
            }

            Log.d("ForensicService", "Contacts collectés: $contactsCount")

        } catch (e: Exception) {
            Log.e("ForensicService", "Erreur collecte contacts: ${e.message}")
        }
    }

    private suspend fun collectImages() {
        if (!hasMediaPermission()) return

        try {
            val cursor = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_ADDED
                ),
                null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )

            cursor?.use {
                while (it.moveToNext() && !shouldStop) {
                    val image = JSONObject().apply {
                        put("id", it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)))
                        put("name", it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: "")
                        put("path", it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)) ?: "")
                        put("size", it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)))
                        put("date_added", it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)))
                    }

                    imagesData.put(image)
                    imagesCount++

                    if (imagesCount % 20 == 0) {
                        sendBroadcastUpdate("Collecte Images: $imagesCount")
                        delay(10)
                    }
                }
            }

            Log.d("ForensicService", "Images collectées: $imagesCount")

        } catch (e: Exception) {
            Log.e("ForensicService", "Erreur collecte images: ${e.message}")
        }
    }

    private suspend fun collectVideos() {
        if (!hasMediaPermission()) return

        try {
            val cursor = contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.DATE_ADDED
                ),
                null, null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )

            cursor?.use {
                while (it.moveToNext() && !shouldStop) {
                    val video = JSONObject().apply {
                        put("id", it.getLong(it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)))
                        put("name", it.getString(it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)) ?: "")
                        put("path", it.getString(it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)) ?: "")
                        put("size", it.getLong(it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)))
                        put("duration", it.getLong(it.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)))
                        put("date_added", it.getLong(it.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)))
                    }

                    videosData.put(video)
                    videosCount++

                    if (videosCount % 10 == 0) {
                        sendBroadcastUpdate("Collecte Vidéos: $videosCount")
                        delay(10)
                    }
                }
            }

            Log.d("ForensicService", "Vidéos collectées: $videosCount")

        } catch (e: Exception) {
            Log.e("ForensicService", "Erreur collecte vidéos: ${e.message}")
        }
    }

    private suspend fun collectAudio() {
        if (!hasMediaPermission()) return

        try {
            val cursor = contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DATE_ADDED
                ),
                null, null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )

            cursor?.use {
                while (it.moveToNext() && !shouldStop) {
                    val audio = JSONObject().apply {
                        put("id", it.getLong(it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)))
                        put("name", it.getString(it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)) ?: "")
                        put("path", it.getString(it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)) ?: "")
                        put("size", it.getLong(it.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)))
                        put("duration", it.getLong(it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)))
                        put("artist", it.getString(it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)) ?: "")
                        put("date_added", it.getLong(it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)))
                    }

                    audioData.put(audio)
                    audioCount++

                    if (audioCount % 20 == 0) {
                        sendBroadcastUpdate("Collecte Audio: $audioCount")
                        delay(10)
                    }
                }
            }

            Log.d("ForensicService", "Audio collectés: $audioCount")

        } catch (e: Exception) {
            Log.e("ForensicService", "Erreur collecte audio: ${e.message}")
        }
    }

    private suspend fun saveAllData() {
        if (sessionFolder == null) return

        try {
            // Sauvegarder SMS
            if (smsData.length() > 0) {
                saveJsonToFile(smsData, File(sessionFolder, "SMS/sms_data.json"))
            }

            // Sauvegarder appels
            if (callLogData.length() > 0) {
                saveJsonToFile(callLogData, File(sessionFolder, "CallLogs/call_logs.json"))
            }

            // Sauvegarder contacts
            if (contactsData.length() > 0) {
                saveJsonToFile(contactsData, File(sessionFolder, "Contacts/contacts.json"))
            }

            // Sauvegarder liste des images
            if (imagesData.length() > 0) {
                saveJsonToFile(imagesData, File(sessionFolder, "Images/images_list.json"))
            }

            // Sauvegarder liste des vidéos
            if (videosData.length() > 0) {
                saveJsonToFile(videosData, File(sessionFolder, "Videos/videos_list.json"))
            }

            // Sauvegarder liste audio
            if (audioData.length() > 0) {
                saveJsonToFile(audioData, File(sessionFolder, "Audio/audio_list.json"))
            }

            // Créer un rapport de synthèse
            createSummaryReport()

            Log.d("ForensicService", "Toutes les données sauvegardées dans: ${sessionFolder!!.absolutePath}")

        } catch (e: Exception) {
            Log.e("ForensicService", "Erreur sauvegarde: ${e.message}")
        }
    }

    private fun saveJsonToFile(jsonArray: JSONArray, file: File) {
        try {
            FileWriter(file).use { writer ->
                writer.write(jsonArray.toString(2)) // Indentation pour lisibilité
            }
        } catch (e: IOException) {
            Log.e("ForensicService", "Erreur écriture fichier ${file.absolutePath}: ${e.message}")
        }
    }

    private fun createSummaryReport() {
        val report = JSONObject().apply {
            put("collection_date", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            put("total_items", getTotalCount())
            put("sms_count", smsCount)
            put("call_logs_count", callLogCount)
            put("contacts_count", contactsCount)
            put("images_count", imagesCount)
            put("videos_count", videosCount)
            put("audio_count", audioCount)
            put("device_info", JSONObject().apply {
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("android_version", Build.VERSION.RELEASE)
                put("api_level", Build.VERSION.SDK_INT)
            })
        }

        val reportFile = File(sessionFolder, "collection_report.json")
        saveJsonToFile(JSONArray().put(report), reportFile)
    }

    private fun getTotalCount(): Int {
        return smsCount + callLogCount + contactsCount + imagesCount + videosCount + audioCount
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(android.Manifest.permission.READ_MEDIA_IMAGES) ||
                    hasPermission(android.Manifest.permission.READ_MEDIA_VIDEO) ||
                    hasPermission(android.Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            hasPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun sendBroadcast(status: String) {
        val intent = Intent("DATA_COLLECTION_UPDATE").apply {
            putExtra("status", status)
            putExtra("sms_count", smsCount)
            putExtra("call_count", callLogCount)
            putExtra("contact_count", contactsCount)
            putExtra("image_count", imagesCount)
            putExtra("video_count", videosCount)
            putExtra("audio_count", audioCount)
        }
        sendBroadcast(intent)
    }

    private fun sendBroadcastUpdate(status: String) {
        sendBroadcast(status)
    }

    private fun sendBroadcastComplete(status: String, savePath: String?) {
        val intent = Intent("DATA_COLLECTION_COMPLETE").apply {
            putExtra("status", status)
            putExtra("sms_count", smsCount)
            putExtra("call_count", callLogCount)
            putExtra("contact_count", contactsCount)
            putExtra("image_count", imagesCount)
            putExtra("video_count", videosCount)
            putExtra("audio_count", audioCount)
            putExtra("save_path", savePath)
        }
        sendBroadcast(intent)
    }

    private fun sendBroadcastStopped(status: String, savePath: String?) {
        val intent = Intent("DATA_COLLECTION_STOPPED").apply {
            putExtra("status", status)
            putExtra("sms_count", smsCount)
            putExtra("call_count", callLogCount)
            putExtra("contact_count", contactsCount)
            putExtra("image_count", imagesCount)
            putExtra("video_count", videosCount)
            putExtra("audio_count", audioCount)
            putExtra("save_path", savePath)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Collecte de données forensiques",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications pour la collecte de données"
                setSound(null, null)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Collecte Forensique")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(notificationId, createNotification(content))
    }
}
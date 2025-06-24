package com.forensics.digitalinvestigationagent.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AuthService(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "ForensicAuthPrefs"
        private const val TOKEN_KEY = "auth_token"
        private const val USER_KEY = "username"

        // Configuration avec votre IP locale - Mise à jour pour correspondre à settings.py
        private const val BASE_URL = "http://192.168.137.9:8000"
        private const val AUTH_ENDPOINT = "/api-token-auth/"  // Endpoint standard Django REST
        private const val UPLOAD_ENDPOINT = "/api/v1/upload-session/"  // Nouveau endpoint adapté

        private const val CONNECT_TIMEOUT = 30000
        private const val READ_TIMEOUT = 60000
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun login(username: String, password: String): AuthResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("AuthService", "=== DÉBUT LOGIN ===")
                Log.d("AuthService", "URL: ${BASE_URL}${AUTH_ENDPOINT}")
                Log.d("AuthService", "Username: $username")

                val url = URL(BASE_URL + AUTH_ENDPOINT)
                val conn = url.openConnection() as HttpURLConnection

                conn.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    doOutput = true
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT
                }

                val jsonBody = JSONObject().apply {
                    put("username", username)
                    put("password", password)
                }

                Log.d("AuthService", "JSON envoyé: $jsonBody")

                DataOutputStream(conn.outputStream).use { output ->
                    output.writeBytes(jsonBody.toString())
                }

                val responseCode = conn.responseCode
                Log.d("AuthService", "Code de réponse: $responseCode")

                val response = if (responseCode == HttpURLConnection.HTTP_OK) {
                    readStream(conn.inputStream)
                } else {
                    readStream(conn.errorStream)
                }

                Log.d("AuthService", "Réponse complète: $response")

                when (responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val jsonResponse = JSONObject(response)
                        val token = jsonResponse.getString("token")
                        Log.d("AuthService", "Token reçu: ${token.take(10)}...")
                        saveCredentials(token, username)
                        AuthResult(true, token, "Connexion réussie")
                    }
                    HttpURLConnection.HTTP_BAD_REQUEST -> {
                        val errorMsg = try {
                            val jsonError = JSONObject(response)
                            jsonError.optString("non_field_errors", "Identifiants incorrects")
                        } catch (e: Exception) {
                            "Identifiants incorrects"
                        }
                        Log.e("AuthService", "Erreur 400: $errorMsg")
                        AuthResult(false, null, errorMsg)
                    }
                    else -> {
                        val errorMsg = try {
                            JSONObject(response).optString("detail", "Erreur d'authentification")
                        } catch (e: Exception) {
                            "Erreur d'authentification (Code: $responseCode)"
                        }
                        Log.e("AuthService", "Erreur $responseCode: $errorMsg")
                        AuthResult(false, null, errorMsg)
                    }
                }
            } catch (e: Exception) {
                Log.e("AuthService", "Exception dans login:", e)
                AuthResult(false, null, "Erreur réseau: ${e.message}")
            }
        }
    }

    suspend fun uploadSession(sessionFolder: File): UploadResult {
        val token = getToken() ?: return UploadResult(false, "Non authentifié")

        return withContext(Dispatchers.IO) {
            try {
                Log.d("AuthService", "Création ZIP pour: ${sessionFolder.name}")
                val zipFile = createZip(sessionFolder)

                if (!zipFile.exists()) {
                    Log.e("AuthService", "Échec création ZIP")
                    return@withContext UploadResult(false, "Échec création ZIP")
                }

                Log.d("AuthService", "ZIP créé: ${zipFile.name} (${zipFile.length()} bytes)")
                Log.d("AuthService", "Upload vers: ${BASE_URL}${UPLOAD_ENDPOINT}")

                val url = URL(BASE_URL + UPLOAD_ENDPOINT)
                val conn = url.openConnection() as HttpURLConnection
                val boundary = "----FormBoundary${UUID.randomUUID()}"

                conn.apply {
                    requestMethod = "POST"
                    setRequestProperty("Authorization", "Token $token")
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    doOutput = true
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT * 3  // Plus de temps pour l'upload
                }

                DataOutputStream(conn.outputStream).use { output ->
                    // En-tête multipart
                    output.writeBytes("--$boundary\r\n")
                    output.writeBytes("Content-Disposition: form-data; name=\"data_file\"; filename=\"${zipFile.name}\"\r\n")
                    output.writeBytes("Content-Type: application/zip\r\n")
                    output.writeBytes("\r\n")

                    // Contenu du fichier
                    FileInputStream(zipFile).use { input ->
                        Log.d("AuthService", "Début envoi fichier...")
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytes = 0

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                        }

                        Log.d("AuthService", "Fichier envoyé: $totalBytes bytes")
                    }

                    // Fin multipart
                    output.writeBytes("\r\n--$boundary--\r\n")
                    output.flush()
                }

                val responseCode = conn.responseCode
                Log.d("AuthService", "Code réponse upload: $responseCode")

                val response = if (responseCode in 200..299) {
                    readStream(conn.inputStream)
                } else {
                    readStream(conn.errorStream)
                }

                Log.d("AuthService", "Réponse upload: $response")

                // Nettoyage du fichier temporaire
                zipFile.delete()

                when (responseCode) {
                    in 200..299 -> {
                        val jsonResponse = JSONObject(response)
                        val sessionId = jsonResponse.optString("session_id")
                            ?: jsonResponse.optString("id", "unknown")

                        UploadResult(
                            success = true,
                            message = "Session uploadée avec succès",
                            sessionId = sessionId
                        )
                    }
                    401 -> {
                        UploadResult(false, "Token d'authentification invalide")
                    }
                    413 -> {
                        UploadResult(false, "Fichier trop volumineux")
                    }
                    else -> {
                        val errorMsg = try {
                            val jsonError = JSONObject(response)
                            jsonError.optString("message", "Erreur serveur: $responseCode")
                        } catch (e: Exception) {
                            "Erreur serveur: $responseCode"
                        }
                        UploadResult(false, errorMsg)
                    }
                }
            } catch (e: Exception) {
                Log.e("AuthService", "Erreur upload", e)
                UploadResult(false, "Erreur d'envoi: ${e.message}")
            }
        }
    }

    fun getToken(): String? = prefs.getString(TOKEN_KEY, null)
    fun getUsername(): String? = prefs.getString(USER_KEY, null)
    fun isAuthenticated(): Boolean = getToken() != null

    private fun saveCredentials(token: String, username: String) {
        prefs.edit().apply {
            putString(TOKEN_KEY, token)
            putString(USER_KEY, username)
            apply()
        }
        Log.d("AuthService", "Credentials sauvegardées pour: $username")
    }

    fun logout() {
        prefs.edit().clear().apply()
        Log.d("AuthService", "Déconnexion effectuée")
    }

    private fun readStream(inputStream: InputStream?): String {
        return inputStream?.let { stream ->
            BufferedReader(InputStreamReader(stream)).use { reader ->
                reader.readText()
            }
        } ?: ""
    }

    private fun createZip(sourceFolder: File): File {
        val zipFile = File(context.cacheDir, "forensic_session_${System.currentTimeMillis()}.zip")

        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
                var fileCount = 0

                sourceFolder.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        val relativePath = file.relativeTo(sourceFolder).path
                        Log.d("AuthService", "Ajout au ZIP: $relativePath")

                        zipOut.putNextEntry(ZipEntry(relativePath))
                        file.inputStream().use { input ->
                            input.copyTo(zipOut)
                        }
                        zipOut.closeEntry()
                        fileCount++
                    }
                }

                Log.d("AuthService", "ZIP créé avec $fileCount fichiers")
            }
        } catch (e: Exception) {
            Log.e("AuthService", "Erreur création ZIP", e)
            if (zipFile.exists()) {
                zipFile.delete()
            }
        }

        return zipFile
    }
}

data class AuthResult(
    val success: Boolean,
    val token: String? = null,
    val message: String? = null
)

data class UploadResult(
    val success: Boolean,
    val message: String? = null,
    val sessionId: String? = null
)
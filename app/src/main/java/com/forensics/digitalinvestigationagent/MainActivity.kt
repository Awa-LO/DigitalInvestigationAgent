package com.forensics.digitalinvestigationagent

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.forensics.digitalinvestigationagent.services.AuthService
import com.forensics.digitalinvestigationagent.services.ForensicCollectionService
import com.forensics.digitalinvestigationagent.ui.theme.DigitalInvestigationAgentTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class CollectionResult(
    val smsCount: Int = 0,
    val callLogCount: Int = 0,
    val contactsCount: Int = 0,
    val imagesCount: Int = 0,
    val videosCount: Int = 0,
    val audioCount: Int = 0
) {
    val totalCount: Int
        get() = smsCount + callLogCount + contactsCount + imagesCount + videosCount + audioCount
}

data class PermissionInfo(
    val permission: String,
    val name: String,
    val description: String,
    val icon: ImageVector,
    val isGranted: Boolean = false,
    val canProceedWithoutIt: Boolean = true
)

class MainActivity : ComponentActivity() {

    private fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }

    private val permissionRequestCode = 101
    private var permissionCallback: ((Boolean) -> Unit)? = null
    private var updateCallback: ((String, CollectionResult, String?) -> Unit)? = null

    private val manageStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, "Permission de gestion des fichiers accordée", Toast.LENGTH_SHORT).show()
                permissionCallback?.invoke(true)
            } else {
                Toast.makeText(this, "Permission de gestion des fichiers refusée - Collecte continuera avec accès limité", Toast.LENGTH_LONG).show()
                permissionCallback?.invoke(true)
            }
        } else {
            permissionCallback?.invoke(true)
        }
    }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                when (it.action) {
                    "DATA_COLLECTION_UPDATE" -> {
                        val status = it.getStringExtra("status") ?: ""
                        val result = CollectionResult(
                            smsCount = it.getIntExtra("sms_count", 0),
                            callLogCount = it.getIntExtra("call_count", 0),
                            contactsCount = it.getIntExtra("contact_count", 0),
                            imagesCount = it.getIntExtra("image_count", 0),
                            videosCount = it.getIntExtra("video_count", 0),
                            audioCount = it.getIntExtra("audio_count", 0)
                        )
                        updateCallback?.invoke(status, result, null)
                    }
                    "DATA_COLLECTION_COMPLETE" -> {
                        val status = "Collecte terminée avec succès"
                        val result = CollectionResult(
                            smsCount = it.getIntExtra("sms_count", 0),
                            callLogCount = it.getIntExtra("call_count", 0),
                            contactsCount = it.getIntExtra("contact_count", 0),
                            imagesCount = it.getIntExtra("image_count", 0),
                            videosCount = it.getIntExtra("video_count", 0),
                            audioCount = it.getIntExtra("audio_count", 0)
                        )
                        val savePath = it.getStringExtra("save_path")
                        updateCallback?.invoke(status, result, savePath)
                    }
                    "DATA_COLLECTION_STOPPED" -> {
                        val status = "Collecte arrêtée par l'utilisateur"
                        val result = CollectionResult(
                            smsCount = it.getIntExtra("sms_count", 0),
                            callLogCount = it.getIntExtra("call_count", 0),
                            contactsCount = it.getIntExtra("contact_count", 0),
                            imagesCount = it.getIntExtra("image_count", 0),
                            videosCount = it.getIntExtra("video_count", 0),
                            audioCount = it.getIntExtra("audio_count", 0)
                        )
                        val savePath = it.getStringExtra("save_path")
                        updateCallback?.invoke(status, result, savePath)
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val filter = IntentFilter().apply {
            addAction("DATA_COLLECTION_UPDATE")
            addAction("DATA_COLLECTION_COMPLETE")
            addAction("DATA_COLLECTION_STOPPED")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_EXPORTED)
        }

        setContent {
            DigitalInvestigationAgentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ForensicApp()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(updateReceiver)
        } catch (e: Exception) {
            // Receiver déjà désenregistré
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ForensicApp() {
        var currentStatus by remember { mutableStateOf("Prêt à commencer la collecte") }
        var collectionResult by remember { mutableStateOf(CollectionResult()) }
        var isCollecting by remember { mutableStateOf(false) }
        var savePath by remember { mutableStateOf<String?>(null) }
        var showPermissionsDialog by remember { mutableStateOf(false) }
        var showLoginDialog by remember { mutableStateOf(false) }
        var showUploadDialog by remember { mutableStateOf(false) }
        var uploadStatus by remember { mutableStateOf<String?>(null) }

        val authService = remember { AuthService(this@MainActivity) }
        val isAuthenticated by remember { mutableStateOf(authService.isAuthenticated()) }

        val scrollState = rememberScrollState()

        LaunchedEffect(Unit) {
            updateCallback = { status, result, path ->
                currentStatus = status
                collectionResult = result
                savePath = path

                if (status.contains("terminée") || status.contains("arrêtée")) {
                    isCollecting = false
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // En-tête
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Agent d'Investigation Numérique",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Collecte de données forensiques",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Statut actuel
            StatusCard(currentStatus, isCollecting)

            // Compteurs en temps réel
            CountersCard(collectionResult)

            // Boutons d'action
            ActionButtons(
                isCollecting = isCollecting,
                onStartCollection = {
                    showPermissionsDialog = true
                },
                onStopCollection = {
                    stopCollection()
                    currentStatus = "Arrêt de la collecte en cours..."
                },
                onCheckPermissions = {
                    showPermissionsDialog = true
                }
            )

            // Bouton d'envoi au serveur
            if (savePath != null) {
                if (isAuthenticated) {
                    Button(
                        onClick = { showUploadDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Envoyer vers serveur")
                    }
                } else {
                    OutlinedButton(
                        onClick = { showLoginDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Login,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Se connecter pour envoyer")
                    }
                }
            }

            // Résultats et chemin de sauvegarde
            savePath?.let { path ->
                SaveLocationCard(path)
            }

            // Dialog des permissions
            if (showPermissionsDialog) {
                PermissionsDialog(
                    onDismiss = { showPermissionsDialog = false },
                    onRequestPermissions = {
                        showPermissionsDialog = false
                        requestAllPermissions { granted ->
                            val grantedPermissions = getGrantedPermissions()
                            if (grantedPermissions.isNotEmpty()) {
                                startCollection()
                                isCollecting = true
                                currentStatus = "Démarrage de la collecte avec ${grantedPermissions.size} permissions accordées..."
                                collectionResult = CollectionResult()
                                savePath = null
                                Toast.makeText(this@MainActivity,
                                    "Collecte démarrée avec ${grantedPermissions.size}/${getRequiredPermissions().size} permissions",
                                    Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this@MainActivity,
                                    "Aucune permission accordée. Impossible de démarrer la collecte.",
                                    Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onStartWithCurrentPermissions = {
                        showPermissionsDialog = false
                        val grantedPermissions = getGrantedPermissions()
                        if (grantedPermissions.isNotEmpty()) {
                            startCollection()
                            isCollecting = true
                            currentStatus = "Démarrage de la collecte avec ${grantedPermissions.size} permissions accordées..."
                            collectionResult = CollectionResult()
                            savePath = null
                            Toast.makeText(this@MainActivity,
                                "Collecte démarrée avec ${grantedPermissions.size}/${getRequiredPermissions().size} permissions",
                                Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@MainActivity,
                                "Aucune permission accordée. Impossible de démarrer la collecte.",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }

            // Dialogue de connexion
            if (showLoginDialog) {
                var username by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                var isLoading by remember { mutableStateOf(false) }
                var errorMessage by remember { mutableStateOf<String?>(null) }

                AlertDialog(
                    onDismissRequest = { showLoginDialog = false },
                    title = { Text("Connexion au serveur") },
                    text = {
                        Column {
                            if (errorMessage != null) {
                                Text(
                                    text = errorMessage!!,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = { Text("Nom d'utilisateur") },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isLoading
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Mot de passe") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isLoading
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (username.isBlank() || password.isBlank()) {
                                    errorMessage = "Veuillez remplir tous les champs"
                                } else {
                                    isLoading = true
                                    errorMessage = null
                                    CoroutineScope(Dispatchers.IO).launch {
                                        try {
                                            val result = authService.login(username, password)
                                            withContext(Dispatchers.Main) {
                                                isLoading = false
                                                if (result.success) {
                                                    showLoginDialog = false
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        "Connexion réussie",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                } else {
                                                    errorMessage = result.message ?: "Échec de la connexion"
                                                }
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                isLoading = false
                                                errorMessage = "Erreur de connexion: ${e.message}"
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = !isLoading
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Se connecter")
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showLoginDialog = false },
                            enabled = !isLoading
                        ) {
                            Text("Annuler")
                        }
                    }
                )
            }

            // Dialogue d'envoi
            if (showUploadDialog && savePath != null) {
                AlertDialog(
                    onDismissRequest = { showUploadDialog = false },
                    title = { Text("Envoyer les données") },
                    text = {
                        Column {
                            Text("Voulez-vous envoyer ces données au serveur?")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(savePath!!, style = MaterialTheme.typography.bodySmall)
                            uploadStatus?.let {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(it, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                CoroutineScope(Dispatchers.IO).launch {
                                    uploadStatus = "Envoi en cours..."
                                    val result = authService.uploadSession(File(savePath!!))
                                    uploadStatus = if (result.success) {
                                        "Envoi réussi! ID: ${result.sessionId}"
                                    } else {
                                        "Erreur: ${result.message}"
                                    }
                                }
                            }
                        ) {
                            Text("Confirmer")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showUploadDialog = false }) {
                            Text("Annuler")
                        }
                    }
                )
            }
        }
    }

    @Composable
    fun StatusCard(status: String, isCollecting: Boolean) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isCollecting)
                    MaterialTheme.colorScheme.secondaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isCollecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    @Composable
    fun CountersCard(result: CollectionResult) {
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Éléments collectés",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                val counters = listOf(
                    "SMS" to result.smsCount,
                    "Appels" to result.callLogCount,
                    "Contacts" to result.contactsCount,
                    "Images" to result.imagesCount,
                    "Vidéos" to result.videosCount,
                    "Audio" to result.audioCount
                )

                counters.forEach { (label, count) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "TOTAL",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = result.totalCount.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    @Composable
    fun ActionButtons(
        isCollecting: Boolean,
        onStartCollection: () -> Unit,
        onStopCollection: () -> Unit,
        onCheckPermissions: () -> Unit
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isCollecting) {
                Button(
                    onClick = onStopCollection,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Arrêter la collecte")
                }
            } else {
                Button(
                    onClick = onStartCollection,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Démarrer la collecte")
                }
            }

            OutlinedButton(
                onClick = onCheckPermissions,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Vérifier les permissions")
            }
        }
    }

    @Composable
    fun SaveLocationCard(path: String) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Données sauvegardées",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { openFileLocation(path) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ouvrir le dossier")
                }
            }
        }
    }

    @Composable
    fun PermissionsDialog(
        onDismiss: () -> Unit,
        onRequestPermissions: () -> Unit,
        onStartWithCurrentPermissions: () -> Unit
    ) {
        val permissions = getPermissionsList()
        val grantedCount = permissions.count { it.isGranted }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text("Permissions pour la collecte")
            },
            text = {
                Column {
                    Text(
                        text = "Permissions accordées: $grantedCount/${permissions.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "La collecte peut démarrer avec les permissions actuelles. Seules les données autorisées seront collectées.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(
                        modifier = Modifier.height(300.dp)
                    ) {
                        items(permissions) { permission ->
                            PermissionItem(permission)
                        }
                    }
                }
            },
            confirmButton = {
                Column {
                    Button(
                        onClick = onRequestPermissions,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Demander toutes les permissions")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onStartWithCurrentPermissions,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = grantedCount > 0
                    ) {
                        Text("Démarrer avec permissions actuelles ($grantedCount)")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Annuler")
                }
            }
        )
    }

    @Composable
    fun PermissionItem(permission: PermissionInfo) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (permission.isGranted) Icons.Default.Check else Icons.Default.Warning,
                contentDescription = null,
                tint = if (permission.isGranted) Color.Green else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = permission.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = permission.description + if (permission.canProceedWithoutIt) " (Optionnel)" else " (Requis)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    private fun getPermissionsList(): List<PermissionInfo> {
        val requiredPermissions = getRequiredPermissions()
        val permissionInfos = mutableListOf<PermissionInfo>()

        requiredPermissions.forEach { permission ->
            val (name, description, icon) = when (permission) {
                Manifest.permission.READ_SMS -> Triple("SMS", "Lire les messages", Icons.Default.Message)
                Manifest.permission.READ_CALL_LOG -> Triple("Historique d'appels", "Lire l'historique des appels", Icons.Default.Phone)
                Manifest.permission.READ_CONTACTS -> Triple("Contacts", "Lire les contacts", Icons.Default.Contacts)
                Manifest.permission.READ_EXTERNAL_STORAGE -> Triple("Stockage", "Lire les fichiers", Icons.Default.Storage)
                Manifest.permission.READ_MEDIA_IMAGES -> Triple("Images", "Lire les images", Icons.Default.Image)
                Manifest.permission.READ_MEDIA_VIDEO -> Triple("Vidéos", "Lire les vidéos", Icons.Default.VideoLibrary)
                Manifest.permission.READ_MEDIA_AUDIO -> Triple("Audio", "Lire les fichiers audio", Icons.Default.AudioFile)
                Manifest.permission.POST_NOTIFICATIONS -> Triple("Notifications", "Afficher les notifications", Icons.Default.Notifications)
                else -> Triple("Autre", "Permission système", Icons.Default.Security)
            }

            permissionInfos.add(
                PermissionInfo(
                    permission = permission,
                    name = name,
                    description = description,
                    icon = icon,
                    isGranted = checkPermission(permission),
                    canProceedWithoutIt = true
                )
            )
        }

        return permissionInfos
    }

    private fun checkPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun getGrantedPermissions(): List<String> {
        return getRequiredPermissions().filter { checkPermission(it) }
    }

    private fun requestAllPermissions(callback: (Boolean) -> Unit) {
        permissionCallback = callback

        val allPermissions = getRequiredPermissions()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            allPermissions.contains(Manifest.permission.READ_EXTERNAL_STORAGE)) {

            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            manageStoragePermissionLauncher.launch(intent)
            return
        }

        ActivityCompat.requestPermissions(this, allPermissions, permissionRequestCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == permissionRequestCode) {
            val grantedCount = grantResults.count { it == PackageManager.PERMISSION_GRANTED }

            Toast.makeText(this,
                "Permissions accordées: $grantedCount/${permissions.size}",
                Toast.LENGTH_LONG).show()

            permissionCallback?.invoke(grantedCount > 0)
        }
    }

    private fun startCollection() {
        val intent = Intent(this, ForensicCollectionService::class.java).apply {
            action = "START_COLLECTION"
        }
        startForegroundService(intent)
    }

    private fun stopCollection() {
        val intent = Intent(this, ForensicCollectionService::class.java).apply {
            action = "STOP_COLLECTION"
        }
        startService(intent)
    }

    private fun openFileLocation(path: String) {
        try {
            val file = File(path)
            val parentDir = file.parentFile

            if (parentDir != null && parentDir.exists()) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(parentDir), "resource/folder")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }

                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                } else {
                    val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    startActivity(Intent.createChooser(fallbackIntent, "Ouvrir avec"))
                }
            } else {
                Toast.makeText(this, "Dossier introuvable", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Impossible d'ouvrir le dossier: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
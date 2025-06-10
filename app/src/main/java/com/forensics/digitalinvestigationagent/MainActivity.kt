package com.forensics.digitalinvestigationagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.forensics.digitalinvestigationagent.services.ForensicCollectionService
import com.forensics.digitalinvestigationagent.ui.theme.DigitalInvestigationAgentTheme

class MainActivity : ComponentActivity() {
    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startForensicService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DigitalInvestigationAgentTheme {
                val context = LocalContext.current
                ForensicApp(
                    onStartCollection = {
                        if (checkPermissions()) {
                            startForensicService()
                        } else {
                            permissionLauncher.launch(requiredPermissions)
                        }
                    },
                    onStopCollection = {
                        stopService(Intent(context, ForensicCollectionService::class.java))
                    }
                )
            }
        }
    }

    private fun checkPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startForensicService() {
        val serviceIntent = Intent(this, ForensicCollectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}

@Composable
fun ForensicApp(
    onStartCollection: () -> Unit = {},
    onStopCollection: () -> Unit = {},
    initialStatus: String = "Prêt à collecter",
    initialItemsCount: Int = 0
) {
    var collectionStatus by remember { mutableStateOf(initialStatus) }
    var collectedItems by remember { mutableStateOf(initialItemsCount) }
    var isCollecting by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Agent d'Investigation Numérique",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(32.dp))

            StatusIndicator(
                status = collectionStatus,
                isActive = isCollecting
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    if (!isCollecting) {
                        onStartCollection()
                        collectionStatus = "Collecte en cours..."
                        isCollecting = true
                    } else {
                        onStopCollection()
                        collectionStatus = "Collecte arrêtée"
                        isCollecting = false
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (!isCollecting) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.width(200.dp)
            ) {
                Text(if (!isCollecting) "Démarrer" else "Arrêter")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Artefacts collectés: $collectedItems",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
fun StatusIndicator(
    status: String,
    isActive: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(8.dp)
    ) {
        val color = when {
            isActive -> MaterialTheme.colorScheme.primary
            status.contains("Prêt") -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.error
        }

        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, CircleShape)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ForensicAppPreview() {
    DigitalInvestigationAgentTheme {
        ForensicApp(
            onStartCollection = {},
            onStopCollection = {},
            initialStatus = "Prévisualisation",
            initialItemsCount = 42
        )
    }
}
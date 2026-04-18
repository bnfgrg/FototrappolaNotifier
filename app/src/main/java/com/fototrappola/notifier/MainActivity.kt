package com.fototrappola.notifier

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* risultato gestito dinamicamente */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SetupScreen(
                    initialEmail = CredentialStore.email(this) ?: "",
                    initialPassword = CredentialStore.password(this) ?: "",
                    initialFilter = CredentialStore.filterSender(this) ?: "",
                    onSave = { email, password, filter ->
                        CredentialStore.save(this, email, password, filter.ifBlank { null })
                        ImapListenerService.stop(this)
                        ImapListenerService.start(this)
                    },
                    onRequestBatteryOptim = { requestIgnoreBatteryOptimizations() },
                    onRequestFullScreen = { openFullScreenIntentSettings() },
                    onClear = {
                        CredentialStore.clear(this)
                        ImapListenerService.stop(this)
                    }
                )
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun openFullScreenIntentSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    initialEmail: String,
    initialPassword: String,
    initialFilter: String,
    onSave: (String, String, String) -> Unit,
    onRequestBatteryOptim: () -> Unit,
    onRequestFullScreen: () -> Unit,
    onClear: () -> Unit,
) {
    var email by remember { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf(initialPassword) }
    var filter by remember { mutableStateOf(initialFilter) }
    var showPassword by remember { mutableStateOf(false) }
    var savedMsg by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Fototrappola Notifier") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Configura l'account Gmail della fototrappola. Richiede 2FA attiva e una password per app.",
                style = MaterialTheme.typography.bodyMedium
            )

            OutlinedTextField(
                value = email,
                onValueChange = { email = it.trim() },
                label = { Text("Email Gmail") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password per app (16 caratteri)") },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null
                        )
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text("Filtro mittente (opzionale)") },
                supportingText = { Text("Es: fototrappola@ oppure il dominio. Vuoto = tutte le email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    if (email.isNotBlank() && password.isNotBlank()) {
                        onSave(email, password.replace(" ", ""), filter.trim())
                        savedMsg = "Credenziali salvate. Servizio avviato."
                    } else {
                        savedMsg = "Inserisci email e password"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Salva e avvia servizio") }

            savedMsg?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Permessi necessari", style = MaterialTheme.typography.titleMedium)

            OutlinedButton(
                onClick = onRequestBatteryOptim,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Disattiva ottimizzazione batteria") }

            OutlinedButton(
                onClick = onRequestFullScreen,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Consenti notifiche schermo intero") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            TextButton(
                onClick = {
                    onClear()
                    email = ""; password = ""; filter = ""
                    savedMsg = "Credenziali cancellate"
                }
            ) { Text("Cancella credenziali e ferma servizio") }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Come ottenere la password per app:\n" +
                        "1. Vai su myaccount.google.com/security\n" +
                        "2. Attiva la verifica in 2 passaggi\n" +
                        "3. Cerca 'Password per le app'\n" +
                        "4. Genera una nuova password e incollala qui",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Start)
            )
        }
    }
}

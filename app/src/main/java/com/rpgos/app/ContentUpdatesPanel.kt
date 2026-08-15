package com.rpgos.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun ContentUpdatesPanel(context: android.content.Context) {
    val manager = remember(context) { ContentUpdateManager(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var candidates by remember { mutableStateOf<List<ContentUpdateCandidate>>(emptyList()) }
    var status by remember { mutableStateOf("Nie sprawdzano aktualizacji zawartości.") }
    var busy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp, 14.dp, 26.dp, 18.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xE6071420),
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            border = BorderStroke(1.dp, Color(0x6656D8D0))
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Aktualizacje zawartości", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Światy, reguły MG, konfiguracje i dane — bez instalowania nowego APK.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(status, style = MaterialTheme.typography.bodyMedium)

                if (candidates.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        candidates.forEach { candidate ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(candidate.remote.id, fontWeight = FontWeight.SemiBold)
                                Text("${candidate.installedVersion ?: 0} → ${candidate.remote.version}", color = MaterialTheme.colorScheme.secondary)
                            }
                            if (candidate.remote.description.isNotBlank()) {
                                Text(candidate.remote.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            status = "Sprawdzanie kanału zawartości..."
                            runCatching { manager.check() }
                                .onSuccess {
                                    candidates = it
                                    status = if (it.isEmpty()) "Zawartość jest aktualna." else "Dostępne pakiety: ${it.size}."
                                }
                                .onFailure { status = "Błąd sprawdzania: ${it.message}" }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                ) { Text("Sprawdź aktualizacje zawartości", fontWeight = FontWeight.Bold) }

                if (candidates.isNotEmpty()) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                val pending = candidates
                                var installed = 0
                                try {
                                    pending.forEachIndexed { index, candidate ->
                                        status = "Instalowanie ${index + 1}/${pending.size}: ${candidate.remote.id}..."
                                        manager.install(candidate)
                                        installed++
                                    }
                                    candidates = manager.check()
                                    status = "Zainstalowano $installed pakietów. Backup i rollback są aktywne."
                                } catch (t: Throwable) {
                                    status = "Aktualizacja przerwana po $installed pakietach: ${t.message}"
                                } finally { busy = false }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary)
                    ) { Text("Aktualizuj zawartość", fontWeight = FontWeight.Bold) }
                }
            }
        }

        Text("DLA DEWELOPERA • TEMP LOCAL GM", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        TempGmDeveloperSection()
    }
}

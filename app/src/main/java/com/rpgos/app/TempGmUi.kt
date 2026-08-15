package com.rpgos.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
fun TempGmDeveloperSection(client: TempGmBridgeClient = remember { TempGmBridgeClient() }) {
    val scope = rememberCoroutineScope()
    var health by remember { mutableStateOf(TempGmHealth(false, TempGmStatus.STARTING)) }
    var message by remember { mutableStateOf("") }
    var narrative by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var showBugs by remember { mutableStateOf(false) }

    fun refresh() { scope.launch { health = client.health() } }
    LaunchedEffect(Unit) { refresh() }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("TEMP LOCAL AI-GM", fontWeight = FontWeight.Bold)
            Text("TEMP / NON-AUTHORITATIVE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            Text("Provider: Bielik 4.5B v3")
            Text("Logical ID: ${health.providerId}")
            Text("TEMP GM: ${health.status}")
            Text("BRIDGE: ${if (health.bridgeConnected) "CONNECTED" else "DISCONNECTED"}")
            if (!health.bridgeConnected) Text("Bridge niedostępny. Canonical aplikacja działa dalej.", style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { refresh() }) { Text("Odśwież") }
                OutlinedButton(onClick = { showBugs = !showBugs }) { Text("Oczekujące raporty") }
            }

            OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth(), label = { Text("Testowa deklaracja gracza") }, minLines = 2)
            Button(
                enabled = message.isNotBlank() && health.status == TempGmStatus.READY,
                onClick = {
                    scope.launch {
                        runCatching { client.turn(message) }
                            .onSuccess { narrative = it.narrative; error = "" }
                            .onFailure { error = safeTempError(it) }
                    }
                }
            ) { Text("Wyślij testową turę") }

            if (narrative.isNotBlank()) {
                Text("Narracja TEMP GM", fontWeight = FontWeight.Bold)
                Text(narrative)
                Text("Nie zapisano do canonical state.", style = MaterialTheme.typography.labelSmall)
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)

            TempBugCreate(client)
            if (showBugs) TempPendingReports(client)
        }
    }
}

@Composable
private fun TempBugCreate(client: TempGmBridgeClient) {
    val scope = rememberCoroutineScope()
    var description by remember { mutableStateOf("") }
    var logcat by remember { mutableStateOf(false) }
    var screenshot by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    HorizontalDivider()
    Text("Zgłoś błąd", fontWeight = FontWeight.Bold)
    OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Opis błędu") }, minLines = 3)
    Row { Checkbox(logcat, { logcat = it }); Text("Dołącz ograniczony log diagnostyczny", Modifier.padding(top = 12.dp)) }
    Row { Checkbox(screenshot, { screenshot = it; if (!it) consent = false }); Text("Dołącz zrzut ekranu", Modifier.padding(top = 12.dp)) }
    if (screenshot) {
        Text("Zrzut może zostać dołączony tylko do tego raportu po jawnej zgodzie. Bridge nie wykonuje ukrytego capture.", style = MaterialTheme.typography.bodySmall)
        Row { Checkbox(consent, { consent = it }); Text("Wyrażam zgodę na dołączenie zrzutu", Modifier.padding(top = 12.dp)) }
    }
    Button(
        enabled = description.isNotBlank(),
        onClick = {
            scope.launch {
                runCatching { client.createBug(description, logcat, screenshot, consent) }
                    .onSuccess {
                        result = "${it.submissionState} • ${it.reportUid}\nLog diagnostyczny: ${it.logcatStatus}\nSamo przygotowanie raportu nie publikuje Issue."
                        description = ""; screenshot = false; consent = false; error = ""
                    }
                    .onFailure { error = safeTempError(it) }
            }
        }
    ) { Text("Przygotuj raport") }
    if (result.isNotBlank()) Text(result)
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun TempPendingReports(client: TempGmBridgeClient) {
    val scope = rememberCoroutineScope()
    var reports by remember { mutableStateOf<List<TempBugSummary>>(emptyList()) }
    var selected by remember { mutableStateOf<TempBugSummary?>(null) }
    var detail by remember { mutableStateOf<TempBugDetail?>(null) }
    var preview by remember { mutableStateOf<TempBugPreview?>(null) }
    var error by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            runCatching { client.listBugs() }
                .onSuccess { reports = it; error = "" }
                .onFailure { error = safeTempError(it) }
        }
    }
    fun open(r: TempBugSummary) {
        selected = r
        confirmDelete = false
        scope.launch {
            runCatching { client.detail(r.reportUid) to client.preview(r.reportUid) }
                .onSuccess { detail = it.first; preview = it.second; error = "" }
                .onFailure { error = safeTempError(it) }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    HorizontalDivider()
    Text("Oczekujące raporty: ${reports.size}", fontWeight = FontWeight.Bold)
    if (reports.isEmpty()) Text("Brak LOCAL_PENDING/READY.", style = MaterialTheme.typography.bodySmall)

    reports.forEach { r ->
        Card(onClick = { open(r) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text(r.description.ifBlank { r.reportUid })
                Text("${r.submissionState} • logcat ${r.logcatStatus} • ADB ${r.adbState}", style = MaterialTheme.typography.bodySmall)
                if (r.duplicateCount > 0) Text("Możliwe duplikaty: ${r.duplicateCount}")
            }
        }
    }

    selected?.let { r ->
        Text("Raport ${r.reportUid}", fontWeight = FontWeight.Bold)
        Text("Status: ${r.submissionState}\nFingerprint: ${r.fingerprint}\nRoute: ${r.route}\nLogcat: ${r.logcatStatus}\nADB: ${r.adbState}\nScreenshot: ${if (r.screenshotAvailable) "dostępny" else if (r.screenshotRequested) "brak evidence / brak zgody lub referencji" else "nie żądano"}")

        detail?.report?.let { report ->
            val device = report["DEVICE-CAPTURED"]?.jsonObject
            val app = device?.get("app")?.jsonObject
            val user = report["USER-SUPPLIED"]?.jsonObject
            Text("Szczegóły", fontWeight = FontWeight.Bold)
            Text("Opis: ${user?.get("originalReport")?.jsonPrimitive?.contentOrNull.orEmpty()}")
            Text("Build: ${app?.get("versionName")?.jsonPrimitive?.contentOrNull.orEmpty()} (${app?.get("versionCode")?.jsonPrimitive?.contentOrNull.orEmpty()})", style = MaterialTheme.typography.bodySmall)
            Text("Campaign: ${device?.get("campaignUid")?.jsonPrimitive?.contentOrNull.orEmpty()} • World Pack: ${device?.get("worldPackUid")?.jsonPrimitive?.contentOrNull.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            Text("Provider: ${device?.get("tempProviderId")?.jsonPrimitive?.contentOrNull.orEmpty()}", style = MaterialTheme.typography.bodySmall)
        }

        preview?.let { p ->
            Text("Preview", fontWeight = FontWeight.Bold)
            Text(p.preview, style = MaterialTheme.typography.bodySmall)
            if (p.candidates.isNotEmpty()) {
                Text("Kandydaci duplicate — wybór użytkownika jest wymagany", fontWeight = FontWeight.Bold)
                p.candidates.forEach { candidate ->
                    val o = candidate.jsonObject
                    val n = o["issueNumber"]?.jsonPrimitive?.intOrNull
                    OutlinedButton(
                        enabled = n != null,
                        onClick = {
                            if (n != null) scope.launch {
                                runCatching { client.decision(r.reportUid, "CONFIRM_LINK_DUPLICATE", n) }
                                    .onSuccess { refresh(); open(r) }
                                    .onFailure { error = safeTempError(it) }
                            }
                        }
                    ) { Text("Jawnie wybierz istniejące #${n ?: "?"}: ${o["title"]?.jsonPrimitive?.contentOrNull.orEmpty()}") }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { scope.launch { runCatching { client.decision(r.reportUid, "KEEP_PENDING") }.onSuccess { refresh(); open(r) }.onFailure { error = safeTempError(it) } } }) { Text("KEEP_PENDING") }
            Button(onClick = { scope.launch { runCatching { client.decision(r.reportUid, "CONFIRM_NEW_ISSUE") }.onSuccess { refresh(); open(r) }.onFailure { error = safeTempError(it) } } }) { Text("CONFIRM_NEW_ISSUE") }
            OutlinedButton(onClick = { scope.launch { runCatching { client.cancel(r.reportUid) }.onSuccess { selected = null; detail = null; preview = null; refresh() }.onFailure { error = safeTempError(it) } } }) { Text("CANCEL") }
        }
        OutlinedButton(onClick = { scope.launch { runCatching { client.retry(r.reportUid) }.onSuccess { refresh(); open(r) }.onFailure { error = safeTempError(it) } } }) { Text("Ponów lokalny workflow") }
        Row { Checkbox(confirmDelete, { confirmDelete = it }); Text("Potwierdzam lokalne usunięcie", Modifier.padding(top = 12.dp)) }
        OutlinedButton(
            enabled = confirmDelete,
            onClick = {
                scope.launch {
                    runCatching { client.delete(r.reportUid, true) }
                        .onSuccess { selected = null; detail = null; preview = null; confirmDelete = false; refresh() }
                        .onFailure { error = safeTempError(it) }
                }
            }
        ) { Text("Usuń lokalny raport") }
        Text("READY oznacza jednorazową zgodę dla zewnętrznego uprzywilejowanego adaptera. Android nie ma tokenu GitHub i nie tworzy Issue.", style = MaterialTheme.typography.bodySmall)
    }

    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
}

fun safeTempError(t: Throwable): String = when (t) {
    is TempBridgeException -> when (t.httpCode) {
        400 -> "Żądanie odrzucone: ${t.safeMessage}"
        404 -> "Raport nie istnieje."
        409 -> "Operacja niedozwolona w obecnym stanie / zgoda została już wykorzystana."
        507 -> "Lokalna kolejka raportów jest pełna lub niedostępna."
        else -> "TEMP bridge: ${t.safeMessage}"
    }
    else -> "TEMP bridge jest niedostępny. Raporty już zapisane lokalnie pozostają w BugReportStore."
}

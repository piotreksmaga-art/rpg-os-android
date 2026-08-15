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
fun TempGmDeveloperSection(client:TempGmBridgeClient=remember{TempGmBridgeClient()}){
    val scope=rememberCoroutineScope(); var health by remember{mutableStateOf(TempGmHealth(false,TempGmStatus.STARTING))}; var message by remember{mutableStateOf("")}; var narrative by remember{mutableStateOf("")}; var error by remember{mutableStateOf("")}; var showBugs by remember{mutableStateOf(false)}
    fun refresh(){scope.launch{health=client.health()}}
    LaunchedEffect(Unit){refresh()}
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("TEMP LOCAL AI-GM",fontWeight=FontWeight.Bold); Text("TEMP / NON-AUTHORITATIVE",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
        Text("Provider: Bielik 4.5B v3"); Text("Logical ID: ${health.providerId}"); Text("TEMP GM: ${health.status}"); Text("BRIDGE: ${if(health.bridgeConnected)"CONNECTED" else "DISCONNECTED"}")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={refresh()}){Text("Odśwież")};OutlinedButton(onClick={showBugs=!showBugs}){Text("Oczekujące raporty")}}
        OutlinedTextField(message,{message=it},Modifier.fillMaxWidth(),label={Text("Testowa deklaracja gracza")},minLines=2)
        Button(enabled=message.isNotBlank()&&health.status==TempGmStatus.READY,onClick={{scope.launch{runCatching{client.turn(message)}.onSuccess{narrative=it.narrative;error=""}.onFailure{error=safeTempError(it)}}}}){Text("Wyślij testową turę")}
        if(narrative.isNotBlank()){Text("Narracja TEMP GM",fontWeight=FontWeight.Bold);Text(narrative)}; if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        TempBugCreate(client); if(showBugs)TempPendingReports(client)
    }}
}

@Composable private fun TempBugCreate(client:TempGmBridgeClient){
    val scope=rememberCoroutineScope(); var description by remember{mutableStateOf("")}; var logcat by remember{mutableStateOf(false)}; var screenshot by remember{mutableStateOf(false)}; var consent by remember{mutableStateOf(false)}; var result by remember{mutableStateOf("")}; var error by remember{mutableStateOf("")}
    HorizontalDivider();Text("Zgłoś błąd",fontWeight=FontWeight.Bold);OutlinedTextField(description,{description=it},Modifier.fillMaxWidth(),label={Text("Opis błędu")},minLines=3)
    Row{Checkbox(logcat,{logcat=it});Text("Dołącz ograniczony log diagnostyczny",Modifier.padding(top=12.dp))};Row{Checkbox(screenshot,{screenshot=it;if(!it)consent=false});Text("Dołącz zrzut ekranu",Modifier.padding(top=12.dp))}
    if(screenshot){Text("Zrzut zostanie dołączony do tego raportu wyłącznie po jawnej zgodzie.",style=MaterialTheme.typography.bodySmall);Row{Checkbox(consent,{consent=it});Text("Wyrażam zgodę na dołączenie zrzutu",Modifier.padding(top=12.dp))}}
    Button(enabled=description.isNotBlank(),onClick={{scope.launch{runCatching{client.createBug(description,logcat,screenshot,consent)}.onSuccess{result="${it.submissionState} • ${it.reportUid}\nLog diagnostyczny: ${it.logcatStatus}";description="";screenshot=false;consent=false;error=""}.onFailure{error=safeTempError(it)}}}}){Text("Przygotuj raport")}
    if(result.isNotBlank())Text(result);if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
}

@Composable private fun TempPendingReports(client:TempGmBridgeClient){
    val scope=rememberCoroutineScope();var reports by remember{mutableStateOf<List<TempBugSummary>>(emptyList())};var selected by remember{mutableStateOf<TempBugSummary?>(null)};var preview by remember{mutableStateOf<TempBugPreview?>(null)};var error by remember{mutableStateOf("")};var confirmDelete by remember{mutableStateOf(false)}
    fun refresh(){scope.launch{runCatching{client.listBugs()}.onSuccess{reports=it;error=""}.onFailure{error=safeTempError(it)}}};LaunchedEffect(Unit){refresh()}
    HorizontalDivider();Text("Oczekujące raporty: ${reports.size}",fontWeight=FontWeight.Bold)
    reports.forEach{r->Card(onClick={selected=r;scope.launch{runCatching{client.preview(r.reportUid)}.onSuccess{preview=it}.onFailure{error=safeTempError(it)}}},modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp)){Text(r.description.ifBlank{r.reportUid});Text("${r.submissionState} • logcat ${r.logcatStatus} • ADB ${r.adbState}",style=MaterialTheme.typography.bodySmall);if(r.duplicateCount>0)Text("Możliwe duplikaty: ${r.duplicateCount}")}}}
    selected?.let{r->
        Text("Raport ${r.reportUid}",fontWeight=FontWeight.Bold);Text("Status: ${r.submissionState}\nFingerprint: ${r.fingerprint}\nScreenshot: ${if(r.screenshotAvailable)"dostępny" else if(r.screenshotRequested)"brak evidence / brak zgody" else "nie żądano"}")
        preview?.let{p->Text("Preview",fontWeight=FontWeight.Bold);Text(p.preview,style=MaterialTheme.typography.bodySmall);if(p.candidates.isNotEmpty()){Text("Kandydaci duplicate",fontWeight=FontWeight.Bold);p.candidates.forEach{c->val o=c.jsonObject;val n=o["issueNumber"]?.jsonPrimitive?.intOrNull;OutlinedButton(enabled=n!=null,onClick={{if(n!=null)scope.launch{runCatching{client.decision(r.reportUid,"CONFIRM_LINK_DUPLICATE",n)}.onSuccess{refresh()}.onFailure{error=safeTempError(it)}}}}){Text("#${n?:"?"} ${o["title"]?.jsonPrimitive?.contentOrNull.orEmpty()}")}}}}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton(onClick={{scope.launch{runCatching{client.decision(r.reportUid,"KEEP_PENDING")}.onSuccess{refresh()}.onFailure{error=safeTempError(it)}}}}){Text("Zostaw")};Button(onClick={{scope.launch{runCatching{client.decision(r.reportUid,"CONFIRM_NEW_ISSUE")}.onSuccess{refresh()}.onFailure{error=safeTempError(it)}}}}){Text("Wyślij")};OutlinedButton(onClick={{scope.launch{runCatching{client.cancel(r.reportUid)}.onSuccess{selected=null;preview=null;refresh()}.onFailure{error=safeTempError(it)}}}}){Text("Anuluj")}}
        OutlinedButton(onClick={{scope.launch{runCatching{client.retry(r.reportUid)}.onSuccess{refresh()}.onFailure{error=safeTempError(it)}}}}){Text("Ponów lokalny workflow")}
        Row{Checkbox(confirmDelete,{confirmDelete=it});Text("Potwierdzam lokalne usunięcie",Modifier.padding(top=12.dp))};OutlinedButton(enabled=confirmDelete,onClick={{scope.launch{runCatching{client.delete(r.reportUid,true)}.onSuccess{selected=null;preview=null;confirmDelete=false;refresh()}.onFailure{error=safeTempError(it)}}}}){Text("Usuń lokalny raport")}
        Text("READY oznacza zgodę na jedną akcję zewnętrznego adaptera; aplikacja Android nie tworzy Issue samodzielnie.",style=MaterialTheme.typography.bodySmall)
    };if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
}

fun safeTempError(t:Throwable):String=when(t){is TempBridgeException->when(t.httpCode){400->"Żądanie odrzucone: ${t.safeMessage}";404->"Raport nie istnieje.";409->"Operacja niedozwolona w obecnym stanie / zgoda została już wykorzystana.";507->"Lokalna kolejka raportów jest pełna lub niedostępna.";else->"TEMP bridge: ${t.safeMessage}"};else->"TEMP bridge jest niedostępny. Raporty już zapisane lokalnie pozostają w BugReportStore."}

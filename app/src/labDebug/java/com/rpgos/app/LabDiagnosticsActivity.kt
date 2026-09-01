package com.rpgos.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Human-readable Stage-3 diagnostics. This activity exists only in the labDebug source set. */
class LabDiagnosticsActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        LabCodexProviderRuntime.install(applicationContext)
        setContent{MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF58A6FF),secondary=Color(0xFF18C7B5))){LabDiagnosticsScreen()}}
    }
}

@Composable
private fun LabDiagnosticsScreen(){
    var snapshot by remember{mutableStateOf(JSONObject().put("state","LOADING").toString(2))}
    var actionResult by remember{mutableStateOf("")}
    val scope=rememberCoroutineScope()
    suspend fun refresh(){snapshot=withContext(Dispatchers.IO){diagnosticSnapshot().toString(2)}}
    LaunchedEffect(Unit){while(true){refresh();delay(2_000L)}}
    Column(
        modifier=Modifier.fillMaxSize().background(Color(0xFF06121F)).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement=Arrangement.spacedBy(14.dp)
    ){
        Text("RPG OS LAB • Bridge Etap 3",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Text("Automatyczny Codex jako MG i Director. Panel nie istnieje w wariancie release.")
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Button(onClick={
                LabCodexProviderRuntime.setAssignments(JSONObject().put("game_master","PINNED").put("director","PINNED"))
                actionResult="Codex przypięty do obu ról"
            }){Text("Przypnij obie role")}
            Button(onClick={scope.launch{actionResult=withContext(Dispatchers.IO){runCatching{LabCodexProviderRuntime.runDirector(JSONObject()).toString()}.getOrElse{it.message.orEmpty()}}}}){Text("Uruchom Directora")}
        }
        if(actionResult.isNotBlank())Text(actionResult,color=MaterialTheme.colorScheme.secondary)
        Card(Modifier.fillMaxWidth()){
            Text(snapshot,modifier=Modifier.padding(14.dp),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
        }
    }
}

private fun diagnosticSnapshot():JSONObject{
    val provider=LabCodexProviderRuntime.state()
    val trace=LabCodexProviderRuntime.trace.latestExchange("GM_PROPOSAL")
    val guidanceUsed=trace.optJSONObject("request")?.optString("payload")?.let{payload->
        runCatching{!JSONObject(payload).isNull("strategic_guidance")}.getOrDefault(false)
    }?:false
    return JSONObject()
        .put("bridge_stage",3)
        .put("release_included",false)
        .put("provider",provider)
        .put("director",LabCodexProviderRuntime.directorState())
        .put("director_guidance_used_in_last_gm_proposal",guidanceUsed)
        .put("last_error",provider.optJSONObject("provider")?.opt("last_error_uid")?:JSONObject.NULL)
}

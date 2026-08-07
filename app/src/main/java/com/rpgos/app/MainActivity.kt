package com.rpgos.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val vm: RpgOsViewModel = viewModel()
                RpgOsApp(vm)
            }
        }
    }
}

enum class Tab(val label: String) {
    GAME("Gra"), STATUS("Status"), TIME("Czas"), WORLD("Świat"), NPCS("NPC"), SOCIAL("Relacje"), DASHBOARD("Dashboard"),
    TECHNIQUES("Techniki"), MISSIONS("Misje"), VISUALS("Grafika"),
    CHRONICLE("Kronika"), PACKAGES("Paczki"), DB("Baza"), GM("MG"), SETTINGS("Ustawienia")
}

@Composable
fun RpgOsApp(vm: RpgOsViewModel) {
    var tab by remember { mutableStateOf(Tab.GAME) }
    Scaffold(
        topBar = { TopAppBar(title={Text("RPG OS • Naruto")},actions={TextButton(onClick=vm::refresh){Text("Odśwież")}}) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach {
                    NavigationBarItem(
                        selected=tab==it,onClick={tab=it},
                        icon={Text(it.label.take(1))},label={Text(it.label)}
                    )
                }
            }
        }
    ){padding->
        Box(Modifier.padding(padding).fillMaxSize()){
            when(tab){
                Tab.GAME->GameScreen(vm)
                Tab.STATUS->FullStatusScreen(vm)
                Tab.TIME->TimeScreen(vm)
                Tab.WORLD->WorldScreen(vm)
                Tab.NPCS->NpcScreen(vm)
                Tab.SOCIAL->SocialScreen(vm)
                Tab.DASHBOARD->WorldDashboardScreen(vm)
                Tab.TECHNIQUES->TechniquesScreen(vm)
                Tab.MISSIONS->MissionsScreen(vm)
                Tab.VISUALS->VisualGeneratorScreen(vm)
                Tab.CHRONICLE->ChronicleScreen(vm)
                Tab.PACKAGES->PackagesScreen(vm)
                Tab.DB->DatabaseScreen(vm)
                Tab.GM->GmDiagnosticsScreen(vm)
                Tab.SETTINGS->SettingsScreen(vm)
            }
        }
    }
}

@Composable
private fun NpcScreen(vm:RpgOsViewModel){
    val npcs by vm.npcs.collectAsState()
    val detail by vm.selectedNpc.collectAsState()
    var q by remember{mutableStateOf("")}
    LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Text("NPC Browser",style=MaterialTheme.typography.headlineMedium)}
        item{OutlinedTextField(q,{q=it;vm.searchNpcs(it)},Modifier.fillMaxWidth(),label={Text("Szukaj NPC")})}
        items(npcs.take(200)){n->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
            Text(n.name,style=MaterialTheme.typography.titleMedium);Text("${n.clan} • ${n.village} • ${n.status}")
            Button(onClick={vm.selectNpc(n.uid)}){Text("Szczegóły")}
        }}}
        detail?.let{d->
            item{SectionTitle("Wybrany NPC: ${d.name}")}
            items(d.fields.take(30)){DataRow(it.key,it.value)}
            item{SectionTitle("Pamięć")};items(d.memories){Text("• $it")}
            item{SectionTitle("Przekonania")};items(d.beliefs){Text("• $it")}
            item{SectionTitle("Plany")};items(d.schedules){Text("• $it")}
            item{SectionTitle("Decyzje")};items(d.decisions){Text("• $it")}
        }
    }
}

@Composable
private fun WorldDashboardScreen(vm:RpgOsViewModel){
    val economies by vm.economies.collectAsState()
    val wars by vm.wars.collectAsState()
    val rel by vm.relationEdges.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Text("World Simulation Dashboard",style=MaterialTheme.typography.headlineMedium)}
        item{SectionTitle("Wojny / konflikty")}
        items(wars){w->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text(w.name);Text(w.status);Text(w.summary)}}}
        item{SectionTitle("Ekonomia")}
        items(economies){e->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
            Text(e.name,style=MaterialTheme.typography.titleMedium);Text("Skarbiec ${e.treasury} • dobrobyt ${e.prosperity} • stabilność ${e.stability}")
        }}}
        item{SectionTitle("Graf relacji")}
        item{RelationGraph(rel,Modifier.fillMaxWidth().height(320.dp))}
    }
}

@Composable
private fun DatabaseScreen(vm:RpgOsViewModel){
    val tables by vm.dbTables.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        item{Text("Database Explorer MG",style=MaterialTheme.typography.headlineMedium)}
        items(tables){t->Card(Modifier.fillMaxWidth()){Row(Modifier.fillMaxWidth().padding(10.dp),horizontalArrangement=Arrangement.SpaceBetween){
            Text(t.name);Text("${t.rows} • ${if(t.writable)"WRITE" else "READ"}")
        }}}
    }
}

@Composable
private fun SocialScreen(vm:RpgOsViewModel){
    val rel by vm.relationships.collectAsState()
    val org by vm.organizations.collectAsState()
    val pol by vm.politics.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Text("Relacje i polityka",style=MaterialTheme.typography.headlineMedium)}
        item{SectionTitle("Relacje")}
        items(rel){r->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
            Text(r.entityUid,style=MaterialTheme.typography.titleMedium);Text("${r.type} • ${r.score}")
        }}}
        item{SectionTitle("Organizacje")}
        items(org){o->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
            Text(o.name,style=MaterialTheme.typography.titleMedium);Text("${o.type} • ${o.status}")
        }}}
        item{SectionTitle("Polityka")}
        items(pol){p->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
            Text(p.name,style=MaterialTheme.typography.titleMedium)
            Text("Legitymizacja ${p.legitimacy} • wpływ ${p.influence} • stabilność ${p.stability}")
        }}}
    }
}

@Composable
private fun VisualGeneratorScreen(vm:RpgOsViewModel){
    val context=LocalContext.current
    val status by vm.imageStatus.collectAsState()
    val library by vm.visualLibrary.collectAsState()
    val suggestions by vm.visualSuggestions.collectAsState()

    var kind by remember{mutableStateOf("scene")}
    var title by remember{mutableStateOf("")}
    var prompt by remember{mutableStateOf("")}
    var traits by remember{mutableStateOf("")}
    var equipment by remember{mutableStateOf("")}
    var notes by remember{mutableStateOf("")}
    var selected by remember{mutableStateOf<VisualRecord?>(null)}
    var editInstruction by remember{mutableStateOf("")}

    LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{Text("Generator obrazów RPG",style=MaterialTheme.typography.headlineMedium)}

        item{SectionTitle("Sugestie dla bieżącej sceny")}
        items(suggestions){s->
            Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
                Text(s.title,style=MaterialTheme.typography.titleMedium)
                Text(s.reason)
                Button(
                    onClick={vm.generateSuggestedVisual(context,s)},
                    modifier=Modifier.fillMaxWidth().padding(top=6.dp)
                ){Text("Wygeneruj")}
            }}
        }

        item{SectionTitle("Nowy obraz")}
        item{
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(selected=kind=="scene",onClick={kind="scene"},label={Text("Scena")})
                FilterChip(selected=kind=="location",onClick={kind="location"},label={Text("Sceneria")})
                FilterChip(selected=kind=="character",onClick={kind="character"},label={Text("Postać")})
            }
        }
        item{
            OutlinedTextField(title,{title=it},Modifier.fillMaxWidth(),label={Text("Nazwa / tytuł")})
            OutlinedTextField(prompt,{prompt=it},Modifier.fillMaxWidth(),label={Text(if(kind=="scene")"Opis sceny" else "Opis")})
            if(kind=="character"){
                OutlinedTextField(traits,{traits=it},Modifier.fillMaxWidth(),label={Text("Cechy postaci")})
                OutlinedTextField(equipment,{equipment=it},Modifier.fillMaxWidth(),label={Text("Ekwipunek")})
                OutlinedTextField(notes,{notes=it},Modifier.fillMaxWidth(),label={Text("Uwagi o ciągłości")})
            }
            Button(
                onClick={
                    when(kind){
                        "scene"->vm.generateSceneImage(context,title,prompt)
                        "location"->vm.generateLocationImage(context,title,prompt)
                        else->vm.generateCharacterImage(context,title,traits,equipment,notes.ifBlank{prompt})
                    }
                },
                modifier=Modifier.fillMaxWidth().padding(top=8.dp)
            ){Text("Generuj i zapisz w galerii")}
            if(status.isNotBlank())Text(status,style=MaterialTheme.typography.bodySmall)
        }

        item{SectionTitle("Biblioteka kampanii")}
        items(library){g->
            Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
                Text(g.title,style=MaterialTheme.typography.titleMedium)
                Text("${g.kind} • rozdział ${g.chapter ?: "—"}")
                if(g.relatedEntityUid!=null) Text("Postać: ${g.relatedEntityUid}")
                if(g.relatedLocationUid!=null) Text("Lokacja: ${g.relatedLocationUid}")
                Text(g.uri,style=MaterialTheme.typography.bodySmall)
                Button(onClick={selected=g},modifier=Modifier.fillMaxWidth().padding(top=6.dp)){Text("Edytuj")}
            }}
        }

        if(selected!=null){
            item{SectionTitle("Edycja obrazu")}
            item{
                Text("Źródło: ${selected!!.title}")
                OutlinedTextField(
                    editInstruction,
                    {editInstruction=it},
                    Modifier.fillMaxWidth(),
                    label={Text("Co zmienić?")}
                )
                Button(
                    onClick={
                        val src=selected
                        if(src!=null && editInstruction.isNotBlank()){
                            vm.editVisual(context,src,editInstruction)
                            editInstruction=""
                            selected=null
                        }
                    },
                    modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                ){Text("Wygeneruj edytowaną wersję")}
            }
        }
    }
}

@Composable
private fun GmDiagnosticsScreen(vm:RpgOsViewModel){
    val d by vm.diagnostics.collectAsState()
    val sync by vm.sync.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Panel diagnostyczny MG",style=MaterialTheme.typography.headlineMedium)
        DataRow("Aktywna kampania",d.activeCampaign)
        DataRow("World Pack",d.activeWorldPack)
        DataRow("Backupy",d.backupCount.toString())
        DataRow("World Packi",d.worldPackCount.toString())
        DataRow("Kampanie",d.campaignCount.toString())
        DataRow("Source of Truth",d.sourceOfTruthDomains.toString())
        DataRow("Otwarte alerty timeline",d.openTimelineAlerts.toString())
        DataRow("Synchronizacja",if(sync.ok)"OK" else "BŁĘDY")
        sync.issues.forEach{Text("• $it")}
        Text(d.contextSummary,style=MaterialTheme.typography.bodySmall)
        Text("Ten ekran jest przeznaczony do diagnostyki silnika i nie powinien ujawniać graczowi ukrytej wiedzy świata.")
    }
}

@Composable
private fun WorldScreen(vm:RpgOsViewModel){
    val regions by vm.regions.collectAsState()
    val locations by vm.locations.collectAsState()
    val events by vm.worldEvents.collectAsState()
    var q by remember{mutableStateOf("")}
    LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Text("Świat",style=MaterialTheme.typography.headlineMedium)}
        item{OutlinedTextField(q,{q=it;vm.searchWorld(it)},Modifier.fillMaxWidth(),label={Text("Szukaj lokacji")})}
        item{SectionTitle("Aktywne wydarzenia")}
        items(events){e->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
            Text(e.name,style=MaterialTheme.typography.titleMedium);Text(e.status);Text(e.summary)
        }}}
        item{SectionTitle("Regiony")}
        items(regions){r->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
            Text(r.name,style=MaterialTheme.typography.titleMedium);Text(r.type);if(r.description.isNotBlank())Text(r.description)
        }}}
        item{SectionTitle("Mapa wizualna")}
        item{WorldMapCanvas(locations,Modifier.fillMaxWidth().height(300.dp))}
        item{SectionTitle("Lokacje")}
        items(locations){l->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
            Text(l.name,style=MaterialTheme.typography.titleMedium);Text("${l.type} • ${l.region}");if(l.description.isNotBlank())Text(l.description)
        }}}
    }
}

@Composable
private fun GameScreen(vm:RpgOsViewModel){
    val messages by vm.messages.collectAsState()
    val settings by vm.settings.collectAsState()
    val contextSummary by vm.lastContextSummary.collectAsState()
    var text by remember{mutableStateOf("")}
    Column(Modifier.fillMaxSize().padding(12.dp)){
        if(settings.showGmDiagnostics){Text(contextSummary,style=MaterialTheme.typography.labelSmall);Spacer(Modifier.height(6.dp))}
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){
            items(messages){msg->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
                Text(if(msg.role=="player")"Gracz" else if(msg.role=="gm")"Mistrz Gry" else "System",style=MaterialTheme.typography.labelMedium)
                Text(msg.text)
            }}}
        }
        OutlinedTextField(text,{text=it},Modifier.fillMaxWidth(),placeholder={Text("Co robisz?")})
        Button(onClick={vm.send(text);text=""},modifier=Modifier.fillMaxWidth().padding(top=8.dp)){Text("Wyślij")}
    }
}

@Composable
private fun FullStatusScreen(vm:RpgOsViewModel){
    val panel by vm.characterPanel.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Text("Panel postaci",style=MaterialTheme.typography.headlineMedium)}
        item{SectionTitle("Tożsamość")};items(panel.identity){DataRow(it.key,it.value)}
        item{SectionTitle("Statystyki")};items(panel.stats){DataRow(it.key,it.value)}
        item{SectionTitle("Zasoby")};items(panel.resources){DataRow(it.key,it.value)}
        item{SectionTitle("Umiejętności")};items(panel.skills){Text("${it.name} • ${it.mastery} • ${it.category}")}
        item{SectionTitle("Techniki")};items(panel.techniques){Text("${it.name} • mastery ${it.mastery} • chakra ${it.chakraCost}")}
        item{SectionTitle("Ekwipunek")};items(panel.equipment){Text(it)}
        item{SectionTitle("Relacje")};items(panel.relationships){Text(it)}
        item{SectionTitle("Cele")};items(panel.goals){Text("• $it")}
    }
}

@Composable private fun TimeScreen(vm:RpgOsViewModel){
    val t by vm.time.collectAsState()
    Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Czas świata",style=MaterialTheme.typography.headlineMedium)
        DataRow("Data",t.label);DataRow("Era",t.era);DataRow("Pora roku",t.season);DataRow("Godzina",t.hour)
    }
}

@Composable private fun ChronicleScreen(vm:RpgOsViewModel){
    val entries by vm.chronicle.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        if(entries.isEmpty())item{Text("Kronika jest pusta.")}
        items(entries){e->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
            Text("Rozdział ${e.chapter}: ${e.title}",style=MaterialTheme.typography.titleMedium);if(e.summary.isNotBlank())Text(e.summary)
        }}}
    }
}

@Composable private fun TechniquesScreen(vm:RpgOsViewModel){
    val list by vm.techniques.collectAsState();var q by remember{mutableStateOf("")}
    Column(Modifier.fillMaxSize().padding(12.dp)){
        OutlinedTextField(q,{q=it;vm.searchTechniques(it)},Modifier.fillMaxWidth(),label={Text("Szukaj techniki")})
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){items(list){t->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
            Text(t.name,style=MaterialTheme.typography.titleMedium);Text("${t.category} • ranga ${t.rank.ifBlank{"—"}} • ${t.element}")
            Text("Weryfikacja: ${t.verification}",style=MaterialTheme.typography.bodySmall)
        }}}}
    }
}

@Composable private fun MissionsScreen(vm:RpgOsViewModel){
    val missions by vm.missions.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Text("Misje i zlecenia",style=MaterialTheme.typography.headlineMedium)}
        items(missions){m->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
            Text("${m.rank} • ${m.title}",style=MaterialTheme.typography.titleMedium);Text("Status: ${m.status} • ${m.reward} ryō");Text(m.objective)
        }}}
    }
}

@Composable
private fun PackagesScreen(vm:RpgOsViewModel){
    val context=LocalContext.current
    val packs by vm.worldPacks.collectAsState()
    val campaigns by vm.campaigns.collectAsState()
    val backups by vm.backups.collectAsState()
    val activeCampaign by vm.activeCampaign.collectAsState()
    val activePack by vm.activeWorldPack.collectAsState()
    var newCampaign by remember{mutableStateOf("")}
    var importMessage by remember{mutableStateOf("")}

    val importCampaign=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){runCatching{
            val temp=FilePickerBridge(context).copyUriToTemp(uri,"campaign_import.zip")
            val target="Imported_${System.currentTimeMillis()}.campaign"
            importMessage=RpgPackageManager(context).validatedImportCampaign(temp,target).message;vm.refresh()
        }.onFailure{importMessage=it.message?:"Import failed"}}
    }
    val importWorld=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){runCatching{
            val temp=FilePickerBridge(context).copyUriToTemp(uri,"world_import.zip")
            val target="Imported_${System.currentTimeMillis()}.worldpack"
            importMessage=RpgPackageManager(context).validatedImportWorldPack(temp,target).message;vm.refresh()
        }.onFailure{importMessage=it.message?:"Import failed"}}
    }
    val exportCampaign=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->
        if(uri!=null){runCatching{
            val temp=File(context.cacheDir,"${activeCampaign}.zip")
            RpgPackageManager(context).exportCampaign(activeCampaign,temp)
            FilePickerBridge(context).copyFileToUri(temp,uri);importMessage="Eksport zakończony."
        }.onFailure{importMessage=it.message?:"Export failed"}}
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Text("World Packi",style=MaterialTheme.typography.headlineSmall)}
        items(packs){p->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
            Text(p.name,style=MaterialTheme.typography.titleMedium);Text(if(p.path.endsWith(activePack))"AKTYWNY" else "")
            Button(onClick={vm.activateWorldPack(File(p.path).name)}){Text("Aktywuj")}
        }}}
        item{SectionTitle("Kampanie")}
        items(campaigns){c->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
            Text(c.name,style=MaterialTheme.typography.titleMedium);Text("Backupy: ${c.backupCount}");Text(if(c.path.endsWith(activeCampaign))"AKTYWNA" else "")
            Button(onClick={vm.activateCampaign(File(c.path).name)}){Text("Aktywuj")}
        }}}
        item{
            OutlinedTextField(newCampaign,{newCampaign=it},Modifier.fillMaxWidth(),label={Text("Nowa kampania")})
            Button(onClick={vm.createCampaign(newCampaign)},modifier=Modifier.fillMaxWidth()){Text("Utwórz kampanię")}
        }
        item{SectionTitle("Import / eksport")}
        item{
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(onClick={importCampaign.launch(arrayOf("application/zip","application/octet-stream"))}){Text("Import Save")}
                Button(onClick={importWorld.launch(arrayOf("application/zip","application/octet-stream"))}){Text("Import World")}
            }
            Button(onClick={exportCampaign.launch("${activeCampaign}.zip")},modifier=Modifier.fillMaxWidth().padding(top=8.dp)){Text("Eksportuj aktywny Save")}
            if(importMessage.isNotBlank())Text(importMessage)
        }
        item{SectionTitle("Backupy")}
        items(backups){b->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
            Text(b,style=MaterialTheme.typography.bodySmall);Button(onClick={vm.restoreBackup(b)}){Text("Przywróć")}
        }}}
    }
}

@Composable private fun SettingsScreen(vm:RpgOsViewModel){
    val current by vm.settings.collectAsState()
    val backendTest by vm.backendTest.collectAsState()
    var backend by remember(current.backendUrl){mutableStateOf(current.backendUrl)}
    var diagnostics by remember(current.showGmDiagnostics){mutableStateOf(current.showGmDiagnostics)}
    var backups by remember(current.autoBackup){mutableStateOf(current.autoBackup)}
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Ustawienia",style=MaterialTheme.typography.headlineMedium)
        OutlinedTextField(backend,{backend=it},Modifier.fillMaxWidth(),label={Text("Adres backendu")})
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Diagnostyka MG");Switch(diagnostics,{diagnostics=it})}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Automatyczny backup");Switch(backups,{backups=it})}
        Button(onClick={vm.saveSettings(current.copy(backendUrl=backend.trim(),showGmDiagnostics=diagnostics,autoBackup=backups))},
            modifier=Modifier.fillMaxWidth()){Text("Zapisz ustawienia")}
        Button(onClick={vm.testBackend()},modifier=Modifier.fillMaxWidth()){Text("Testuj backend")}
        Text(backendTest,style=MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun SectionTitle(text:String){Spacer(Modifier.height(8.dp));Text(text,style=MaterialTheme.typography.titleLarge);HorizontalDivider()}
@Composable private fun DataRow(label:String,value:String){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label,style=MaterialTheme.typography.labelLarge);Text(value)}}

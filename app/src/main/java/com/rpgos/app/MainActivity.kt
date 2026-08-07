@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.rpgos.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RpgOsTheme {
                val vm: RpgOsViewModel = viewModel()
                RpgOsApp(vm)
            }
        }
    }
}

private val RpgOsColors = darkColorScheme(
    primary = Color(0xFFB8A7FF),
    onPrimary = Color(0xFF24164E),
    primaryContainer = Color(0xFF39276D),
    onPrimaryContainer = Color(0xFFE7DEFF),
    secondary = Color(0xFF9FC9FF),
    onSecondary = Color(0xFF062E52),
    secondaryContainer = Color(0xFF173F68),
    onSecondaryContainer = Color(0xFFD4E7FF),
    tertiary = Color(0xFFFFB4C8),
    background = Color(0xFF0E1017),
    onBackground = Color(0xFFE5E1EC),
    surface = Color(0xFF151821),
    onSurface = Color(0xFFE5E1EC),
    surfaceVariant = Color(0xFF222631),
    onSurfaceVariant = Color(0xFFC9C5D0),
    outline = Color(0xFF8F8A98),
    error = Color(0xFFFFB4AB)
)

@Composable
private fun RpgOsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RpgOsColors,
        content = content
    )
}

private enum class AppRoute {
    HOME, NEW_GAME, NARUTO_SETUP, CONTINUE, SAVES, GALLERY, SETTINGS, ABOUT, CAMPAIGN
}

private enum class CampaignTab(val label: String, val glyph: String) {
    GAME("Gra", "◆"),
    CHARACTER("Postać", "◇"),
    CHRONICLE("Kronika", "▤"),
    WORLD("Świat", "◎"),
    MENU("Menu", "≡")
}

private enum class CampaignTool(val title: String) {
    NPCS("NPC"),
    SOCIAL("Relacje"),
    TECHNIQUES("Techniki"),
    MISSIONS("Misje"),
    GALLERY("Galeria"),
    TIME("Czas"),
    DASHBOARD("Symulacja świata"),
    PACKAGES("Kampanie i pakiety"),
    GM("Diagnostyka MG"),
    DEV("Panel deweloperski"),
    DB("Baza danych"),
    SETTINGS("Ustawienia")
}

@Composable
fun RpgOsApp(vm: RpgOsViewModel) {
    var route by remember { mutableStateOf(AppRoute.HOME) }

    when (route) {
        AppRoute.HOME -> HomeScreen(
            onNewGame = { route = AppRoute.NEW_GAME },
            onContinue = { route = AppRoute.CONTINUE },
            onSaves = { route = AppRoute.SAVES },
            onGallery = { route = AppRoute.GALLERY },
            onSettings = { route = AppRoute.SETTINGS },
            onAbout = { route = AppRoute.ABOUT }
        )

        AppRoute.NEW_GAME -> WorldSelectionScreen(
            onBack = { route = AppRoute.HOME },
            onNaruto = { route = AppRoute.NARUTO_SETUP }
        )

        AppRoute.NARUTO_SETUP -> NarutoSetupScreen(
            vm = vm,
            onBack = { route = AppRoute.NEW_GAME },
            onEnterCampaign = { route = AppRoute.CAMPAIGN }
        )

        AppRoute.CONTINUE -> ContinueScreen(
            vm = vm,
            onBack = { route = AppRoute.HOME },
            onContinue = { dirName ->
                vm.activateCampaign(dirName)
                route = AppRoute.CAMPAIGN
            }
        )

        AppRoute.SAVES -> StandardPage(
            title = "Zapisy i kampanie",
            onBack = { route = AppRoute.HOME }
        ) { PackagesScreen(vm) }

        AppRoute.GALLERY -> StandardPage(
            title = "Galeria",
            onBack = { route = AppRoute.HOME }
        ) { VisualGeneratorScreen(vm) }

        AppRoute.SETTINGS -> StandardPage(
            title = "Ustawienia",
            onBack = { route = AppRoute.HOME }
        ) { SettingsScreen(vm) }

        AppRoute.ABOUT -> AboutScreen(onBack = { route = AppRoute.HOME })

        AppRoute.CAMPAIGN -> CampaignShell(
            vm = vm,
            onExit = { route = AppRoute.HOME }
        )
    }
}

@Composable
private fun HomeScreen(
    onNewGame: () -> Unit,
    onContinue: () -> Unit,
    onSaves: () -> Unit,
    onGallery: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 42.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "RPG OS",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black
                )
                Text(
                    "Twoje kampanie. Jeden system.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        "ALPHA • ${BuildConfig.VERSION_NAME}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.height(18.dp))
            }

            item {
                PrimaryHomeCard(
                    title = "Nowa gra",
                    subtitle = "Wybierz świat i rozpocznij nową kampanię.",
                    glyph = "＋",
                    onClick = onNewGame
                )
            }

            item {
                HomeCard(
                    title = "Kontynuuj",
                    subtitle = "Wróć do ostatniej lub wybranej kampanii.",
                    glyph = "▶",
                    onClick = onContinue
                )
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CompactHomeCard(
                        modifier = Modifier.weight(1f),
                        title = "Zapisy",
                        glyph = "▣",
                        onClick = onSaves
                    )
                    CompactHomeCard(
                        modifier = Modifier.weight(1f),
                        title = "Galeria",
                        glyph = "▧",
                        onClick = onGallery
                    )
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CompactHomeCard(
                        modifier = Modifier.weight(1f),
                        title = "Ustawienia",
                        glyph = "⚙",
                        onClick = onSettings
                    )
                    CompactHomeCard(
                        modifier = Modifier.weight(1f),
                        title = "O programie",
                        glyph = "i",
                        onClick = onAbout
                    )
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    "RPG OS jest silnikiem kampanii. Świat wybierasz dopiero podczas tworzenia gry.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PrimaryHomeCard(
    title: String,
    subtitle: String,
    glyph: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Text(
                    glyph,
                    modifier = Modifier.padding(horizontal = 17.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Text("›", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun HomeCard(
    title: String,
    subtitle: String,
    glyph: String,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(glyph, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("›", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun CompactHomeCard(
    modifier: Modifier = Modifier,
    title: String,
    glyph: String,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.height(112.dp),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(glyph, style = MaterialTheme.typography.headlineSmall)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun WorldSelectionScreen(
    onBack: () -> Unit,
    onNaruto: () -> Unit
) {
    StandardPage(title = "Nowa gra", onBack = onBack) {
        LazyColumn(
            Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    "Wybierz świat",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Każdy świat jest osobnym modułem zasad, wiedzy i kampanii.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Card(
                    onClick = onNaruto,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(22.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Naruto", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    "DOSTĘPNY",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Świat shinobi • kampanie długoterminowe • pełna baza świata",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(18.dp))
                        Button(onClick = onNaruto, modifier = Modifier.fillMaxWidth()) {
                            Text("Wybierz świat")
                        }
                    }
                }
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Więcej światów w przyszłości", fontWeight = FontWeight.Bold)
                        Text(
                            "Architektura RPG OS jest już przygotowana na kolejne moduły.",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NarutoSetupScreen(
    vm: RpgOsViewModel,
    onBack: () -> Unit,
    onEnterCampaign: () -> Unit
) {
    var campaignName by remember { mutableStateOf("") }

    StandardPage(title = "Naruto • Nowa kampania", onBack = onBack) {
        LazyColumn(
            Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    "Utwórz kampanię",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Na tym etapie RPG OS używa domyślnego pakietu świata Naruto. Rozbudowany kreator kampanii pojawi się w kolejnych wersjach.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                ElevatedCard(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        OutlinedTextField(
                            value = campaignName,
                            onValueChange = { campaignName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Nazwa kampanii") },
                            placeholder = { Text("np. Era Hashiramy") },
                            singleLine = true
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                vm.createAndActivateCampaign(campaignName)
                                onEnterCampaign()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Rozpocznij kampanię")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueScreen(
    vm: RpgOsViewModel,
    onBack: () -> Unit,
    onContinue: (String) -> Unit
) {
    val campaigns by vm.campaigns.collectAsState()
    val activeCampaign by vm.activeCampaign.collectAsState()

    StandardPage(title = "Kontynuuj", onBack = onBack) {
        LazyColumn(
            Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Twoje kampanie",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (campaigns.isEmpty()) {
                item {
                    EmptyState(
                        title = "Brak kampanii",
                        text = "Utwórz pierwszą grę z ekranu „Nowa gra”."
                    )
                }
            }

            items(campaigns) { campaign ->
                val dirName = File(campaign.path).name
                ElevatedCard(
                    onClick = { onContinue(dirName) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                campaign.name,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            if (campaign.path.endsWith(activeCampaign)) {
                                Text(
                                    "AKTYWNA",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Backupy: ${campaign.backupCount}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(
                            onClick = { onContinue(dirName) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Kontynuuj")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CampaignShell(
    vm: RpgOsViewModel,
    onExit: () -> Unit
) {
    var tab by remember { mutableStateOf(CampaignTab.GAME) }
    var tool by remember { mutableStateOf<CampaignTool?>(null) }
    val activeCampaign by vm.activeCampaign.collectAsState()

    if (tool != null) {
        StandardPage(
            title = tool!!.title,
            onBack = { tool = null }
        ) {
            when (tool!!) {
                CampaignTool.NPCS -> NpcScreen(vm)
                CampaignTool.SOCIAL -> SocialScreen(vm)
                CampaignTool.TECHNIQUES -> TechniquesScreen(vm)
                CampaignTool.MISSIONS -> MissionsScreen(vm)
                CampaignTool.GALLERY -> VisualGeneratorScreen(vm)
                CampaignTool.TIME -> TimeScreen(vm)
                CampaignTool.DASHBOARD -> WorldDashboardScreen(vm)
                CampaignTool.PACKAGES -> PackagesScreen(vm)
                CampaignTool.GM -> GmDiagnosticsScreen(vm)
                CampaignTool.DEV -> DeveloperPanelScreen(vm)
                CampaignTool.DB -> DatabaseScreen(vm)
                CampaignTool.SETTINGS -> SettingsScreen(vm)
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("RPG OS", fontWeight = FontWeight.Bold)
                        Text(
                            activeCampaign.ifBlank { "Aktywna kampania" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onExit) { Text("Wyjdź") }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                CampaignTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Text(item.glyph, fontWeight = FontWeight.Bold) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                CampaignTab.GAME -> GameScreen(vm)
                CampaignTab.CHARACTER -> FullStatusScreen(vm)
                CampaignTab.CHRONICLE -> ChronicleScreen(vm)
                CampaignTab.WORLD -> WorldScreen(vm)
                CampaignTab.MENU -> CampaignMenuScreen(
                    onTool = { tool = it },
                    onExit = onExit
                )
            }
        }
    }
}

@Composable
private fun CampaignMenuScreen(
    onTool: (CampaignTool) -> Unit,
    onExit: () -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                "Narzędzia kampanii",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Funkcje dodatkowe są schowane tutaj, żeby główny interfejs gry pozostał prosty.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item { MenuSection("Rozgrywka") }
        item {
            MenuGridRow(
                left = "NPC" to CampaignTool.NPCS,
                right = "Relacje" to CampaignTool.SOCIAL,
                onTool = onTool
            )
        }
        item {
            MenuGridRow(
                left = "Techniki" to CampaignTool.TECHNIQUES,
                right = "Misje" to CampaignTool.MISSIONS,
                onTool = onTool
            )
        }
        item {
            MenuGridRow(
                left = "Galeria" to CampaignTool.GALLERY,
                right = "Czas" to CampaignTool.TIME,
                onTool = onTool
            )
        }

        item { MenuSection("System") }
        item {
            MenuGridRow(
                left = "Symulacja świata" to CampaignTool.DASHBOARD,
                right = "Kampanie" to CampaignTool.PACKAGES,
                onTool = onTool
            )
        }
        item {
            MenuGridRow(
                left = "Ustawienia" to CampaignTool.SETTINGS,
                right = "Diagnostyka" to CampaignTool.GM,
                onTool = onTool
            )
        }
        item {
            MenuGridRow(
                left = "Dev" to CampaignTool.DEV,
                right = "Baza danych" to CampaignTool.DB,
                onTool = onTool
            )
        }

        item {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                Text("Powrót do ekranu głównego")
            }
        }
    }
}

@Composable
private fun MenuGridRow(
    left: Pair<String, CampaignTool>,
    right: Pair<String, CampaignTool>,
    onTool: (CampaignTool) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SmallMenuCard(
            modifier = Modifier.weight(1f),
            text = left.first,
            onClick = { onTool(left.second) }
        )
        SmallMenuCard(
            modifier = Modifier.weight(1f),
            text = right.first,
            onClick = { onTool(right.second) }
        )
    }
}

@Composable
private fun SmallMenuCard(
    modifier: Modifier = Modifier,
    text: String,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.height(82.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Box(
            Modifier.fillMaxSize().padding(14.dp),
            contentAlignment = Alignment.BottomStart
        ) {
            Text(text, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MenuSection(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun StandardPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("‹ Wróć") }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            content()
        }
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    StandardPage(title = "O RPG OS", onBack = onBack) {
        LazyColumn(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    "RPG OS",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black
                )
                Text(
                    "Silnik długoterminowych kampanii RPG wspieranych przez AI.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                ElevatedCard(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        DataRow("Wersja", BuildConfig.VERSION_NAME)
                        DataRow("VersionCode", BuildConfig.VERSION_CODE.toString())
                        DataRow("Kanał", "ALPHA")
                    }
                }
            }
            item {
                Text(
                    "RPG OS oddziela silnik aplikacji od światów gry. " +
                        "Na ekranie głównym nie ma żadnego konkretnego uniwersum; " +
                        "świat wybierasz dopiero podczas tworzenia kampanii."
                )
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                text,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

    Column(
        Modifier.fillMaxSize().padding(horizontal=14.dp,vertical=10.dp)
    ){
        if(settings.showGmDiagnostics){
            Surface(
                shape=RoundedCornerShape(14.dp),
                color=MaterialTheme.colorScheme.surfaceVariant
            ){
                Text(
                    contextSummary,
                    modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),
                    style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement=Arrangement.spacedBy(10.dp),
            contentPadding=PaddingValues(bottom=12.dp)
        ){
            items(messages){msg->
                val isPlayer=msg.role=="player"
                val isGm=msg.role=="gm"
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement=if(isPlayer) Arrangement.End else Arrangement.Start
                ){
                    Card(
                        modifier=Modifier.fillMaxWidth(if(isPlayer)0.88f else 0.96f),
                        shape=RoundedCornerShape(
                            topStart=20.dp,
                            topEnd=20.dp,
                            bottomStart=if(isPlayer)20.dp else 6.dp,
                            bottomEnd=if(isPlayer)6.dp else 20.dp
                        ),
                        colors=CardDefaults.cardColors(
                            containerColor=when{
                                isPlayer->MaterialTheme.colorScheme.primaryContainer
                                isGm->MaterialTheme.colorScheme.surfaceVariant
                                else->MaterialTheme.colorScheme.surface
                            }
                        )
                    ){
                        Column(Modifier.padding(14.dp)){
                            Text(
                                when(msg.role){
                                    "player"->"TY"
                                    "gm"->"MISTRZ GRY"
                                    else->"SYSTEM"
                                },
                                style=MaterialTheme.typography.labelSmall,
                                color=if(isPlayer)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.secondary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(msg.text,style=MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }

        Surface(
            shape=RoundedCornerShape(22.dp),
            tonalElevation=2.dp
        ){
            Column(Modifier.padding(10.dp)){
                OutlinedTextField(
                    value=text,
                    onValueChange={text=it},
                    modifier=Modifier.fillMaxWidth(),
                    placeholder={Text("Co robisz?")},
                    maxLines=5,
                    shape=RoundedCornerShape(18.dp)
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick={
                        val sendText=text.trim()
                        if(sendText.isNotBlank()){
                            vm.send(sendText)
                            text=""
                        }
                    },
                    modifier=Modifier.fillMaxWidth(),
                    shape=RoundedCornerShape(16.dp)
                ){
                    Text("Wyślij akcję")
                }
            }
        }
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


@Composable
private fun DeveloperPanelScreen(vm:RpgOsViewModel){
    val devStatus by vm.developerStatus.collectAsState()
    val diagnostic by vm.developerDiagnostic.collectAsState()
    val context=LocalContext.current

    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp)
    ){
        item{
            Text("Panel deweloperski",style=MaterialTheme.typography.headlineMedium)
            Text(
                "Narzędzia diagnostyczne RPG OS bez Termuxa.",
                style=MaterialTheme.typography.bodySmall
            )
        }

        item{SectionTitle("Stan systemu")}
        item{
            Card(Modifier.fillMaxWidth()){
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    Text("Wersja: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    Text(devStatus)
                }
            }
        }

        item{SectionTitle("Testy")}
        item{
            Button(onClick={vm::runDeveloperSelfTest},modifier=Modifier.fillMaxWidth()){
                Text("Uruchom pełny Self-Test")
            }
            Button(onClick={vm::testContextBuilder},modifier=Modifier.fillMaxWidth()){
                Text("Test ContextBundle")
            }
            Button(onClick={vm::testBackendConnection},modifier=Modifier.fillMaxWidth()){
                Text("Test backendu")
            }
            Button(onClick={vm::createDeveloperBackup},modifier=Modifier.fillMaxWidth()){
                Text("Utwórz backup diagnostyczny")
            }
        }

        item{SectionTitle("Anti-Crash / diagnostyka")}
        item{
            Button(onClick={vm::loadDeveloperDiagnostics},modifier=Modifier.fillMaxWidth()){
                Text("Odśwież raport błędów")
            }
            Button(onClick={vm::clearDeveloperDiagnostics},modifier=Modifier.fillMaxWidth()){
                Text("Wyczyść raport błędów")
            }
        }
        item{
            Card(Modifier.fillMaxWidth()){
                Text(
                    if(diagnostic.isBlank()) "Brak raportu." else diagnostic,
                    Modifier.padding(12.dp),
                    style=MaterialTheme.typography.bodySmall
                )
            }
        }

        item{SectionTitle("Szybkie akcje")}
        item{
            Button(onClick=vm::refresh,modifier=Modifier.fillMaxWidth()){
                Text("Przeładuj stan gry")
            }
            Text(
                "Panel nie modyfikuje fabuły. Self-Test używa tylko bezpiecznych odczytów i diagnostyki.",
                style=MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable private fun SettingsScreen(vm:RpgOsViewModel){
    val context=LocalContext.current
    val current by vm.settings.collectAsState()
    val updateStatus by vm.updateStatus.collectAsState()
    val availableUpdate by vm.availableUpdate.collectAsState()

    var backend by remember(current.backendUrl){mutableStateOf(current.backendUrl)}
    var updateFeed by remember(current.updateFeedUrl){mutableStateOf(current.updateFeedUrl)}
    var diagnostics by remember(current.showGmDiagnostics){mutableStateOf(current.showGmDiagnostics)}
    var backups by remember(current.autoBackup){mutableStateOf(current.autoBackup)}

    val localApkPicker=rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ){uri->
        if(uri!=null) vm.selectLocalUpdate(context,uri)
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)
    ){
        item{Text("Ustawienia",style=MaterialTheme.typography.headlineMedium)}
        item{
            OutlinedTextField(
                backend,
                {backend=it},
                Modifier.fillMaxWidth(),
                label={Text("Adres AI / backendu gry")}
            )
            OutlinedTextField(
                updateFeed,
                {updateFeed=it},
                Modifier.fillMaxWidth(),
                label={Text("Kanał aktualizacji GitHub")}
            )
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                Text("Diagnostyka MG");Switch(diagnostics,{diagnostics=it})
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                Text("Automatyczny backup");Switch(backups,{backups=it})
            }
            Button(
                onClick={vm.saveSettings(current.copy(
                    backendUrl=backend.trim(),
                    updateFeedUrl=updateFeed.trim(),
                    showGmDiagnostics=diagnostics,
                    autoBackup=backups
                ))},
                modifier=Modifier.fillMaxWidth()
            ){Text("Zapisz ustawienia")}
        }

        item{SectionTitle("Aktualizacje RPG OS")}
        item{
            Text("Zainstalowana: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            if(availableUpdate!=null){
                Text("Online: ${availableUpdate!!.versionName} (${availableUpdate!!.versionCode})")
                if(availableUpdate!!.notes.isNotBlank())Text(availableUpdate!!.notes)
            }
            Text(updateStatus,style=MaterialTheme.typography.bodySmall)
        }
        item{
            Button(onClick={vm.checkForUpdates(context)},modifier=Modifier.fillMaxWidth()){
                Text("Sprawdź aktualizacje online")
            }
            Button(onClick={vm.downloadOnlineUpdate(context)},modifier=Modifier.fillMaxWidth()){
                Text("Pobierz aktualizację online")
            }
            Button(
                onClick={localApkPicker.launch(arrayOf(
                    "application/vnd.android.package-archive",
                    "application/octet-stream"
                ))},
                modifier=Modifier.fillMaxWidth()
            ){Text("Wybierz lokalny plik APK")}

            Button(onClick={vm.installPreparedUpdate(context)},modifier=Modifier.fillMaxWidth()){
                Text("Zainstaluj przygotowaną aktualizację")
            }

            Text(
                "Przed instalacją tworzony jest backup aktywnej kampanii. " +
                "APK jest sprawdzany pod kątem pakietu, wersji i podpisu; " +
                "aktualizacja online czyta GitHub Releases i dodatkowo sprawdza SHA-256. " +
                "Kanał aktualizacji jest niezależny od backendu AI.",
                style=MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable private fun SectionTitle(text:String){Spacer(Modifier.height(8.dp));Text(text,style=MaterialTheme.typography.titleLarge);HorizontalDivider()}
@Composable private fun DataRow(label:String,value:String){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label,style=MaterialTheme.typography.labelLarge);Text(value)}}

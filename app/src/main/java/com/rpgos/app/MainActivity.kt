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
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
    primary = Color(0xFF4EA8FF),
    onPrimary = Color(0xFF001E35),
    primaryContainer = Color(0xFF073B6B),
    onPrimaryContainer = Color(0xFFD8ECFF),
    secondary = Color(0xFF2ED6C7),
    onSecondary = Color(0xFF00201D),
    secondaryContainer = Color(0xFF07554F),
    onSecondaryContainer = Color(0xFFC9FFF8),
    tertiary = Color(0xFF8EC5FF),
    background = Color(0xFF030812),
    onBackground = Color(0xFFE6F0FF),
    surface = Color(0xFF07111F),
    onSurface = Color(0xFFE6F0FF),
    surfaceVariant = Color(0xFF0B1B2B),
    onSurfaceVariant = Color(0xFFB8C8DA),
    surfaceContainerLowest = Color(0xFF030812),
    surfaceContainerLow = Color(0xFF06101C),
    surfaceContainer = Color(0xFF081522),
    surfaceContainerHigh = Color(0xFF0A1928),
    surfaceContainerHighest = Color(0xFF0D2030),
    surfaceBright = Color(0xFF10293B),
    surfaceDim = Color(0xFF020710),
    outline = Color(0xFF31516D),
    error = Color(0xFFFFB4AB)
)

@Composable
private fun RpgOsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RpgOsColors) {
        CompositionLocalProvider(
            LocalContentColor provides RpgOsColors.onBackground
        ) {
            content()
        }
    }
}

private val ScreenGradient = Brush.verticalGradient(
    colorStops = arrayOf(
        0.00f to Color(0xFF01050D),
        0.20f to Color(0xFF03101D),
        0.46f to Color(0xFF041B2C),
        0.70f to Color(0xFF07313A),
        0.86f to Color(0xFF06252D),
        1.00f to Color(0xFF01050A)
    )
)

private val BlueGradient = Brush.horizontalGradient(
    listOf(Color(0xFF0C4ECF), Color(0xFF1473E6), Color(0xFF0CA4CF))
)

private val TealGradient = Brush.horizontalGradient(
    listOf(Color(0xFF007D78), Color(0xFF0A9D90), Color(0xFF0E7B86))
)

@Composable
private fun GradientScreen(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(ScreenGradient)) {
        Box(
            Modifier.fillMaxWidth().height(420.dp).background(
                Brush.radialGradient(
                    colors = listOf(Color(0x3322A8FF), Color(0x1810C7D4), Color.Transparent),
                    radius = 760f
                )
            )
        )
        Box(
            Modifier.fillMaxWidth().height(560.dp).background(
                Brush.horizontalGradient(
                    colors = listOf(Color(0x141E8FFF), Color.Transparent, Color(0x1219D4C6))
                )
            )
        )
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground
        ) {
            Box(Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}

@Composable
private fun GradientActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    brush: Brush = BlueGradient
) {
    val shape = RoundedCornerShape(
        topStart = 22.dp,
        topEnd = 12.dp,
        bottomStart = 12.dp,
        bottomEnd = 24.dp
    )
    Surface(
        onClick = onClick,
        modifier = modifier.height(58.dp).background(brush, shape),
        shape = shape,
        color = Color.Transparent,
        contentColor = Color.White
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun GlowPanel(
    modifier: Modifier = Modifier,
    borderColor: Color = Color(0x6658B8FF),
    shape: RoundedCornerShape,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val base = modifier
        .background(
            Brush.verticalGradient(listOf(Color(0xEA0A1624), Color(0xEA050B13))),
            shape
        )
        .border(1.dp, borderColor, shape)

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = base,
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) { Column(Modifier.padding(18.dp), content = content) }
    } else {
        Card(
            modifier = base,
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) { Column(Modifier.padding(18.dp), content = content) }
    }
}

private enum class AppRoute {
    HOME, NEW_GAME, NARUTO_SETUP, CHARACTER_CREATOR, CONTINUE, SAVES, GALLERY, SETTINGS, ABOUT, CAMPAIGN
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
    AI("AI"),
    GM("Diagnostyka MG"),
    DEV("Panel deweloperski"),
    DB("Baza danych"),
    SETTINGS("Ustawienia")
}

@Composable
fun RpgOsApp(vm: RpgOsViewModel) {
    var route by remember { mutableStateOf(AppRoute.HOME) }
    val hasActivePlayer by vm.hasActivePlayer.collectAsState()
    when (route) {
        AppRoute.HOME -> HomeScreen(
            vm = vm,
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
            onEnterCampaign = { requiresCharacterCreation ->
                route = if(requiresCharacterCreation) AppRoute.CHARACTER_CREATOR else AppRoute.CAMPAIGN
            }
        )

        AppRoute.CHARACTER_CREATOR -> CharacterCreatorScreen(
            vm=vm,
            onBack={route=AppRoute.HOME},
            onOpenAiSettings={route=AppRoute.SETTINGS},
            onEnterCampaign={route=AppRoute.CAMPAIGN}
        )

        AppRoute.CONTINUE -> ContinueScreen(
            vm = vm,
            onBack = { route = AppRoute.HOME },
            onContinue = { dirName ->
                vm.activateCampaign(dirName)
                route = if(vm.hasActivePlayer.value) AppRoute.CAMPAIGN else AppRoute.CHARACTER_CREATOR
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
    vm: RpgOsViewModel,
    onNewGame: () -> Unit,
    onContinue: () -> Unit,
    onSaves: () -> Unit,
    onGallery: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit
) {
    val campaigns by vm.campaigns.collectAsState()
    val backups by vm.backups.collectAsState()
    val worldPacks by vm.worldPacks.collectAsState()
    val alphaLabel=remember(BuildConfig.VERSION_NAME){
        Regex("alpha(\\d+)",RegexOption.IGNORE_CASE).find(BuildConfig.VERSION_NAME)
            ?.groupValues?.getOrNull(1)?.let{"ALPHA $it"}?:"ALPHA"
    }
    GradientScreen {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 38.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                SystemHeader()
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xB8061422),
                        border = BorderStroke(1.dp, Color(0xAA22BFFF))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("✦", color = Color(0xFF63E6FF), style = MaterialTheme.typography.labelLarge)
                            Text(alphaLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color(0xFF5DDCFF))
                            Text("•", color = Color(0xFF5D7690))
                            Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.labelMedium, color = Color(0xFFD8E9F7))
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }

            item {
                PremiumHomeAction(
                    title = "Nowa gra",
                    subtitle = "Wybierz świat i rozpocznij nową kampanię.",
                    icon = HomeIcon.NEW_GAME,
                    accent = Color(0xFF48B7FF),
                    iconBrush = BlueGradient,
                    onClick = onNewGame
                )
            }

            item {
                PremiumHomeAction(
                    title = "Kontynuuj",
                    subtitle = "Wróć do ostatniej lub wybranej kampanii.",
                    icon = HomeIcon.CONTINUE,
                    accent = Color(0xFF56E1D2),
                    iconBrush = TealGradient,
                    onClick = onContinue
                )
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PremiumMiniHomeCard(
                        modifier = Modifier.weight(1f),
                        title = "Zapisy",
                        subtitle = "Kampanie i kopie",
                        icon = HomeIcon.SAVES,
                        accent = Color(0xFF7BBEFF),
                        onClick = onSaves
                    )
                    PremiumMiniHomeCard(
                        modifier = Modifier.weight(1f),
                        title = "Galeria",
                        subtitle = "Obrazy i sceny",
                        icon = HomeIcon.GALLERY,
                        accent = Color(0xFF56E1D2),
                        onClick = onGallery
                    )
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PremiumMiniHomeCard(
                        modifier = Modifier.weight(1f),
                        title = "Ustawienia",
                        subtitle = "Dostosuj system",
                        icon = HomeIcon.SETTINGS,
                        accent = Color(0xFF52D8FF),
                        onClick = onSettings
                    )
                    PremiumMiniHomeCard(
                        modifier = Modifier.weight(1f),
                        title = "O programie",
                        subtitle = "Wersja i informacje",
                        icon = HomeIcon.ABOUT,
                        accent = Color(0xFF8FC9FF),
                        onClick = onAbout
                    )
                }
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp, 26.dp, 18.dp, 22.dp),
                    color = Color(0xB5071420),
                    border = BorderStroke(1.dp, Color(0x443F95C7))
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatMini(worldPacks.size.toString(), "Światów")
                        StatMini(campaigns.count{File(it.path).name!=ActiveCampaignRef.DEFAULT_DIRECTORY}.toString(), "Kampanie")
                        StatMini(backups.size.toString(), "Backupy")
                        StatMini(alphaLabel, "Wersja")
                    }
                }
            }
        }
    }
}


@Composable
private fun SystemHeader() {
    Box(modifier = Modifier.fillMaxWidth().height(184.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SYSTEM", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Color(0xFF44D8FF))
            Spacer(Modifier.height(5.dp))
            Text("RPG OS", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black, color = Color(0xFFE4F4FF))
            Spacer(Modifier.height(9.dp))
            Row(modifier = Modifier.width(210.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFF45D9FF)))))
                Text("◇", modifier = Modifier.padding(horizontal = 8.dp), color = Color(0xFF69E7FF))
                Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(Color(0xFF45D9FF), Color.Transparent))))
            }
            Spacer(Modifier.height(9.dp))
            Text("Twoje kampanie. Jeden system.", style = MaterialTheme.typography.titleMedium, color = Color(0xFFC1D0E1))
        }
    }
}

private enum class HomeIcon { NEW_GAME, CONTINUE, SAVES, GALLERY, SETTINGS, ABOUT }

@Composable
private fun RpgHomeIcon(icon: HomeIcon, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val c = Offset(w / 2f, h / 2f)
        val sw = size.minDimension * 0.085f
        when (icon) {
            HomeIcon.NEW_GAME -> {
                drawCircle(color.copy(alpha = 0.95f), radius = size.minDimension * 0.34f, center = c, style = Stroke(width = sw))
                drawLine(color, Offset(w * 0.50f, h * 0.31f), Offset(w * 0.50f, h * 0.69f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.31f, h * 0.50f), Offset(w * 0.69f, h * 0.50f), sw, StrokeCap.Round)
            }
            HomeIcon.CONTINUE -> {
                val p = Path().apply {
                    moveTo(w * 0.35f, h * 0.27f)
                    lineTo(w * 0.72f, h * 0.50f)
                    lineTo(w * 0.35f, h * 0.73f)
                    close()
                }
                drawPath(p, color)
            }
            HomeIcon.SAVES -> {
                val p = Path().apply {
                    moveTo(w * 0.24f, h * 0.20f)
                    lineTo(w * 0.68f, h * 0.20f)
                    lineTo(w * 0.78f, h * 0.30f)
                    lineTo(w * 0.78f, h * 0.80f)
                    lineTo(w * 0.22f, h * 0.80f)
                    lineTo(w * 0.22f, h * 0.20f)
                    close()
                }
                drawPath(p, color, style = Stroke(width = sw))
                drawLine(color, Offset(w * 0.34f, h * 0.22f), Offset(w * 0.34f, h * 0.43f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.34f, h * 0.62f), Offset(w * 0.66f, h * 0.62f), sw, StrokeCap.Round)
            }
            HomeIcon.GALLERY -> {
                drawRoundRect(color, Offset(w * 0.18f, h * 0.22f), androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.56f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f), style = Stroke(width = sw))
                drawCircle(color, radius = w * 0.075f, center = Offset(w * 0.62f, h * 0.38f))
                val p = Path().apply {
                    moveTo(w * 0.25f, h * 0.67f)
                    lineTo(w * 0.42f, h * 0.49f)
                    lineTo(w * 0.53f, h * 0.60f)
                    lineTo(w * 0.62f, h * 0.52f)
                    lineTo(w * 0.76f, h * 0.67f)
                }
                drawPath(p, color, style = Stroke(width = sw))
            }
            HomeIcon.SETTINGS -> {
                drawCircle(color, radius = size.minDimension * 0.25f, center = c, style = Stroke(width = sw))
                drawCircle(color, radius = size.minDimension * 0.075f, center = c, style = Stroke(width = sw))
                repeat(8) { i ->
                    val a = Math.toRadians((i * 45.0) - 90.0)
                    val r1 = size.minDimension * 0.31f
                    val r2 = size.minDimension * 0.41f
                    drawLine(color, Offset(c.x + (kotlin.math.cos(a) * r1).toFloat(), c.y + (kotlin.math.sin(a) * r1).toFloat()), Offset(c.x + (kotlin.math.cos(a) * r2).toFloat(), c.y + (kotlin.math.sin(a) * r2).toFloat()), sw, StrokeCap.Round)
                }
            }
            HomeIcon.ABOUT -> {
                drawCircle(color, radius = size.minDimension * 0.35f, center = c, style = Stroke(width = sw))
                drawCircle(color, radius = size.minDimension * 0.045f, center = Offset(c.x, h * 0.34f))
                drawLine(color, Offset(c.x, h * 0.47f), Offset(c.x, h * 0.67f), sw, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun PremiumHomeAction(
    title: String,
    subtitle: String,
    icon: HomeIcon,
    accent: Color,
    iconBrush: Brush,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(26.dp)
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xEF0A1725), Color(0xF2050B13))
                ),
                shape
            )
            .border(1.dp, accent.copy(alpha = 0.42f), shape),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(46.dp)
                    .background(accent.copy(alpha = 0.82f), RoundedCornerShape(50))
            )
            Spacer(Modifier.width(14.dp))
            Box(
                Modifier
                    .size(60.dp)
                    .background(iconBrush, RoundedCornerShape(18.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                RpgHomeIcon(icon = icon, color = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFEAF5FF)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB9C9DA)
                )
            }
        }
    }
}

@Composable
private fun PremiumMiniHomeCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: HomeIcon,
    accent: Color,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Card(
        onClick = onClick,
        modifier = modifier
            .height(118.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xED091521), Color(0xF3050B12))
                ),
                shape
            )
            .border(1.dp, accent.copy(alpha = 0.38f), shape),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(accent.copy(alpha = 0.10f), RoundedCornerShape(11.dp))
                    .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) {
                RpgHomeIcon(icon = icon, color = accent, modifier = Modifier.size(22.dp))
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFFE7F1FB))
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Color(0xFF8FA4B8))
            }
        }
    }
}

@Composable
private fun StatMini(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color(0xFF57C6FF), fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
    onEnterCampaign: (Boolean) -> Unit
) {
    var campaignName by remember { mutableStateOf("") }
    val creationUi by vm.campaignCreationUi.collectAsState()

    LaunchedEffect(creationUi.completedCampaignDir){
        if(creationUi.completedCampaignDir!=null){
            val requiresCharacterCreation=creationUi.requiresCharacterCreation
            vm.consumeCampaignCreationCompletion()
            onEnterCampaign(requiresCharacterCreation)
        }
    }

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
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !creationUi.inProgress
                        ) {
                            if(creationUi.inProgress){
                                CircularProgressIndicator(
                                    modifier=Modifier.size(20.dp),
                                    strokeWidth=2.dp
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("Tworzenie kampanii…")
                            }else Text("Rozpocznij kampanię")
                        }
                        creationUi.errorMessage?.let{message->
                            Spacer(Modifier.height(10.dp))
                            Text(message,color=MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterCreatorScreen(
    vm:RpgOsViewModel,
    onBack:()->Unit,
    onOpenAiSettings:()->Unit,
    onEnterCampaign:()->Unit
){
    val messages by vm.messages.collectAsState()
    val turnUi by vm.chatTurnUi.collectAsState()
    val hasActivePlayer by vm.hasActivePlayer.collectAsState()
    val aiCenter by vm.aiProviderCenter.collectAsState()
    var description by remember{mutableStateOf("")}
    val aiReady=aiCenter.modelOptions.any{it.availability==AiAvailabilityState.READY}

    LaunchedEffect(hasActivePlayer){if(hasActivePlayer)onEnterCampaign()}

    StandardPage(title="Kreator postaci",onBack=onBack){
        GradientScreen{
            Column(Modifier.fillMaxSize().padding(horizontal=16.dp,vertical=12.dp)){
                GlowPanel(
                    modifier=Modifier.fillMaxWidth(),
                    borderColor=Color(0x6656E1D2),
                    shape=RoundedCornerShape(24.dp,14.dp,28.dp,18.dp)
                ){
                    Text("Stwórz swoją postać",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text(
                        "Opisz, kim chcesz grać. Mistrz Gry dobierze dostępne w tym świecie statystyki, talent, potencjał, umiejętności, techniki, pochodzenie i pozostałe cechy. Nic nie zostanie zapisane bez Twojego potwierdzenia.",
                        color=MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if(!aiReady){
                        Spacer(Modifier.height(8.dp))
                        Text("Najpierw skonfiguruj lokalny model lub OpenRouter.",color=MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick=onOpenAiSettings,modifier=Modifier.fillMaxWidth()){Text("Otwórz ustawienia AI")}
                    }
                }

                Spacer(Modifier.height(10.dp))
                if(turnUi.stage!=ChatTurnUiStage.IDLE&&turnUi.stage!=ChatTurnUiStage.COMPLETED){
                    Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.secondaryContainer){
                        Column(Modifier.fillMaxWidth().padding(12.dp)){
                            Text(turnUi.statusText,fontWeight=FontWeight.Bold)
                            turnUi.reasonUid?.let{Text(it,style=MaterialTheme.typography.labelSmall)}
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
                                if(turnUi.canCancel)TextButton(onClick=vm::cancelCurrentAiTurn){Text("Anuluj")}
                                if(turnUi.canConfirmCharacterCreation)TextButton(onClick=vm::confirmCharacterCreation){Text("Potwierdź i utwórz postać")}
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                LazyColumn(
                    modifier=Modifier.weight(1f),
                    verticalArrangement=Arrangement.spacedBy(8.dp),
                    contentPadding=PaddingValues(bottom=10.dp)
                ){
                    items(messages.filter{it.role!="system"||it.text.contains("posta",ignoreCase=true)}.takeLast(20)){message->
                        val player=message.role=="player"
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=if(player)Arrangement.End else Arrangement.Start){
                            Card(
                                modifier=Modifier.fillMaxWidth(if(player)0.88f else 0.96f),
                                colors=CardDefaults.cardColors(containerColor=if(player)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                                shape=RoundedCornerShape(18.dp)
                            ){
                                Column(Modifier.padding(12.dp)){
                                    Text(if(player)"TY" else if(message.role=="gm")"MISTRZ GRY" else "SYSTEM",style=MaterialTheme.typography.labelSmall)
                                    Text(message.text)
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value=description,
                    onValueChange={description=it},
                    modifier=Modifier.fillMaxWidth(),
                    label={Text("Twój pomysł na postać")},
                    placeholder={Text("Np. sprytny zwiadowca, który chroni słabszych…")},
                    minLines=2,
                    maxLines=5,
                    enabled=!turnUi.canConfirmCharacterCreation
                )
                Spacer(Modifier.height(8.dp))
                GradientActionButton(
                    text=if(turnUi.canConfirmCharacterCreation)"Najpierw potwierdź projekt powyżej" else "Wyślij do Mistrza Gry",
                    onClick={
                        val text=description.trim()
                        if(text.isNotBlank()&&!turnUi.canConfirmCharacterCreation){vm.send(text);description=""}
                    },
                    modifier=Modifier.fillMaxWidth(),
                    brush=TealGradient
                )
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
                CampaignTool.AI -> AiProviderCenterScreen(vm)
                CampaignTool.GM -> GmDiagnosticsScreen(vm)
                CampaignTool.DEV -> DeveloperPanelScreen(vm)
                CampaignTool.DB -> DatabaseScreen(vm)
                CampaignTool.SETTINGS -> SettingsScreen(vm)
            }
        }
        return
    }

    GradientScreen {
        Scaffold(
            containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xE6030B16)),
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
            NavigationBar(
                containerColor = Color(0xF207111E),
                tonalElevation = 0.dp
            ) {
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
                left = "AI" to CampaignTool.AI,
                right = "Ustawienia" to CampaignTool.SETTINGS,
                onTool = onTool
            )
        }
        item {
            MenuGridRow(
                left = "Diagnostyka" to CampaignTool.GM,
                right = "Dev" to CampaignTool.DEV,
                onTool = onTool
            )
        }
        item { OutlinedButton(onClick={onTool(CampaignTool.DB)},modifier=Modifier.fillMaxWidth()){Text("Baza danych")} }

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
    val shape = RoundedCornerShape(20.dp)
    Card(
        onClick = onClick,
        modifier = modifier
            .height(82.dp)
            .background(
                Brush.verticalGradient(listOf(Color(0xED091521), Color(0xF3050B12))),
                shape
            )
            .border(1.dp, Color(0x553EBBE0), shape),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            Modifier.fillMaxSize().padding(14.dp),
            contentAlignment = Alignment.BottomStart
        ) {
            Text(text, fontWeight = FontWeight.Bold, color = Color(0xFFE7F1FB))
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
    GradientScreen {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            title,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEAF5FF)
                        )
                    },
                    navigationIcon = {
                        TextButton(onClick = onBack) {
                            Text("‹ Wróć", color = Color(0xFF63B9FF))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xE6030B16),
                        scrolledContainerColor = Color(0xF2030B16)
                    )
                )
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                content()
            }
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
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0x443EBBE0), RoundedCornerShape(22.dp)),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xE6081522)
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
    val context = LocalContext.current
    val suggestions by vm.visualSuggestions.collectAsState()
    val library by vm.visualLibrary.collectAsState()
    val imageStatus by vm.imageStatus.collectAsState()

    var title by remember{mutableStateOf("")}
    var description by remember{mutableStateOf("")}
    var category by remember{mutableStateOf("Scena")}
    var showCreator by remember{mutableStateOf(false)}
    var filter by remember{mutableStateOf("Wszystkie")}
    var visualPendingEdit by remember{mutableStateOf<VisualRecord?>(null)}
    var editInstruction by remember{mutableStateOf("")}
    val filteredLibrary=remember(library,filter){when(filter){
        "Sceny"->library.filter{it.kind in setOf("scene","location")}
        "Postacie"->library.filter{it.kind=="character"}
        "Przedmioty"->library.filter{it.kind in setOf("item","object")}
        else->library
    }}

    visualPendingEdit?.let{source->
        AlertDialog(
            onDismissRequest={visualPendingEdit=null;editInstruction=""},
            title={Text("Edytuj obraz")},
            text={
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Text(source.title,fontWeight=FontWeight.Bold)
                    OutlinedTextField(
                        value=editInstruction,
                        onValueChange={editInstruction=it},
                        label={Text("Opisz zmianę")},
                        minLines=3
                    )
                }
            },
            confirmButton={TextButton(
                onClick={vm.editVisual(context,source,editInstruction.trim());visualPendingEdit=null;editInstruction=""},
                enabled=editInstruction.isNotBlank()
            ){Text("Wygeneruj edycję")}},
            dismissButton={TextButton(onClick={visualPendingEdit=null;editInstruction=""}){Text("Anuluj")}}
        )
    }

    GradientScreen {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    "Generator obrazów RPG",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black
                )
                Text(
                    "Twórz i przechowuj ilustracje dla swojej kampanii.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if(imageStatus.isNotBlank()){
                item{
                    Surface(
                        modifier=Modifier.fillMaxWidth(),
                        shape=RoundedCornerShape(16.dp),
                        color=MaterialTheme.colorScheme.surfaceContainerHigh
                    ){Text(imageStatus,Modifier.padding(12.dp))}
                }
            }

            item {
                GlowPanel(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = Color(0x664EA8FF),
                    shape = RoundedCornerShape(28.dp, 16.dp, 22.dp, 30.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Nowy obraz",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Utwórz scenę, postać, lokację lub przedmiot.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "✦",
                            style = MaterialTheme.typography.displaySmall,
                            color = Color(0xFF53C8FF)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    GradientActionButton(
                        text = if(showCreator) "Ukryj kreator" else "Nowy obraz",
                        onClick = { showCreator = !showCreator },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if(showCreator){
                item {
                    GlowPanel(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = Color(0x6656E1D2),
                        shape = RoundedCornerShape(18.dp, 28.dp, 30.dp, 16.dp)
                    ) {
                        Text(
                            "Kreator",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(12.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("Scena","Sceneria","Postać").forEach { item ->
                                val selected = category == item
                                Surface(
                                    onClick = { category = item },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(
                                        topStart = if(selected) 18.dp else 12.dp,
                                        topEnd = if(selected) 10.dp else 16.dp,
                                        bottomStart = if(selected) 10.dp else 16.dp,
                                        bottomEnd = if(selected) 20.dp else 12.dp
                                    ),
                                    color = if(selected) Color(0xFF0C6F91) else Color(0xFF0A1521),
                                    border = BorderStroke(
                                        1.dp,
                                        if(selected) Color(0xFF52D8FF) else Color(0x44365C75)
                                    )
                                ) {
                                    Box(
                                        Modifier.height(44.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(item, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Nazwa / tytuł") },
                            singleLine = true,
                            shape = RoundedCornerShape(18.dp, 12.dp, 18.dp, 24.dp)
                        )

                        Spacer(Modifier.height(10.dp))

                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Opis sceny") },
                            minLines = 3,
                            shape = RoundedCornerShape(12.dp, 22.dp, 18.dp, 14.dp)
                        )

                        Spacer(Modifier.height(12.dp))

                        GradientActionButton(
                            text = "Generuj i zapisz w galerii",
                            onClick = {
                                when (category) {
                                    "Postać" -> vm.generateCharacterImage(
                                        context,
                                        title,
                                        description,
                                        "",
                                        ""
                                    )
                                    "Sceneria" -> vm.generateLocationImage(
                                        context,
                                        title,
                                        description
                                    )
                                    else -> vm.generateSceneImage(
                                        context,
                                        title,
                                        description
                                    )
                                }
                                title = ""
                                description = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                            brush = TealGradient
                        )
                    }
                }
            }

            item {
                Text(
                    "Biblioteka obrazów",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Wszystkie zapisane obrazy.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Wszystkie","Sceny","Postacie","Przedmioty").forEach { item ->
                        val selected = filter == item
                        Surface(
                            onClick = { filter = item },
                            shape = RoundedCornerShape(16.dp, 10.dp, 18.dp, 12.dp),
                            color = if(selected) Color(0xFF087C7C) else Color(0xFF08131E),
                            border = BorderStroke(
                                1.dp,
                                if(selected) Color(0xFF42D9CF) else Color(0x44334B5D)
                            )
                        ) {
                            Text(
                                item,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            if(filteredLibrary.isEmpty()){
                item {
                    GlowPanel(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = Color(0x443F95C7),
                        shape = RoundedCornerShape(22.dp, 16.dp, 28.dp, 18.dp)
                    ) {
                        Text(
                            if(library.isEmpty())"Biblioteka jest pusta" else "Brak obrazów w tej kategorii",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if(library.isEmpty())"Pierwsze wygenerowane obrazy pojawią się tutaj jako karty galerii." else "Wybierz inną kategorię albo wygeneruj nowy obraz.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(filteredLibrary.chunked(2)) { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        row.forEachIndexed { index, item ->
                            val cardShape = if(index == 0)
                                RoundedCornerShape(24.dp, 14.dp, 18.dp, 26.dp)
                            else
                                RoundedCornerShape(14.dp, 26.dp, 24.dp, 16.dp)

                            GlowPanel(
                                modifier = Modifier.weight(1f).height(150.dp),
                                borderColor = if(index == 0) Color(0x6658B8FF) else Color(0x6656E1D2),
                                shape = cardShape,
                                onClick = { visualPendingEdit=item }
                            ) {
                                Text(
                                    item.title.ifBlank { "Obraz" },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    item.kind,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if(index == 0) Color(0xFF67B8FF) else Color(0xFF56E1D2)
                                )
                            }
                        }
                        if(row.size == 1){
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            if(suggestions.isNotEmpty()){
                item {
                    Text(
                        "Sugestie dla bieżącej sceny",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(suggestions.take(4)) { suggestion ->
                    GlowPanel(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = Color(0x443E7897),
                        shape = RoundedCornerShape(18.dp, 24.dp, 16.dp, 22.dp),
                        onClick = { vm.generateSuggestedVisual(context,suggestion) }
                    ) {
                        Text(
                            suggestion.title,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            suggestion.promptSeed,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Dotknij, aby wygenerować",style=MaterialTheme.typography.labelMedium,color=Color(0xFF56E1D2))
                    }
                }
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
    val turnUi by vm.chatTurnUi.collectAsState()
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

        if(turnUi.stage!=ChatTurnUiStage.IDLE&&turnUi.stage!=ChatTurnUiStage.COMPLETED){
            Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.secondaryContainer){
                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
                    Column(Modifier.weight(1f)){Text(turnUi.statusText,fontWeight=FontWeight.Bold);turnUi.reasonUid?.let{Text(it,style=MaterialTheme.typography.labelSmall)}}
                    if(turnUi.canCancel)TextButton(onClick=vm::cancelCurrentAiTurn){Text("Anuluj")}
                    if(turnUi.canRetryNarration)TextButton(onClick=vm::retryCommittedNarration){Text("Ponów narrację")}
                    if(turnUi.canConfirmCharacterCreation)TextButton(onClick=vm::confirmCharacterCreation){Text("Potwierdź postać")}
                }
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
                GradientActionButton(
                    text = "Wyślij akcję",
                    onClick = {
                        val sendText=text.trim()
                        if(sendText.isNotBlank()){
                            vm.send(sendText)
                            text=""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun FullStatusScreen(vm:RpgOsViewModel){
    val legacy by vm.characterPanel.collectAsState()
    val panel by vm.characterPanelV2.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Text("Panel postaci",style=MaterialTheme.typography.headlineMedium)}
        if(panel==null){
            item{Text("Widok zgodności — panel zostanie rozszerzony po utworzeniu postaci.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
            item{SectionTitle("Tożsamość")};items(legacy.identity){DataRow(it.key,it.value)}
            item{SectionTitle("Statystyki")};items(legacy.stats){DataRow(it.key,it.value)}
            item{SectionTitle("Zasoby")};items(legacy.resources){DataRow(it.key,it.value)}
            item{SectionTitle("Umiejętności")};items(legacy.skills){Text("${it.name} • ${it.mastery} • ${it.category}")}
            item{SectionTitle("Techniki")};items(legacy.techniques){Text("${it.name} • poziom ${it.mastery} • koszt ${it.chakraCost}")}
            item{SectionTitle("Ekwipunek")};items(legacy.equipment){Text(it)}
            item{SectionTitle("Relacje")};items(legacy.relationships){Text(it)}
            item{SectionTitle("Cele")};items(legacy.goals){Text("• $it")}
        }else{
            val v2=panel!!
            if(v2.identity.isNotEmpty()){item{SectionTitle("Tożsamość i pochodzenie")};items(v2.identity){DataRow(humanizeUid(it.keyUid),it.value)}}
            if(v2.stats.isNotEmpty()){item{SectionTitle("Statystyki")};items(v2.stats){DataRow(humanizeUid(it.semanticsUid),it.exactValue.toString())}}
            if(v2.resources.isNotEmpty()){item{SectionTitle("Zasoby")};items(v2.resources){DataRow(humanizeUid(it.semanticsUid),it.exactValue.toString())}}
            if(v2.skills.isNotEmpty()){item{SectionTitle("Umiejętności")};items(v2.skills){Text("${it.displayName?:humanizeUid(it.targetUid)} • ${it.exactProgress}")}}
            if(v2.techniques.isNotEmpty()){item{SectionTitle("Techniki")};items(v2.techniques){Text("${it.displayName?:humanizeUid(it.targetUid)} • ${it.exactProgress}")}}
            if(v2.talent.isNotEmpty()){item{SectionTitle("Talenty")};items(v2.talent){DataRow(humanizeUid(it.domainUid),it.canonicalValue)}}
            if(v2.potential.isNotEmpty()){item{SectionTitle("Potencjał")};items(v2.potential){DataRow(listOfNotNull(humanizeUid(it.domainUid),it.dimensionUid?.let(::humanizeUid)).joinToString(" • "),it.canonicalValue)}}
            if(v2.innateAndEvolution.isNotEmpty()){item{SectionTitle("Cechy wrodzone, formy i rozwój")};items(v2.innateAndEvolution){DataRow(humanizeUid(it.innateUid),listOfNotNull(humanizeUid(it.stateUid),it.canonicalValue).joinToString(" • "))}}
            if(v2.inventory.isNotEmpty()){item{SectionTitle("Ekwipunek podręczny")};items(v2.inventory){DataRow(humanizeUid(it.definitionUid?:it.itemInstanceUid),"×${it.quantity}")}}
            if(v2.equipment.isNotEmpty()){item{SectionTitle("Wyposażenie")};items(v2.equipment){DataRow(humanizeUid(it.slotUid),it.itemInstanceUid?.let(::humanizeUid)?:"puste")}}
            if(v2.ownershipAndAssets.isNotEmpty()){item{SectionTitle("Własność i aktywa")};items(v2.ownershipAndAssets){DataRow(humanizeUid(it.assetKindUid),humanizeUid(it.assetUid))}}
            if(v2.economy.isNotEmpty()){item{SectionTitle("Finanse")};items(v2.economy){DataRow(humanizeUid(it.currencyUid),it.exactBalance.toString())}}
            if(v2.progression.isNotEmpty()){item{SectionTitle("Postęp")};items(v2.progression){DataRow("${humanizeUid(it.targetKindUid)} • ${humanizeUid(it.targetUid)}",it.exactValue.toString())}}
            if(v2.projects.isNotEmpty()){item{SectionTitle("Projekty")};items(v2.projects){DataRow(humanizeUid(it.projectUid),"${humanizeUid(it.lifecycleUid)} • ${it.exactProgress}")}}
            if(v2.relationships.isNotEmpty()){item{SectionTitle("Relacje")};items(v2.relationships){DataRow(humanizeUid(it.otherEntityUid),"${humanizeUid(it.relationshipTypeUid)} • ${it.exactScore}")}}
            if(v2.goals.isNotEmpty()){item{SectionTitle("Cele")};items(v2.goals){DataRow(it.title,"priorytet ${it.priority}")}}
            item{Text("Panel jest wyliczany z aktualnego stanu silnika i nie posiada osobnej ścieżki zapisu.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
    }
}

private fun humanizeUid(uid:String):String=uid.substringAfterLast(':').replace('_',' ').lowercase().replaceFirstChar{it.titlecase()}

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
    val campaigns by vm.campaigns.collectAsState()
    val activeCampaign by vm.activeCampaign.collectAsState()
    val worlds by vm.worldPacks.collectAsState()
    val activeWorldPack by vm.activeWorldPack.collectAsState()
    val managementUi by vm.campaignManagementUi.collectAsState()
    val creationUi by vm.campaignCreationUi.collectAsState()
    val backups by vm.backups.collectAsState()
    val snapshots by vm.snapshots.collectAsState()
    val transferUi by vm.packageTransferUi.collectAsState()
    val recoveryUi by vm.saveRecoveryUi.collectAsState()

    var tab by remember { mutableStateOf("Kampanie") }
    var newCampaignName by remember { mutableStateOf("") }
    var campaignPendingRemoval by remember { mutableStateOf<CampaignInfo?>(null) }
    var backupPendingRestore by remember { mutableStateOf<String?>(null) }
    var snapshotPendingRestore by remember { mutableStateOf<CampaignSnapshotDescriptor?>(null) }
    var creationNotice by remember{mutableStateOf<String?>(null)}

    LaunchedEffect(creationUi.completedCampaignDir){
        creationUi.completedCampaignDir?.let{dir->
            creationNotice="Utworzono i aktywowano kampanię ${dir.removeSuffix(".campaign")}."
            newCampaignName=""
            vm.consumeCampaignCreationCompletion()
        }
    }

    val campaignImportLauncher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let(vm::importCampaign)}
    val worldImportLauncher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let(vm::importWorldPack)}
    val campaignExportLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->uri?.let(vm::exportActiveCampaign)}

    campaignPendingRemoval?.let{campaign->
        AlertDialog(
            onDismissRequest={campaignPendingRemoval=null},
            title={Text("Usunąć kampanię?")},
            text={Text("„${campaign.name}” zniknie z listy kampanii i zostanie przeniesiona do bezpiecznego kosza. Aktywnej ani systemowej kampanii nie można usunąć.")},
            confirmButton={
                TextButton(onClick={
                    vm.moveCampaignToTrash(File(campaign.path).name)
                    campaignPendingRemoval=null
                }){Text("Przenieś do kosza",color=MaterialTheme.colorScheme.error)}
            },
            dismissButton={TextButton(onClick={campaignPendingRemoval=null}){Text("Anuluj")}}
        )
    }

    backupPendingRestore?.let{path->
        AlertDialog(
            onDismissRequest={backupPendingRestore=null},
            title={Text("Przywrócić backup?")},
            text={Text("Aktualny stan kampanii zostanie najpierw zabezpieczony, a następnie zastąpiony wybranym backupem: ${File(path).name}")},
            confirmButton={TextButton(onClick={vm.restoreBackup(path);backupPendingRestore=null}){Text("Przywróć")}},
            dismissButton={TextButton(onClick={backupPendingRestore=null}){Text("Anuluj")}}
        )
    }

    snapshotPendingRestore?.let{snapshot->
        AlertDialog(
            onDismissRequest={snapshotPendingRestore=null},
            title={Text("Przywrócić snapshot?")},
            text={Text("Snapshot ${snapshot.snapshotUid.takeLast(8)} zostanie zweryfikowany i odtworzony przez mechanizm recovery silnika.")},
            confirmButton={TextButton(onClick={vm.restoreSnapshot(snapshot.snapshotUid);snapshotPendingRestore=null}){Text("Przywróć")}},
            dismissButton={TextButton(onClick={snapshotPendingRestore=null}){Text("Anuluj")}}
        )
    }

    GradientScreen {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    "Zapisy i kampanie",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black
                )
                Text(
                    "Zarządzaj kampaniami, światami i kopiami zapasowymi.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Kampanie", "Pakiety światów").forEach { item ->
                        val selected = tab == item
                        Surface(
                            onClick = { tab = item },
                            modifier = Modifier.weight(1f),
                            shape = if(item == "Kampanie")
                                RoundedCornerShape(20.dp, 12.dp, 14.dp, 24.dp)
                            else
                                RoundedCornerShape(12.dp, 22.dp, 24.dp, 14.dp),
                            color = if(selected) Color(0xFF0B5FA9) else Color(0xFF08131E),
                            border = BorderStroke(
                                1.dp,
                                if(selected) Color(0xFF55C7FF) else Color(0x44334D63)
                            )
                        ) {
                            Box(
                                Modifier.height(48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(item, fontWeight = if(selected) FontWeight.Bold else FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            if(tab == "Kampanie") {
                transferUi.notice?.let{message->item{AssistChip(onClick=vm::clearPackageTransferMessage,label={Text(message)},trailingIcon={Text("×")})}}
                transferUi.errorMessage?.let{message->item{TextButton(onClick=vm::clearPackageTransferMessage){Text(message,color=MaterialTheme.colorScheme.error)}}}
                recoveryUi.notice?.let{message->item{AssistChip(onClick=vm::clearSaveRecoveryMessage,label={Text(message)},trailingIcon={Text("×")})}}
                recoveryUi.errorMessage?.let{message->item{TextButton(onClick=vm::clearSaveRecoveryMessage){Text(message,color=MaterialTheme.colorScheme.error)}}}
                managementUi.notice?.let{message->
                    item{
                        AssistChip(
                            onClick={vm.clearCampaignManagementMessage()},
                            label={Text(message)},
                            trailingIcon={Text("×")}
                        )
                    }
                }
                managementUi.errorMessage?.let{message->
                    item{
                        TextButton(onClick={vm.clearCampaignManagementMessage()}){
                            Text(message,color=MaterialTheme.colorScheme.error)
                        }
                    }
                }
                creationNotice?.let{message->item{AssistChip(onClick={creationNotice=null},label={Text(message)},trailingIcon={Text("×")})}}
                creationUi.errorMessage?.let{message->item{Text(message,color=MaterialTheme.colorScheme.error)}}
                item {
                    Text(
                        "Aktywne kampanie",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                if(campaigns.isEmpty()){
                    item {
                        GlowPanel(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp, 14.dp, 28.dp, 18.dp)
                        ) {
                            Text("Brak kampanii", fontWeight = FontWeight.Bold)
                            Text(
                                "Utwórz nową kampanię, aby rozpocząć grę.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(campaigns) { campaign ->
                        val dirName = File(campaign.path).name
                        val active = campaign.path.endsWith(activeCampaign)

                        GlowPanel(
                            modifier = Modifier.fillMaxWidth(),
                            borderColor = if(active) Color(0x6648D8C8) else Color(0x6658B8FF),
                            shape = if(active)
                                RoundedCornerShape(28.dp, 14.dp, 18.dp, 30.dp)
                            else
                                RoundedCornerShape(18.dp, 28.dp, 30.dp, 16.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        campaign.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Backupy: ${campaign.backupCount}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if(active){
                                    Surface(
                                        shape = RoundedCornerShape(12.dp, 18.dp, 18.dp, 10.dp),
                                        color = Color(0xFF075A4E)
                                    ) {
                                        Text(
                                            "● AKTYWNA",
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF78F0D8)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(14.dp))

                            GradientActionButton(
                                text = if(active) "Kontynuuj" else "Aktywuj kampanię",
                                onClick = { vm.activateCampaign(dirName) },
                                modifier = Modifier.fillMaxWidth(),
                                brush = if(active) TealGradient else BlueGradient
                            )

                            if(dirName!=ActiveCampaignRef.DEFAULT_DIRECTORY){
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick={campaignPendingRemoval=campaign},
                                    modifier=Modifier.fillMaxWidth(),
                                    enabled=!active && managementUi.inProgressCampaignDir==null
                                ){
                                    Text(if(active)"Najpierw aktywuj inną kampanię" else "Usuń kampanię")
                                }
                            }
                        }
                    }
                }

                item {
                    GlowPanel(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = Color(0x6656E1D2),
                        shape = RoundedCornerShape(16.dp, 30.dp, 24.dp, 14.dp)
                    ) {
                        Text(
                            "Nowa kampania",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = newCampaignName,
                            onValueChange = { newCampaignName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Nazwa kampanii") },
                            singleLine = true,
                            shape = RoundedCornerShape(18.dp, 12.dp, 18.dp, 24.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        GradientActionButton(
                            text = if(creationUi.inProgress)"Tworzenie kampanii…" else "Utwórz nową kampanię",
                            onClick = {
                                if(!creationUi.inProgress)vm.createAndActivateCampaign(newCampaignName)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            brush = TealGradient
                        )
                    }
                }

                item {
                    Text(
                        "Import / eksport",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GradientActionButton(
                            text = "Import Save",
                            onClick = { campaignImportLauncher.launch(arrayOf("application/zip","application/octet-stream")) },
                            modifier = Modifier.weight(1f),
                            brush = TealGradient
                        )
                        GradientActionButton(
                            text = "Import World",
                            onClick = { worldImportLauncher.launch(arrayOf("application/zip","application/octet-stream")) },
                            modifier = Modifier.weight(1f),
                            brush = BlueGradient
                        )
                    }
                }

                item {
                    GradientActionButton(
                        text = "Eksportuj aktywny Save",
                        onClick = {
                            campaignExportLauncher.launch("RPG-OS-${activeCampaign.removeSuffix(".campaign")}.zip")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        brush = BlueGradient
                    )
                }

                item{
                    Text("Recovery",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text("Backupy i snapshoty dotyczą aktualnie aktywnej kampanii.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                }

                item{
                    GradientActionButton(
                        text=if(recoveryUi.inProgress)"Operacja recovery…" else "Utwórz bezpieczny snapshot",
                        onClick=vm::createManualSnapshot,
                        modifier=Modifier.fillMaxWidth(),
                        brush=TealGradient
                    )
                }

                if(backups.isNotEmpty()){
                    item{SectionTitle("Backupy (${backups.size})")}
                    items(backups.take(12)){path->
                        GlowPanel(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp,24.dp,16.dp,22.dp)){
                            Text(File(path).name,fontWeight=FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick={backupPendingRestore=path},
                                enabled=!recoveryUi.inProgress,
                                modifier=Modifier.fillMaxWidth()
                            ){Text("Przywróć backup")}
                        }
                    }
                }

                if(snapshots.isNotEmpty()){
                    item{SectionTitle("Snapshoty (${snapshots.size})")}
                    items(snapshots.take(12)){snapshot->
                        val recoverable=snapshot.state==SnapshotPublicationState.VALID&&snapshot.kind in setOf(
                            SnapshotKind.AUTOMATIC,SnapshotKind.MANUAL_BACKUP,SnapshotKind.PRE_RESTORE,SnapshotKind.USER_PINNED
                        )
                        GlowPanel(Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp,14.dp,26.dp,18.dp)){
                            Text("${snapshot.kind.name.replace('_',' ')} • ${snapshot.snapshotUid.takeLast(8)}",fontWeight=FontWeight.Bold)
                            Text("Stan: ${snapshot.state} • commit ${snapshot.anchorCommitOrder}",color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick={snapshotPendingRestore=snapshot},
                                enabled=recoverable&&!recoveryUi.inProgress,
                                modifier=Modifier.fillMaxWidth()
                            ){Text(if(recoverable)"Przywróć snapshot" else "Snapshot niedostępny")}
                        }
                    }
                }
            } else {
                item {
                    Text(
                        "Pakiety światów",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                if(worlds.isEmpty()){
                    item {
                        GlowPanel(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(22.dp, 16.dp, 28.dp, 18.dp)
                        ) {
                            Text("Brak pakietów światów", fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    items(worlds) { world ->
                        GlowPanel(
                            modifier = Modifier.fillMaxWidth(),
                            borderColor = if((File(world.path).name == activeWorldPack)) Color(0x6656E1D2) else Color(0x6658B8FF),
                            shape = RoundedCornerShape(24.dp, 16.dp, 18.dp, 28.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        world.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        if((File(world.path).name == activeWorldPack)) "Aktywny pakiet świata" else "Pakiet gotowy do aktywacji",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if((File(world.path).name == activeWorldPack)){
                                    Text(
                                        "●",
                                        color = Color(0xFF50E6B1),
                                        style = MaterialTheme.typography.headlineSmall
                                    )
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            GradientActionButton(
                                text = if((File(world.path).name == activeWorldPack)) "Aktywny" else "Aktywuj świat",
                                onClick = { vm.activateWorldPack(world.id) },
                                modifier = Modifier.fillMaxWidth(),
                                brush = if((File(world.path).name == activeWorldPack)) TealGradient else BlueGradient
                            )
                        }
                    }
                }
            }
        }
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

@Composable
private fun AiProviderCenterScreen(vm:RpgOsViewModel){
    val state by vm.aiProviderCenter.collectAsState()
    val context=LocalContext.current
    val modelPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)vm.importBielikArtifact(uri)}
    var advanced by remember{mutableStateOf(false)}

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal=16.dp),
        contentPadding=PaddingValues(top=18.dp,bottom=32.dp),
        verticalArrangement=Arrangement.spacedBy(14.dp)
    ){
        item{
            Text("Centrum AI",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
            Text("Jeden system AI. Wybierz model osobno dla Mistrza Gry i okresowego Dyrektora / Scenarzysty.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item{AiRoleAssignmentPanel("Mistrz Gry",AiRole.GAME_MASTER,state.gameMasterAssignment,state.modelOptions,vm::assignAiRole)}
        item{AiRoleAssignmentPanel("Director / Scenarzysta",AiRole.DIRECTOR_SCENARIST,state.directorAssignment,state.modelOptions,vm::assignAiRole)}
        item{
            GlowPanel(Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp,14.dp,26.dp,18.dp)){
                Text("Model lokalny",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                Text(state.localProfile.displayName,color=MaterialTheme.colorScheme.primary)
                Text(if(state.localArtifactInstalled)"Plik modelu: zainstalowany" else "Plik modelu: wymagany",color=if(state.localArtifactInstalled)MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
                Text(if(state.localRuntimeAvailable)"Runtime: gotowy" else "Runtime: oczekuje na zgodny pakiet urządzenia",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick={modelPicker.launch(arrayOf("application/octet-stream","*/*"))},modifier=Modifier.fillMaxWidth()){
                    Text(if(state.localArtifactInstalled)"Zmień plik Bielika" else "Importuj Bielika")
                }
                Text(
                    "Import wymaga pakietu ZIP ExecuTorch zawierającego model .pte i tokenizer. Plik GGUF nie jest zgodnym pakietem dla tej wersji Androida.",
                    style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick={context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://huggingface.co/speakleash/Bielik-4.5B-v3.0-Instruct")))},
                    modifier=Modifier.fillMaxWidth()
                ){Text("Otwórz oficjalną stronę Bielika")}
                TextButton(onClick={advanced=!advanced},modifier=Modifier.fillMaxWidth()){Text(if(advanced)"Ukryj ustawienia zaawansowane" else "Ustawienia zaawansowane")}
                if(advanced){
                    HorizontalDivider(Modifier.padding(vertical=8.dp))
                    Text("Profil: ${if(state.localSettings.recommended)"Auto / Zalecany" else "Ręczny"}",fontWeight=FontWeight.Bold)
                    Text("Kontekst (CTX)")
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        listOf(4096,8192,16384,32768).forEach{ctx->FilterChip(
                            selected=state.localSettings.contextUnits==ctx,onClick={vm.updateLocalAiSettings(state.localSettings.copy(contextUnits=ctx,recommended=false))},
                            label={Text(if(ctx>=1024)"${ctx/1024}k" else ctx.toString())}
                        )}
                    }
                    Text("KV: ${(state.localSettings.contextUnits.toLong()*state.localSettings.kvBytesPerContextUnit)/(1024*1024)} MB",style=MaterialTheme.typography.bodySmall)
                    Text("Backend")
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        listOf(LocalRuntimeBackend.AUTO,LocalRuntimeBackend.CPU,LocalRuntimeBackend.GPU,LocalRuntimeBackend.NPU).forEach{backend->FilterChip(
                            selected=state.localSettings.backend==backend,onClick={vm.updateLocalAiSettings(state.localSettings.copy(backend=backend,recommended=false))},label={Text(backend.name)}
                        )}
                    }
                    Text("Wariant artefaktu")
                    state.localProfile.variants.forEach{variant->RadioButtonRow(
                        selected=state.localSettings.variantUid==variant.variantUid,label="${variant.variantUid} • ${variant.expectedBytes/(1024*1024)} MB",
                        onClick={vm.updateLocalAiSettings(state.localSettings.copy(variantUid=variant.variantUid,recommended=false))}
                    )}
                    when(val admission=state.localAdmission){
                        is LocalAdmissionResult.Admitted->Text("Szacowany szczyt RAM: ${admission.estimatedPeakBytes/(1024*1024)} MB",color=MaterialTheme.colorScheme.secondary)
                        is LocalAdmissionResult.Rejected->Text("Profil odrzucony dla bezpieczeństwa: ${admission.reasonUids.joinToString()}",color=MaterialTheme.colorScheme.error)
                        null->Unit
                    }
                    OutlinedButton(onClick=vm::resetLocalAiSettings,modifier=Modifier.fillMaxWidth()){Text("Przywróć zalecane")}
                }
            }
        }
        item{
            GlowPanel(Modifier.fillMaxWidth(),borderColor=Color(0x6658B8FF),shape=RoundedCornerShape(18.dp,28.dp,20.dp,14.dp)){
                Text("OpenRouter",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                Text(when(state.openRouterStatus.state){
                    CloudAuthState.CONNECTED->"Połączono"
                    CloudAuthState.CONNECTING->"Oczekiwanie na autoryzację w przeglądarce"
                    CloudAuthState.ERROR->"Błąd połączenia"
                    CloudAuthState.EXPIRED->"Połączenie wygasło"
                    CloudAuthState.DISCONNECTED->"Niepołączony"
                },color=if(state.openRouterStatus.state==CloudAuthState.CONNECTED)MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Logowanie używa oficjalnego PKCE i lokalnego callbacku. Klucz jest szyfrowany poza kampanią.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                if(state.openRouterStatus.state==CloudAuthState.CONNECTED)OutlinedButton(onClick=vm::disconnectOpenRouter,modifier=Modifier.fillMaxWidth()){Text("Rozłącz")}
                else GradientActionButton("Połącz z OpenRouter",onClick={
                    val url=vm.beginOpenRouterConnect();context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(url)))
                },modifier=Modifier.fillMaxWidth())
            }
        }
        item{
            GlowPanel(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp,16.dp,20.dp,28.dp)){
                Text("Prywatność",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                AiPrivacySwitch("Zezwól na chmurę",state.privacy.cloudAllowed){vm.updateAiPrivacy(state.privacy.copy(cloudAllowed=it))}
                AiPrivacySwitch("Tekst gracza może trafić do chmury",state.privacy.cloudAllowedForPlayerText){vm.updateAiPrivacy(state.privacy.copy(cloudAllowedForPlayerText=it))}
                AiPrivacySwitch("Director może używać chmury",state.privacy.cloudAllowedForDirector){vm.updateAiPrivacy(state.privacy.copy(cloudAllowedForDirector=it))}
            }
        }
        item{
            GlowPanel(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp,22.dp,28.dp,18.dp)){
                Text("Director",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                Text(state.directorStatusText)
                Text("Director działa okresowo i poza ścieżką zwykłej tury. Brak chmury nie zatrzymuje gry.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AiRoleAssignmentPanel(
    title:String,role:AiRole,assignment:AiRoleAssignment,models:List<AiModelOptionUi>,onAssign:(AiRole,AiModelSelection?)->Unit
){
    GlowPanel(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp,26.dp,16.dp,24.dp)){
        Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        RadioButtonRow(assignment.kind==AiAssignmentKind.AUTO,"Auto (zalecane)"){onAssign(role,null)}
        models.forEach{model->RadioButtonRow(
            assignment.pinned==model.selection,"${if(model.providerKind==AiProviderKind.LOCAL)"Lokalny" else "Chmura"}: ${model.label}${if(model.availability==AiAvailabilityState.READY)"" else " • niedostępny"}"
        ){onAssign(role,model.selection)}}
    }
}

@Composable private fun RadioButtonRow(selected:Boolean,label:String,onClick:()->Unit){
    Surface(onClick=onClick,color=Color.Transparent,modifier=Modifier.fillMaxWidth()){
        Row(Modifier.padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selected,onClick=onClick);Text(label,Modifier.weight(1f))}
    }
}

@Composable private fun AiPrivacySwitch(label:String,value:Boolean,onChange:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Text(label,Modifier.weight(1f));Switch(value,onChange)}
}

private enum class SettingsSection(val label:String){
    AI("Modele AI"),
    SYSTEM("System i aktualizacje")
}

@Composable private fun SettingsScreen(vm:RpgOsViewModel){
    var section by remember{mutableStateOf(SettingsSection.AI)}

    GradientScreen{
        Column(Modifier.fillMaxSize()){
            SingleChoiceSegmentedButtonRow(
                modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp)
            ){
                SettingsSection.entries.forEachIndexed{index,item->
                    SegmentedButton(
                        selected=section==item,
                        onClick={section=item},
                        shape=SegmentedButtonDefaults.itemShape(index,SettingsSection.entries.size)
                    ){Text(item.label)}
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)){
                when(section){
                    SettingsSection.AI->AiProviderCenterScreen(vm)
                    SettingsSection.SYSTEM->SystemSettingsScreen(vm)
                }
            }
        }
    }
}

@Composable private fun SystemSettingsScreen(vm:RpgOsViewModel){
    val context=LocalContext.current
    val current by vm.settings.collectAsState()
    val updateStatus by vm.updateStatus.collectAsState()
    val availableUpdate by vm.availableUpdate.collectAsState()

    var backend by remember(current.backendUrl){mutableStateOf(current.backendUrl)}
    var updateFeed by remember(current.updateFeedUrl){mutableStateOf(current.updateFeedUrl)}
    var diagnostics by remember(current.showGmDiagnostics){mutableStateOf(current.showGmDiagnostics)}
    var backups by remember(current.autoBackup){mutableStateOf(current.autoBackup)}
    var developerExpanded by remember { mutableStateOf(false) }

    val localApkPicker=rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ){uri->
        if(uri!=null) vm.selectLocalUpdate(context,uri)
    }

    GradientScreen {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal=16.dp),
            contentPadding=PaddingValues(top=18.dp,bottom=30.dp),
            verticalArrangement=Arrangement.spacedBy(14.dp)
        ){
            

            item{
                GlowPanel(
                    modifier=Modifier.fillMaxWidth(),
                    shape=RoundedCornerShape(18.dp,28.dp,30.dp,16.dp)
                ){
                    Text("Automatyzacja",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.SpaceBetween,
                        verticalAlignment=Alignment.CenterVertically
                    ){
                        Column(Modifier.weight(1f)){
                            Text("Automatyczny backup",fontWeight=FontWeight.Bold)
                            Text(
                                "Tworzy kopię po ważnych zmianach.",
                                style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(backups,{backups=it})
                    }

                    HorizontalDivider(Modifier.padding(vertical=8.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.SpaceBetween,
                        verticalAlignment=Alignment.CenterVertically
                    ){
                        Column(Modifier.weight(1f)){
                            Text("Diagnostyka MG",fontWeight=FontWeight.Bold)
                            Text(
                                "Pokazuje stan ContextBundle podczas gry.",
                                style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(diagnostics,{diagnostics=it})
                    }
                }
            }

            item{
                GlowPanel(
                    modifier=Modifier.fillMaxWidth(),
                    borderColor=Color(0x6658B8FF),
                    shape=RoundedCornerShape(24.dp,14.dp,18.dp,28.dp)
                ){
                    Text("Aktualizacje RPG OS",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text(
                        "Zainstalowana: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        color=Color(0xFF67B8FF)
                    )
                    if(availableUpdate!=null){
                        Text(
                            "Online: ${availableUpdate!!.versionName} (${availableUpdate!!.versionCode})",
                            color=Color(0xFF56E1D2)
                        )
                        if(availableUpdate!!.notes.isNotBlank()){
                            Text(
                                availableUpdate!!.notes,
                                style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(updateStatus,style=MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))

                    GradientActionButton(
                        text="Sprawdź aktualizacje online",
                        onClick={vm.checkForUpdates(context)},
                        modifier=Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    GradientActionButton(
                        text="Pobierz aktualizację online",
                        onClick={vm.downloadOnlineUpdate(context)},
                        modifier=Modifier.fillMaxWidth(),
                        brush=TealGradient
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick={localApkPicker.launch(arrayOf(
                            "application/vnd.android.package-archive",
                            "application/octet-stream"
                        ))},
                        modifier=Modifier.fillMaxWidth()
                    ){Text("Wybierz lokalny plik APK")}

                    OutlinedButton(
                        onClick={vm.installPreparedUpdate(context)},
                        modifier=Modifier.fillMaxWidth()
                    ){Text("Zainstaluj przygotowaną aktualizację")}
                }
            }

            item { ContentUpdatesPanel(context) }

            item{
                Surface(
                    onClick={developerExpanded=!developerExpanded},
                    modifier=Modifier.fillMaxWidth(),
                    shape=RoundedCornerShape(16.dp,26.dp,22.dp,14.dp),
                    color=Color(0xD908131E),
                    border=BorderStroke(1.dp,Color(0x443D607B))
                ){
                    Row(
                        Modifier.padding(16.dp),
                        horizontalArrangement=Arrangement.SpaceBetween,
                        verticalAlignment=Alignment.CenterVertically
                    ){
                        Column{
                            Text("Dla dewelopera",fontWeight=FontWeight.Bold)
                            Text(
                                "Backend AI i kanał aktualizacji",
                                style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(if(developerExpanded)"⌃" else "⌄")
                    }
                }
            }

            if(developerExpanded){
                item{
                    GlowPanel(
                        modifier=Modifier.fillMaxWidth(),
                        borderColor=Color(0x443E7897),
                        shape=RoundedCornerShape(20.dp,14.dp,26.dp,18.dp)
                    ){
                        OutlinedTextField(
                            backend,
                            {backend=it},
                            Modifier.fillMaxWidth(),
                            label={Text("Adres AI / backendu gry")},
                            shape=RoundedCornerShape(18.dp,12.dp,18.dp,24.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            updateFeed,
                            {updateFeed=it},
                            Modifier.fillMaxWidth(),
                            label={Text("Kanał aktualizacji GitHub")},
                            shape=RoundedCornerShape(12.dp,22.dp,18.dp,14.dp)
                        )
                    }
                }
            }

            item{
                GradientActionButton(
                    text="Zapisz ustawienia",
                    onClick={vm.saveSettings(current.copy(
                        backendUrl=backend.trim(),
                        updateFeedUrl=updateFeed.trim(),
                        showGmDiagnostics=diagnostics,
                        autoBackup=backups
                    ))},
                    modifier=Modifier.fillMaxWidth(),
                    brush=TealGradient
                )
            }
        }
    }
}

@Composable private fun SectionTitle(text:String){Spacer(Modifier.height(8.dp));Text(text,style=MaterialTheme.typography.titleLarge);HorizontalDivider()}
@Composable private fun DataRow(label:String,value:String){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label,style=MaterialTheme.typography.labelLarge);Text(value)}}

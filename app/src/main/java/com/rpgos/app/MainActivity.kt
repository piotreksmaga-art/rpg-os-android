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
    MaterialTheme(
        colorScheme = RpgOsColors,
        content = content
    )
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
        content()
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
                            Text("ALPHA 5", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color(0xFF5DDCFF))
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
                        StatMini("1", "Światów")
                        StatMini("2", "Kampanie")
                        StatMini("3", "Backupy")
                        StatMini("ALPHA 5", "Wersja")
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

    var title by remember{mutableStateOf("")}
    var description by remember{mutableStateOf("")}
    var category by remember{mutableStateOf("Scena")}
    var showCreator by remember{mutableStateOf(false)}
    var filter by remember{mutableStateOf("Wszystkie")}

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
                            listOf("Scena","Sceneria","Postać","Przedmiot").forEach { item ->
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

            if(library.isEmpty()){
                item {
                    GlowPanel(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = Color(0x443F95C7),
                        shape = RoundedCornerShape(22.dp, 16.dp, 28.dp, 18.dp)
                    ) {
                        Text(
                            "Biblioteka jest pusta",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Pierwsze wygenerowane obrazy pojawią się tutaj jako karty galerii.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(library.chunked(2)) { row ->
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
                                shape = cardShape
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
                        shape = RoundedCornerShape(18.dp, 24.dp, 16.dp, 22.dp)
                    ) {
                        Text(
                            suggestion.title,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            suggestion.promptSeed,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
    val campaigns by vm.campaigns.collectAsState()
    val activeCampaign by vm.activeCampaign.collectAsState()
    val worlds by vm.worldPacks.collectAsState()
    val activeWorldPack by vm.activeWorldPack.collectAsState()

    var tab by remember { mutableStateOf("Kampanie") }
    var newCampaignName by remember { mutableStateOf("") }

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
                            text = "Utwórz nową kampanię",
                            onClick = {
                                vm.createCampaign(newCampaignName)
                                newCampaignName = ""
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
                            onClick = { },
                            modifier = Modifier.weight(1f),
                            brush = TealGradient
                        )
                        GradientActionButton(
                            text = "Import World",
                            onClick = { },
                            modifier = Modifier.weight(1f),
                            brush = BlueGradient
                        )
                    }
                }

                item {
                    GradientActionButton(
                        text = "Eksportuj aktywny Save",
                        onClick = { },
                        modifier = Modifier.fillMaxWidth(),
                        brush = BlueGradient
                    )
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

@Composable private fun SettingsScreen(vm:RpgOsViewModel){
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

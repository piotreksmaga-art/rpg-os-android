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
        0.00f to Color(0xFF020711),
        0.18f to Color(0xFF041326),
        0.42f to Color(0xFF06213C),
        0.66f to Color(0xFF053A46),
        0.82f to Color(0xFF082B33),
        1.00f to Color(0xFF02060C)
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
    Box(
        Modifier.fillMaxSize().background(ScreenGradient),
        content = content
    )
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
    val appSettings by vm.settings.collectAsState()

    when (route) {
        AppRoute.HOME -> HomeScreen(
            visualEffectsLevel = appSettings.visualEffectsLevel,
            introAnimation = appSettings.introAnimation,
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
    visualEffectsLevel: String,
    introAnimation: Boolean,
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
                ElementalSystemHeader(
                    visualEffectsLevel = visualEffectsLevel,
                    introAnimation = introAnimation
                )
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp, 18.dp, 18.dp, 10.dp),
                    color = Color(0xCC0A4B9A)
                ) {
                    Text(
                        "ALPHA 5 • ${BuildConfig.VERSION_NAME}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }
                Spacer(Modifier.height(18.dp))
            }

            item {
                GlowPanel(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp, 16.dp, 30.dp, 18.dp),
                    onClick = onNewGame
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(66.dp).background(
                                BlueGradient,
                                RoundedCornerShape(22.dp, 12.dp, 22.dp, 12.dp)
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("+", style = MaterialTheme.typography.displaySmall, color = Color.White)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Nowa gra", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "Wybierz świat i rozpocznij nową kampanię.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("›", style = MaterialTheme.typography.headlineMedium, color = Color(0xFF8FD6FF))
                    }
                }
            }

            item {
                GlowPanel(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = Color(0x6649E1D1),
                    shape = RoundedCornerShape(18.dp, 28.dp, 16.dp, 30.dp),
                    onClick = onContinue
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(58.dp).background(
                                TealGradient,
                                RoundedCornerShape(18.dp, 26.dp, 14.dp, 24.dp)
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("▶", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Kontynuuj", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "Wróć do ostatniej lub wybranej kampanii.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("›", style = MaterialTheme.typography.headlineMedium, color = Color(0xFF78F0E2))
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlowPanel(
                        modifier = Modifier.weight(1f).height(106.dp),
                        shape = RoundedCornerShape(22.dp, 14.dp, 18.dp, 28.dp),
                        onClick = onSaves
                    ) {
                        Text("▣", color = Color(0xFF7BBEFF), style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.weight(1f))
                        Text("Zapisy", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    GlowPanel(
                        modifier = Modifier.weight(1f).height(106.dp),
                        borderColor = Color(0x6656E1D2),
                        shape = RoundedCornerShape(14.dp, 26.dp, 28.dp, 18.dp),
                        onClick = onGallery
                    ) {
                        Text("▧", color = Color(0xFF56E1D2), style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.weight(1f))
                        Text("Galeria", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlowPanel(
                        modifier = Modifier.weight(1f).height(106.dp),
                        borderColor = Color(0x6656E1D2),
                        shape = RoundedCornerShape(28.dp, 18.dp, 16.dp, 24.dp),
                        onClick = onSettings
                    ) {
                        Text("⚙", color = Color(0xFF52D8FF), style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.weight(1f))
                        Text("Ustawienia", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    GlowPanel(
                        modifier = Modifier.weight(1f).height(106.dp),
                        shape = RoundedCornerShape(16.dp, 30.dp, 24.dp, 14.dp),
                        onClick = onAbout
                    ) {
                        Text("i", color = Color(0xFF8FC9FF), style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.weight(1f))
                        Text("O programie", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
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
private fun ElementalSystemHeader(
    visualEffectsLevel: String,
    introAnimation: Boolean
) {
    val mode = visualEffectsLevel.lowercase()
    var introRunning by remember(introAnimation) { mutableStateOf(introAnimation) }

    LaunchedEffect(introAnimation) {
        if (introAnimation) {
            delay(3600)
            introRunning = false
        } else {
            introRunning = false
        }
    }

    val transition = rememberInfiniteTransition(label = "elementalOrbit")
    val orbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (mode) {
                    "full" -> 7600
                    "minimal" -> 16000
                    else -> 9800
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "elementalOrbitAngle"
    )

    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1050, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "elementalPulse"
    )

    val glow by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "elementalLogoGlow"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(258.dp),
        contentAlignment = Alignment.Center
    ) {
        val activeOrbit = if (mode == "minimal" && !introRunning) 0f else orbit

        ElementalOrbit(
            angle = activeOrbit,
            fullEffects = mode == "full",
            minimal = mode == "minimal",
            pulse = if (mode == "minimal") 0.9f else pulse
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "SYSTEM",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF55D6FF).copy(alpha = 0.90f)
            )
            Text(
                "RPG OS",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                color = Color(0xFFD5F1FF),
                modifier = Modifier.graphicsLayer {
                    shadowElevation = 5f + 10f * glow
                }
            )
            Text(
                "Twoje kampanie. Jeden system.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ElementalOrbit(
    angle: Float,
    fullEffects: Boolean,
    minimal: Boolean,
    pulse: Float
) {
    Canvas(Modifier.fillMaxSize()) {
        val radiusX = size.width * 0.405f
        val radiusY = size.height * 0.365f

        fun point(a: Float, offsetNormal: Float = 0f): Offset {
            val r = Math.toRadians(a.toDouble())
            val cx = cos(r).toFloat()
            val sy = sin(r).toFloat()
            val base = Offset(
                center.x + cx * radiusX,
                center.y + sy * radiusY
            )
            if (offsetNormal == 0f) return base

            val nx0 = cx / radiusX.coerceAtLeast(1f)
            val ny0 = sy / radiusY.coerceAtLeast(1f)
            val nLen = kotlin.math.sqrt(nx0 * nx0 + ny0 * ny0).coerceAtLeast(0.0001f)
            return Offset(
                base.x + nx0 / nLen * offsetNormal,
                base.y + ny0 / nLen * offsetNormal
            )
        }

        fun drawSmoothSegment(
            startDeg: Float,
            endDeg: Float,
            colorA: Color,
            colorB: Color,
            width: Float,
            alpha: Float
        ) {
            val steps = if (fullEffects) 42 else 30
            var prev: Offset? = null
            repeat(steps + 1) { i ->
                val t = i.toFloat() / steps
                val a = startDeg + (endDeg - startDeg) * t
                val p = point(a)
                prev?.let { old ->
                    drawLine(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                colorA.copy(alpha = alpha),
                                colorB.copy(alpha = alpha)
                            ),
                            start = old,
                            end = p
                        ),
                        start = old,
                        end = p,
                        strokeWidth = width,
                        cap = StrokeCap.Round
                    )
                }
                prev = p
            }
        }

        val base = angle
        val zone = 72f

        // WIND
        run {
            val start = base
            val end = base + zone
            drawSmoothSegment(
                start, end,
                Color(0xFF64F4E8),
                Color(0xFF5DE58D),
                width = 5.0f * pulse,
                alpha = 0.78f
            )
            if (!minimal) {
                repeat(if (fullEffects) 18 else 10) { i ->
                    val t = i / (if (fullEffects) 17f else 9f)
                    val a = start + zone * t
                    val wave = sin((t * 6.283f * 2f) + Math.toRadians(angle.toDouble()).toFloat()) * (5f + 5f * pulse)
                    val p1 = point(a - 2.3f, wave)
                    val p2 = point(a + 2.3f, -wave * 0.55f)
                    drawLine(
                        color = Color(0xFF9FFFF7).copy(alpha = 0.15f + 0.32f * pulse),
                        start = p1,
                        end = p2,
                        strokeWidth = 1.0f + 1.0f * pulse,
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        // FIRE
        run {
            val start = base + zone
            val end = base + zone * 2f
            drawSmoothSegment(
                start, end,
                Color(0xFFFFB02E),
                Color(0xFFFF4B12),
                width = 6.2f * pulse,
                alpha = 0.95f
            )
            if (!minimal) {
                repeat(if (fullEffects) 22 else 12) { i ->
                    val t = i / (if (fullEffects) 21f else 11f)
                    val a = start + zone * t
                    val amp = (4f + (i % 4) * 2.2f) * pulse
                    val p = point(a)
                    val q = point(a + (if (i % 2 == 0) 1.8f else -1.8f), amp)
                    drawLine(
                        color = if (i % 3 == 0)
                            Color(0xFFFFD05A).copy(alpha = 0.66f)
                        else
                            Color(0xFFFF5B18).copy(alpha = 0.48f),
                        start = p,
                        end = q,
                        strokeWidth = 1.2f + (i % 3) * 0.6f,
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        // WATER
        run {
            val start = base + zone * 2f
            val end = base + zone * 3f
            drawSmoothSegment(
                start, end,
                Color(0xFF54D8FF),
                Color(0xFF2374FF),
                width = 6.0f * pulse,
                alpha = 0.88f
            )
            if (!minimal) {
                drawSmoothSegment(
                    start + 3f, end - 3f,
                    Color(0xFFBDF8FF),
                    Color(0xFF60AFFF),
                    width = 1.8f,
                    alpha = 0.58f
                )
                repeat(if (fullEffects) 16 else 9) { i ->
                    val t = i / (if (fullEffects) 15f else 8f)
                    val a = start + zone * t
                    val p = point(a, 5f + (i % 3) * 3f)
                    drawCircle(
                        color = Color(0xFF78DFFF).copy(alpha = 0.32f + 0.22f * pulse),
                        radius = 1.0f + (i % 3) * 0.8f,
                        center = p
                    )
                }
            }
        }

        // EARTH
        run {
            val start = base + zone * 3f
            val end = base + zone * 4f
            drawSmoothSegment(
                start, end,
                Color(0xFFD49A52),
                Color(0xFF7E5A35),
                width = 6.0f * pulse,
                alpha = 0.86f
            )
            if (!minimal) {
                repeat(if (fullEffects) 15 else 8) { i ->
                    val t = i / (if (fullEffects) 14f else 7f)
                    val a = start + zone * t
                    val normal = if (i % 2 == 0) 3.5f else -3.5f
                    val p = point(a, normal)
                    val r = 1.8f + (i % 4) * 0.75f
                    drawCircle(
                        color = if (i % 3 == 0)
                            Color(0xFFD7B07A).copy(alpha = 0.70f)
                        else
                            Color(0xFF745137).copy(alpha = 0.78f),
                        radius = r,
                        center = p
                    )
                    if (fullEffects && i % 2 == 0) {
                        drawCircle(
                            color = Color(0xFFB7834F).copy(alpha = 0.20f),
                            radius = r * 2.2f,
                            center = p
                        )
                    }
                }
            }
        }

        // LIGHTNING
        run {
            val start = base + zone * 4f
            val end = base + 360f
            drawSmoothSegment(
                start, end,
                Color(0xFFFFF47A),
                Color(0xFFFFB300),
                width = 4.8f * pulse,
                alpha = 0.96f
            )
            if (!minimal) {
                repeat(if (fullEffects) 18 else 10) { i ->
                    val t = i / (if (fullEffects) 17f else 9f)
                    val a = start + zone * t
                    val main = point(a)
                    val branch1 = point(
                        a + (if (i % 2 == 0) 2.2f else -2.2f),
                        (7f + (i % 3) * 3f) * pulse
                    )
                    drawLine(
                        color = Color(0xFFFFD72E).copy(alpha = 0.56f + 0.18f * pulse),
                        start = main,
                        end = branch1,
                        strokeWidth = 1.0f + (i % 2) * 0.7f,
                        cap = StrokeCap.Round
                    )
                    if (fullEffects && i % 3 == 0) {
                        val branch2 = point(
                            a + (if (i % 2 == 0) -4.0f else 4.0f),
                            (11f + (i % 4) * 2f) * pulse
                        )
                        drawLine(
                            color = Color(0xFFFFFFB0).copy(alpha = 0.38f),
                            start = branch1,
                            end = branch2,
                            strokeWidth = 0.8f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }

        if (!minimal) {
            repeat(5) { i ->
                val seam = point(base + zone * i)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.20f + 0.16f * pulse),
                            Color(0xFF78DFFF).copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        center = seam,
                        radius = if (fullEffects) 12f else 8f
                    ),
                    radius = if (fullEffects) 12f else 8f,
                    center = seam
                )
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
    var effects by remember(current.visualEffectsLevel){mutableStateOf(current.visualEffectsLevel)}
    var introAnimation by remember(current.introAnimation){mutableStateOf(current.introAnimation)}
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
                    borderColor=Color(0x6656E1D2),
                    shape=RoundedCornerShape(28.dp,16.dp,22.dp,30.dp)
                ){
                    Text("Wygląd i animacje",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text(
                        "Dostosuj poziom efektów do wydajności telefonu.",
                        color=MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.spacedBy(8.dp)
                    ){
                        listOf(
                            "minimal" to "Minimalna",
                            "standard" to "Standardowa",
                            "full" to "Pełna"
                        ).forEach { (key,label) ->
                            val selected = effects == key
                            Surface(
                                onClick={effects=key},
                                modifier=Modifier.weight(1f),
                                shape=when(key){
                                    "minimal"->RoundedCornerShape(18.dp,10.dp,16.dp,24.dp)
                                    "full"->RoundedCornerShape(10.dp,22.dp,24.dp,14.dp)
                                    else->RoundedCornerShape(16.dp,14.dp,20.dp,12.dp)
                                },
                                color=if(selected) Color(0xFF0B6A8A) else Color(0xFF08131E),
                                border=BorderStroke(
                                    1.dp,
                                    if(selected) Color(0xFF58D8FF) else Color(0x44334D63)
                                )
                            ){
                                Column(
                                    Modifier.padding(vertical=12.dp,horizontal=8.dp),
                                    horizontalAlignment=Alignment.CenterHorizontally
                                ){
                                    Text(
                                        when(key){
                                            "minimal"->"◦"
                                            "full"->"✦"
                                            else->"◆"
                                        },
                                        color=if(selected) Color(0xFF6FE9DB) else Color(0xFF6F8FA9)
                                    )
                                    Text(label,style=MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.SpaceBetween,
                        verticalAlignment=Alignment.CenterVertically
                    ){
                        Column(Modifier.weight(1f)){
                            Text("Animacja startowa",fontWeight=FontWeight.Bold)
                            Text(
                                "Smok okrąża logo podczas uruchamiania.",
                                style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(introAnimation,{introAnimation=it})
                    }
                }
            }

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
                        autoBackup=backups,
                        visualEffectsLevel=effects,
                        introAnimation=introAnimation
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

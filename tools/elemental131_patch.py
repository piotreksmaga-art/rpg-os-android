from pathlib import Path
import json

MAIN = Path("app/src/main/java/com/rpgos/app/MainActivity.kt")
GRADLE = Path("app/build.gradle.kts")
WORKFLOW = Path(".github/workflows/elemental131-patch.yml")
SELF = Path("tools/elemental131_patch.py")

source = MAIN.read_text(encoding="utf-8")
start_marker = "@Composable\nprivate fun ElementalOrbit("
end_marker = "\n@Composable\nprivate fun StatMini("
start = source.index(start_marker)
end = source.index(end_marker, start)

renderer = r'''@Composable
private fun ElementalOrbit(
    angle: Float,
    fullEffects: Boolean,
    minimal: Boolean,
    pulse: Float
) {
    Canvas(Modifier.fillMaxSize()) {
        // Elemental 131: cinematic material pass. Orbit geometry and motion remain unchanged.
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

        fun drawRibbon(
            startDeg: Float,
            endDeg: Float,
            colorA: Color,
            colorB: Color,
            width: Float,
            alpha: Float,
            normal: (Float) -> Float = { 0f }
        ) {
            val steps = if (fullEffects) 60 else 42
            var previous: Offset? = null
            repeat(steps + 1) { i ->
                val t = i.toFloat() / steps
                val a = startDeg + (endDeg - startDeg) * t
                val current = point(a, normal(t))
                previous?.let { old ->
                    drawLine(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                colorA.copy(alpha = alpha),
                                colorB.copy(alpha = alpha)
                            ),
                            start = old,
                            end = current
                        ),
                        start = old,
                        end = current,
                        strokeWidth = width,
                        cap = StrokeCap.Round
                    )
                }
                previous = current
            }
        }

        fun glowAt(centerPoint: Offset, color: Color, radius: Float, alpha: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = alpha),
                        color.copy(alpha = alpha * 0.32f),
                        Color.Transparent
                    ),
                    center = centerPoint,
                    radius = radius
                ),
                center = centerPoint,
                radius = radius
            )
        }

        val base = angle
        val zone = 72f
        val phase = Math.toRadians(angle.toDouble()).toFloat()

        // WIND — luminous vortex plus layered translucent air ribbons.
        run {
            val start = base
            val end = base + zone
            drawRibbon(start, end, Color(0xFF65FFF2), Color(0xFF4BE7B7), 11f * pulse, 0.10f)
            drawRibbon(
                start, end, Color(0xFF9CFFF8), Color(0xFF52F0BE), 3.0f * pulse, 0.78f,
                normal = { t -> sin(t * 12.566f + phase) * 4.6f }
            )

            if (!minimal) {
                listOf(-10f, -5f, 5f, 10f).forEachIndexed { index, lane ->
                    drawRibbon(
                        start + 1.5f + index * 0.7f,
                        end - 1.2f,
                        if (index % 2 == 0) Color(0xFFD8FFFB) else Color(0xFF5FE5E1),
                        Color(0xFF65F5B8),
                        width = if (fullEffects) 1.45f else 1.1f,
                        alpha = if (fullEffects) 0.58f else 0.42f,
                        normal = { t -> lane + sin(t * (15.0f + index) + phase * (0.6f + index * 0.08f)) * 3.0f }
                    )
                }

                val vortex = point(start + zone * 0.48f)
                glowAt(vortex, Color(0xFF62FFF0), if (fullEffects) 22f else 16f, 0.13f)
                val spiralCount = if (fullEffects) 6 else 4
                repeat(spiralCount) { arm ->
                    var previous: Offset? = null
                    val samples = if (fullEffects) 20 else 14
                    repeat(samples) { j ->
                        val t = j.toFloat() / (samples - 1).coerceAtLeast(1)
                        val theta = t * 9.2f + arm * (6.283f / spiralCount) + phase * 0.55f
                        val rr = (16f - 12f * t) * pulse
                        val p = Offset(
                            vortex.x + cos(theta) * rr,
                            vortex.y + sin(theta) * rr * 0.72f
                        )
                        previous?.let { old ->
                            drawLine(
                                color = Color(0xFFCFFFF8).copy(alpha = 0.25f + 0.42f * (1f - t)),
                                start = old,
                                end = p,
                                strokeWidth = 0.8f + (1f - t) * 0.8f,
                                cap = StrokeCap.Round
                            )
                        }
                        previous = p
                    }
                }
            }
        }

        // FIRE — broad orange body, white-hot vein, long flame tongues and embers.
        run {
            val start = base + zone
            val end = base + zone * 2f
            drawRibbon(start, end, Color(0xFFFF7B12), Color(0xFFFF2C05), 15f * pulse, 0.12f)
            drawRibbon(start, end, Color(0xFFFFB11F), Color(0xFFFF4208), 7.4f * pulse, 0.88f)
            drawRibbon(start + 1f, end - 1f, Color(0xFFFFFFC2), Color(0xFFFFA31A), 2.0f * pulse, 0.90f)

            if (!minimal) {
                val flames = if (fullEffects) 30 else 18
                repeat(flames) { i ->
                    val t = (i + 0.35f) / flames
                    val a = start + zone * t
                    val side = if (i % 2 == 0) 1f else -1f
                    val height = (7f + (i % 6) * 2.4f) * pulse
                    val root = point(a, side * 1.0f)
                    val bend = point(a + side * (1.2f + i % 3), side * height * 0.58f)
                    val tip = point(a - side * (0.8f + i % 2), side * height)
                    drawLine(
                        color = Color(0xFFFF6A0A).copy(alpha = 0.62f),
                        start = root,
                        end = bend,
                        strokeWidth = 2.5f + (i % 3) * 0.55f,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = if (i % 4 == 0) Color(0xFFFFFFAD) else Color(0xFFFFC02D),
                        start = bend,
                        end = tip,
                        strokeWidth = 1.0f + (i % 2) * 0.45f,
                        cap = StrokeCap.Round
                    )
                    if (fullEffects && i % 2 == 0) {
                        val ember = point(a + 2.0f + (i % 3), side * (height + 5f + (i % 4)))
                        glowAt(ember, Color(0xFFFF7B12), 3.0f, 0.28f)
                        drawCircle(Color(0xFFFFD35C).copy(alpha = 0.76f), 0.9f + (i % 3) * 0.35f, ember)
                    }
                }
            }
        }

        // WATER — thick flowing body, white foam crest, splashes and suspended droplets.
        run {
            val start = base + zone * 2f
            val end = base + zone * 3f
            val waterWave: (Float) -> Float = { t -> sin(t * 6.283f + phase) * 2.4f }
            drawRibbon(start, end, Color(0xFF0A62E8), Color(0xFF25CFFF), 16f * pulse, 0.13f, waterWave)
            drawRibbon(start, end, Color(0xFF147DFF), Color(0xFF16BFFF), 8.5f * pulse, 0.84f, waterWave)
            drawRibbon(
                start + 1.2f, end - 1.0f,
                Color(0xFFF3FFFF), Color(0xFF8CEBFF),
                2.2f * pulse, 0.90f,
                normal = { t -> waterWave(t) - 3.2f + sin(t * 12.566f + phase) * 1.5f }
            )

            if (!minimal) {
                val sprays = if (fullEffects) 28 else 16
                repeat(sprays) { i ->
                    val t = (i + 0.2f) / sprays
                    val a = start + zone * t
                    val side = if (i % 4 == 0) -1f else 1f
                    val distance = side * (8f + (i % 6) * 2.7f) * pulse
                    val p = point(a + (i % 3) * 0.9f, distance)
                    val c = if (i % 5 == 0) Color(0xFFE8FDFF) else Color(0xFF53CEFF)
                    glowAt(p, c, 3.6f + (i % 2), 0.15f)
                    drawCircle(c.copy(alpha = 0.68f), 0.8f + (i % 4) * 0.45f, p)
                }
                if (fullEffects) {
                    repeat(12) { i ->
                        val t = (i + 0.5f) / 12f
                        val a = start + zone * t
                        val p1 = point(a - 1.5f, 4f + (i % 2) * 2f)
                        val p2 = point(a + 2.2f, 7f + (i % 3) * 2f)
                        drawLine(
                            color = Color(0xFFC9F8FF).copy(alpha = 0.34f),
                            start = p1,
                            end = p2,
                            strokeWidth = 1.0f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }

        // EARTH — broken rocky bed with lifted slabs, dust glows and metallic mineral highlights.
        run {
            val start = base + zone * 3f
            val end = base + zone * 4f
            drawRibbon(start, end, Color(0xFF4E3528), Color(0xFFA66C38), 17f * pulse, 0.11f)
            drawRibbon(start, end, Color(0xFF76503A), Color(0xFFC28A4B), 8.6f * pulse, 0.84f)
            drawRibbon(start + 2f, end - 2f, Color(0xFFF0D7A4), Color(0xFFB67E42), 1.5f, 0.48f)

            if (!minimal) {
                val rocks = if (fullEffects) 21 else 12
                repeat(rocks) { i ->
                    val t = (i + 0.3f) / rocks
                    val a = start + zone * t
                    val side = if (i % 2 == 0) 1f else -1f
                    val lift = side * (4f + (i % 5) * 2.1f)
                    val c = point(a, lift)
                    val r = (2.5f + (i % 5) * 0.75f) * pulse
                    val rock = Path().apply {
                        moveTo(c.x - r * 1.25f, c.y - r * 0.18f)
                        lineTo(c.x - r * 0.45f, c.y - r * 1.10f)
                        lineTo(c.x + r * 0.58f, c.y - r * 0.90f)
                        lineTo(c.x + r * 1.20f, c.y - r * 0.05f)
                        lineTo(c.x + r * 0.72f, c.y + r * 0.92f)
                        lineTo(c.x - r * 0.35f, c.y + r * 1.12f)
                        lineTo(c.x - r * 1.12f, c.y + r * 0.55f)
                        close()
                    }
                    drawPath(
                        rock,
                        color = when (i % 4) {
                            0 -> Color(0xFFD2A068)
                            1 -> Color(0xFF6C4835)
                            2 -> Color(0xFF9B6840)
                            else -> Color(0xFFB7834E)
                        },
                        alpha = 0.92f
                    )
                    drawLine(
                        color = Color(0xFFF4D8A4).copy(alpha = 0.38f),
                        start = Offset(c.x - r * 0.45f, c.y - r * 0.65f),
                        end = Offset(c.x + r * 0.48f, c.y - r * 0.28f),
                        strokeWidth = 0.8f,
                        cap = StrokeCap.Round
                    )
                    if (fullEffects && i % 3 == 0) {
                        glowAt(c, Color(0xFFC88C4A), r * 2.8f, 0.08f)
                    }
                }
            }
        }

        // LIGHTNING — branching plasma arc with strong halo and a white-hot electrical core.
        run {
            val start = base + zone * 4f
            val end = base + 360f
            if (minimal) {
                drawRibbon(start, end, Color(0xFFFFFFB4), Color(0xFFFFC31A), 4.0f * pulse, 0.94f)
            } else {
                val bolts = if (fullEffects) 38 else 26
                var previous = point(start)
                repeat(bolts) { i ->
                    val t = (i + 1f) / bolts
                    val a = start + zone * t
                    val jitter = when (i % 6) {
                        0 -> 6.2f
                        1 -> -3.4f
                        2 -> 4.1f
                        3 -> -6.0f
                        4 -> 2.7f
                        else -> -2.2f
                    } * pulse
                    val next = if (i == bolts - 1) point(end) else point(a, jitter)
                    drawLine(Color(0xFFFFC400).copy(alpha = 0.12f), previous, next, 11f * pulse, StrokeCap.Round)
                    drawLine(Color(0xFFFFD51F).copy(alpha = 0.34f), previous, next, 6.0f * pulse, StrokeCap.Round)
                    drawLine(Color(0xFFFFEA61).copy(alpha = 0.96f), previous, next, 2.5f * pulse, StrokeCap.Round)
                    drawLine(Color.White.copy(alpha = 0.94f), previous, next, 0.85f * pulse, StrokeCap.Round)

                    if (i % (if (fullEffects) 3 else 5) == 1) {
                        val side = if (i % 2 == 0) 1f else -1f
                        val mid = point(a + side * 2.0f, jitter + side * (9f + i % 4) * pulse)
                        val tip = point(a - side * 1.4f, jitter + side * (16f + i % 5) * pulse)
                        drawLine(Color(0xFFFFD51F).copy(alpha = 0.48f), next, mid, 2.2f, StrokeCap.Round)
                        drawLine(Color(0xFFFFFFCE).copy(alpha = 0.78f), next, mid, 0.75f, StrokeCap.Round)
                        drawLine(Color(0xFFFFE34A).copy(alpha = 0.55f), mid, tip, 1.35f, StrokeCap.Round)
                        if (fullEffects && i % 6 == 1) {
                            val fork = point(a + side * 4.1f, jitter + side * 11f * pulse)
                            drawLine(Color(0xFFFFFFB5).copy(alpha = 0.52f), mid, fork, 0.7f, StrokeCap.Round)
                        }
                    }
                    previous = next
                }
            }
        }

        // Soft colored junction glow keeps the five materials visually continuous without changing zones.
        if (!minimal) {
            val seamColors = listOf(
                Color(0xFF72FFF0),
                Color(0xFFFF8C18),
                Color(0xFF45CFFF),
                Color(0xFFB97C44),
                Color(0xFFFFDD28)
            )
            repeat(5) { i ->
                glowAt(
                    point(base + zone * i),
                    seamColors[i],
                    if (fullEffects) 16f else 11f,
                    if (fullEffects) 0.16f else 0.11f
                )
            }
        }
    }
}
'''

source = source[:start] + renderer + source[end:]
MAIN.write_text(source, encoding="utf-8")

gradle = GRADLE.read_text(encoding="utf-8")
gradle = gradle.replace("versionCode = 130", "versionCode = 131")
gradle = gradle.replace('versionName = "1.2.0-alpha5-elemental130"', 'versionName = "1.2.0-alpha5-elemental131"')
if "versionCode = 131" not in gradle or "elemental131" not in gradle:
    raise SystemExit("Version bump failed")
GRADLE.write_text(gradle, encoding="utf-8")

Path("ELEMENTAL_131.md").write_text("""# RPG OS Elemental 131\n\nCel: zbliżyć pięć żywiołów do zaakceptowanego wzorca wizualnego bez przebudowy działającej orbity i reszty interfejsu.\n\nZmiany renderera:\n- Wiatr: wielowarstwowe turkusowe smugi i lokalny wir.\n- Ogień: szeroki żar, jasny rdzeń, języki płomieni i iskry.\n- Woda: grubszy przepływ, jasna piana, rozpryski i krople.\n- Ziemia: ciężka skalna wstęga, odłamki o nieregularnych kształtach i pył.\n- Piorun: silna poświata, biały rdzeń i rozgałęzione wyładowania.\n\nNienaruszone:\n- radiusX = width * 0.405\n- radiusY = height * 0.365\n- angle/base i 5 stref po 72 stopnie\n- czasy obrotu Full/Standard/Minimal\n- pulse, introAnimation, logo, układ oraz pozostały interfejs\n\nVersionCode: 131\nVersionName: 1.2.0-alpha5-elemental131\n""", encoding="utf-8")

Path("ELEMENTAL_131_VALIDATION.json").write_text(json.dumps({
    "versionCode": 131,
    "versionName": "1.2.0-alpha5-elemental131",
    "scope": "five-element-renderer-only",
    "orbit_geometry_preserved": True,
    "orbit_motion_preserved": True,
    "other_ui_preserved": True,
    "reference_direction": "cinematic volumetric elemental ring"
}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

# One-shot helper: remove patch machinery from final branch tree.
if WORKFLOW.exists():
    WORKFLOW.unlink()
if SELF.exists():
    SELF.unlink()

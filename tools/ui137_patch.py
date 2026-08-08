from pathlib import Path

p = Path('app/src/main/java/com/rpgos/app/MainActivity.kt')
s = p.read_text()

repls = {
    'glyph = "+",': 'icon = HomeIcon.NEW_GAME,',
    'glyph = "▶",': 'icon = HomeIcon.CONTINUE,',
    'glyph = "▣",': 'icon = HomeIcon.SAVES,',
    'glyph = "▧",': 'icon = HomeIcon.GALLERY,',
    'glyph = "⚙",': 'icon = HomeIcon.SETTINGS,',
    'glyph = "i",': 'icon = HomeIcon.ABOUT,',
}
for a,b in repls.items():
    s = s.replace(a,b)

s = s.replace('    glyph: String,\n    accent: Color,\n    iconBrush: Brush,', '    icon: HomeIcon,\n    accent: Color,\n    iconBrush: Brush,', 1)
s = s.replace('Text(glyph, style = MaterialTheme.typography.displaySmall, color = Color.White, fontWeight = FontWeight.Light)', 'RpgHomeIcon(icon = icon, color = Color.White, modifier = Modifier.size(30.dp))', 1)

mini_idx = s.index('private fun PremiumMiniHomeCard(')
sub = s[mini_idx:]
sub = sub.replace('    glyph: String,\n    accent: Color,', '    icon: HomeIcon,\n    accent: Color,', 1)
sub = sub.replace('Text(glyph, style = MaterialTheme.typography.titleLarge, color = accent, fontWeight = FontWeight.SemiBold)', 'RpgHomeIcon(icon = icon, color = accent, modifier = Modifier.size(22.dp))', 1)
s = s[:mini_idx] + sub

anchor = '@Composable\nprivate fun PremiumHomeAction('
idx = s.index(anchor)
icons = '''private enum class HomeIcon { NEW_GAME, CONTINUE, SAVES, GALLERY, SETTINGS, ABOUT }\n\n@Composable\nprivate fun RpgHomeIcon(icon: HomeIcon, color: Color, modifier: Modifier = Modifier) {\n    Canvas(modifier) {\n        val w = size.width\n        val h = size.height\n        val c = Offset(w / 2f, h / 2f)\n        val sw = size.minDimension * 0.085f\n        when (icon) {\n            HomeIcon.NEW_GAME -> {\n                drawCircle(color.copy(alpha = 0.95f), radius = size.minDimension * 0.34f, center = c, style = Stroke(width = sw))\n                drawLine(color, Offset(w * 0.50f, h * 0.31f), Offset(w * 0.50f, h * 0.69f), sw, StrokeCap.Round)\n                drawLine(color, Offset(w * 0.31f, h * 0.50f), Offset(w * 0.69f, h * 0.50f), sw, StrokeCap.Round)\n            }\n            HomeIcon.CONTINUE -> {\n                val p = Path().apply {\n                    moveTo(w * 0.35f, h * 0.27f)\n                    lineTo(w * 0.72f, h * 0.50f)\n                    lineTo(w * 0.35f, h * 0.73f)\n                    close()\n                }\n                drawPath(p, color)\n            }\n            HomeIcon.SAVES -> {\n                val p = Path().apply {\n                    moveTo(w * 0.24f, h * 0.20f)\n                    lineTo(w * 0.68f, h * 0.20f)\n                    lineTo(w * 0.78f, h * 0.30f)\n                    lineTo(w * 0.78f, h * 0.80f)\n                    lineTo(w * 0.22f, h * 0.80f)\n                    lineTo(w * 0.22f, h * 0.20f)\n                    close()\n                }\n                drawPath(p, color, style = Stroke(width = sw))\n                drawLine(color, Offset(w * 0.34f, h * 0.22f), Offset(w * 0.34f, h * 0.43f), sw, StrokeCap.Round)\n                drawLine(color, Offset(w * 0.34f, h * 0.62f), Offset(w * 0.66f, h * 0.62f), sw, StrokeCap.Round)\n            }\n            HomeIcon.GALLERY -> {\n                drawRoundRect(color, Offset(w * 0.18f, h * 0.22f), androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.56f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f), style = Stroke(width = sw))\n                drawCircle(color, radius = w * 0.075f, center = Offset(w * 0.62f, h * 0.38f))\n                val p = Path().apply {\n                    moveTo(w * 0.25f, h * 0.67f)\n                    lineTo(w * 0.42f, h * 0.49f)\n                    lineTo(w * 0.53f, h * 0.60f)\n                    lineTo(w * 0.62f, h * 0.52f)\n                    lineTo(w * 0.76f, h * 0.67f)\n                }\n                drawPath(p, color, style = Stroke(width = sw))\n            }\n            HomeIcon.SETTINGS -> {\n                drawCircle(color, radius = size.minDimension * 0.25f, center = c, style = Stroke(width = sw))\n                drawCircle(color, radius = size.minDimension * 0.075f, center = c, style = Stroke(width = sw))\n                repeat(8) { i ->\n                    val a = Math.toRadians((i * 45.0) - 90.0)\n                    val r1 = size.minDimension * 0.31f\n                    val r2 = size.minDimension * 0.41f\n                    drawLine(color, Offset(c.x + (kotlin.math.cos(a) * r1).toFloat(), c.y + (kotlin.math.sin(a) * r1).toFloat()), Offset(c.x + (kotlin.math.cos(a) * r2).toFloat(), c.y + (kotlin.math.sin(a) * r2).toFloat()), sw, StrokeCap.Round)\n                }\n            }\n            HomeIcon.ABOUT -> {\n                drawCircle(color, radius = size.minDimension * 0.35f, center = c, style = Stroke(width = sw))\n                drawCircle(color, radius = size.minDimension * 0.045f, center = Offset(c.x, h * 0.34f))\n                drawLine(color, Offset(c.x, h * 0.47f), Offset(c.x, h * 0.67f), sw, StrokeCap.Round)\n            }\n        }\n    }\n}\n\n'''
s = s[:idx] + icons + s[idx:]
p.write_text(s)

g = Path('app/build.gradle.kts')
t = g.read_text()
assert 'versionCode = 136' in t
assert 'versionName = "1.2.0-alpha5-ui136"' in t
t = t.replace('versionCode = 136', 'versionCode = 137', 1)
t = t.replace('versionName = "1.2.0-alpha5-ui136"', 'versionName = "1.2.0-alpha5-ui137"', 1)
g.write_text(t)

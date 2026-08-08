from pathlib import Path

p = Path("app/src/main/java/com/rpgos/app/MainActivity.kt")
s = p.read_text()

old = '''private val ScreenGradient = Brush.verticalGradient(
    colorStops = arrayOf(
        0.00f to Color(0xFF020711),
        0.18f to Color(0xFF041326),
        0.42f to Color(0xFF06213C),
        0.66f to Color(0xFF053A46),
        0.82f to Color(0xFF082B33),
        1.00f to Color(0xFF02060C)
    )
)'''
new = '''private val ScreenGradient = Brush.verticalGradient(
    colorStops = arrayOf(
        0.00f to Color(0xFF01050D),
        0.20f to Color(0xFF03101D),
        0.46f to Color(0xFF041B2C),
        0.70f to Color(0xFF07313A),
        0.86f to Color(0xFF06252D),
        1.00f to Color(0xFF01050A)
    )
)'''
assert old in s
s = s.replace(old, new, 1)

old = '''@Composable
private fun GradientScreen(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(ScreenGradient),
        content = content
    )
}'''
new = '''@Composable
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
}'''
assert old in s
s = s.replace(old, new, 1)

old = '''            item {
                SystemHeader()
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
            }'''
new = '''            item {
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
            }'''
assert old in s
s = s.replace(old, new, 1)

old = '''@Composable
private fun SystemHeader() {
    Box(
        modifier = Modifier.fillMaxWidth().height(168.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SYSTEM", style = MaterialTheme.typography.labelLarge, color = Color(0xFF55D6FF).copy(alpha = 0.90f))
            Text("RPG OS", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = Color(0xFFD5F1FF))
            Text("Twoje kampanie. Jeden system.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}'''
new = '''@Composable
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
}'''
assert old in s
s = s.replace(old, new, 1)
p.write_text(s)

g = Path("app/build.gradle.kts")
t = g.read_text()
assert "versionCode = 133" in t
assert 'versionName = "1.2.0-alpha5-clean133"' in t
t = t.replace("versionCode = 133", "versionCode = 134", 1)
t = t.replace('versionName = "1.2.0-alpha5-clean133"', 'versionName = "1.2.0-alpha5-ui134"', 1)
g.write_text(t)

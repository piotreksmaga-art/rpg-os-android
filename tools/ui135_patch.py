from pathlib import Path

p = Path('app/src/main/java/com/rpgos/app/MainActivity.kt')
s = p.read_text()

start = s.index('            item {\n                GlowPanel(\n                    modifier = Modifier.fillMaxWidth(),\n                    shape = RoundedCornerShape(28.dp, 16.dp, 30.dp, 18.dp),\n                    onClick = onNewGame')
end = s.index('            item {\n                Surface(\n                    modifier = Modifier.fillMaxWidth(),', start)

replacement = '''            item {
                PremiumHomeAction(
                    title = "Nowa gra",
                    subtitle = "Wybierz świat i rozpocznij nową kampanię.",
                    glyph = "+",
                    accent = Color(0xFF48B7FF),
                    iconBrush = BlueGradient,
                    onClick = onNewGame
                )
            }

            item {
                PremiumHomeAction(
                    title = "Kontynuuj",
                    subtitle = "Wróć do ostatniej lub wybranej kampanii.",
                    glyph = "▶",
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
                        glyph = "▣",
                        accent = Color(0xFF7BBEFF),
                        onClick = onSaves
                    )
                    PremiumMiniHomeCard(
                        modifier = Modifier.weight(1f),
                        title = "Galeria",
                        subtitle = "Obrazy i sceny",
                        glyph = "▧",
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
                        glyph = "⚙",
                        accent = Color(0xFF52D8FF),
                        onClick = onSettings
                    )
                    PremiumMiniHomeCard(
                        modifier = Modifier.weight(1f),
                        title = "O programie",
                        subtitle = "Wersja i informacje",
                        glyph = "i",
                        accent = Color(0xFF8FC9FF),
                        onClick = onAbout
                    )
                }
            }

'''
s = s[:start] + replacement + s[end:]

anchor = '@Composable\nprivate fun StatMini(value: String, label: String) {'
idx = s.index(anchor)
components = '''@Composable
private fun PremiumHomeAction(
    title: String,
    subtitle: String,
    glyph: String,
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
            Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(66.dp)
                    .background(iconBrush, RoundedCornerShape(20.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(glyph, style = MaterialTheme.typography.displaySmall, color = Color.White, fontWeight = FontWeight.Light)
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFEAF5FF)
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFB9C9DA)
                )
            }
            Box(
                Modifier
                    .size(34.dp)
                    .background(accent.copy(alpha = 0.10f), RoundedCornerShape(50)),
                contentAlignment = Alignment.Center
            ) {
                Text("›", style = MaterialTheme.typography.headlineSmall, color = accent)
            }
        }
    }
}

@Composable
private fun PremiumMiniHomeCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    glyph: String,
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
                Text(glyph, style = MaterialTheme.typography.titleLarge, color = accent, fontWeight = FontWeight.SemiBold)
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFFE7F1FB))
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Color(0xFF8FA4B8))
            }
        }
    }
}

'''
s = s[:idx] + components + s[idx:]
p.write_text(s)

g = Path('app/build.gradle.kts')
t = g.read_text()
assert 'versionCode = 134' in t
assert 'versionName = "1.2.0-alpha5-ui134"' in t
t = t.replace('versionCode = 134', 'versionCode = 135', 1)
t = t.replace('versionName = "1.2.0-alpha5-ui134"', 'versionName = "1.2.0-alpha5-ui135"', 1)
g.write_text(t)

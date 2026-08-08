from pathlib import Path

src = Path('app/src/main/java/com/rpgos/app/MainActivity.kt')
s = src.read_text()

# Global Material 3 surfaces: force the whole application into the navy/teal UI 137 family.
old = '''    surface = Color(0xFF07111F),
    onSurface = Color(0xFFE6F0FF),
    surfaceVariant = Color(0xFF0B1B2B),
    onSurfaceVariant = Color(0xFFB8C8DA),
    outline = Color(0xFF31516D),'''
new = '''    surface = Color(0xFF07111F),
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
    outline = Color(0xFF31516D),'''
assert old in s
s = s.replace(old, new, 1)

# StandardPage is used by settings, saves, gallery tools, diagnostics, DB and campaign tools.
old = '''@Composable
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
}'''
new = '''@Composable
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
}'''
assert old in s
s = s.replace(old, new, 1)

# Campaign shell receives the same background and premium chrome.
start = s.index('private fun CampaignShell(')
end = s.index('\n@Composable\nprivate fun CampaignMenuScreen', start)
chunk = s[start:end]
assert '    Scaffold(\n' in chunk
chunk = chunk.replace('    Scaffold(\n', '    GradientScreen {\n        Scaffold(\n            containerColor = Color.Transparent,\n', 1)
chunk = chunk.replace('            TopAppBar(\n', '            TopAppBar(\n                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xE6030B16)),\n', 1)
chunk = chunk.replace('            NavigationBar {\n', '            NavigationBar(\n                containerColor = Color(0xF207111E),\n                tonalElevation = 0.dp\n            ) {\n', 1)
# Add the closing brace for GradientScreen immediately before the function closing brace.
pos = chunk.rfind('\n}')
assert pos != -1
chunk = chunk[:pos] + '\n    }' + chunk[pos:]
s = s[:start] + chunk + s[end:]

# Menu tiles: same glass card language as home mini cards.
old = '''    ElevatedCard(
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
    }'''
new = '''    val shape = RoundedCornerShape(20.dp)
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
    }'''
assert old in s
s = s.replace(old, new, 1)

# Empty states should visually belong to the same system.
old = '''    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {'''
new = '''    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0x443EBBE0), RoundedCornerShape(22.dp)),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xE6081522)
    ) {'''
assert old in s
s = s.replace(old, new, 1)

src.write_text(s)

gradle = Path('app/build.gradle.kts')
g = gradle.read_text()
assert 'versionCode = 137' in g
assert 'versionName = "1.2.0-alpha5-ui137"' in g
g = g.replace('versionCode = 137', 'versionCode = 138', 1)
g = g.replace('versionName = "1.2.0-alpha5-ui137"', 'versionName = "1.2.0-alpha5-ui138-global"', 1)
gradle.write_text(g)

from pathlib import Path

src = Path('app/src/main/java/com/rpgos/app/MainActivity.kt')
s = src.read_text()

old = '''@Composable
private fun RpgOsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RpgOsColors,
        content = content
    )
}'''
new = '''@Composable
private fun RpgOsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RpgOsColors) {
        CompositionLocalProvider(
            LocalContentColor provides RpgOsColors.onBackground
        ) {
            content()
        }
    }
}'''
assert old in s, 'RpgOsTheme block not found'
s = s.replace(old, new, 1)

old = '''@Composable
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
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground
        ) {
            Box(Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}'''
assert old in s, 'GradientScreen block not found'
s = s.replace(old, new, 1)

src.write_text(s)

gradle = Path('app/build.gradle.kts')
g = gradle.read_text()
assert 'versionCode = 138' in g
assert 'versionName = "1.2.0-alpha5-ui138-global"' in g
g = g.replace('versionCode = 138', 'versionCode = 139', 1)
g = g.replace('versionName = "1.2.0-alpha5-ui138-global"', 'versionName = "1.2.0-alpha5-ui139-contrast"', 1)
gradle.write_text(g)

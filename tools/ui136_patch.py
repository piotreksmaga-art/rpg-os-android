from pathlib import Path

p = Path('app/src/main/java/com/rpgos/app/MainActivity.kt')
s = p.read_text()
old = '''            Box(\n                Modifier\n                    .size(34.dp)\n                    .background(accent.copy(alpha = 0.10f), RoundedCornerShape(50)),\n                contentAlignment = Alignment.Center\n            ) {\n                Text("›", style = MaterialTheme.typography.headlineSmall, color = accent)\n            }\n'''
assert old in s
s = s.replace(old, '', 1)
p.write_text(s)

g = Path('app/build.gradle.kts')
t = g.read_text()
assert 'versionCode = 135' in t
assert 'versionName = "1.2.0-alpha5-ui135"' in t
t = t.replace('versionCode = 135', 'versionCode = 136', 1)
t = t.replace('versionName = "1.2.0-alpha5-ui135"', 'versionName = "1.2.0-alpha5-ui136"', 1)
g.write_text(t)

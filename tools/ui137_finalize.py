from pathlib import Path

p = Path('app/src/main/java/com/rpgos/app/MainActivity.kt')
s = p.read_text()
old = '''        Row(
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
                RpgHomeIcon(icon = icon, color = Color.White, modifier = Modifier.size(30.dp))
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
        }
'''
new = '''        Row(
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
'''
if old not in s:
    raise SystemExit('PremiumHomeAction layout block not found')
s = s.replace(old, new, 1)
p.write_text(s)

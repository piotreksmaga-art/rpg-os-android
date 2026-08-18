# RPG OS ALPHA 1.2.0-alpha3

## GitHub Releases Updater
Updater nie używa już endpointów `/v1/updates/*`.

Domyślny kanał:
`https://api.github.com/repos/piotreksmaga-art/rpg-os-android/releases/latest`

Release zawiera:
- `RPG-OS-ALPHA-<wersja>.apk`
- `RPG-OS-ALPHA-<wersja>.apk.sha256`
- `update.json`

Aplikacja:
1. czyta GitHub Release,
2. odczytuje `update.json`,
3. znajduje APK,
4. sprawdza SHA-256,
5. sprawdza applicationId, versionCode i podpis,
6. tworzy backup kampanii,
7. uruchamia systemowy instalator Androida.

## Backend AI jest osobny
`backendUrl` jest przeznaczony dla Mistrza Gry AI.
`updateFeedUrl` jest przeznaczony tylko dla aktualizacji.

## Stały podpis APK
Od alpha3 workflow wymaga GitHub Secrets:
- `RPGOS_KEYSTORE_B64`
- `RPGOS_KEYSTORE_PASSWORD`
- `RPGOS_KEY_PASSWORD`

Uruchom jednorazowo:
`bash tools/setup_github_signing_termux.sh`

Keystore NIE trafia do publicznego repozytorium.

## Ważna migracja podpisu
Poprzednie wersje ALPHA były budowane jako debug APK na efemerycznych runnerach.
Alpha3 ustanawia pierwszy stały klucz wydawniczy. Z tego powodu przejście ze starego
debugowego APK do alpha3 może wymagać jednorazowego backupu, odinstalowania starej
aplikacji, instalacji alpha3 i przywrócenia kampanii. Od alpha3 wzwyż aktualizacje
będą mogły działać normalnie, o ile ten sam keystore zostanie zachowany.

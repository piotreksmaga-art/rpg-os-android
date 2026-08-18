# Automatyczne wydania GitHub

Workflow `.github/workflows/build-alpha.yml` po każdym pushu na `master` lub `main`:

1. odczytuje `versionName` i `versionCode`,
2. buduje APK,
3. zmienia nazwę na `RPG-OS-ALPHA-<versionName>.apk`,
4. tworzy plik SHA-256,
5. zachowuje zwykły GitHub Actions Artifact,
6. tworzy GitHub Release `v<versionName>`,
7. dołącza APK i SHA-256 do Release.

Jeżeli Release dla danego numeru wersji już istnieje, pliki zostają podmienione zamiast tworzenia duplikatu.

Ważne:
- workflow ma `permissions: contents: write`,
- przed kolejnym wydaniem zwiększ `versionCode` i `versionName`,
- aktualizacja Androida wymaga zgodnego applicationId i tego samego podpisu APK.

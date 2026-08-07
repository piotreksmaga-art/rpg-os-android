# RPG OS Android 1.0 — Build Fix #1

## Zmiany

1. Dodano:
   `org.jetbrains.kotlin.plugin.compose` 2.3.21

2. Nie dodano `org.jetbrains.kotlin.android`.
   AGP 9.x ma wbudowaną obsługę Kotlin i ręczne zastosowanie kotlin-android
   może powodować konflikt.

3. `android.builtInKotlin=true` jest jawnie ustawione w `gradle.properties`.

4. GitHub Actions:
   - `actions/checkout@v5`
   - `actions/setup-java@v5`
   - usunięto `android-actions/setup-android@v3`
   - Android SDK 37 jest instalowane przez `sdkmanager`
   - build używa `--stacktrace --info`
   - przy błędzie jest wysyłany artifact `RPG-OS-build-log`

## Jak użyć

Najprościej zastąp zawartość repozytorium GitHub zawartością tej paczki i wykonaj commit/push.
Workflow uruchomi się automatycznie.

Jeśli build ponownie zakończy się czerwonym X:
1. otwórz Actions,
2. wybierz nieudany run,
3. na dole pobierz artifact `RPG-OS-build-log`,
4. wyślij jego `build.log`.

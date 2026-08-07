# Jak zbudować APK — RPG OS Android 1.0

## Najprościej: Android Studio na komputerze
1. Otwórz katalog projektu.
2. Poczekaj na Gradle Sync.
3. Wybierz **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.
4. APK debug będzie w `app/build/outputs/apk/debug/app-debug.apk`.
5. Przenieś APK na telefon i otwórz go, zezwalając na instalację z tego źródła.

## Z telefonu: AndroidIDE
1. Rozpakuj projekt w pamięci telefonu.
2. Otwórz katalog jako projekt Gradle w AndroidIDE.
3. Pozwól AndroidIDE zainstalować wymagane SDK/JDK/Gradle.
4. Uruchom zadanie `assembleDebug`.
5. Zainstaluj `app-debug.apk`.

## GitHub Actions
W projekcie jest `.github/workflows/build-apk.yml`.
Po umieszczeniu projektu w repozytorium GitHub uruchom akcję **Build RPG OS APK**. Gotowy APK pojawi się jako artefakt `RPG-OS-Android-1.0-debug`.

## Tryb demo
Jeżeli adres backendu nadal ma wartość `https://YOUR-BACKEND.example`, aplikacja automatycznie uruchamia lokalny **TRYB DEMO OFFLINE**. Można wtedy testować interfejs i bazę bez klucza OpenAI.

## Pełna gra z AI
Do rozmowy z modelem potrzebny jest backend z katalogu `backend/` i ustawiony jego adres w ekranie Ustawienia. Klucz OpenAI pozostaje na backendzie.

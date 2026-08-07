# Backend działający na tym samym telefonie

## Najprostszy wariant: Termux

1. Zainstaluj Termux z F-Droid lub GitHub Releases.
2. Skopiuj folder `backend` z tej paczki do pamięci telefonu.
3. W Termux przejdź do folderu backendu.
4. Uruchom:
   `bash termux_install.sh`

## Najpierw test bez API
Uruchom:
`bash termux_mock.sh`

Backend będzie działać pod:
`http://127.0.0.1:8000`

W RPG OS → Ustawienia ustaw ten adres i naciśnij „Testuj backend”.

## Pełny tryb AI
Skopiuj:
`.env.example` → `.env`

W `.env` ustaw:
`OPENAI_API_KEY=...`

Następnie:
`bash termux_start.sh`

W aplikacji naciśnij „Testuj backend”.
Powinno pojawić się `RPG_OS_BACKEND_OK`.

## Ważne
Klucz API jest używany przez Termux/backend i nie trafia do APK.

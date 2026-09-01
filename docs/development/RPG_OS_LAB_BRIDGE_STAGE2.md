# RPG OS LAB Bridge — etap 2

Nadrzędna, aktualna architektura i instrukcja Etapów 1–3: `docs/development/RPG_OS_LAB_BRIDGE.md`.

Status: `IMPLEMENTED CANDIDATE`

Transport: lokalny socket Androida przez ADB

Wariant: wyłącznie `labDebug`

## Cel

Bridge daje Codexowi obserwowalny i powtarzalny dostęp do prawdziwego przepływu RPG OS na urządzeniu. Nie jest drugim silnikiem gry, nie ma własnej mechaniki i nie zapisuje bezpośrednio do SQLite. Każda komenda zmieniająca stan przechodzi przez te same porty aplikacyjne co interfejs gracza.

Etap 2 dodaje do fundamentu etapu 1:

- jeden pełny snapshot pipeline'u: kampania, postać, commit, fingerprint, AI, Bekko, recovery, Director, pamięć procesu i ostatnia wymiana z modelem;
- sekwencje do 100 realnych akcji oraz scenariusze walki wykonywane przez `ChatApplicationPort`;
- inspekcję i uruchomienie restart-safe recovery narracji po canonical commit;
- weryfikowalne fixtures wskazujące istniejący canonical save;
- ostatni request/response AI dla wybranego workloadu;
- zrzut ekranu, restart aplikacji i failure bundle po stronie hosta.

## Granice bezpieczeństwa

1. Kod bridge'a i jego initializer istnieją tylko w `app/src/labDebug`; wariant `release` ich nie zawiera.
2. Androidowy provider nie jest eksportowany. Połączenie jest dostępne lokalnie przez `localabstract:rpgos_lab_bridge` i jawny forwarding ADB.
3. `SUBMIT_PLAYER_ACTION`, `RUN_ACTION_SEQUENCE`, `RUN_COMBAT_SCENARIO`, kreator postaci i recovery używają produkcyjnych portów aplikacyjnych.
4. Fixture nie jest kopią bazy ani alternatywnym formatem save. Zawiera katalog kampanii, canonical UID, committed order i fingerprinty. `LOAD_LAB_FIXTURE` wybiera istniejącą kampanię, weryfikuje te wartości i cofa wybór przy niezgodności.
5. Director w etapie 2 jest obserwowany, ale nie jest sztucznie uruchamiany. `GET_DIRECTOR_STATE` jawnie raportuje, czy scheduler produkcyjny jest podłączony. Bridge nie może tworzyć równoległego Directora ani mutować świata w jego imieniu.
6. Failure bundle i semantic sidecar są diagnostyką/cache; nie należą do canonical hash kampanii.

## Najważniejsze komendy aplikacyjne

| Obszar | Komendy |
|---|---|
| Stan | `HEALTH`, `GET_CAPABILITIES`, `GET_ACTIVE_STATE`, `GET_CHARACTER_STATE`, `GET_TURN_STATE` |
| Pipeline | `GET_PIPELINE_SNAPSHOT`, `GET_COMMIT_STATE`, `GET_RUNTIME_STATE`, `GET_CONTEXT_BUNDLE` |
| AI | `GET_AI_STATE`, `GET_AI_TRACE`, `GET_LAST_AI_EXCHANGE`, `SEARCH_BEKKO`, `GET_DIRECTOR_STATE` |
| Recovery | `GET_RECOVERY_STATE`, `RECOVER_PENDING_NARRATION` |
| Scenariusze | `SUBMIT_PLAYER_ACTION`, `RUN_ACTION_SEQUENCE`, `RUN_COMBAT_SCENARIO`, `GET_LAST_SCENARIO` |
| Postać | `SUBMIT_CHARACTER_CREATION`, `GET_PENDING_CHARACTER_DRAFT`, `CONFIRM_CHARACTER_CREATION` |
| Fixtures | `EXPORT_LAB_FIXTURE`, `LOAD_LAB_FIXTURE` |
| Diagnostyka | `GET_LAST_FAILURE`, `EXPORT_FAILURE_BUNDLE`, `CLEAR_AI_TRACE`, `CANCEL_ACTIVE_OPERATION` |

Pełny rejestr zwraca `GET_CAPABILITIES`; to on jest machine-readable źródłem prawdy o wersji bridge'a.

## Użycie z komputera

```powershell
.\tools\rpgos-lab.ps1 HOST_HELP
.\tools\rpgos-lab.ps1 GET_CAPABILITIES
.\tools\rpgos-lab.ps1 GET_PIPELINE_SNAPSHOT '{"include_context":false}'
.\tools\rpgos-lab.ps1 GET_LAST_AI_EXCHANGE '{"workload":"NARRATIVE_RENDER"}'
.\tools\rpgos-lab.ps1 RUN_ACTION_SEQUENCE '{"scenario_uid":"smoke-2","actions":["Rozglądam się.","Idę na zajęcia."],"stop_on_non_narrated":true}'
.\tools\rpgos-lab.ps1 HOST_SCREENSHOT
.\tools\rpgos-lab.ps1 HOST_FAILURE_BUNDLE '{"include_context":true,"trace_limit":100}'
```

`HOST_FAILURE_BUNDLE` zapisuje w `build/lab-artifacts`:

- odpowiedź `EXPORT_FAILURE_BUNDLE`;
- ograniczony logcat;
- pamięć procesu;
- właściwości urządzenia;
- zrzut ekranu.

## Kryteria akceptacji etapu 2

- kontrakt i parser sekwencji przechodzą testy `testLabDebugUnitTest`;
- `assembleLabDebug` jest zielone;
- aplikacja po restarcie odpowiada `bridge_stage = 2`;
- pipeline snapshot, fixture export/load, runtime/recovery/Director/AI exchange działają na fizycznym Androidzie;
- failure bundle zawiera poprawny PNG i wszystkie pliki diagnostyczne;
- komenda z błędnym fixture albo błędną sekwencją kończy się typed failure bez canonical mutation;
- release manifest nie zawiera `RpgOsLabBridgeInitializer`.

## Lokalna akceptacja 2026-08-31

- `RpgOsLabBridgeStage2Test`: `4/4` PASS;
- `assembleLabDebug` i `processReleaseMainManifest`: PASS;
- APK: `130649543` B, SHA-256 `CF11ED745DAFAF3AD2CAD052A199B9240AD23F60036FDFF3FD031D3B7DA5B77A`;
- urządzenie: Motorola Edge 30 Neo, Android 14;
- po restarcie: `bridge_stage=2`, kampania `test-motorola-2`, Bekko `READY`;
- snapshot, fixture export/load, runtime, recovery, Director, AI state i host failure bundle: PASS;
- błędny fingerprint fixture: typed failure, aktywna kampania i committed order bez zmian;
- błędna pusta sekwencja: typed failure, committed order bez zmian;
- realna sekwencja jednej tury: `NARRATED`, receipt v3, committed order `14 -> 15`, fingerprint trwały po restarcie;
- Bekko po commicie/restarcie: checkpoint `15`, `209` rekordów;
- finalny failure bundle: `build/lab-artifacts/failure-20260831-113543`.

Bridge ujawnił dwa findingi produktu poza zakresem samego transportu: neutralna akcja obserwacji została odrzucona w planowaniu jako `REQUIRED_REFERENCE_LATENT_NOT_MATERIALIZABLE`, a udana narracja lokalnego GGUF zawierała techniczną linię `PLAYER tor TRAINING:GENERAL 1`. Zostały zachowane jako jawne błędy silnika/jakości; bridge ich nie maskuje ani nie zastępuje narracją awaryjną.

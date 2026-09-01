# RPG OS LAB Bridge — architektura i instrukcja użycia (Etapy 1–3)

Status: `IMPLEMENTED CANDIDATE`

Aktualny kontrakt: `bridge_stage = 3`

Protokół: `RPGOS_LAB_V1`

Transport: Android `localabstract:rpgos_lab_bridge` udostępniany komputerowi wyłącznie przez ADB

Wariant aplikacji: wyłącznie `labDebug`

## 1. Cel

LAB Bridge skraca lokalną pętlę rozwoju do:

```text
zmiana kodu -> labDebug APK -> ADB -> urządzenie -> prawdziwe porty aplikacyjne/Core
                                          -> wynik, trace albo failure bundle -> poprawka
```

Bridge nie jest drugim silnikiem gry, specjalnym trybem mechaniki ani alternatywną bazą danych. Pozwala automatyzacji wykonać te same operacje, które wykonuje interfejs gracza, oraz odczytać kontrolowaną diagnostykę niedostępną w zwykłym UI. Etap 3 może dodatkowo podłączyć bezstanowego Codexa jako laboratoryjnego providera MG i Directora.

## 2. Architektura

```text
Codex / operator / test
          |
          | tools/rpgos-lab.ps1
          | ADB forward: tcp:<PORT> -> localabstract:rpgos_lab_bridge
          v
RPG OS labDebug
  RpgOsLabBridgeInitializer
          |
          v
  RpgOsLabBridgeServer  -- RPGOS_LAB_V1 / JSON Lines
          |
          +---- odczyty diagnostyczne
          |
          +---- production-path commands
          |        |
          |        v
          |   te same application ports co UI
          |        |
          |        v
          |   planner -> retrieval/Bekko -> AI -> validators/mechanics -> Core -> COMMIT
          |
          `---- lab administration (kampania testowa, model, host, Director)

Etap 3:

UI albo Bridge -> LAB_CODEX broker
                    |-- GAME_MASTER queue -> codex exec --ephemeral
                    `-- DIRECTOR queue    -> codex exec --ephemeral

Codex JSON -> istniejący codec/schema -> istniejące walidatory -> Core
Director JSON -> Phase65 validator -> per-campaign CACHE/REBUILDABLE sidecar
```

Główne elementy:

- `app/src/labDebug/.../RpgOsLabBridge.kt` — kontrakt, lokalny serwer i adapter do prawdziwych portów aplikacji;
- `tools/rpgos-lab.ps1` — klient hosta, forwarding ADB, screenshoty, restart i failure bundle;
- `app/src/labDebug/.../LabCodexProvider.kt` — labowy provider, broker żądań i dwa niezależne pasy pracy;
- `tools/rpgos-codex-host.ps1` — supervisor i workery `GAME_MASTER`/`DIRECTOR`;
- `app/src/labDebug/.../LabDirectorStage3.kt` — trwałe zadania/kandydaci Directora oraz bezpieczna wskazówka dla MG;
- `app/src/labDebug/.../LabDiagnosticsActivity.kt` — panel stanu dostępny tylko w wariancie laboratoryjnym.

## 3. Nienaruszalne zasady działania

1. **Wyłącznie `labDebug`.** Initializer, socket, panel, `LAB_CODEX` i przypisania laboratoryjne nie mogą znaleźć się w APK `release`.
2. **Core pozostaje jedynym źródłem prawdy.** Bridge nie rozstrzyga mechaniki, nie ustanawia FACT, nie nadaje dowolnych skutków i nie omija walidacji.
3. **Brak bezpośrednich canonical writes.** Komendy zmieniające grę wywołują produkcyjne application ports. Bridge nie zapisuje arbitralnie do SQLite ani tabel canonical.
4. **Jedna ścieżka UI i automatyzacji.** Akcja przesłana przez Bridge oraz akcja z prawdziwego interfejsu korzystają z tej samej konfiguracji providerów, ContextBundle, routera, kodeków, mechaniki i transakcji.
5. **Autoryzowany kontekst.** Bekko, Codex i Director otrzymują wyłącznie projekcje dozwolone dla bieżącej kampanii, audience, purpose i `as-of`.
6. **Brak cross-campaign i hidden leakage.** Odczyty oraz sidecary są per-campaign. Wskazówka Directora może użyć tylko supporting UID-ów ponownie dozwolonych w bieżącej turze.
7. **AI zawsze tworzy kandydata.** Odpowiedź Bielika, GGUF, OpenRoutera lub Codexa przechodzi ten sam codec i walidację. Director nie może sam wykonać commitu.
8. **Commit wykonuje się najwyżej raz.** Awaria narracji po canonical commit uruchamia `RECOVER_PENDING_NARRATION`; nie wolno ponownie wykonywać mechaniki ani transakcji.
9. **Sidecary nie są save'em.** Indeks Bekko, trace AI, zadania/kandydaci Directora i failure bundle są `CACHE/REBUILDABLE` lub diagnostyką i nie uczestniczą w canonical hash.
10. **Typed failure zamiast maskowania.** Awaria transportu, providera, modelu, recovery albo walidacji ma pozostać widoczna jako `reason_uid`. Bridge nie zastępuje jej pozornie udaną turą.
11. **Prywatność chmury obowiązuje także w laboratorium.** `LAB_CODEX` ma rodzaj `CLOUD`; tekst gracza lub dane Directora nie mogą zostać wysłane bez odpowiednich zgód aplikacji.
12. **Dostęp ADB oznacza dostęp laboratoryjny.** Używamy dedykowanego urządzenia/testowych kampanii. Failure bundle może zawierać tekst gracza i legalny ContextBundle — nie wolno go automatycznie commitować ani publikować.

## 4. Zakres etapów

### Etap 1 — bezpieczny fundament

Etap 1 ustanowił transport `RPGOS_LAB_V1`, izolację `labDebug` i pierwszą pionową ścieżkę przez prawdziwą aplikację. Obecny kontrakt zachowuje:

- `HEALTH` i machine-readable `GET_CAPABILITIES`;
- listę, utworzenie i wybór kampanii testowej;
- stan kampanii, postaci, ContextBundle, commitu i fingerprintu;
- pojedynczą rzeczywistą akcję gracza;
- tworzenie i zatwierdzanie postaci;
- stan AI, trace oraz kontrolowane wyszukiwanie Bekko;
- laboratoryjny import/wybór lokalnego GGUF lub istniejącego ExecuTorch;
- anulowanie aktywnej operacji.

Już na tym etapie żadna komenda gameplay nie otrzymała własnej mechaniki ani bezpośredniego writer'a SQL.

### Etap 2 — obserwowalność i powtarzalne testy

Etap 2 rozszerzył fundament o:

- pełny snapshot pipeline'u (`GET_PIPELINE_SNAPSHOT`);
- sekwencje do 100 realnych akcji i scenariusze walki;
- inspekcję ostatniej wymiany AI dla workloadu;
- restart-safe recovery narracji po commicie;
- weryfikowalne fixtures wskazujące istniejący canonical save;
- stan runtime, pamięci procesu, recovery, Bekko i Directora;
- hostowy restart aplikacji, screenshot i failure bundle z ograniczonym logcat/meminfo;
- zatrzymanie sekwencji na pierwszym wyniku innym niż `NARRATED` i zachowanie pełnego evidence.

Fixture nie jest kopią bazy. Zawiera identyfikator kampanii, katalog, committed order i fingerprinty. `LOAD_LAB_FIXTURE` najpierw wybiera istniejącą kampanię, następnie sprawdza wartości i przy niezgodności przywraca poprzedni wybór.

### Etap 3 — automatyczny Codex jako MG i Director

Etap 3 zachowuje Etapy 1–2 i dodaje `LAB_CODEX` dla wszystkich istniejących workloadów MG oraz strategii Directora:

- supervisor rejestruje hosta i wysyła heartbeat co 5 sekund;
- heartbeat starszy niż 15 sekund oznacza niedostępnego providera;
- osobny worker `GAME_MASTER` obsługuje pracę pierwszoplanową;
- osobny worker `DIRECTOR` nie blokuje odpowiedzi graczowi;
- limit oczekiwania wynosi 180 sekund dla MG i 300 sekund dla Directora;
- każde żądanie uruchamia oddzielne `codex exec --ephemeral` w pustym katalogu;
- host używa przekazanego output schema i nie przekazuje repozytorium, historii tego czatu ani arbitralnych narzędzi;
- domyślny model to `gpt-5.6-sol`; intent/narracja używają `medium`, a propozycje, naprawy, kreator i Director `high`;
- jawny `PINNED` bez żywego hosta kończy się typed failure; `AUTO` może użyć zwykłego fallbacku aplikacji;
- zaakceptowany pakiet Directora trafia do per-campaign sidecaru, a tylko aktualna i ponownie autoryzowana `DirectorGuidanceEnvelope` może wejść do późniejszego `GM_PROPOSAL`;
- wskazówka Directora nie trafia bezpośrednio do narracji, nie staje się FACT i nie może zawierać mutation payload.

Director może być planowany po otwarciu kampanii, zatwierdzeniu postaci, co 10 commitów oraz po legalnych triggerach semantycznych, tempa i powtórzeń. Praca jest asynchroniczna.

## 5. Protokół i odkrywanie możliwości

Bridge używa jednej linii JSON na żądanie i jednej linii JSON na odpowiedź. Maksymalny rozmiar żądania wynosi 1 MiB.

Żądanie:

```json
{
  "protocol": "RPGOS_LAB_V1",
  "request_uid": "LAB:unikalny-identyfikator",
  "command": "HEALTH",
  "arguments": {}
}
```

Odpowiedź:

```json
{
  "protocol": "RPGOS_LAB_V1",
  "request_uid": "LAB:unikalny-identyfikator",
  "state": "SUCCESS",
  "payload": {},
  "reason_uid": null
}
```

`GET_CAPABILITIES` jest nadrzędnym, maszynowo czytelnym źródłem aktualnej listy komend. Dokumentacja opisuje intencję kontraktu, ale klient automatyczny powinien zawsze najpierw odczytać capabilities.

## 6. Aktualne komendy

| Rodzaj | Komendy |
|---|---|
| Production path | `SUBMIT_PLAYER_ACTION`, `RUN_ACTION_SEQUENCE`, `RUN_COMBAT_SCENARIO`, `SUBMIT_CHARACTER_CREATION`, `CONFIRM_CHARACTER_CREATION`, `RECOVER_PENDING_NARRATION` |
| Stan bazowy | `HEALTH`, `GET_CAPABILITIES`, `LIST_CAMPAIGNS`, `GET_ACTIVE_STATE`, `GET_CHARACTER_STATE`, `GET_CONTEXT_BUNDLE`, `GET_TURN_STATE` |
| Commit/pipeline | `GET_PIPELINE_SNAPSHOT`, `GET_LAST_COMMIT`, `GET_CANONICAL_FINGERPRINT`, `GET_COMMIT_STATE`, `GET_RECOVERY_STATE` |
| AI/runtime | `GET_AI_STATE`, `GET_RUNTIME_STATE`, `GET_AI_TRACE`, `GET_LAST_AI_EXCHANGE`, `GET_LAST_TURN`, `GET_LAST_SCENARIO`, `GET_LAST_FAILURE` |
| Bekko/Director | `SEARCH_BEKKO`, `GET_DIRECTOR_STATE`, `GET_DIRECTOR_JOBS`, `GET_DIRECTOR_CANDIDATES`, `GET_DIRECTOR_GUIDANCE` |
| Diagnostyka/fixtures | `EXPORT_FAILURE_BUNDLE`, `EXPORT_LAB_FIXTURE`, `GET_PENDING_CHARACTER_DRAFT` |
| Administracja lab | `SET_ACTIVE_CAMPAIGN`, `CREATE_CAMPAIGN`, `LOAD_LAB_FIXTURE`, `IMPORT_LOCAL_GGUF`, `SELECT_LOCAL_AI`, `CLEAR_AI_TRACE`, `CANCEL_ACTIVE_OPERATION`, `OPEN_LAB_DIAGNOSTICS` |
| Host Codexa | `REGISTER_CODEX_HOST`, `CODEX_HOST_HEARTBEAT`, `CLAIM_AI_REQUEST`, `COMPLETE_AI_REQUEST`, `FAIL_AI_REQUEST`, `CANCEL_AI_REQUEST`, `GET_CODEX_PROVIDER_STATE`, `SET_LAB_AI_ASSIGNMENTS` |
| Director Etapu 3 | `RUN_DIRECTOR_NOW`, `CLEAR_DIRECTOR_SIDECAR` oraz odczyty Directora wymienione wyżej |

Komendy `REGISTER/HEARTBEAT/CLAIM/COMPLETE/FAIL/CANCEL` są protokołem między aplikacją i `rpgos-codex-host.ps1`. Operator zwykle nie wywołuje ich ręcznie.

## 7. Przygotowanie środowiska

Wymagania:

- Windows z Android SDK/ADB (instalacja Android Studio wystarcza);
- urządzenie lub emulator z włączonym debugowaniem USB;
- autoryzacja komputera na urządzeniu;
- JDK/Gradle wymagane przez projekt;
- dla Etapu 3: zainstalowany i zalogowany lokalny program `codex` oraz włączone właściwe zgody chmurowe w aplikacji.

Nie polegaj na domyślnym numerze seryjnym skryptów. Zawsze ustal urządzenie i jawnie przekaż `-Serial`:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices
$serial = "<SERIAL_URZADZENIA>"
```

Zbuduj i zainstaluj wariant laboratoryjny:

```powershell
.\gradlew.bat :app:assembleLabDebug --no-daemon
& $adb -s $serial install -r ".\app\build\outputs\apk\labDebug\app-labDebug.apk"
& $adb -s $serial shell monkey -p com.rpgos.app -c android.intent.category.LAUNCHER 1
```

Sprawdź połączenie i kontrakt:

```powershell
.\tools\rpgos-lab.ps1 HEALTH '{}' -Serial $serial
.\tools\rpgos-lab.ps1 GET_CAPABILITIES '{}' -Serial $serial
```

Klient sam tworzy forwarding `tcp:43137 -> localabstract:rpgos_lab_bridge`. Port można zmienić parametrem `-Port`; port hosta Codexa i klienta musi być taki sam.

## 8. Typowe przepływy użycia

### 8.1 Diagnoza bieżącej aplikacji

```powershell
.\tools\rpgos-lab.ps1 GET_PIPELINE_SNAPSHOT '{"include_context":false}' -Serial $serial
.\tools\rpgos-lab.ps1 GET_AI_STATE '{}' -Serial $serial
.\tools\rpgos-lab.ps1 GET_LAST_AI_EXCHANGE '{"workload":"NARRATIVE_RENDER"}' -Serial $serial
```

Snapshot jest odczytem. Nie zmienia kampanii.

### 8.2 Utworzenie kampanii i postaci przez prawdziwą ścieżkę

```powershell
.\tools\rpgos-lab.ps1 CREATE_CAMPAIGN '{"name":"Konoha LAB"}' -Serial $serial
.\tools\rpgos-lab.ps1 SUBMIT_CHARACTER_CREATION '{"text":"Jestem Smagi. Wylosuj dla mnie postać ucznia Akademii w Konoha."}' -Serial $serial -TimeoutSeconds 900
.\tools\rpgos-lab.ps1 GET_PENDING_CHARACTER_DRAFT '{}' -Serial $serial
.\tools\rpgos-lab.ps1 CONFIRM_CHARACTER_CREATION '{}' -Serial $serial -TimeoutSeconds 900
```

Zmiana wybranych części draftu może użyć `locked_sections`; wartości muszą odpowiadać aktualnemu enumowi `CharacterCreationDraftSection` raportowanemu przez kod aplikacji.

### 8.3 Pojedyncza tura i sekwencja

```powershell
.\tools\rpgos-lab.ps1 SUBMIT_PLAYER_ACTION '{"text":"Idę na poranne zajęcia w Akademii."}' -Serial $serial -TimeoutSeconds 900

.\tools\rpgos-lab.ps1 RUN_ACTION_SEQUENCE `
  '{"scenario_uid":"academy-smoke","actions":["Rozglądam się.","Idę na zajęcia.","Rozmawiam z nauczycielem."],"stop_on_non_narrated":true}' `
  -Serial $serial -TimeoutSeconds 1800
```

Każdy krok sekwencji przechodzi przez `ChatApplicationPort`. `stop_on_non_narrated=true` zatrzymuje test na pierwszej regresji i zachowuje wynik.

### 8.4 Bekko

```powershell
.\tools\rpgos-lab.ps1 SEARCH_BEKKO `
  '{"query":"komu Smagi obiecał pomoc","audience":"PLAYER","namespace":"CAMPAIGN","limit":10}' `
  -Serial $serial
```

To jest audience-scoped retrieval. Wynik Bekko jest rankingiem kandydatów, nigdy canonical FACT ani samodzielnym skutkiem tury.

### 8.5 Automatyczny Codex MG + Director

```powershell
.\tools\rpgos-lab.ps1 HOST_CODEX_START '{}' -Serial $serial
.\tools\rpgos-lab.ps1 HOST_CODEX_STATUS '{}' -Serial $serial
.\tools\rpgos-lab.ps1 GET_CODEX_PROVIDER_STATE '{}' -Serial $serial

.\tools\rpgos-lab.ps1 RUN_DIRECTOR_NOW '{"reason_uid":"MANUAL_ACCEPTANCE"}' -Serial $serial -TimeoutSeconds 900
.\tools\rpgos-lab.ps1 GET_DIRECTOR_JOBS '{}' -Serial $serial
.\tools\rpgos-lab.ps1 GET_DIRECTOR_CANDIDATES '{}' -Serial $serial
.\tools\rpgos-lab.ps1 GET_DIRECTOR_GUIDANCE '{}' -Serial $serial

.\tools\rpgos-lab.ps1 SUBMIT_PLAYER_ACTION '{"text":"Odpoczywam chwilę."}' -Serial $serial -TimeoutSeconds 900
.\tools\rpgos-lab.ps1 GET_LAST_AI_EXCHANGE '{"workload":"GM_PROPOSAL"}' -Serial $serial

.\tools\rpgos-lab.ps1 HOST_CODEX_STOP '{}' -Serial $serial
```

`HOST_CODEX_START` przypina `LAB_CODEX` jako MG i Directora w `labDebug`. Po teście host należy zatrzymać. Status providera powinien pokazywać świeży heartbeat, a wymiana `GM_PROPOSAL` pozwala sprawdzić, czy użyto wskazówki i czy nastąpił fallback.

### 8.6 Save/restart/continue i recovery

```powershell
.\tools\rpgos-lab.ps1 GET_COMMIT_STATE '{}' -Serial $serial
.\tools\rpgos-lab.ps1 HOST_RESTART '{}' -Serial $serial
.\tools\rpgos-lab.ps1 GET_COMMIT_STATE '{}' -Serial $serial
.\tools\rpgos-lab.ps1 GET_RECOVERY_STATE '{}' -Serial $serial
```

Committed order i fingerprint muszą pozostać zgodne po restarcie. Jeśli `GET_RECOVERY_STATE` zgłasza oczekującą narrację, użyj:

```powershell
.\tools\rpgos-lab.ps1 RECOVER_PENDING_NARRATION '{}' -Serial $serial -TimeoutSeconds 900
```

Nie wysyłaj ponownie poprzedniej akcji gracza po commicie.

### 8.7 Failure bundle

```powershell
.\tools\rpgos-lab.ps1 HOST_FAILURE_BUNDLE `
  '{"include_context":true,"trace_limit":100}' `
  -Serial $serial
```

Pakiet jest zapisywany w `build/lab-artifacts` i zawiera ograniczony stan Bridge'a, trace, logcat, meminfo, właściwości urządzenia oraz screenshot. Przed udostępnieniem należy sprawdzić, czy nie zawiera prywatnej treści kampanii.

### 8.8 Fixtures

```powershell
.\tools\rpgos-lab.ps1 EXPORT_LAB_FIXTURE '{}' -Serial $serial
```

Zapisz zwrócony obiekt w kontrolowanym artefakcie testowym i przekaż go jako arguments do `LOAD_LAB_FIXTURE`. Nie edytuj fingerprintów. Niezgodność musi zakończyć się typed failure bez zmiany canonical state.

## 9. Diagnostyka problemów

| Objaw | Sprawdzenie / działanie |
|---|---|
| Brak połączenia | `adb devices`, autoryzacja urządzenia, uruchomiony `labDebug`, następnie `HEALTH` |
| `LAB_PROTOCOL_MISMATCH` | klient i APK muszą używać `RPGOS_LAB_V1` |
| `LAB_CODEX_HOST_NOT_REGISTERED` | uruchom `HOST_CODEX_START` i sprawdź `HOST_CODEX_STATUS` |
| `LAB_CODEX_HOST_HEARTBEAT_STALE` | supervisor nie żyje albo komputer spał; zatrzymaj i uruchom host ponownie |
| Pinned provider unavailable | sprawdź heartbeat, zgody chmurowe i `GET_CODEX_PROVIDER_STATE`; nie maskuj błędu AUTO fallbackiem podczas testu PINNED |
| Tura po commicie bez narracji | sprawdź `GET_RECOVERY_STATE`, potem `RECOVER_PENDING_NARRATION` |
| Nieoczekiwany fallback | sprawdź `GET_LAST_AI_EXCHANGE` dla każdego workloadu i provider/model/reason UID |
| Director nie dostarczył wskazówki | sprawdź jobs, candidates, aktualność pakietu i ponowną autoryzację supporting UID-ów |
| Crash albo śmierć usługi AI | natychmiast wykonaj `HOST_FAILURE_BUNDLE`; nie czyść danych przed zebraniem evidence |

## 10. Testy i bramki publikacji

Minimalna weryfikacja zmian Bridge'a:

1. testy `RpgOsLabBridgeStage2Test` oraz `LabCodexStage3Test`;
2. testy workloadów i produkcyjnych schematów JSON;
3. `assembleLabDebug`;
4. instalacja na urządzeniu i `HEALTH`/`GET_CAPABILITIES`;
5. rzeczywista kampania/postać, Director, tura z guidance, save/restart/continue;
6. sekwencja 10 tur i osobna sekwencja 100 tur zatrzymywana na pierwszej regresji;
7. `processReleaseMainManifest` i inspekcja potwierdzająca brak `RpgOsLab`, `LabCodex`, `LabDiagnostics`, `LAB_CODEX` i socketu Bridge'a w release.

Nie wolno oznaczać Etapu 3 jako ukończonego tylko dlatego, że host odpowiada. Bramka obejmuje także poprawność Core, brak utraty commitu, brak niejawnego fallbacku, poprawne schematy, restart oraz test dłuższej gry.

## 11. Relacja do pozostałych dokumentów

Ten dokument jest nadrzędną instrukcją bieżącego Bridge'a Etapów 1–3. Raporty:

- `docs/development/RPG_OS_LAB_BRIDGE_STAGE2.md`;
- `docs/development/RPG_OS_LAB_BRIDGE_STAGE3.md`;

zachowują szczegółowe kryteria i historyczne evidence poszczególnych etapów. W razie różnicy w liście komend rozstrzyga odpowiedź działającego APK na `GET_CAPABILITIES`, a w sprawach authority i bezpieczeństwa — `docs/Architektura projektu.md`.

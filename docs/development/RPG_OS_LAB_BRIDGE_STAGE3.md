# RPG OS LAB Bridge — etap 3

Nadrzędna, aktualna architektura i instrukcja Etapów 1–3: `docs/development/RPG_OS_LAB_BRIDGE.md`.

Status: `IMPLEMENTED CANDIDATE`

Transport: `RPGOS_LAB_V1` przez lokalny socket Androida i ADB

Wariant: wyłącznie `labDebug`

## Cel

Etap 3 dodaje bezstanowego Codexa jako automatycznego dostawcę wszystkich workloadów MG oraz Directora. Prawdziwy interfejs aplikacji i komendy bridge'a korzystają z tego samego `AiProviderExtensionRegistry`, routera, kodeków JSON, walidatorów, mechaniki i canonical transaction path.

Codex nie otrzymuje repozytorium, historii aktywnego czatu ani arbitralnego dostępu do telefonu. Host przekazuje mu pojedyncze, już autoryzowane żądanie AI i wymagany schemat odpowiedzi. Każda odpowiedź pozostaje kandydatem; Core może ją przyjąć, naprawić albo odrzucić.

## Przepływ

```text
UI lub bridge -> workload RPG OS -> LAB_CODEX broker
                                    |-- kolejka GAME_MASTER -> codex exec --ephemeral
                                    `-- kolejka DIRECTOR    -> codex exec --ephemeral

Codex JSON -> istniejący codec -> walidatory/Core -> ewentualny COMMIT
Director JSON -> Phase65 validator -> rebuildable sidecar -> budżetowana wskazówka następnej propozycji MG
```

Host utrzymuje heartbeat co 5 sekund. Provider uznaje go za niedostępny po 15 sekundach. Limit żądania MG wynosi 180 sekund, a Directora 300 sekund. Kolejka Directora jest niezależna i nigdy nie blokuje pierwszoplanowej odpowiedzi graczowi.

## Granice

- `LAB_CODEX`, host, panel diagnostyczny i initializer istnieją tylko w `app/src/labDebug`.
- Wariant release nie zawiera providera, serwera, zapisanych przypisań ani aktywności diagnostycznej.
- Provider ma rodzaj `CLOUD`; router egzekwuje istniejące zgody na chmurę, tekst gracza i Directora.
- Jawny PIN bez hosta zwraca typed failure. AUTO zachowuje istniejący fallback.
- Sidecar Directora jest per-campaign i odbudowywalny. Nie należy do save, replay ani canonical hash.
- Wskazówka Directora jest tylko do odczytu, musi być aktualna i oparta na UID-ach autoryzowanych również w bieżącej turze. Nie trafia bezpośrednio do narracji i nie może zawierać mutation payload.
- Awaria narracji po commicie korzysta z istniejącego recovery; mechanika i commit nie są wykonywane ponownie.

## Uruchomienie

```powershell
.\tools\rpgos-lab.ps1 HOST_CODEX_START
.\tools\rpgos-lab.ps1 HOST_CODEX_STATUS
.\tools\rpgos-lab.ps1 GET_CODEX_PROVIDER_STATE
.\tools\rpgos-lab.ps1 RUN_DIRECTOR_NOW
.\tools\rpgos-lab.ps1 GET_DIRECTOR_JOBS
.\tools\rpgos-lab.ps1 GET_DIRECTOR_CANDIDATES
.\tools\rpgos-lab.ps1 HOST_CODEX_STOP
```

`tools/rpgos-codex-host.ps1` uruchamia supervisora i dwóch ukrytych workerów. Każde żądanie dostaje osobny pusty katalog w katalogu tymczasowym oraz własny output schema. Profil używa `gpt-5.6-sol`, poziomu `medium` dla intentu i narracji oraz `high` dla propozycji, napraw, kreatora i Directora.

Panel można otworzyć komendą `OPEN_LAB_DIAGNOSTICS`; sama aktywność nie jest eksportowana. Pokazuje heartbeat, model, kolejki, aktywne zadania, ostatni błąd, trwały stan Directora oraz użycie wskazówki w ostatnim żądaniu propozycji MG.

## Komendy etapu 3

- host: `REGISTER_CODEX_HOST`, `CODEX_HOST_HEARTBEAT`;
- broker: `CLAIM_AI_REQUEST`, `COMPLETE_AI_REQUEST`, `FAIL_AI_REQUEST`, `CANCEL_AI_REQUEST`;
- role i stan: `SET_LAB_AI_ASSIGNMENTS`, `GET_CODEX_PROVIDER_STATE`;
- Director: `RUN_DIRECTOR_NOW`, `GET_DIRECTOR_JOBS`, `GET_DIRECTOR_CANDIDATES`, `GET_DIRECTOR_GUIDANCE`, `CLEAR_DIRECTOR_SIDECAR`.

Pełny rejestr komend zwraca `GET_CAPABILITIES`. Komendy Etapu 1–2 i protokół `RPGOS_LAB_V1` pozostają kompatybilne.

## Bramka akceptacji

- testy routingu, korelacji, rozdzielenia kolejek, heartbeat, anulowania, timeoutu i odrzucenia mutacji Directora;
- prawdziwe workloady intent/proposal/repair/narrative/character/director ze schematami produkcyjnymi;
- kampania i postać opisem oraz losowaniem, minimum 10 tur, zaakceptowany Director i późniejsza tura z jego wskazówką;
- save, restart, continue, test 100 tur z failure bundle przy pierwszej regresji;
- brak śmierci usług, utraty commitu, technicznych linii i niejawnego fallbacku;
- inspekcja release manifestu potwierdzająca brak całego bridge'a Etapu 3.

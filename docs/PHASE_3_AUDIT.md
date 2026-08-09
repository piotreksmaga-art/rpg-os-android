# RPG OS — AUDYT FAZY 3: PLAYER STATE CONTRACT

Status audytu: COMPLETE
Wynik Fazy 3 po audycie: PARTIAL — HARDENING REQUIRED
Zakres: aktualny `master` po commicie `1d52783fdba103a8c07e000113d5858a15a33940`

## 1. Cel audytu

Zweryfikować rzeczywistą implementację Fazy 3 względem MASTER Architecture i Definition of DONE, a nie wyłącznie względem dokumentu `PHASE_3_PLAYER_STATE_CONTRACT.md`.

Sprawdzono:
- aktualny master i ostatnie commity,
- `ActivePlayerRef`,
- `ActivePlayerStore`,
- migrację `RPGOS-3.0-PLAYER-STATE`,
- `PlayerStateSnapshot`, `PlayerStatePolicy`, `PlayerStateStore`,
- `CampaignRepository` / `UnifiedGameRepository` / `LocalGameStore`,
- `ContextBuilder`, `ContextBundle`, `JsonCodec`, backend GM prompt,
- `CharacterPanelReader` i `StatusSnapshot` read path,
- testy JVM,
- najnowszy GitHub Actions build.

## 2. Elementy poprawne — PASS

### 2.1 Jawna tożsamość aktywnego gracza
PASS.

Istnieje `ActivePlayerRef(campaignId, playerUid)` oraz persisted tabela `active_player_ref` per kampania.

Normalny runtime może odczytać ten UID przez `ActivePlayerStore.active()` zamiast rozwiązywać gracza heurystycznie przy każdej turze.

### 2.2 Addytywna migracja legacy
PASS z zastrzeżeniem opisanym w sekcji ryzyk.

`MigrationManager.ensureV3()` tworzy `active_player_ref` bez niszczenia istniejących tabel i rejestruje `RPGOS-3.0-PLAYER-STATE`.

Po migracji wykonywany jest seed legacy tylko wtedy, gdy brak persisted active player.

### 2.3 Formalny podział Player State
PASS.

Istnieją trzy klasy:
- PERSISTENT,
- DERIVED,
- RUNTIME.

`PlayerStateSnapshot` zachowuje je osobno, a `toContextMap()` nie spłaszcza warstw.

### 2.4 Repository integration
PASS.

`CampaignRepository` udostępnia:
- `activePlayerRef()`,
- `setActivePlayer(playerUid)`,
- `playerState()`.

`UnifiedGameRepository` prowadzi te operacje przez `LocalGameStore`.

### 2.5 GM context integration
PASS.

`LocalGameStore.buildContext()` buduje `PlayerStateStore` dla aktywnej kampanii i dodaje `player_state` do `ContextBundle`.

`JsonCodec` serializuje tę sekcję.

Backend GM ma jawne instrukcje, że:
- `active_player` identyfikuje kontrolowaną postać,
- persistent jest durable authoritative data,
- runtime jest chwilowy,
- derived jest rebuildable/read-only.

### 2.6 Główne read pathy wykorzystują ActivePlayerRef
PASS częściowy.

`ContextBuilder` korzysta z `ActivePlayerStore`.

`LocalGameStore.status()` pobiera lokalizację według aktywnego UID.

`fullCharacterPanel()` przekazuje aktywny UID do `CharacterPanelReader`.

### 2.7 CI / build
PASS.

Build #103 zakończył się SUCCESS, w tym JVM unit tests i signed ALPHA APK.

Po aktualizacji roadmapy Build #105 również zakończył się SUCCESS.

## 3. Finding P3-A — brak walidacji istnienia Player UID

Severity: HIGH
Status: OPEN

`ActivePlayerStore.set(playerUid)` waliduje wyłącznie:
- niepusty campaignId,
- niepusty playerUid.

Nie sprawdza, czy `playerUid` faktycznie istnieje w danych kampanii ani czy reprezentuje legalną postać gracza.

Skutek:
- literówka lub błędne wywołanie może zostać persisted jako authoritative active player,
- `PlayerStateStore.load()` zwróci wtedy formalnie poprawny snapshot z pustymi sekcjami,
- pozostałe readery mogą zachowywać się niespójnie.

Wymagane hardening:
- dodać `PlayerIdentityValidator` lub równoważną walidację w kontrolowanej ścieżce `setActivePlayer`,
- walidacja powinna potwierdzić istnienie UID w authoritative character/entity source albo w jawnie zdefiniowanym legacy player source,
- odrzucać nieistniejący UID przed persistence.

## 4. Finding P3-B — canonical PlayerStateStore jest sztucznie ograniczony LIMIT 100

Severity: HIGH
Status: OPEN

`PlayerStateStore.rowsForEntity()` posiada domyślny `limit = 100`.

Dotyczy to m.in.:
- stats,
- skills,
- techniques,
- organizations,
- goals,
- injuries.

To jest poprawne jako bounded GM context retrieval, ale nie jako canonical repository read contract nazwany `playerState()`.

W długiej kampanii gracz może posiadać więcej niż 100 technik/skills/rekordów. Wtedy `PlayerStateSnapshot` staje się niekompletny bez żadnego sygnału o truncation.

Wymagane hardening:
- authoritative `playerState()` nie może cicho ucinać danych,
- bounded profile powinien być osobną projekcją/context profile,
- jeśli tymczasowo musi istnieć limit, snapshot musi jawnie zawierać `truncated=true` / counts, ale docelowo pełny repository read i bounded context read powinny być rozdzielone.

## 5. Finding P3-C — błędy persistence są maskowane jako pusty stan

Severity: HIGH
Status: OPEN

`PlayerStateStore.queryMany()` przechwytuje dowolny `Throwable` i zwraca `emptyList()`.

Podobny wzorzec istnieje w legacy readerach.

Skutek:
- brak tabeli,
- błąd migracji,
- uszkodzony schema contract,
- błędna nazwa kolumny,
- inne problemy SQLite

mogą wyglądać dla wyższej warstwy dokładnie tak samo jak legalne „gracz nie ma żadnych skills/techniques/injuries”.

Dla authoritative Player State jest to niebezpieczne.

Wymagane hardening:
- rozróżnić expected legacy absence od realnego query/schema failure,
- failure authoritative read powinien zwracać kontrolowany błąd/diagnostic result, a nie fałszywie pusty stan,
- tolerancyjne `safeQuery` może pozostać w presentation/context adapterach, ale nie w canonical player repository bez sygnału integralności.

## 6. Finding P3-D — legacy CharacterPanel nadal może ominąć identity isolation

Severity: HIGH
Status: OPEN

`CharacterPanelReader` przy braku `playerUid` albo braku kolumny `entity_uid` wraca do zapytań typu:
- `SELECT * ... LIMIT 1`,
- `SELECT ... FROM character_stats ORDER BY ...`,
- global inventory / relationships / goals.

Jeżeli migracja nie potrafi ustalić aktywnego gracza albo legacy tabela zawiera więcej niż jedną postać bez `entity_uid`, presentation może wyświetlić dane innego podmiotu albo dane wielu podmiotów.

To narusza zasadę: brak authoritative identity nie może być zastępowany przypadkowym pierwszym rekordem.

Wymagane hardening:
- przy braku ActivePlayerRef panel powinien zwrócić jawny `PLAYER_NOT_RESOLVED`/empty-safe snapshot, nie globalny fallback,
- dla tabel legacy bez `entity_uid` można używać ich wyłącznie wtedy, gdy schema/data potwierdzają jednoznacznie pojedynczy player snapshot,
- nie wolno arbitralnie używać pierwszego rekordu z wielowierszowej tabeli.

## 7. Finding P3-E — seed legacy nadal może wybrać złą postać

Severity: MEDIUM/HIGH
Status: OPEN

`legacyCandidate()` wybiera kolejno:
1. entity z największą liczbą `character_skills`,
2. entity z największą liczbą `character_techniques`,
3. pierwszy `character_finances`,
4. najnowszy `entity_positions`.

Jest deterministyczny, ale nadal heurystyczny.

Jeżeli NPC ma więcej skills niż gracz, pierwszy migration seed może utrwalić błędny UID jako authoritative.

Wymagane hardening:
- przed Fazą 4 zweryfikować realny bundled/default campaign schema i znaleźć najmocniejszy jednoznaczny legacy player marker,
- jeżeli brak jednoznacznego markera, migration powinna wykryć ambiguity i wymagać jawnego wyboru/repair, zamiast automatycznie zatwierdzać niepewnego kandydata,
- heurystyka może pozostać jako candidate suggestion, nie jako bezwarunkowe źródło prawdy.

## 8. Finding P3-F — testy nie pokrywają krytycznych invariantów persistence

Severity: HIGH dla Definition of DONE
Status: OPEN

Obecny `PlayerStatePolicyTest` sprawdza głównie:
- blank IDs,
- klasyfikację PERSISTENT/DERIVED/RUNTIME,
- strukturę context projection.

Brakuje testów:
- `ActivePlayerStore.set -> close -> reopen -> active`,
- migracji starej kampanii do `active_player_ref`,
- idempotentnego `ensureV3`,
- nieistniejącego Player UID,
- izolacji dwóch kampanii,
- izolacji dwóch postaci w jednej bazie,
- `PlayerStateStore` zwracającego wyłącznie aktywnego gracza,
- CharacterPanel nieprzeciekającego na innego entity,
- błędnego/niepełnego schematu,
- >100 skills/techniques bez silent truncation.

MASTER Definition of DONE wymaga persistence, migration safety i testów core invariants. Sam zielony build nie wystarcza do uznania tych invariantów za udowodnione.

## 9. Dodatkowe obserwacje

### 9.1 Dublowanie player data w ContextBundle
Nie jest blockerem Fazy 3, ale obecnie skills/techniques/organizations występują zarówno w starszych top-level polach ContextBundle, jak i wewnątrz `player_state.persistent`.

To tworzy potencjalne dwa read modele dla tego samego konceptu.

Docelowo należy wskazać jedną canonical projekcję i pozostawić stare pola jako compatibility adapter do czasu migracji GM Context.

### 9.2 CharacterPanelSnapshot pozostaje presentation
To jest poprawne i zgodne z roadmapą. Jego przebudowa należy do późniejszych etapów 24/25 i nie jest powodem niezaliczenia Fazy 3.

### 9.3 Brak DerivedValueResolver
To jest poprawne. DerivedValueResolver należy do Fazy 5 i nie powinien być implementowany w ramach naprawy Fazy 3.

## 10. Wynik końcowy

### Funkcjonalność bazowa
PASS.

### Architektura Player State
PASS z lukami hardening.

### Integralność authoritative identity
PARTIAL.

### Persistence / migration proof
PARTIAL.

### Isolation safety
PARTIAL.

### Test coverage Definition of DONE
FAIL / INSUFFICIENT.

### CI / build
PASS.

## 11. Decyzja audytowa

Faza 3 nie powinna obecnie mieć statusu COMPLETE.

Nowy status:

`[-] PARTIAL — core contract implemented, integrity hardening required`

Nie należy jeszcze rozpoczynać Fazy 4, ponieważ najwcześniejsza brakująca zależność nadal znajduje się w Fazie 3.

## 12. Minimalny plan domknięcia Fazy 3

1. Dodać walidację istnienia/legalności `setActivePlayer`.
2. Usunąć silent truncation z canonical `PlayerStateStore` albo rozdzielić FULL repository read od bounded context projection.
3. Zastąpić silent query failure kontrolowanym wynikiem/diagnostyką w authoritative read path.
4. Usunąć global fallback w `CharacterPanelReader`, gdy player identity nie jest resolved.
5. Utwardzić legacy seed przeciw ambiguity.
6. Dodać testy persistence/migration/isolation i >100 records.
7. Uruchomić pełny JVM test + signed APK CI.
8. Dopiero wtedy przywrócić `[x] COMPLETE` i rozpocząć Fazę 4.

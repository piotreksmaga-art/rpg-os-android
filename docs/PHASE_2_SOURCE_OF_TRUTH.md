# RPG OS — PHASE 2 SOURCE OF TRUTH EVIDENCE

Status: IMPLEMENTED / awaiting final head CI confirmation before roadmap COMPLETE marker

Architecture: `docs/RPG_OS_MASTER_ARCHITECTURE.md`
Roadmap item: `2. Campaign Source of Truth + FACT/BELIEF/NARRATIVE + provenance`

## 1. Cel

Faza 2 wprowadza jednoznaczny kontrakt semantyczny dla informacji kampanii. System rozróżnia:

- `FACT` — obiektywną, zatwierdzoną rzeczywistość kampanii,
- `BELIEF` — przekonanie konkretnego aktora/perspektywy,
- `NARRATIVE` — warstwę prezentacyjną, która nie staje się automatycznie faktem.

Każdy nowy rekord tej warstwy posiada jawne provenance.

## 2. Implementacja

### `CampaignTruthModels.kt`
Wprowadzono:
- `TruthKind`,
- `ProvenanceSourceType`,
- `Provenance`,
- `CampaignTruthRecord`,
- `CampaignTruthPolicy`.

Kontrakt wymusza m.in.:
- `BELIEF` wymaga `perspectiveUid`,
- `NARRATIVE` wymaga `narrativeText`,
- `FACT` i `BELIEF` nie mogą przenosić pola narracyjnego,
- confidence mieści się w zakresie `0..1`,
- narracja nie może automatycznie awansować do faktu.

### `CampaignTruthStore.kt`
Dodano trwały store działający na `campaign.db`:
- zapis tylko z jawnym `TruthKind` i `Provenance`,
- izolacja po `campaignId`,
- bounded retrieval,
- filtrowanie po kind/subject/perspective,
- supersession przez oznaczenie starego rekordu jako nieaktywnego zamiast niszczenia historii,
- ochrona przed supersede rekordu należącego do innej kampanii.

### `MigrationManager.ensureV2()`
Dodano addytywną migrację `RPGOS-2.0-TRUTH` tworzącą `campaign_truth_records` i indeksy.

Migracja nie backfilluje starych tabel wymyślonymi faktami ani provenance. Istniejące kampanie zachowują dotychczasowe dane, a nowa warstwa zaczyna gromadzić jawnie typowane informacje od momentu jej użycia.

### `CampaignRepository`
Kanoniczny kontrakt repozytorium otrzymał:
- `recordTruth(...)`,
- `truthRecords(...)`.

### Runtime integration
`LocalGameStore` uruchamia `ensureV2` przy bootstrapie, zmianie aktywnej kampanii, restore oraz przed budową kontekstu.

`ContextBundle` otrzymał `campaignTruth`, a `JsonCodec` serializuje go jako `campaign_truth`. Retrieval jest ograniczony do 80 aktywnych rekordów dla bieżącego kontekstu.

`UnifiedGameRepository` udostępnia zapis/odczyt truth records przez kontrakt `CampaignRepository`; runtime ViewModel korzystający jeszcze z `LocalGameStore` również otrzymuje tę samą warstwę w ContextBundle, więc Phase 2 działa w aktualnej ścieżce wykonawczej bez oczekiwania na późniejszy refactor ViewModelu.

### Generic StatePatch guard
`SourceOfTruthRegistry.canWrite()` jawnie blokuje `campaign_truth_records` dla generycznego `StatePatchEngine`.

AI nie może więc ominąć typed/provenance-aware API i wpisać dowolnego tekstu bezpośrednio jako prawdy kampanii.

### Backend GM semantics
Backend otrzymał jawne reguły:
- `FACT` jest obiektywną zatwierdzoną rzeczywistością,
- `BELIEF` obowiązuje wyłącznie perspektywę właściciela,
- `NARRATIVE` jest prezentacją, nie historią faktualną,
- `NARRATIVE` nie może zostać automatycznie promowane do `FACT`,
- przy konflikcie FACT ma pierwszeństwo przed narracją,
- state patch nie może pisać bezpośrednio do `campaign_truth_records`.

## 3. Provenance

Obsługiwane źródła obejmują:
`WORLD_CANON`, `CAMPAIGN_EVENT`, `PLAYER_ACTION`, `NPC_OBSERVATION`, `NPC_COMMUNICATION`, `NPC_INFERENCE`, `RESEARCH`, `SIMULATION`, `RULE_ENGINE`, `MANUAL_IMPORT`, `SYSTEM_MIGRATION`, `LEGACY`.

Rekord może przechowywać m.in.:
- sourceId,
- createdTurn,
- createdEvent,
- confidence,
- canonStatus,
- verified,
- actorUid,
- method,
- engineVersion.

## 4. Bezpieczeństwo istniejących kampanii

Zmiana jest addytywna:
- nie usuwa starych tabel,
- nie zmienia istniejących UID,
- nie przepisuje dotychczasowej historii,
- nie wymyśla provenance dla danych legacy,
- po restore migracja jest ponownie idempotentnie zapewniana,
- tabela jest odseparowana przez `campaign_id`.

## 5. Testy

Dodano `CampaignTruthPolicyTest` obejmujący:
- BELIEF bez perspektywy -> reject,
- NARRATIVE bez tekstu -> reject,
- FACT z narrativeText -> reject,
- confidence > 1 -> reject,
- brak automatycznego NARRATIVE -> FACT,
- poprawny FACT z jawnym provenance -> accept.

CI run #73 dla commita `1d1c492b1ac96785ec77ed0520a379f6a62ca65f` zakończył się SUCCESS: walidacja, JVM unit tests, signed APK i artifact przeszły poprawnie. Późniejsze commity Phase 2 zachowują ten sam kontrakt i są ponownie walidowane przez CI przed ustawieniem roadmapy na COMPLETE.

## 6. Frontend

Frontend pozostał nietknięty. Zmiany dotyczą kontraktu danych, persistence, kontekstu GM i backendowych zasad interpretacji prawdy.

## 7. Granica Fazy 2

Faza 2 definiuje i persistuje typowaną prawdę kampanii oraz provenance. Nie zastępuje jeszcze:
- pełnego Event Store,
- Turn Transaction,
- temporal truth resolver,
- NPC knowledge acquisition validator,
- PlayerDomainEngine.

Te elementy mają własne późniejsze etapy roadmapy.

## 8. Następna zależność

Po pełnym zielonym CI dla finalnego `master` najwcześniejszą zależnością jest:

`3. Player State Contract: Persistent / Derived / Runtime`.

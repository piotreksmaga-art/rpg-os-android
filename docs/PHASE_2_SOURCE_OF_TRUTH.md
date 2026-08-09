# RPG OS — PHASE 2 SOURCE OF TRUTH EVIDENCE

Status: COMPLETE

Architecture: `docs/RPG_OS_MASTER_ARCHITECTURE.md`
Roadmap item: `2. Campaign Source of Truth + FACT/BELIEF/NARRATIVE + provenance`

## 1. Cel
Faza 2 wprowadza jednoznaczny kontrakt semantyczny dla informacji kampanii:
- `FACT` — obiektywna, zatwierdzona rzeczywistość kampanii,
- `BELIEF` — przekonanie konkretnego aktora/perspektywy,
- `NARRATIVE` — warstwa prezentacyjna, która nie staje się automatycznie faktem.

Każdy nowy rekord tej warstwy posiada jawne provenance.

## 2. Implementacja

### `CampaignTruthModels.kt`
Wprowadzono `TruthKind`, `ProvenanceSourceType`, `Provenance`, `CampaignTruthRecord` i `CampaignTruthPolicy`.

Invariants:
- `BELIEF` wymaga `perspectiveUid`,
- `NARRATIVE` wymaga `narrativeText`,
- `FACT` i `BELIEF` nie mogą przenosić pola narracyjnego,
- confidence musi należeć do `0..1`,
- `NARRATIVE` nie może automatycznie awansować do `FACT`.

### `CampaignTruthStore.kt`
Trwały store w `campaign.db` zapewnia:
- zapis tylko z jawnym `TruthKind` i `Provenance`,
- izolację po `campaignId`,
- bounded retrieval,
- filtrowanie po kind/subject/perspective,
- supersession przez dezaktywację starego rekordu zamiast niszczenia historii,
- ochronę przed supersede rekordu z innej kampanii.

### `MigrationManager.ensureV2()`
Addytywna migracja `RPGOS-2.0-TRUTH` tworzy `campaign_truth_records` i indeksy.

Migracja nie backfilluje starych tabel wymyślonymi faktami ani provenance. Istniejące kampanie zachowują dotychczasowe dane.

### `CampaignRepository`
Kanoniczny kontrakt repozytorium otrzymał `recordTruth(...)` i `truthRecords(...)`.

### Runtime integration
`LocalGameStore` zapewnia `ensureV2` przy bootstrapie, zmianie aktywnej kampanii, restore i budowie kontekstu. `ContextBundle` posiada `campaignTruth`, a `JsonCodec` serializuje go jako `campaign_truth`. Retrieval dla GM jest ograniczony do 80 aktywnych rekordów.

`UnifiedGameRepository` udostępnia zapis/odczyt truth records, a aktualna ścieżka ViewModel -> LocalGameStore także otrzymuje tę samą warstwę w ContextBundle.

### Generic StatePatch guard
`SourceOfTruthRegistry.canWrite()` jawnie blokuje `campaign_truth_records` dla generycznego `StatePatchEngine`. AI nie może ominąć typed/provenance-aware API i wpisać dowolnego tekstu bezpośrednio jako prawdy kampanii.

### Backend GM semantics
Backend otrzymał reguły:
- FACT = obiektywna zatwierdzona rzeczywistość,
- BELIEF = wyłącznie przekonanie `perspective_uid`,
- NARRATIVE = prezentacja, nie faktualna historia,
- brak automatycznej promocji NARRATIVE -> FACT,
- FACT wygrywa z konfliktem narracji,
- `state_patch` nie może pisać do `campaign_truth_records`.

## 3. Provenance
Obsługiwane źródła: `WORLD_CANON`, `CAMPAIGN_EVENT`, `PLAYER_ACTION`, `NPC_OBSERVATION`, `NPC_COMMUNICATION`, `NPC_INFERENCE`, `RESEARCH`, `SIMULATION`, `RULE_ENGINE`, `MANUAL_IMPORT`, `SYSTEM_MIGRATION`, `LEGACY`.

Rekord może przechowywać `sourceId`, `createdTurn`, `createdEvent`, `confidence`, `canonStatus`, `verified`, `actorUid`, `method`, `engineVersion`.

## 4. Bezpieczeństwo istniejących kampanii
Zmiana jest addytywna: nie usuwa starych tabel, nie zmienia istniejących UID, nie przepisuje historii, nie wymyśla legacy provenance, po restore idempotentnie zapewnia nowy schema contract i izoluje rekordy przez `campaign_id`.

## 5. Testy i CI
`CampaignTruthPolicyTest` sprawdza odrzucenie niepoprawnych BELIEF/NARRATIVE/FACT, zakres confidence, zakaz automatycznego NARRATIVE -> FACT i poprawny FACT z provenance.

CI run #73 dla `1d1c492b1ac96785ec77ed0520a379f6a62ca65f` zakończył się pełnym SUCCESS.

Finalny kod implementacyjny Phase 2 na commicie `c05944af5ec04160eddbbc7694e38a0267d08905` przeszedł w run #76: `Validate project` SUCCESS, `Run JVM unit tests` SUCCESS, `Build signed ALPHA APK` SUCCESS, przygotowanie i upload artifact SUCCESS oraz release asset update SUCCESS. Końcowe post-actions są częścią tego samego workflow.

## 6. Frontend
Frontend pozostał nietknięty.

## 7. Granica Fazy 2
Faza 2 definiuje i persistuje typowaną prawdę kampanii oraz provenance. Pełny Event Store, Turn Transaction, temporal truth resolver, NPC knowledge acquisition validator i PlayerDomainEngine pozostają osobnymi późniejszymi etapami roadmapy.

## 8. Następna zależność
Najwcześniejszą zależnością po Phase 2 jest `3. Player State Contract: Persistent / Derived / Runtime`.

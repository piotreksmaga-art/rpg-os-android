# Phase 55–59 — Memory, Consolidation, Retrieval, Undo — implementation candidate

Data: 2026-09-02

## Status

- Fazy 55–57, 58, 59 są wdrożonymi kandydatami (`implementation candidate`) i mają lokalnie potwierdzony GREEN flow.
- Nie są globalnie oznaczone `COMPLETE` do czasu:
  - pełnego `exact-SHA CI`,
  - urządzeniowych testów Android dla docelowych scenariuszy,
  - i ostatecznej walidacji `undo/replay/memory lifecycle`.
- Branching i rewiring globalnej linii historii **nie jest częścią tego wydania** (pozostaje faza 72).

## 1. Ownership i authority boundaries

- **RPG OS owns memory**: pamięć kampanii, derived memory i index artifacts należą do RPG OS, a nie do modelu AI ani runtime.
- **Phase37 remains knowledge owner**: holder knowledge, holder-aware beliefs, temporal evidence i knowledge provenance pozostają domeną Phase37.
- **Bekko scope**: tylko `SEARCH / MATCH / RANK / CLUSTER`; wyniki trafiają jako candidate ranking, nie jako FACT.
- **AI scope**: `Bielik`, `OpenRouter` i inne generatory modelowe tworzą wyłącznie interpretacyjne pre-sumy lub presentation-only payloady.

## 2. Working memory pipeline (Phase55/56/57)

- `WorkingMemoryScope` jest zbudowany z istniejących boundary: `Phase38 / Phase39 / Phase45`.
- Konsolidacja pamięci działa w trzech warstwach:
  1. `EpisodeManifest`
  2. `EpisodeInterpretation`
  3. `HolderEpisodeMemory`
- Segmentacja epizodów jest deterministyczna i **nie nakłada się**:
  - max `20` tur,
  - max `128` eventów na epizod,
  - brak overlapów i brak niejawnego „sliding window”.
- `HolderEpisodeMemory` powstaje wyłącznie z legalnych `acquisition` / evidence z Phase37.
- `SemanticMemoryAssertion` zawiera typowane pola:
  - `polarity`
  - `epistemicKind`
  - `temporalValidity`
  - `lifeCycle`
  - `support`
  - `contradictionLineage`
- Zasadniczo **nie inferujemy** faktów z braku danych (brak „closed world” bez jawnej reguły).
- Segmentacja epizodów jest deterministyczna (`DeterministicEpisodeSegmenter`) i pracuje na wejściu z typed `MemoryEventLeaf`.

## 3. Phase58 — deterministic consolidation

- Konsolidacja jest idempotentna i wyłączona z rekursywnego `summary-of-summary`.
- Pipeline jest resume-able:
  - `ConsolidationReceipt`
  - `watermark`
  - `resumeState`
- `EpisodeManifest` i artifact payload są persistowane w `MemoryArtifactStore`/`MemoryManifestStore`.
- Memory dependency model używa stanów: `CLEAN`, `DIRTY`, `REBUILDING`, `FAILED`, `SUPERSEDED`, `ORPHANED`.
- Typowane miary/score używane przez warstwę rankingową: `SemanticSimilarityScore`, `BeliefConfidence`, `RecallStrength`, `MemoryAccuracy`.
- Granice bezpieczeństwa:
  - do `256` leaves / batch
  - do ok. `500ms` pracy na batch
- Projekcja aktywnych artefaktów do Phase59 używa keyset pagination i sesji reconciliation:
  - strony po maksymalnie `128` artefaktów,
  - indeks SQL po `campaign/history/status/as-of/revision`,
  - brak materializacji całej listy 200 tys.–1 mln artefaktów,
  - atomowe wycofanie rewizji, których nie ma już w aktywnym zbiorze.
- Uruchomienie:
  - brak periodicznego WorkManagera,
  - tylko `post-commit` oraz `open campaign` gap fix.
- Cała porcja Phase58 działa we wspólnej bramce `SemanticCampaignTransitionRegistry`.
  Operacja wymagająca podmiany lub usunięcia storage (w szczególności `undo`) czeka więc
  zarówno na wyszukiwanie/indeksowanie, jak i na aktywną konsolidację; worker starej
  `HistoryGenerationUid` nie może pisać równolegle do podmiany DB/WAL/SHM.
- Używany workload: `MEMORY_ENRICHMENT`.
- Przy awarii/cięciu:
  - nie usuwamy commit history,
  - replay/digest pozwalają wznowić bez utraty idempotencji.
- Asynchroniczny AI side może produkować tylko: title/description/tags; nie blokuje tury.

## 4. Phase59 — vector/semantic retrieval integration

- Indeks to `per-campaign cache/rebuildable`:
  - `principal`
  - `holderFingerprint`
  - `purpose`
  - `activePlayer`
  - `policyVersion`
  - `HistoryGenerationUid`
- Zwracane wyniki:
  - canonical UID,
  - typowany score,
  - diagnostyczny chunk evidence.
- Każdy wynik jest rehydratowany z aktualnego canonical ownera przed użyciem.
- Zapytanie produkcyjne skanuje strumieniowo wyłącznie pełny, dokładnie autoryzowany zakres
  (`history generation + principal + holder set + policy + active player + projection version`).
  Dopiero top-K UID-ów jest materializowane, ponownie sprawdzane i rehydratowane; nie powstaje
  mapa wszystkich UID-ów kampanii na każdą turę.
- Weryfikacje przy użyciu wyniku:
  - as-of i source version,
  - `Phase38 access`,
  - `Phase45 budget`.
- `REQUIRED` + `SAFETY` mają priorytet ponad similarity score.
- Nie traktujemy domyślnie braku evidence jako `PROJECTED_FACT`.
- `HistoryGenerationUid` jest właścicielem wersji indeksu: zmiana UID unieważnia stare derived runs i index jobs, także przy undo/restore.
- Director otrzymuje ograniczony tekst wyłącznie z canonical rehydration, nigdy tekst zapisany
  w sidecarze. UID, kind, epistemic state i score pozostają diagnostyczne.
- Zmiana backendu Bekko (`CPU`/ręczny `Vulkan`) jest procesową transakcją konfiguracji:
  zapis ustawień, odświeżenie wszystkich instancji aplikacji i zamknięcie współdzielonych
  natywnych runtime następują w tej samej bramce. Zapobiega to jednoczesnemu utrzymywaniu
  dwóch kopii modelu przez UI, Bridge i Directora.
- Pełna operacja konsumenta (embedding, scan, canonical rehydration) posiada semantic-runtime
  lease. Pooled provider stosuje read-lease dla `open/embed/cancel` i write-lease dla `close`,
  więc ostatnia referencja nie może zamknąć ani ponownie osierocić natywnego uchwytu w trakcie
  wywołania. Wyrejestrowanie aplikacji i zamknięcie jej runtime są jedną operacją pod tą samą bramką.
- Bramka procesu używa sprawiedliwego kontraktu wielu czytelników/jednego pisarza. Przejście
  konfiguracji lub storage najpierw blokuje nowych czytelników i sygnalizuje anulowanie aktywnego
  requestu embeddingów, a dopiero później oczekuje na wyłączny dostęp. Sygnał jest ponawiany dla
  runtime opublikowanego podczas wyścigu inicjalizacji. Dzięki temu ścieżka odpowiedzialna za
  anulowanie nie czeka sama na siebie.
- `BekkoSemanticApplication.close()` jest terminalne: zachowany port konsumenta po zamknięciu może
  wyłącznie zwrócić typed fallback i nie może ponownie utworzyć JNI/SQLite/RAF. Zmiana ustawień,
  usunięcie modelu/indeksu i zamknięcie ViewModelu wykonują potencjalnie blokujący lifecycle poza
  głównym wątkiem Androida; terminalność jest ustawiana synchronicznie przed uruchomieniem workera,
  a executor konsolidacji repozytorium również jest zamykany.
- Każda dzierżawa współdzielonego natywnego providera nadaje requestom własny namespace. Identyczne
  deterministyczne UID-y z UI, Bridge i Directora nie mogą anulować cudzej operacji. Częściowe
  ustawienia (`enabled`/`backend`) są scalane pod tą samą procesową transakcją, więc równoległe
  kontrolery nie odtwarzają starszej wartości drugiego pola.
- Plik wektorów jest kompaktowany także przy wielu otwartych instancjach indeksu. Kompaktowanie
  in-place jest serializowane globalną blokadą, posiada marker awarii i aktualizuje offsety
  w transakcji SQLite; niedokończony cache jest czyszczony i odbudowywany, nigdy używany jako truth.

## 5. Undo i derived lifecycle

- `Undo` jest bezbranchingowy:
  1) `UndoRequest` (preview),
  2) `UndoPreview`,
  3) osobna confirm.
- Odzyskanie stanu odbywa się przez replay/reconstruction envelope V2, sprawdzenie digesta i stagingu, a następnie atomic swap.
- Cofnięta została **tylko ostatnia committed tura**.
- Po udanym undo:
  - wszystkie derived memory rows cofniętej aktywnej historii są fizycznie usuwane ze zrekonstruowanej bazy,
  - `HistoryGenerationUid` przechodzi do nowej generacji,
  - cały per-campaign sidecar Bekko (SQLite + wektory) jest usuwany i odbudowywany,
  - nieaktualne snapshoty wewnętrzne po granicy cofnięcia są usuwane,
  - ręczne backupy pozostają dostępne.
- Derived memory/indexy są trwałe, ale rebuildable i **nie wchodzą** do canonical hash.

## 6. Związki między sekcjami dokumentu

- Szczegółowy model pipeline: `docs/Architektura projektu.md` (sekcja 16.1 i 13.3).
- Główna roadmapa aktualizacji: `docs/Roadmap.md`.

## 7. Dodatkowe uwagi

- `HistoryGenerationUid` pełni rolę punktu unieważnienia starych derived pipelines po undo i przy restore backupu.

## 8. Aktualna bramka akceptacyjna

- 2026-09-06: exact-SHA CI dla `96e24465ba080fb49330c69fb3793e1b66a9b3a3`
  przeszedł wszystkie pięć bramek: memory/undo, pełny JVM, Android API 28 i 36,
  izolacja release. Run: https://github.com/piotreksmaga-art/rpg-os-android/actions/runs/33999385120.
- Dalszy test rzeczywistego Bielika ujawnił przepełnienie Directora (6024 > 2048 tokenów)
  oraz nieobsłużony Android `JSONException` po urwanej odpowiedzi. Kompaktowy lokalny
  kodek i obsługa wyjątku przeszły cztery testy regresyjne. Powtórka na Motoroli:
  435 tokenów wejścia, 145 wyjścia, 37,777 s inferencji; zadanie `DIRECTOR_BUNDLE_ACCEPTED`,
  aplikacja nadal dostępna. To dowód techniczny, nie akceptacja jakości: odpowiedź była
  po angielsku i proponowała nieugruntowanych „Sage Warriors”. Bramka jakości/A-B pozostaje otwarta.
- Lokalny Director generuje jedną propozycję bez referencji do rekordów. Aplikacja wiąże
  ją z oryginalną tożsamością zadania; kandydat trafia do Phase65 i zwykłej walidacji,
  nigdy bezpośrednio do mutacji. Pełny manifest UID-ów nie jest wysyłany małemu modelowi.

- Pełny JVM (`debug` + `labDebug`) oraz celowane testy Phase55–59 są GREEN.
- Kompilacja testów Androida i `assembleLabDebug` są GREEN.
- Motorola Edge 30 Neo / Android 14: test utworzenia kampanii i postaci, dwóch tur,
  bezpowrotnego undo oraz alternatywnej dalszej gry jest GREEN.
- Motorola Edge 30 Neo / Android 14: pełny test 100 tur jest GREEN (save/reopen,
  monotoniczne commity, undo ostatniej tury, zmiana `HistoryGenerationUid`, 99 tur po undo
  i alternatywna przyszłość; czas instrumentacji 773,612 s). Emulator nie został jeszcze
  doprowadzony do stanu testowego: obraz API 36 osiąga `Boot completed`, ale lokalny host
  ADB odrzuca go jako `unauthorized` (brak interaktywnego potwierdzenia klucza w trybie
  headless).
- Użytkownik zlecił wydanie ALPHA 18 z ujawnionymi ograniczeniami jakości. Publikacja wymaga
  zielonego CI finalnej rewizji. Oznaczenie faz 55–59 jako globalnie `COMPLETE` nadal wymaga
  A/B i jakości Bielik/Bekko; wydanie ALPHA nie zastępuje tej bramki.

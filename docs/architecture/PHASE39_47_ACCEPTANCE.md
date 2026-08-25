# RPG OS — Phase 39–47 Consolidated Acceptance

Status: **IMPLEMENTED / LOCAL + EXACT-SHA CI GREEN / COORDINATOR ACCEPTANCE REQUIRED**

Work ID: `WORK-20260825-001`

Ten jeden rekord zawiera dziewięć osobnych sekcji acceptance, aby ograniczyć powtarzane audyty bez łączenia odpowiedzialności faz.

## Phase acceptance

- **39 — Temporal Engine:** typowane wyniki temporalne, jawne `atOrder`, deterministyczne sortowanie i port do historycznej authority Phase 38; brak zastępowania historii stanem bieżącym.
- **40 — Scheduler:** trwałe evaluation points i typowane przejścia `PENDING/CLAIMED/CANCELLED/PROCESSED`; outcome nie jest precommitowany, zapis wymaga transakcji, a stan jest liczony as-of wskazanego order.
- **41 — Structured Retriever:** zamknięty rejestr providerów i operacji, parametryzowane requesty, twarde limity, provenance i typowane fail-closed results; brak SQL pochodzącego z promptu.
- **42 — causal/graph retrieval:** bounded traversal z kierunkiem, głębokością, limitami, campaign scope i zachowaniem rodzaju relacji; brak tworzenia canonical facts.
- **43 — Intent Parser:** canonical `IntentDocument` v2 zachowuje graf wielu działań, participants/roles, reference states, future-result dependencies, conditions, constraints/preferences, modality/polarity, correction/cancellation, commitment, uncertainty i player-context claims. Deterministyczny validator odrzuca dangling/cyclic/duplicate relations oraz próby nadania przez AI canonical action/world UID. Regułowy parser pozostaje compatibility fallbackiem.
- **44 — Turn Planner:** pure i deterministyczny `GraphTurnPlanner` planuje wszystkie legalnie rozwiązane cele z trusted capability registry i wydaje formalny `CapabilityEnvelope`; nie pobiera danych, nie wykonuje mechaniki i nie mutuje stanu.
- **45 — Context Builder:** składa wyłącznie audience/purpose-scoped projected results, waliduje provenance i zachowuje typed state; pełny canonical intent+plan stanowi niedropowalny semantic core.
- **46 — Context Budget Manager:** budżet obejmuje final serialized payload oraz protocol/system/output/safety reserves. REQUIRED/SAFETY context nie jest po cichu usuwany, a overflow daje wynik unsafe zamiast ucięcia semantyki.
- **47 — Iterative Retrieval:** jawne limity iteracji, follow-upów, rekordów i payloadu, deduplikacja fingerprintów, re-budget po każdej iteracji oraz zakaz wyjścia poza oryginalny `CapabilityEnvelope`.

## Integration and boundaries

Pipeline `input -> IntentDocument -> graph turn plan -> structured retrieval -> integrity context -> budget -> bounded completion` działa deterministycznie bez providera AI. Adapter Phase 38.5 nie został wprowadzony; powstały wyłącznie konkretne porty do zaakceptowanych właścicieli Phase 38–42. Legacy `NormalizedIntent`/`TurnPlan` pozostają fallbackiem, a nie drugim canonical authority systemem.

Schemat Phase 40 jest rejestrowany w `TEMPORAL_SCHEDULE_STATE`, tworzony przez production bootstrap i objęty istniejącą warstwą runtime truth/mutation guards. Plik writera jest jawnie sklasyfikowany w zamkniętym inventory Phase 32.

## Evidence

- forced production Kotlin compilation: GREEN, zero compiler warnings;
- `Phase39To47BlockTest`: 5/0/0;
- `Phase39To47R1BoundaryRepairTest`: 20/0/0;
- `Phase39To47Audit3RepairTest`: 15/0/0;
- `Phase43To54VerticalSliceTest`: 7/0/0;
- combined local canonical gate: 47/0/0;
- full Windows Robolectric run: 1051 tests / 192 failures, environment-inconclusive. 190 failures są zgodne z ograniczeniami lokalnego sqlite4java (`UPSERT`, `VACUUM INTO`, brak runtime custom SQL functions i file-backed DB path); 2 izolowane legacy concurrency assertions również nie przechodzą na tym backendzie. Nie dodano obejścia do kodu produkcyjnego;
- exact code-bearing SHA: `5ae6f0648704b114c6aa38ddea7f912006709d8d`;
- `Validate RPG OS ALPHA` run `32889856844`, job `97938967272`: SUCCESS, full JVM + signed validation APK;
- `Phase39-47 Audit3 Validation` run `32889856923`, focused job `97938967635` and full-JVM job `97939753043`: SUCCESS;
- `Phase38 AUD002 Forensic Gate` run `32889856858`, targeted job `97938967407` and full-JVM job `97939877522`: SUCCESS;
- immutable signed artifact `9579252027`, `RPG-OS-VALIDATION-1.2.0-alpha5-hybrid145-5ae6f0648704b114c6aa38ddea7f912006709d8d`, digest `sha256:0ad2e25010501b235695be0c1823a21e4f1f336d1e85f7e7e1a7ba39d48a841e`.

Wymagany vertical slice Phase 48–54 jest opisany oddzielnie w `PHASE48_54_VERTICAL_SLICE_ACCEPTANCE.md`; nie oznacza pełnego acceptance szerokich Faz 48–54.

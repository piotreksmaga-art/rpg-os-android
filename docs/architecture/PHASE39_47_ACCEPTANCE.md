# RPG OS — Phase 39–47 Consolidated Acceptance

Status: **IMPLEMENTED / LOCAL GATES GREEN / EXACT-SHA CI REQUIRED**

Work ID: `WORK-20260825-001`

Ten jeden rekord zawiera dziewięć osobnych sekcji acceptance, aby ograniczyć powtarzane audyty bez łączenia odpowiedzialności faz.

## Phase acceptance

- **39 — Temporal Engine:** typowane wyniki temporalne, jawne `atOrder`, deterministyczne sortowanie i port do historycznej authority Phase 38; brak zastępowania historii stanem bieżącym.
- **40 — Scheduler:** trwałe evaluation points i typowane przejścia `PENDING/CLAIMED/CANCELLED/PROCESSED`; outcome nie jest precommitowany, zapis wymaga transakcji, a stan jest liczony as-of wskazanego order.
- **41 — Structured Retriever:** zamknięty rejestr providerów i operacji, parametryzowane requesty, twarde limity, provenance i typowane fail-closed results; brak SQL pochodzącego z promptu.
- **42 — causal/graph retrieval:** bounded traversal z kierunkiem, głębokością, limitami, campaign scope i zachowaniem rodzaju relacji; brak tworzenia canonical facts.
- **43 — Intent Parser:** deterministyczny parser regułowy zachowujący actor/action/target/method/time; niejednoznaczne lub nieobsługiwane wejście nie jest zgadywane.
- **44 — Turn Planner:** deterministyczny, bounded plan capability/retrieval; planner nie pobiera danych, nie wykonuje mechaniki i nie mutuje stanu.
- **45 — Context Builder:** składa wyłącznie audience/purpose-scoped wyniki providerów i zachowuje typed state/provenance; nie jest właścicielem surowych ukrytych danych.
- **46 — Context Budget Manager:** profil efektywnego okna i rezerwy odpowiedzi, deterministyczny priorytet oraz twardy invariant `usedUnits <= availableUnits`.
- **47 — Iterative Retrieval:** jawne limity iteracji i follow-upów, deduplikacja fingerprintów, zakaz rozszerzania campaign/audience/purpose oraz bezpieczny wynik częściowy.

## Integration and boundaries

Pipeline `input -> normalized intent -> turn plan -> structured retrieval -> context candidate -> budget -> bounded follow-up` działa bez providera AI i bez funkcji Phase 48+. Adapter Phase 38.5 nie został wprowadzony; powstały tylko konkretne porty właścicieli Phase 39–42.

Schemat Phase 40 jest rejestrowany w `TEMPORAL_SCHEDULE_STATE`, tworzony przez production bootstrap i objęty istniejącą warstwą runtime truth/mutation guards. Plik writera jest jawnie sklasyfikowany w zamkniętym inventory Phase 32.

## Evidence

- production Kotlin compilation: GREEN;
- `Phase39To47BlockTest`: 5/0/0;
- `Phase32RepositoryWideWriterSourceInventoryTest`: 3/0/0;
- combined local gate run: 8/0/0;
- full Windows Robolectric run: environment-inconclusive because the Windows sqlite4java backend rejects baseline SQL features (`UPSERT`, `VACUUM INTO`) before Phase 39–47 paths; no production workaround was added;
- exact-SHA GitHub Actions JVM regression and signed validation APK: required before merge.

Phase 48 remains not started.

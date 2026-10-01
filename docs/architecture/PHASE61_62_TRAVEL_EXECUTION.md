# Phase61–62 R1 — podróż NPC

Status: **R1 COMPLETE CANDIDATE — merge wyłącznie po exact-SHA GREEN**.  
Baza R0: `4e6debebf4df04b165aa59f94fead65d57fa77ca`.

## Niezmienny kontrakt

```text
NPC intent / plan
!= rozpoczęcie podróży
!= upływ czasu
!= dotarcie

Dotarcie =
jawny kontrakt trasy
+ aktualny dostęp aktora
+ świeży preflight
+ Phase60 boundary
+ Phase50 SpatialChange
+ zwykły TurnTransaction
+ persisted receipt + committed replay
```

Phase62 nie jest właścicielem pozycji, zasobów ani czasu. Model nie może utworzyć trasy, przyznać dostępu, określić sukcesu ani zapisać lokacji.

## Authority trasy

R1 dodaje administracyjną rodzinę definicji:

- `rpgos_travel_route_definitions`;
- `rpgos_travel_route_costs`;
- `rpgos_travel_route_access`.

Jest klasyfikowana jako `MECHANICS_DEFINITION_AUTHORITY`, tworzona tylko przez jawny bootstrap/migrację i chroniona administrative guards. Zwykły gameplay nie może dodawać ani zmieniać tras.

`SqliteNpcTravelRoutePort` odczytuje wyłącznie:

- aktywną trasę dokładnej kampanii;
- dokładny origin;
- wersję kontraktu;
- jawny czas i regułę czasu;
- koszt zasobów;
- eligibility/capability;
- politykę `PUBLIC` albo dokładny grant aktora.

Legacy `travel_profiles` opisuje profile prędkości, a `trade_routes_v2` gospodarkę. Żaden z nich nie jest automatycznie promowany do fizycznej drogi. Dwie istniejące lokacje również nie tworzą połączenia.

World Pack lub narzędzie administracyjne może dostarczyć dowolne własne UID-y tras bez dodawania nazw świata do Core.

## Affordance

Opcja podróży istnieje tylko, gdy równocześnie:

1. `MechanicalActorView` należy do właściwej kampanii i aktora;
2. canonical `locationRef` dokładnie odpowiada origin trasy;
3. aktualny route authority dopuszcza trasę dla tego aktora;
4. destination jest już w legalnej holder-scoped wiedzy NPC;
5. aktor spełnia eligibility/capability;
6. aktor jest materializowany i zdolny do działania;
7. posiada wymagane zasoby.

Samo istnienie trasy nie ujawnia destination. Phase37/38 nadal kontroluje wiedzę.

## Stan przestrzenny

`MechanicalActorStateStore.actor()` projektuje `locationRef` z canonical `entity_positions.location_uid`, a nie z technicznego `POSITION:<entity>`. Exact coordinates pozostają osobnym odczytem. Brak legacy tabeli pozycji daje `locationRef=null`, nie fikcyjną lokację i nie zapis przy odczycie.

## Wykonanie

`NpcTravelMechanics` ma osobną fail-closed ścieżkę w `NpcMechanicalActionApplication`.

Start:

- Core wybiera istniejącą opcję;
- wykonuje preflight;
- zapisuje wyłącznie `NpcPendingAction` i deadline;
- preflight effects są odrzucane.

Completion:

- nie ma drugiego wywołania modelu;
- ponownie odczytywany jest current scope;
- ponownie odczytywany jest canonical actor/location;
- route authority jest ponownie pytane o dokładnego aktora;
- route fingerprint, destination, capability, koszt i timing muszą nadal odpowiadać saved intention;
- staged movement lub staged koszt zasobu może unieważnić próbę;
- dopiero wtedy powstają verified effects.

Polityka `COMPLETION_ONLY_V1` rozlicza wszystkie koszty i `LOCATION_TRANSITION` razem. Przerwanie nie przyznaje częściowej drogi ani kosztu. Etapowe zużycie zasobów wymaga w przyszłości osobnego kontraktu.

`LOCATION_TRANSITION` przechodzi przez istniejący Phase50 materializer do `SpatialChange`; nie ma nowego writera pozycji.

## Plany i alternatywa

Pending action nie serializuje przyszłych efektów. Round-trip zachowuje zamiar, czas i regułę.

Jeśli pierwsza preautoryzowana trasa znika przy granicy czasu, wcześniej wskazana alternatywa może zostać rozpoczęta po nowym context/preflight bez drugiego AI call. Alternatywa nie może pojawić się znikąd ani obejść aktualnej wiedzy, route authority lub mechaniki.

## Arrival evidence i cele

Speculative `SpatialChange`, verified effect oraz zakończony plan nie oznaczają osiągnięcia celu.

`NpcTravelArrivalEvidence` wydaje `NpcActivityResolutionEvidence` wyłącznie z:

- persisted V3 `TurnCommitReceipt`;
- dokładnego `CommittedReplayPayload` tej samej transakcji;
- dokładnego route fingerprint;
- dokładnego committed `SpatialChange` do destination;
- dokładnego zestawu committed kosztów tej trasy.

Brak receipt/replay, inna trasa, dodatkowy lub brakujący koszt, błędny destination albo materiał po Undo nie daje arrival evidence.

`LocationReachedCriterion` jest post-commit. Precommit travel completion pozostaje goal-neutral.

## Recovery, replay i undo

JVM acceptance obejmuje:

- wspólny TurnTransaction;
- atomiczne koszty + arrival;
- idempotentny retry;
- rollback po części zapisów;
- reopen SQLite;
- replay;
- destructive undo;
- inną transakcję po Undo;
- unieważnienie starego arrival evidence po Undo.

Android API 28/36 wykonuje dodatkowy host-driven gate:

1. zapisuje realny `NpcPendingAction` w `FileTemporalCheckpointStore`;
2. host wykonuje `am force-stop`;
3. nowy instrumentation process ponownie odczytuje checkpoint;
4. canonical location i zasoby nadal są sprzed podróży.

Ten gate nie dokonuje bezpośredniego zapisu po restarcie. Zwykły TurnTransaction jest oddzielnie udowodniony przez test persistence.

## Acceptance R1

Obowiązkowe przed merge:

- targeted JVM: lifecycle, route authority, contracts, persistence, recovery;
- pełny `:app:testDebugUnitTest` i `:app:testLabDebugUnitTest`;
- Android emulator API 28;
- Android emulator API 36;
- host process-death pending-travel gate;
- release isolation;
- Phase55–59 regression;
- exact SHA zapisany w PR.

R1 nie wymaga fizycznego telefonu ani lokalnego modelu. Te bramki pozostają odłożone zgodnie z przyjętym trybem pracy.

## Granice dalszych faz

R1 nie implementuje:

- treningu/nauki/czytania — R2;
- leczenia — R3;
- obowiązków — R4;
- szerszej percepcji i legacy NPC completion — R5;
- Living World / globalnej topologii dynamicznej — Phase63–64;
- branchingu — Phase72.

Fazy 61–62 pozostają `PARTIAL`, dopóki R2–R5 i końcowy odbiór całego bloku nie zostaną zakończone.

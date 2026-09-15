# Phase61–62 R0 — audyt właścicieli skutków i kontrakt lifecycle czynności NPC

Status: **KANDYDAT R0 — kontrakt architektoniczny, bez formalnego odbioru faz 61–62**  
Baza audytu: `377790babb707f5d92c0095841e23f98240a719c`  
Zakres: audyt właścicieli, wspólny lifecycle próby czynności oraz bramki dla R1–R5.

## Cel

R0 zamraża granicę między:

1. decyzją i planem NPC;
2. orkiestracją czasu;
3. rzeczywistym skutkiem domenowym;
4. dowodem osiągnięcia celu.

Nie wdraża jeszcze podróży, zdobywania umiejętności, leczenia ani obowiązków. Nie tworzy nowego magazynu stanu i nie zmienia formatu zapisu. Jego zadaniem jest zapobiec powstaniu drugiego silnika lokacji, progresji, zdrowia, wiedzy albo ról wewnątrz Phase62.

Nadrzędny przepływ:

```text
NpcBrain / Phase62
→ wybór autoryzowanej czynności
→ plan próby

Phase60
→ rozpoczęcie, termin, przerwanie

właściciel domeny
→ świeży preflight
→ mechanika
→ typowany wynik

TurnTransaction
→ canonical changes
→ receipt / Event Store / replay / undo

Phase37 i Phase55–59
→ legalna wiedza oraz pamięć wyniku
```

Twarde inwarianty:

```text
MODEL INTENT != DOMAIN RESULT
TIME ELAPSED != SUCCESS
NARRATIVE TEXT != COMPLETION EVIDENCE
VECTOR SCORE != SUCCESS PROBABILITY
PHASE62 PLAN != POSITION / SKILL / HEALTH / KNOWLEDGE AUTHORITY
```

## Stan istniejący, którego nie wolno implementować ponownie

Na bazowym SHA istnieją już:

- trwały `NpcBrainState`, cele, emocje i plany;
- `NpcPendingAction` oraz proces `P62:NPC_EXECUTION`;
- Phase60 jako właściciel zegara, terminów i wznowienia;
- świeży preflight i wykonanie mechaniki przy terminie;
- przerwanie planu bez przyszłych efektów;
- krótkie sekwencje i jedna alternatywa pierwszego kroku;
- `NpcActivityContractPort` dla zarejestrowanych czynności;
- `NpcActivityMechanics` zapisujący wyłącznie jawny wysiłek oraz ograniczony REST;
- osobne ścieżki `NPC_DECISION` i `NPC_DIALOGUE`;
- holder-scoped Phase37/38 oraz pamięć Phase55–59/Bekko;
- wspólny commit, replay, undo i recovery.

R0 nie zastępuje żadnego z tych właścicieli.

## Macierz właścicieli skutków

| Wynik czynności | Aktualny właściciel canonical | Istniejący kontrakt/zmiana | Rola Phase62 | Stan przed R1–R5 |
|---|---|---|---|---|
| lifecycle zamiaru i planu NPC | Phase61/62 | `NpcGoal`, `NpcPlan`, `NpcBrainChange`, `NpcPendingAction` | tworzy i aktualizuje plan | istnieje |
| czas, termin i wznowienie | Phase60 | `TemporalOwnerState`, deadline, checkpoint | rejestruje ownera i odpowiada na granicy | istnieje |
| wysiłek zwykłej czynności | Phase50 przez wspólny commit | `MechanicalTrackChange` z `NpcActivityMechanics` | wybiera tylko zarejestrowany kontrakt | istnieje |
| ruch lokalny i zmiana lokacji | Phase50 mechanical/spatial | `SpatialChange`, `MechanicalActorStateStore.applySpatial`, `entity_positions` | może wybrać podróż; nie zapisuje pozycji | owner istnieje, brakuje kontraktu podróży |
| bieżące zasoby aktora | Phase50 | `ResourceChange`, `mechanical_actor_resources` | nie tworzy zasobów | istnieje |
| rana / track obrażeń | Phase50 | `WoundChange`, `MechanicalTrackChange("WOUND")` | nie usuwa rany tekstem modelu | istnieje |
| warunek/status | Phase50 compatibility owner | `ConditionChange` | może wybrać próbę leczenia | istnieje |
| umiejętność | Skill domain | `SkillChange`, `SkillStore` i polityka progresji | wybiera czynność treningową | owner istnieje, brak adaptera NPC |
| technika | Technique domain | `TechniqueChange`, `TechniqueStore` i polityka progresji | wybiera naukę/ćwiczenie | owner istnieje, brak adaptera NPC |
| wiedza zdobyta przez czytanie/naukę | Phase37 | acquisition/evidence/state; odczyt przez Phase38 | może rozpocząć czytanie | owner istnieje, brak typed completion adaptera |
| własność/przedmiot | Inventory/Ownership domain | `InventoryChange`, `OwnershipChange` | cel może oczekiwać wyniku | istnieje |
| wynik walki | Universal Mechanics / Phase50 | zweryfikowane efekty i canonical changes | wybiera zdolność, nie ogłasza zwycięstwa | istnieje |
| role i dostęp | Phase38 | aktualna projekcja role/access authority | bierze pod uwagę legalne role | istnieje |
| konkretny obowiązek i jego wykonanie | brak jednego zaakceptowanego ownera | brak zamrożonego kontraktu duty | nie może wymyślać obowiązku | **GAP R4** |
| globalne konsekwencje organizacji | Phase63–64 | poza zakresem | brak authority | wyłączone z 61–62 |

## Rozstrzygnięcia R0

### 1. Lifecycle próby nie jest nowym canonical ownerem świata

Wprowadzony kontrakt `NpcActivityLifecycleSnapshot` jest typowaną kontrolą przepływu. Nie zapisuje pozycji, umiejętności, zdrowia, wiedzy, własności ani ról.

Etapy:

```text
AUTHORIZED
STARTED
IN_PROGRESS
INTERRUPTED
COMPLETED
FAILED
CANCELLED
```

Znaczenie:

- `AUTHORIZED` — dokładna opcja i scope zostały zatwierdzone;
- `STARTED` — Phase60 przyjął próbę oraz termin;
- `IN_PROGRESS` — próba nadal trwa, bez przyznania przyszłego skutku;
- `INTERRUPTED` — próba przerwana, bez przeniesienia niewykonanych efektów;
- `COMPLETED` — właściciel wyniku wydał typed evidence;
- `FAILED` — właściciel domeny wydał typed failure;
- `CANCELLED` — próba anulowana; nie stanowi porażki domenowej ani sukcesu.

Stan terminalny nie może zostać ponownie otwarty.

### 2. Tożsamość próby jest związana z historią

`NpcActivityAttemptIdentity` wiąże:

- kampanię;
- `historyGenerationUid`;
- aktora;
- plan;
- opcję;
- capability;
- czas autoryzacji i termin;
- fingerprint autoryzacji;
- fingerprint kontraktu ownera.

Po Undo albo przejściu do innej historii stary dowód nie pasuje do nowej próby.

### 3. Skutek wymaga aktualnego właściciela domeny

`NpcActivityOwnerContract` wskazuje:

- ownera lifecycle;
- ownera wyniku;
- rodzaj evidence;
- wersję kontraktu;
- dozwolone typy canonical changes;
- politykę `ATTEMPT_EVIDENCE` albo `DOMAIN_RESULT_EVIDENCE`.

World Pack może użyć własnych UID-ów przez rejestrację kontraktu. Core nie rozpoznaje nazw świata ani listy czasowników.

### 4. Completion evidence jest referencją, nie równoległym stanem

`NpcActivityResolutionEvidence` może wskazywać wyłącznie:

- dokładną próbę;
- właściciela;
- rodzaj receipt/evidence;
- UID rozstrzygnięcia;
- canonical change/effect UIDs;
- czas rozstrzygnięcia;
- fingerprint źródła.

Nie zawiera tekstu modelu, prawdopodobieństwa, wyniku embeddingu ani narracji.

Dla `DOMAIN_RESULT_EVIDENCE` pełne lub częściowe zakończenie wymaga co najmniej jednego canonical evidence leaf zgodnego z allowlistą kontraktu.

### 5. Częściowy wynik nie zamyka celu jako pełny sukces

`COMPLETED + PARTIAL` może oznaczać zakończoną próbę z częściowym skutkiem, ale:

```text
PARTIAL != ACHIEVED
```

Tylko `COMPLETED + SUCCEEDED` daje `provesFullDomainSuccess()` i może zostać później użyte przez typowane kryterium celu.

## Zakazy implementacyjne dla R1–R5

Nie wolno:

- zapisywać lokacji bez `SpatialChange`;
- ustawiać `COMPLETED` na podstawie samego deadline;
- przyznawać XP/skill/technique z tekstu `TRAIN`;
- tworzyć Phase37 acquisition bez legalnego źródła;
- usuwać ran przez REST;
- tworzyć brakującego HEALTH/STAMINA podczas leczenia;
- uznawać obecność w lokacji za automatyczną percepcję;
- kopiować pozycji aktywnego gracza do starszego NPC;
- tworzyć obowiązku tylko dlatego, że model wspomniał rolę;
- budować globalnej symulacji organizacji z faz 63–64;
- traktować `NpcActivityLifecycleSnapshot` jako nowy save owner.

## Plan kolejnych pakietów

### R1 — podróż

Wymagane:

- typed travel contract;
- legalny start i destination;
- dostępność drogi;
- koszt/czas;
- stan tranzytu bez przedwczesnej zmiany lokacji;
- świeży preflight przy terminie;
- `SpatialChange`;
- arrival receipt;
- restart, interruption, replay i undo;
- `LocationReachedCriterion`.

### R2 — trening, nauka i czytanie

Wymagane:

- jawny source/capability/material contract;
- owner skill/technique/progression;
- osobny postęp i threshold;
- czytanie przez Phase37;
- brak automatycznego FACT;
- przerwanie bez końcowej nagrody;
- typed criteria dla skill/technique/knowledge acquisition.

### R3 — leczenie

Wymagane:

- healer, patient, access, ability, tool, resource i duration;
- rozdzielenie RESOURCE / WOUND / CONDITION;
- brak skutków z wyprzedzeniem;
- wynik pełny/częściowy/nieudany/przerwany;
- evidence z Phase50;
- brak automatycznego sukcesu próby.

### R4 — obowiązki

Najpierw trzeba zamrozić wąski owner obowiązku. Role pozostają Phase38, termin Phase60, ale wykonanie konkretnego obowiązku potrzebuje własnego, wersjonowanego kontraktu. Nie obejmuje to globalnej symulacji organizacji.

### R5 — integracja

- typowane obserwacje i Phase37 admission;
- kryteria wyników domenowych;
- produkcyjne sekwencje i alternatywa;
- jawna migracja niepełnych starszych NPC;
- Android acceptance, migracje, 100 tur i audit.

## Migracje i zgodność

R0:

- nie dodaje tabel;
- nie zmienia canonical digest;
- nie zmienia checkpointu Phase60;
- nie zmienia payloadu `NpcBrainState`;
- nie zmienia istniejących UID-ów ani fingerprintów czynności;
- nie zapisuje niczego przy odczycie;
- jest bezpiecznym kontraktem źródłowym dla kolejnych pakietów.

Każde późniejsze użycie lifecycle w persistence wymaga osobnej wersji kodeka, migracji oraz replay/undo evidence.

## Bramka R0

R0 może zostać uznany za gotowy do scalenia, gdy:

1. nowe kontrakty kompilują się w produkcyjnym source set;
2. testy odrzucają completion bez evidence;
3. testy odrzucają zły owner, rodzaj evidence, historię i change kind;
4. przerwanie nie może nieść przyszłych efektów;
5. stan terminalny nie może zostać ponownie otwarty;
6. custom World Pack UID działa bez warunku świata;
7. test instrumentacyjny przechodzi na API 28 i API 36;
8. pełne testy JVM są zielone;
9. exact SHA i wyniki workflow są zapisane w PR;
10. status 61–62 pozostaje `PARTIAL`.

Dopiero po scaleniu R0 rozpoczyna się implementacja R1.

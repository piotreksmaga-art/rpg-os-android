from pathlib import Path

arch = Path('docs/Architektura projektu.md')
road = Path('docs/Roadmap.md')

a = arch.read_text(encoding='utf-8')
r = road.read_text(encoding='utf-8')

arch_marker = "World Pack nie kopiuje infrastruktury Core: transactions, events, memory, economy framework, snapshots, retrieval, NPC Brain, World Simulation, Save/Load.\n\n## 10. World Actor Knowledge / Epistemic State — CANONICAL TARGET"
arch_insert = r'''World Pack nie kopiuje infrastruktury Core: transactions, events, memory, economy framework, snapshots, retrieval, NPC Brain, World Simulation, Save/Load.

### 9.1 World Actor Mechanical Domain — CANONICAL FUTURE CONTRACT
Combat, Living World i inne mechaniki potrzebują jednego uniwersalnego widoku rzeczywistych możliwości aktora. Core nie może utrzymywać osobnych, konkurencyjnych fizyk dla Playera i NPC. Zaakceptowany Player Domain Phase 1–36 pozostaje bez zmian; przyszły `WorldActorMechanicalView`/adapter udostępnia wspólny kontrakt nad istniejącym Player State, a NPC/monster/summon/vehicle/unit mogą posiadać native `WorldActorMechanicalState`.

Minimalny kontrakt mechanicznego aktora obejmuje zależnie od świata: identity, dynamic attributes, resources, skills, executable abilities/techniques, innate traits, resistances, equipment, target components/anatomy, conditions, wounds/structural damage, cooldowns i effective modifiers. `KNOWLEDGE ABOUT ABILITY != EXECUTABLE ABILITY AUTHORITY`.

`GENERATION TEMPLATE != CURRENT MECHANICAL STATE`. Po materializacji aktor posiada persistent canonical state i zmienia go wyłącznie przez legalny domain/transaction path: trening, rozwój, obrażenia, starzenie, equipment, learned abilities itd. Aktualny stan nie może być ponownie losowany z archetypu przy kolejnym spotkaniu.

### 9.2 World Actor Generation & Materialization Framework
Core dostarcza uniwersalny język generacji, nie world-specific zawartość. World Pack może komponować dowolne archetypes/definitions i reguły `REQUIRED`, `CONDITIONAL`, `WEIGHTED`, `FORBIDDEN`, dependencies/exclusions, rarity/population/uniqueness constraints oraz mechanical power envelopes. Core nie zna pojęć `GENIN`, `DRAGON`, `WITCHER` itd.

Generacja używa controlled variance, korelacji i budget/envelope constraints zamiast niezależnego randomowania wszystkich statów. Hierarchiczny persistent seed rozdziela co najmniej mechanical/appearance/personality/knowledge/history randomness, aby zmiana jednego generatora nie zmieniała innych domen. Existing canonical facts zawsze mają pierwszeństwo przed generative defaults.

Materialization może być lazy (`SEED_ONLY` / `PARTIAL_MECHANICAL` / `FULL_MECHANICAL`) dla skali Living World, ale musi być deterministic/replay-safe i conservation-safe. Actor promoted z population/group aggregate zachowuje wcześniejsze fakty, a aggregate traci odpowiadającego mu członka/zasoby.

Zwykły actor power wynika z world context, roli, frakcji, rank/status, historii i World Pack constraints — nie z mocy aktualnego PC. Globalny invariant: `ENCOUNTER DIFFICULTY MUST EMERGE FROM WORLD STATE, NOT PLAYER POWER SCALING`. Director/AI nie może retconować statów ani generować perfect counter tylko dlatego, że zna kartę gracza. Wyjątek wymaga legalnej causal przyczyny w świecie; np. organizacja świadomie dobiera przeciwnika na podstawie własnej holder-scoped wiedzy o PC.

### 9.3 Universal Combat Engine — CANONICAL FUTURE CONTRACT
Combat Engine nie wybiera intencji aktora, nie generuje przeciwników i nie jest źródłem narracji. `DECISION ENGINE DECIDES; COMBAT ENGINE RESOLVES`. Jego zadanie: z legalnego `CombatIntent`/`CombatAction`, immutable relevant snapshotu, World Rules, przestrzeni, czasu i deterministic RNG evidence wyprowadzić `CombatResolution`/domain ChangeSets, które dopiero canonical transaction może commitować.

Docelowy pipeline:
`CombatIntent -> Action Construction -> Eligibility/Preconditions -> Spatial Feasibility -> Temporal Scheduling -> Detection/Perception -> Reaction/Interrupt Opportunities -> Action-Action Interaction/Clash -> Contest Resolution -> Effect Pipeline -> Target Components/Protection/Resistance -> Conditions/Resources/Movement -> Objective Evaluation -> Resolution Evidence -> Domain ChangeSets -> Validation -> TurnTransaction -> Events/Causal Graph/Ledgers -> COMMIT -> Knowledge acquisition/narration`.

Core zapewnia abstrakcje, a World Pack definiuje konkretne reguły. Spatial model może używać exact coordinates, grid, zones, range bands, formation space lub world-defined resolvera. Timing nie zakłada wyłącznie rund; akcja może mieć fazy `DECLARE/PREPARE/COMMIT/EXECUTE/IMPACT/RECOVERY`, simultaneous actions, delayed effects, reactions i interrupts.

Reaction jest legalna tylko gdy aktor posiada capability, wykrył/zna zagrożenie, ma czas i wymagany resource. `COUNTER CAPABILITY MUST PREEXIST`; `COUNTER SELECTION MUST USE ACTOR-AVAILABLE KNOWLEDGE`. Ukryta akcja istniejąca w FACT nie daje automatycznej reakcji targetowi.

Effect resolution jest kompozycyjne i nie redukuje walki do jednego HP: damage/wounds, resources, status, displacement, equipment/structure damage, morale/cohesion, formation, environment i World Pack-defined effects. Optional `TargetComponentModel` obsługuje ciało, skrzydła smoka, moduły pojazdu, okręt itd. Mechanika zachowuje degree-of-effect i objective outcomes (kill/capture/delay/escape/protect/hold/break formation/survive/world-defined), nie tylko `winnerUid`.

Combat supports LOD: `LOD0 strategic aggregate`, `LOD1 formations/units`, `LOD2 groups + important actors`, `LOD3 full individual tactical resolution`. Przejścia LOD zachowują manpower/resources/casualties/unique actors/equipment/important conditions i nie materializują dodatkowej authority. Lokalny rezultat LOD3 może propagować causal effect do LOD1/0, np. utrata generała -> command/morale effect.

Mechanical fairness oznacza te same legalne reguły i brak hidden boost/rubber-banding, nie równe szanse. Easy, fair i suicidal encounters są legalne. Extreme mismatch może dawać deterministic outcome bounds zamiast obowiązkowego RNG; ekspert nie ma sztucznego fixed-percent critical failure bez world-rule przyczyny.

Player Agency pozostaje nadrzędna: forced mechanical consequence (`knockback`, stun, unconsciousness itd.) nie jest voluntary PC action. Combat Engine nie może sam wybierać ruchu/dialogu/techniki aktualnego PC.

### 9.4 Adaptive Turn Runtime & Response-Time Policy
RPG OS optymalizuje perceived latency do jakości, nie do minimalnej liczby milisekund. Globalne cele: `MECHANICS LATENCY << AI LATENCY`, `SIMULATION COST scales with RELEVANT STATE, not TOTAL WORLD SIZE`, `NO SERIAL AI CALLS WITHOUT NECESSITY`, `PRECOMPUTE/PARALLELIZE work that does not require AI output`.

`AdaptiveTurnRuntime` jest performance orchestrator, nie source of truth ani mutation authority. Zarządza workload estimation, AI-latency estimation, quality budgetem, parallel preparation, fast/deep paths i background-safe work. Praca jest klasyfikowana co najmniej jako `CRITICAL`, `REQUIRED`, `QUALITY`, `BACKGROUND`; deadline może ograniczyć tylko opcjonalną jakość, nigdy player agency, canonical validation, World Rules, transaction/replay safety ani data integrity.

Domyślny `ResponseTimeMode.AUTO` dobiera budżet do modelu, urządzenia, thermal state i workload. Normalna interaktywna tura ma preferred target około `5 s`, lecz nie jest to correctness timeout. Szybszy model powinien pozwalać użyć wolnego budżetu na relevant retrieval, continuity/consistency, NPC/world evaluation, combat verification lub narrative repair zamiast sztucznego natychmiastowego zwrotu.

Ustawienia aplikacji mogą oferować proste profile `AUTO` (default), `FAST`, `BALANCED`, `QUALITY`, `CUSTOM`; Custom może określać preferowane minimum czasu odpowiedzi. Przy ręcznie ustawionym minimum wolny czas jest zużywany `QUALITY FIRST -> IDLE ONLY LAST`. Auto nie musi sztucznie czekać do pełnych 5 s, jeśli kompletna odpowiedź jest gotowa i dalsza praca nie wnosi wartości.

Background-safe praca może wykorzystywać czas czytania/myślenia gracza, ale `SPECULATION MAY PREPARE; SPECULATION MAY NOT COMMIT`. Performance/profile sprzętu może zmieniać ilość opcjonalnej pracy, nigdy canonical semantics/mechanical truth.

## 10. World Actor Knowledge / Epistemic State — CANONICAL TARGET'''
if arch_marker not in a:
    raise SystemExit('architecture marker missing')
a = a.replace(arch_marker, arch_insert, 1)

# Roadmap: strengthen Phase 50, 63-64, 78-79 without renumbering accepted/future phases.
r = r.replace('- [ ] 50. Mechanics Resolution integration', '- [ ] 50. Universal Mechanics & Combat Resolution integration', 1)
r = r.replace('- [ ] 63. World Simulation LOD 0–3 + multi-rate WorldProcess engine', '- [ ] 63. World Simulation LOD 0–3 + World Actor mechanical materialization + Combat LOD integration', 1)
r = r.replace('- [ ] 64. Background-world causal simulation: organizations/economy/projects/demography/wars/knowledge propagation + controlled randomness', '- [ ] 64. Background-world causal simulation: organizations/economy/projects/demography/wars/knowledge propagation/conflict resolution + controlled randomness', 1)
r = r.replace('- [-] 78. Android performance profiling/optimization', '- [-] 78. Android performance profiling/optimization + Adaptive Turn Runtime', 1)
r = r.replace('- [-] 79. AI workload / provider / model / runtime routing', '- [-] 79. AI workload / provider / model / runtime routing + Response-Time Policy', 1)

road_marker = "Konkretny model, provider, format i runtime są adapter/evidence candidates, nie canonical lock-in.\n\nFuture Player Interaction acceptance"
road_insert = r'''Konkretny model, provider, format i runtime są adapter/evidence candidates, nie canonical lock-in.

Phase 50 Universal Mechanics & Combat Resolution acceptance obejmuje co najmniej:
- jeden `MechanicalActorView`/równoważny adapter contract dla Active PC, NPC, former PC, monster/summon/vehicle/unit/group bez przepisywania accepted Player Domain Phase 1–36;
- persistent World Actor Mechanical State dla non-player actors: dynamic attributes/resources/skills/executable abilities/traits/resistances/equipment/components/conditions/wounds/cooldowns/modifiers;
- `GENERATION TEMPLATE != CURRENT MECHANICAL STATE`; materialized actor nie jest rerollowany przy kolejnym encounter;
- `ENCOUNTER DIFFICULTY MUST EMERGE FROM WORLD STATE, NOT PLAYER POWER SCALING`;
- World Actor Generation Core: composable archetypes + REQUIRED/CONDITIONAL/WEIGHTED/FORBIDDEN rules + controlled variance + persistent hierarchical seed + power envelope + provenance;
- ordinary generation nie dostaje Player mechanical power jako difficulty input; causal counter-selection może używać tylko legalnej holder-scoped wiedzy aktora/organizacji;
- `CombatIntent != Outcome`; Decision Engine/validated player command wybiera intencję, Combat Engine tylko ją rozstrzyga;
- immutable relevant Combat Snapshot + eligibility/preconditions + spatial/timing + detection + reaction/interrupt + clash + contest + effect + objectives + resolution evidence;
- reaction wymaga capability + perception/knowledge + time + resource; hidden FACT nie daje automatycznej reakcji;
- effects są typed/compositional i mogą obejmować HP/wounds/resources/status/movement/equipment/structure/morale/cohesion/formation/environment zamiast jednego damage number;
- optional TargetComponentModel obsługuje anatomy oraz non-biological components;
- deterministic/replay-safe RNG/evidence; same committed inputs/rules/random evidence -> same outcome;
- Combat Engine nie zapisuje authority bezpośrednio: wynik -> domain ChangeSets -> validation -> TurnTransaction -> Event/Causal evidence -> COMMIT -> narration;
- mechanical consequence pozostaje odrębna od voluntary PC action;
- extreme mismatch może być rozstrzygany deterministic bounds, bez obowiązkowego fixed critical-success/failure percentage;
- adversarial tests: no player scaling, no retroactive stat buff, no omniscient perfect counter, unavailable ability rejected, hidden attack cannot be auto-reacted to, rollback/retry/replay equality, outcome explainability.

Future Player Interaction acceptance'''
if road_marker not in r:
    raise SystemExit('roadmap phase50 marker missing')
r = r.replace(road_marker, road_insert, 1)

lw_marker = "Phase 63–64 Living World:\n- global invariant: **THE WORLD DOES NOT WAIT FOR THE PLAYER**;"
lw_insert = r'''Phase 63–64 Living World:
- global invariant: **THE WORLD DOES NOT WAIT FOR THE PLAYER**;
- World Actor Generation/materialization supports `SEED_ONLY/PARTIAL/FULL` LOD and existing canonical facts override generative defaults;
- generic/population/group aggregates may be promoted to persistent actors deterministically with conservation of member count/resources/history;
- Combat LOD is integrated with World Simulation: LOD0 strategic aggregate, LOD1 formations/units, LOD2 groups + important actors, LOD3 full individual tactical resolution;
- LOD refinement/coarsening conserves manpower/resources/casualties/unique actors/equipment/important conditions; local combat results propagate causally back to larger-scale simulation;
- army/fleet/group combat may retain important named actors as full mechanical actors while bulk members remain aggregate;
- orders/command propagation may include latency, communication channel, failure/interception/distortion according to World Pack and Knowledge rules;
- background conflict resolution uses the same canonical mechanical/world rules at an appropriate LOD instead of arbitrary event generation;'''
if lw_marker not in r:
    raise SystemExit('living world marker missing')
r = r.replace(lw_marker, lw_insert, 1)

perf_marker = "- Phase 79 separates workload policy, provider execution choice, ModelRouter and RuntimeBackendSelector; deterministic tasks bypass AI."
perf_insert = r'''- Phase 79 separates workload policy, provider execution choice, ModelRouter and RuntimeBackendSelector; deterministic tasks bypass AI.
- Phase 78–79 implement `AdaptiveTurnRuntime`/equivalent performance orchestration without mutation authority: workload/AI-latency estimation, quality-budget allocation, parallel preparation, fast/deep paths and background-safe work;
- performance classes at least `CRITICAL`, `REQUIRED`, `QUALITY`, `BACKGROUND`; correctness/player-agency/transaction/replay validation cannot be skipped to hit latency target;
- default `ResponseTimeMode.AUTO`; normal interactive preferred target około 5 s, adaptively adjusted to model/device/thermal/workload rather than a hard correctness timeout;
- user settings may expose simple `AUTO`/`FAST`/`BALANCED`/`QUALITY`/`CUSTOM`; Custom may define preferred minimum response time;
- spare latency is spent `QUALITY FIRST -> IDLE ONLY LAST`; Auto may answer earlier when no useful quality work remains;
- mechanics/storage/retrieval overhead should remain small relative to AI latency; serial AI calls require justification;
- safe background computation may use player reading/thinking time, but speculative preparation has no COMMIT authority;
- performance acceptance records P50/P90/P95/P99 total latency plus AI/mechanics/DB/retrieval overhead, background lag and thermal behavior on target Android hardware.'''
if perf_marker not in r:
    raise SystemExit('performance marker missing')
r = r.replace(perf_marker, perf_insert, 1)

cross_marker = "- [ ] mobile default interaction remains usable with text input + three primary helpers (`Cofnij`, `Kontynuuj`, `Sugestie`) and progressive disclosure for advanced features"
cross_insert = r'''- [ ] mobile default interaction remains usable with text input + three primary helpers (`Cofnij`, `Kontynuuj`, `Sugestie`) and progressive disclosure for advanced features
- [ ] `WORLD_ACTOR_MECHANICAL_STATE`: same canonical combat-facing contract works for PC/NPC/former PC/monster/summon/vehicle/unit/group without creating a second Player physics
- [ ] materialized actor mechanical state persists and cannot be rerolled from template after combat/history changes
- [ ] ordinary NPC generation is independent of active-PC power; no hidden level scaling/rubber-banding
- [ ] counter capability must preexist mechanically and counter selection must be justified by holder-available knowledge/perception
- [ ] controlled generation respects required/conditional/weighted/forbidden World Pack constraints, power envelope, rarity/uniqueness and deterministic seed/provenance
- [ ] CombatIntent does not imply success; Combat Engine resolves before narration/COMMIT
- [ ] hidden/unperceived attack cannot create omniscient dodge/parry/counter
- [ ] combat reaction/interrupt/clash/timing replay produces deterministic equality from stored evidence
- [ ] Combat LOD refinement/coarsening conserves manpower/resources/casualties/unique actors/equipment/conditions
- [ ] strategic/army combat can descend to individual important-actor combat and propagate results back without double counting
- [ ] response-time `AUTO` adapts quality/workload while preserving canonical correctness; user-selected minimum never permits skipping required validation
- [ ] available latency improves quality before idle waiting; speculative/background preparation never commits future player/world decisions'''
if cross_marker not in r:
    raise SystemExit('cross cutting marker missing')
r = r.replace(cross_marker, cross_insert, 1)

arch.write_text(a, encoding='utf-8')
road.write_text(r, encoding='utf-8')
print('patched architecture and roadmap')

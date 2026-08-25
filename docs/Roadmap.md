# Roadmap

Status: ACTIVE / CANONICAL ROADMAP

Architecture: `docs/Architektura projektu.md`

File map: `docs/Mapa plików.md`

Project history / acceptance chronology: `docs/Historia projektu.md`

Coordination: `docs/PARALLEL_WORK_COORDINATION.md`, `docs/architecture/CHAT_COORDINATION_POLICY.md`

Operational protocol: `docs/PROJECT_WORK_PROTOCOL.md`

## Statusy
- `[x] COMPLETE` — globalnie zaakceptowany etap;
- `[-] PARTIAL` — realny fundament istnieje, ale pełny kontrakt nie jest jeszcze zaakceptowany;
- `[ ] MISSING` — docelowa implementacja nie istnieje;
- `[!] BLOCKED` — kandydat istnieje, ale acceptance blokuje nierozwiązany problem.

Globalny status zmienia koordynator po sprawdzeniu implementacji, integracji, persistence/migration safety, regresji, full JVM/build/CI i wymaganych audytów. Raport workera, sama klasa/tabela albo zielony pojedynczy test nie oznaczają COMPLETE.

## Globalny invariant uniwersalności przyszłych faz
Każda przyszła Core phase musi być projektowana jako world-agnostic i style-agnostic. Nie może być jakościowo uznana za gotową tylko dlatego, że działa w jednym/dwóch aktualnych World Packach. Naruto/Bleach/Wiedźmin/fantasy/sci-fi i inne światy są fixtures/adversarial cases, nie hardcoded modelem Core.

`CORE DEFINES UNIVERSAL CONTRACTS; WORLD PACK DEFINES SEMANTICS/CONTENT`.

Każdy przyszły Core acceptance audit sprawdza co najmniej:
- brak world-specific hardcoded authority/ról/ras/ranków/abilities/senses/secrecy classes;
- możliwość rozszerzenia przez typed/data-driven World Pack definitions bez tworzenia konkurencyjnego Core engine;
- działanie dla różnych typów World Actor/holder/group/organization, jeżeli domena ma zastosowanie;
- działanie w więcej niż jednym stylu gry i adversarial cases z odmienną metafizyką/technologią/information model;
- fail-closed behavior dla unknown World Pack rule/extension.

Wyjątek stanowią jawnie World-Pack-specific fazy integracyjne (obecnie Phase 80–84), których zadaniem jest test konkretnego packa przeciwko uniwersalnemu Core, a nie zmiana Core pod ten pack.

## Aktualny baseline
- Canonical globally accepted runtime remains Phase 38 at code-bearing SHA `db2f836fe3575204d045e5d3a861e07bb61cd5a9`. Candidate Phase 39–47 implementation and required Phase 48–54 vertical slice są zaimplementowane lokalnie i wymagają exact-SHA CI przed merge/acceptance.
- Phase 38 final audit: PASS; protected projection, trusted perception, sealed carrier/effective-access, persistence/replay oraz repository-wide consumer inventory findings są zamknięte.
- Exact-SHA GREEN evidence: Phase 38 `117/0/0`, full JVM `1004/0/0`, Actions run `32776574352`, job `97588891710`.
- Acceptance record: `docs/architecture/PHASE38_ACCEPTANCE.md`; pełne historyczne SHA/CI/artifacts/findingi pozostają w `docs/Historia projektu.md` i phase acceptance records.
- Aktualny gate: exact-SHA CI + signed validation APK dla Phase 39–47 i wymaganego vertical slice Phase 48–54.
- Phase 48–54 mają status `[-]`: wymagany provider-independent slice istnieje, lecz szeroki zakres faz pozostaje częściowy.
- World Pack Creator pozostaje DEFERRED do czasu globalnego ACCEPTED Phase 1–84.

# FAZA 0 — BASELINE / AUDYT
- [x] 0. Baseline / audit foundation

# FAZA A — FUNDAMENT DANYCH I GRACZA
- [x] 1. Unified Repository + stable UID
- [x] 2. Campaign Source of Truth + FACT/BELIEF/NARRATIVE + provenance
- [x] 3. Player State Contract: Persistent / Derived / Runtime
- [x] 4. Dynamic StatDefinition/PlayerStat + ResourceDefinition/PlayerResource
- [x] 5. DerivedValueResolver + modifier model
- [x] 6. TalentProfile + PotentialProfile
- [x] 7. Skill model
- [x] 8. Technique model
- [x] 9. Innate/Racial/Bloodline/Evolution runtime model
- [x] 10. Inventory model
- [x] 11. Equipment domain/loadout model
- [x] 12. OwnershipRecord domain
- [x] 13. Financial Ledger / Economy model
- [x] 14. Assets / debts / obligations / net-worth model
- [x] 15. DevelopmentProject model
- [x] 16. PlayerCommand contract
- [x] 17. PlayerChangeSet contract
- [x] 18. PlayerDomainEngine orchestration
- [x] 19. WorldRuleProvider contract
- [x] 20. ProgressionEngine + Progression Ledger
- [x] 21. Diminishing Returns + passive progression hooks
- [x] 22. Player Invariant Validator + No-Retrogression
- [x] 23. Unified Player ledgers + provenance integration
- [x] 24. CharacterPanelSnapshot v2
- [x] 25. PlayerSnapshotBuilder + FULL/COMBAT/PROGRESSION/ECONOMY/SOCIAL/GM_CONTEXT profiles

# FAZA B — INTEGRALNOŚĆ KAMPANII
- [x] 26. Single Truth Mutation Path enforcement
- [x] 27. Turn Transaction atomic commit/rollback
- [x] 28. Idempotency + double-commit protection
- [x] 29. Crash recovery / LAST VALID COMMIT
- [x] 30. Event Store append-only
- [x] 31. Causal Graph
- [x] 32. Authoritative / Derived / Cache / Presentation runtime enforcement
- [x] 33. Snapshot System
- [x] 34. Automatic snapshot retention max 6
- [x] 35. Canon Divergence
- [x] 36. Schema Versioning + migration safety + legacy provenance

Accepted scope i historical evidence Phase 1–36 są zamrożone i zarchiwizowane. Future changes nie reinterpretują ich retroaktywnie.

## Referencyjny audyt kompatybilności historycznego Core

Marker `[REF-ADAPTER]` przy przyszłej fazie oznacza ryzyko kontaktu z historyczną architekturą Phase 1–38. Przed implementacją tej fazy należy przeczytać `docs/Adapter-prototyp.mb` i w audycie wejściowym zapisać jedną decyzję: `NO ADAPTER`, `OWNER FIX` albo `MINIMAL ADAPTER`.

Marker nie dodaje Phase 38.5 do roadmapy, nie nakazuje implementacji adaptera i nie rozszerza zakresu oznaczonej fazy. Dokument jest wyłącznie wartością referencyjną używaną po potwierdzeniu problemu w aktualnym repozytorium.

# FAZA C — CZAS, WIEDZA I RETRIEVAL

Phase 39–47 wykonano jako jeden ciągły blok roboczy z czterema wewnętrznymi bramkami acceptance. Plan i kontrakty: `docs/PHASE_39_47_EXECUTION_BLOCK.md`; skonsolidowany rekord z dziewięcioma osobnymi sekcjami: `docs/architecture/PHASE39_47_ACCEPTANCE.md`.

- [x] 37. World Actor Knowledge, Expertise & Acquisition Provenance
- [x] 38. Universal Visibility, Access & Audience Boundary — GM/NPC/PC/player/access/perception/disclosure isolation
- [x] 39. Temporal Engine historical truth `[REF-ADAPTER]`
- [x] 40. Scheduler — evaluation points/deadlines, not precommitted outcomes `[REF-ADAPTER]`
- [x] 41. Structured SQL Retriever `[REF-ADAPTER]`
- [x] 42. Knowledge Graph / causal retrieval `[REF-ADAPTER]`
- [x] 43. Intent Parser
- [x] 44. Turn Planner
- [x] 45. Context Builder — holder isolation + separate local/cloud task context boundaries `[REF-ADAPTER]`
- [x] 46. Context Budget Manager — ModelProfile/AiCapability/workload-aware
- [x] 47. Iterative Retrieval + missing-context loop

Canonical hardening 39–47 rozszerza wcześniejszy minimalny blok addytywnie: legacy `NormalizedIntent`/`TurnPlan` pozostają fallbackiem, natomiast nowe ścieżki używają `IntentDocument` v2, `GraphTurnPlanner`, formalnego `CapabilityEnvelope`, niedropowalnego semantic core, pełnego budżetu serialized payload oraz typed/bounded context completion. Nie wprowadzono Phase 38.5 ani równoległego systemu visibility, causal graph lub transakcji.

## Acceptance direction Phase 37–47
Phase 37 jest uniwersalnym epistemic core świata, nie tylko tabelą `NPC knowledge`. Ma obsługiwać indywidualnych i instytucjonalnych holderów bez budowania osobnych systemów wiedzy dla każdego gatunku gry.

Wymagane dla Phase 37:
- typed `KnowledgeHolder`/równoważny model dla character/NPC, PC, organization, military command/unit, city/state/agency, intelligence service, research institution/team i World Pack-defined holderów;
- rozdzielenie `FACT`, `INFORMATION/CLAIM`, `EVIDENCE`, `KNOWLEDGE STATE`, `BELIEF/ESTIMATE/HYPOTHESIS/SUSPICION`;
- twarde invarianty `FACT != KNOWLEDGE`, `KNOWLEDGE != BELIEF`, `KNOWLEDGE != EXECUTABLE SKILL`, `KNOWLEDGE != DECISION`;
- granular claim identity zamiast jednego omniscient blobu „wiedza o obiekcie”;
- typed/data-driven acquisition provenance: observation, communication, document/report/media, rumor/hearsay, education/training, research/experiment, inference, institutional sharing, interrogation, surveillance/espionage, memory, World Pack mechanics, legacy/unknown;
- immutable/traceable acquisition evidence + current holder epistemic state/projection;
- acquisition lineage umożliwiający śledzenie event/observation -> report -> summary -> recipient -> later sharing;
- legacy unknown provenance pozostaje `LEGACY` / `UNKNOWN_NOT_RECORDED`; bez fabrykowania przeszłości;
- informacje mogą być true/false/partial/uncertain/contradicted/outdated bez zmiany authoritative FACT;
- quality metadata/semantics dla confidence, precision, freshness, completeness, source reliability, corroboration i uncertainty gdzie domena tego wymaga;
- sprzeczne evidence może współistnieć zamiast bezwarunkowego last-write-wins;
- typed/data-driven knowledge domains + expertise hooks; expertise poprawia recognition/interpretation/estimate/inference, ale nie nadaje zdolności wykonawczej;
- personal knowledge, institutional knowledge i role-accessible knowledge są rozdzielone;
- wiedza instytucji nie przecieka automatycznie do wszystkich jej członków;
- dokumenty/raporty/archiwa/notatki/mapy mogą być nośnikami evidence bez bycia autonomicznymi decision actors;
- access/secrecy metadata Phase 37 są konsumowane przez zaakceptowany Phase 38 GM/NPC/PC/player-visible boundary;
- model jest temporal-ready dla późniejszego pytania „co holder wiedział wtedy?”, pełny historical query engine pozostaje Phase 39;
- canonical acquisition korzysta z Single Truth Mutation Path / TurnTransaction / Event-Causal evidence / idempotency / rollback / snapshot/replay / schema safety;
- AI, raw SQL, ContextBuilder i generic StatePatch nie posiadają authority do tworzenia legalnego canonical acquisition;
- ContextBuilder docelowo używa holder-scoped typed Knowledge projection/API zamiast definiować własny kontrakt bezpośrednimi SQL reads.

Wielostylowe wymagania Phase 37:
- character RPG: osobiste sekrety, relacje, rozpoznanie osób/technik/zdolności;
- general/tactical/strategy: fog of war, reconnaissance, delayed reports, strength/location estimates i chain-of-command knowledge;
- city/state management: census/tax/economy/food/crime/public-order reports, niepewność, opóźnienie i możliwość falsification/corruption;
- merchant/trading: regional price/demand/supply/route-risk knowledge, stale information i information advantage;
- science/research: observations, hypotheses, experiments, replications, disputed results i discoveries bez magicznego truth unlock;
- medicine: evidence/symptoms/tests + uncertain differential diagnosis zamiast dostępu do hidden disease FACT;
- espionage/politics: secrets, deception, counterintelligence, source trust i institutional distribution;
- detective/investigation: clues, testimony, evidence chains i hypotheses;
- exploration/cartography: known routes/locations/hazards/resources i niepełne map knowledge;
- World Pack może dodawać domeny, ale nie własny konkurencyjny Knowledge Engine.

Minimalne acceptance tests Phase 37 obejmują co najmniej:
- global FACT bez acquisition -> holder nie zna go;
- holder A zna X, holder B nie zna X;
- direct observation -> exact provenance;
- communication A->B -> nowe acquisition B z lineage;
- false report/deception -> BELIEF bez zmiany FACT;
- contradictory evidence -> oba źródła zachowane, brak silent overwrite;
- stale knowledge nie odświeża się automatycznie po zmianie FACT;
- merchant estimate może różnić się od aktualnej ceny rynku;
- commander dostaje intelligence estimate zamiast omniscient military FACT;
- scientist może utrzymywać hipotezę zgodną z evidence, ale niezgodną z hidden FACT;
- doctor może posiadać uncertain diagnosis bez dostępu do hidden diagnosis truth;
- knowledge about technique nie oznacza ability to execute technique;
- institutional knowledge nie przecieka automatycznie do każdego członka;
- role-accessible knowledge zmienia dostęp bez kopiowania prywatnej pamięci poprzednika;
- evidence carrier może przetrwać śmierć autora;
- cross-campaign acquisition/evidence -> FAIL;
- raw SQL/helper/StatePatch/ADMIN nie fabrykuje `RECORDED` canonical provenance;
- retry -> bez duplicate semantic acquisition;
- rollback -> zero phantom knowledge;
- snapshot/replay -> exact epistemic state;
- holder-scoped context A nie zawiera B-only knowledge.

Wymagane dla Phase 38 — Universal Visibility, Access & Audience Boundary:
- world-agnostic `AudienceContext` + `PurposeContext`; każdy protected read ma jawnego odbiorcę i cel;
- `FACT != KNOWLEDGE != ACCESS != PERCEPTION != INTERPRETATION != DISCLOSURE != PRESENTATION`;
- Core nie zna world-specific roles/ranks/senses/secrecy classes; World Pack dostarcza typed definitions/rules, ale nie własny Visibility Engine;
- campaign-qualified `VisibilitySubject/SubjectPropertyRef`, holder/actor/organization/role/grant/carrier/policy bindings; global immutable scope jest explicit;
- composable generic `AccessPolicy` primitives + explicit temporal `AccessGrant/Revocation` z provenance;
- `AUTHORIZED ACCESS != EFFECTIVE ACCESS`; legalny i nielegalny/bypass dostęp są reprezentowalne bez robienia z Phase38 law engine;
- `ACCESS != AVAILABILITY != DECODE != COMPREHENSION != ACQUISITION`;
- institutional/role access nie kopiuje automatycznie personal KnowledgeState; revocation usuwa dostęp, nie wcześniejszą memory/acquisition;
- generic InformationCarrier + reach/open/decode/comprehend/copy/share semantics i World Pack-defined carrier kinds;
- policy access oraz observational perception są osobnymi resolverami pod wspólnym Phase38 boundary;
- perception działa na signal/evidence + capability/conditions, nie na omniscient identity; wspiera disguise/illusion/stealth/camouflage/decoy/false credentials bez hidden-FACT correction;
- detection/location/recognition/classification/interpretation/understanding pozostają osobnymi etapami; Phase37 expertise może wpływać na interpretację, nie tworzy obserwacji;
- disclosure jest granularne (`DENY`/existence/category/qualitative/approximate/range/summary/redacted/detailed/full lub data-driven equivalent), nie boolean;
- uncertainty/confidence/precision/freshness/completeness są zachowywane przez projection;
- `WORLD ACTOR != KNOWLEDGE HOLDER != HUMAN PLAYER`; shared minds, possession, party/multi-character i World Pack-defined cognition są representable bez kopiowania authority;
- `PC_INTERNAL != PLAYER_VISIBLE`; jawna game-mode policy może dać graczowi narrative/strategic disclosure większe niż wiedza PC bez tworzenia PC acquisition;
- Audience composition dla multi-character/party może być single holder/union/explicit shared/world-defined, ale knowledge holders pozostają rozdzieleni;
- reputation jest holder/group/institution-scoped belief/assessment z evidence, nie globalnym score;
- protected ContextBuilder/AI/UI consumers otrzymują już zredagowaną `VisibilityProjection`; raw hidden data nie trafia do promptu/UI tylko z instrukcją „nie pokazuj”;
- `PROMPT INSTRUCTION IS NOT ACCESS CONTROL`; prompt injection/world text nie może eskalować audience;
- Player Suggestions, Continue, recap, NPC dialogue/decision, Combat reactions, Local AI i Cloud AI używają dozwolonego audience+purpose projection;
- World/Combat physics może używać hidden FACT do mechanicznego skutku, ale NPC/PC volitional decision nie może używać hidden FACT bez legalnej perception/knowledge;
- local/cloud mogą mieć różne formaty contextu, ale cloud semantic entitlement nie może być szersze;
- authoritative access bindings/grants/revocations są snapshot/replay/branch/undo-safe; derived visibility/perception decisions są deterministic/replay-safe, gdy wpływają na committed history;
- Phase38 jest temporal-ready dla Phase39, ale nie implementuje pełnego historical query engine;
- on-demand/batch/LOD-aware evaluation; brak globalnej macierzy actor x fact; cache ma bezpieczną invalidację;
- repository-wide `Visibility Consumer Inventory`/równoważny fail-closed gate klasyfikuje każdy protected information consumer; nieklasyfikowany raw-query bypass -> CI FAIL;
- unknown/corrupt/cross-campaign policy/grant/role/audience/disclosure -> DENY/typed error, nigdy fallback PUBLIC;
- legacy `visibility/hidden/gm/...` jest compatibility inputem do validated adaptera, nie canonical authority;
- authorized debug explainability pokazuje WHY allow/deny/partial bez leakowania tej internal explanation do niewłaściwego audience.

Minimalne adversarial acceptance tests Phase 38 obejmują co najmniej:
- GM/WORLD internal może czytać hidden FACT zgodnie z purpose; NPC/PC/player-facing projection nie;
- NPC A nie uzyska B-only private knowledge przez ContextBuilder/AI/raw consumer path;
- organization/role/clearance/grant działa tylko dla właściwej campaign i aktualnego bindingu;
- membership/role access != personal acquisition; revoke access != delete memory;
- carrier dostępny, ale encrypted/unknown language/no capability -> brak full content disclosure;
- formalnie unauthorized spy/hacker/telepathic/world-defined bypass może uzyskać effective access tylko przez legalny world-rule resolution, nie przez policy spoofing;
- disguise/illusion/decoy powoduje observer belief/evidence zgodne z perceived signal, a hidden objective identity nie poprawia projection;
- hidden/unperceived combat action nie tworzy NPC ReactionOpportunity;
- stale military/market/report knowledge nie zostaje odświeżone current FACT-em przez visibility projection;
- partial estimate/range pozostaje partial i uncertain;
- strategic/cutscene/player disclosure może być widoczne użytkownikowi bez nadania knowledge aktywnemu PC, jeśli jawna game-mode policy to dopuszcza;
- split-party/controlled multi-character view nie miesza KnowledgeState postaci;
- shared/hive holder model działa bez miliona duplikowanych personal acquisitions;
- player suggestion nie ujawnia hidden GM/world fact; `Continue` nie zdradza hidden Living World process;
- UI nie otrzymuje hidden raw fields do client-side ukrycia;
- local i cloud player/NPC contexts obey ten sam semantic access envelope;
- prompt injection w carrier/NPC text nie eskaluje projection authority;
- cross-campaign holder/carrier/role/grant/policy ref -> FAIL;
- unknown policy/disclosure/World Pack extension -> fail closed;
- snapshot/replay/undo odtwarza grants/revocations/disclosures/perception evidence bez phantom visibility/knowledge;
- repository-wide inventory test wykrywa nowy consumer omijający Phase38;
- multi-world suite obejmuje co najmniej skrajnie odmienne modele: ordinary sensory world, high-tech/remote sensing, supernatural/telepathic, collective/shared-mind oraz strategy/management view — bez hardcoded gałęzi Core.

Dalsze Phase 39–47:
- historical truth/visibility queries są temporalne, nie present-state substitution;
- Scheduler owns future evaluation points/deadlines, not guaranteed outcomes;
- retrieval jest bounded/iterative i context actor/time/visibility-safe;
- cloud context, gdy później aktywny, jest minimalny i sanitised zamiast whole-save export.

# FAZA D — GM ENGINE / HYBRID AI FOUNDATION
- [-] 48. AI Provider & Hybrid Local-First Inference Architecture — provider-independent vertical integration boundary implemented; concrete local/cloud runtime and Android performance evidence remain
- [-] 49. Structured GM Output contract — canonical proposal schema/validator implemented in required vertical slice; broader conformance remains
- [-] 50. Universal Mechanics & Combat Resolution integration `[REF-ADAPTER]` — trusted resolver registry and no-mutation mechanics proposal seam implemented; full universal mechanics/combat scope remains
- [-] 51. Consistency Validator — required vertical validator implemented; broader domain matrices remain
- [-] 52. Counterfactual Guard — projected-support/subject/effect guard implemented in vertical slice; broader world-domain coverage remains
- [-] 53. Repair Pass for proposal/narrative — bounded proposal repair with full revalidation implemented; narrative-quality repair scope remains
- [-] 54. Committed narrative delivery only after valid transaction — persisted receipt permit and Chat→Engine vertical slice implemented; production UI/provider wiring remains

Status `[-]` jest celowy: `docs/architecture/PHASE48_54_VERTICAL_SLICE_ACCEPTANCE.md` potwierdza wymagany działający slice i łatwą wymianę providera, ale nie oznacza pełnego acceptance szerokich Faz 48–54 ani nie przenosi zakresu Faz 55+ do tego bloku.

## Acceptance direction Phase 48–54
Phase 48 buduje provider/execution foundation, nie pełny Director.

Zaimplementowany pionowy kontrakt:
- `Chat/UI -> AiChatEngineFacade -> AiProvider -> IntentDocument -> GraphTurnPlanner -> Context -> StructuredGmProposal -> Mechanics/Guards/Repair -> canonical TurnTransaction -> persisted TurnCommitReceipt -> Narrative`;
- `AiProviderRegistry` i `AiCapabilityContract` pozwalają zamienić Provider A na B bez zmian Phase 43–54;
- `TransportAiProviderAdapter` przyjmuje konfigurowalny transport+codec dla realnego local/cloud runtime, a deterministic provider służy wyłącznie conformance/CI;
- AI nie otrzymuje repository/DB ani mutation authority; proposal i narrative nie są rzeczywistością;
- narrative request wymaga niepodrabialnego w ścieżce aplikacji evidence wydanego po odczycie trwałego V3 receipt;
- provider failure, invalid structured output i cancellation przed commit kończą się bez mutacji; cancellation/failure po commit nie cofa rzeczywistości i zwraca typed `CommittedWithoutNarrative`.

Poza tym slice pozostają: wybór konkretnego modelu, `LocalInferenceRuntime`, backend CPU/GPU/NPU, credentials/cloud policy, offline failover, streaming UI, real-device performance i pełne systemy mechanics/combat. Są to nadal kryteria szerokiego Phase 48–54, a nie fałszywie zamknięte elementy obecnej implementacji.

Wymagane docelowo:
- provider-independent `AiProvider`/semantic contract;
- `AiCapabilityContract`, data-driven `ModelProfile`;
- `LocalInferenceRuntime` + independent `RuntimeBackendSelector` CPU/GPU/supported NPU/AUTO;
- optional cloud execution pod tym samym semantic contractem;
- model/provider/runtime/backend replacement bez campaign migration;
- local path wystarcza do kontynuowania kampanii offline po zainstalowaniu kompatybilnego modelu;
- cloud disabled/no-network/timeout/429/quota/provider/credential failure -> local continuation;
- allowlisted `GmToolGateway`, no raw writable DB / no COMMIT authority;
- cloud context minimization/privacy boundary i credentials poza Campaign State;
- `VOLITIONAL PLAYER ACTION SOURCE = USER / VALIDATED PLAYER COMMAND ONLY`;
- `ACTIVE_PLAYER_CHARACTER CONTROL AUTHORITY = USER ONLY`; AI/MG/Director/NPC Brain/World Simulation nie wykonują dobrowolnych akcji, dialogu, wyborów ani control transfer za aktualnego PC;
- brak user input/odpowiedzi nie oznacza zgody; `MECHANICAL CONSEQUENCE != VOLITIONAL PLAYER ACTION`;
- structural ACTOR/ACTION/TARGET preservation;
- provider conformance: agency, direction, knowledge isolation, FACT/BELIEF, stop point, invented ability/dialogue, internal-context leak, structured output, mutation boundary;
- AI crash/cancel/process-death -> no partial committed turn;
- real Android local integration/performance evidence.

Konkretny model, provider, format i runtime są adapter/evidence candidates, nie canonical lock-in.

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

Future Player Interaction acceptance, rozwijane wraz z Phase 43–54, 63–64 i 71–75, obejmuje:
- typed `Player Interaction Orchestrator` nad istniejącym PlayerCommand/Turn pipeline, bez własnej mutation authority;
- `PLAYER_ACTION_CANDIDATE != PLAYER_COMMAND != COMMIT`;
- `Suggestions`: maksymalnie trzy domyślne propozycje, generowane tylko z PC-known/visible epistemic context; kliknięcie = explicit validated user command, brak kliknięcia = brak akcji;
- manual input zawsze pozostaje dostępny i może zignorować wszystkie sugestie;
- optional Assisted Mode automatycznie pokazuje sugestie, ale nadal nie wybiera za gracza;
- `Continue`: kontynuacja już zatwierdzonej intencji/świata/NPC bez tworzenia nowej wolitywnej decyzji PC;
- `Player Decision Point` + meaningful-interruption/soft-stop policy zatrzymuje auto-advance przed nowym ważnym wyborem PC;
- `Undo Request` korzysta z replay/branch/reconstruction, nie z ręcznego partial rollback;
- `UNDO CONFIRMATION INVARIANT`: cofnięcie committed tury wymaga osobnego świadomego potwierdzenia po pierwszym kliknięciu; większy rewind wymaga wyraźnego zakresu/confirm;
- undo odtwarza pełny stan świata na canonical granicy, w tym knowledge/events/relations/resources/ownership/background consequences;
- domyślny mobile/chat UX pozostaje minimalistyczny: pole tekstowe + `Cofnij` / `Kontynuuj` / `Sugestie`; zaawansowane opcje przez progressive disclosure/menu;
- situation recap / `Co się dzieje?` respektuje PC knowledge/visibility i nie ujawnia internal GM context.

# FAZA E — PAMIĘĆ I DŁUGOTERMINOWA SYMULACJA
- [-] 55. Working Memory — AI provider/model is not durable owner `[REF-ADAPTER]`
- [-] 56. Episodic Memory — AI provider/model is not durable owner `[REF-ADAPTER]`
- [-] 57. Semantic Campaign Memory — AI provider/model is not durable owner `[REF-ADAPTER]`
- [ ] 58. Memory Consolidation without recursive summary degradation
- [ ] 59. Vector/Semantic Retrieval engine/index integration `[REF-ADAPTER]`
- [ ] 60. Time Skip Processor + Scheduler/WorldProcess orchestration `[REF-ADAPTER]`
- [-] 61. NPC Brain + persistent individuality/personality/values/goals/fears/emotional state/relationships `[REF-ADAPTER]`
- [-] 62. NPC Decision Engine + knowledge/memory/social-role constrained autonomy `[REF-ADAPTER]`
- [ ] 63. World Simulation LOD 0–3 + World Actor mechanical materialization + Combat LOD integration `[REF-ADAPTER]`
- [ ] 64. Background-world causal simulation: organizations/economy/projects/demography/wars/knowledge propagation/conflict resolution + controlled randomness `[REF-ADAPTER]`

## Acceptance direction Phase 55–64
Memory pozostaje RPG OS-owned i odtwarzalna po zmianie modelu/runtime.

Phase 61 NPC individuality:
- personality/traits persisted lub deterministically reproducible;
- archetype/culture/organization/background + controlled stable RNG;
- osobne personality, values/goals/fears, relationship, emotional state, knowledge/beliefs, memory, social role i resources/capabilities;
- różne NPC mogą legalnie reagować inaczej na ten sam bodziec;
- long-term personality adaptation wymaga committed cause/provenance;
- reputation/rumor są holder-scoped beliefs, nie omniscient global score;
- hidden NPC traits nie są automatycznie player-visible;
- LOD/tier dla crowd/minor/persistent/major NPC.

Phase 62 decyzje:
- decision inputs = personality + values/goals/fears + emotions + relationships + knowledge/beliefs + memory + social/organization constraints + resources/capabilities + current situation + World Pack rules + controlled randomness where legal;
- `KNOWLEDGE != DECISION`, `PERSONALITY != DECISION`, `AI PROPOSAL != COMMIT`;
- debug/replay powinien móc wyjaśnić czynniki/evidence decyzji.

Phase 63–64 Living World:
- global invariant: **THE WORLD DOES NOT WAIT FOR THE PLAYER**;
- World Actor Generation/materialization supports `SEED_ONLY/PARTIAL/FULL` LOD and existing canonical facts override generative defaults;
- generic/population/group aggregates may be promoted to persistent actors deterministically with conservation of member count/resources/history;
- Combat LOD is integrated with World Simulation: LOD0 strategic aggregate, LOD1 formations/units, LOD2 groups + important actors, LOD3 full individual tactical resolution;
- LOD refinement/coarsening conserves manpower/resources/casualties/unique actors/equipment/important conditions; local combat results propagate causally back to larger-scale simulation;
- army/fleet/group combat may retain important named actors as full mechanical actors while bulk members remain aggregate;
- orders/command propagation may include latency, communication channel, failure/interception/distortion according to World Pack and Knowledge rules;
- background conflict resolution uses the same canonical mechanical/world rules at an appropriate LOD instead of arbitrary event generation;
- Living World is a causal simulator, not a random event generator; material events require actor/process/domain basis;
- WorldActor support for NPC/family/clan/organization/company/guild/city/state/army/world-specific actors;
- persisted `Motivational Core`: needs/pressures, desires, dreams/aspirations, ambitions, fears/aversions, values, loyalties/obligations, goals, plans/commitments and optional core drives/obsessions;
- motivations can form/change/weaken/resolve/conflict through committed causes; actor is not reset to archetype off-screen;
- `World Actor Life Continuity`: knowledge + memory + relationships + personality + values + motivation + goals + commitments survive scenes/time skips according to authority;
- causal loop: `WORLD STATE -> KNOWLEDGE -> PERSONALITY/VALUES/NEEDS -> DESIRES/DREAMS -> GOALS -> PLANS -> OPPORTUNITY/THREAT -> DECISION -> ACTION -> CONSEQUENCES -> WORLD STATE`;
- long-running WorldProcess: wars, trade, politics, migration, research, construction, epidemics, crime, economy, demography, diplomacy, espionage itd.;
- institutional agendas/strategic drives exist separately from every member's personal motivations;
- information ecology: actors react to their holder-scoped beliefs/estimates, while information propagates with world-specific channels/delays/distortion;
- opportunity/threat engine can surface legal action candidates from goals + knowledge + situation, but does not commit decisions itself;
- consequence propagation follows relationships/dependencies/ownership/supply/organization/process links;
- collective phenomena may emerge from many legal processes instead of arbitrary random events;
- dynamic LOD0–3 + multi-rate simulation;
- population/crowd aggregation and provenance-safe materialization;
- world/domain conservation for supported resources/population/money/goods/armies/projects;
- important background changes -> Event/Causal history;
- background FACT does not automatically become PLAYER/NPC KNOWLEDGE;
- legal information propagation using world-specific communication constraints;
- opportunities/quests may emerge from world state/process;
- former PC after explicit relinquish may use autonomous motivational/decision/Living World pipeline; active PC remains USER-controlled only;
- local world simulation works without cloud;
- Living World remains explicitly extensible: further improvements are allowed, but every material improvement must be documented with semantic contract, authority/invariants, replay/migration/performance impact and regression/adversarial tests before canonical acceptance;
- `LIVING WORLD IMPROVEMENT WITHOUT DOCUMENTED SEMANTIC CONTRACT = NOT CANONICAL`.

# FAZA F — DIRECTOR / JAKOŚĆ NARRACJI
- [ ] 65. Director Engine + optional Cloud Director / candidate bundles
- [-] 66. Narrative Promise Ledger
- [ ] 67. Pacing Metrics
- [ ] 68. Anti-Repetition
- [ ] 69. Narrative Style Profile
- [-] 70. Chronicle generated from committed structured reality

## Acceptance direction Phase 65–70
- Director controls attention/pacing/strategic candidates, not laws of reality;
- optional Cloud Director may produce bounded arc/quest/NPC-agenda/faction/foreshadowing/pacing candidates;
- `DIRECTOR OUTPUT = CANDIDATE != FACT != COMMIT`;
- Director cannot retroactively rewrite NPC personality/history or fabricate committed crisis/war without causal/domain basis;
- cloud enrichment may be deferred/asynchronous and cannot block current turn or rewrite past COMMIT;
- Chronicle remains projection from committed structured reality.

# FAZA G — SAVE / DEBUG / SKALA
- [-] 71. Save/Load integration + Persistent World / Character Succession `[REF-ADAPTER]`
- [ ] 72. Branching without full database duplication `[REF-ADAPTER]`
- [-] 73. Backup System
- [-] 74. Observability metrics
- [ ] 75. Replay Debugger `[REF-ADAPTER]`
- [-] 76. Integrity Test Suite `[REF-ADAPTER]`
- [-] 77. Long Campaign Stress Tests
- [-] 78. Android performance profiling/optimization + Adaptive Turn Runtime
- [-] 79. AI workload / provider / model / runtime routing + Response-Time Policy

## Acceptance direction Phase 71–79
Persistent World / Character Succession w Phase 71 ma zagwarantować rozdzielenie Campaign World od aktualnej Player Character:
- `CAMPAIGN/WORLD != ACTIVE PLAYER CHARACTER`;
- użytkownik może rozpocząć nową postać w istniejącym świecie bez resetu committed history i world state;
- poprzednia żyjąca postać może przejść do statusu autonomicznego World Actora/NPC zamiast znikać lub pozostawać zamrożona;
- previous PC zachowuje własny authoritative stan, ownership, relacje, memory i holder-scoped knowledge;
- new PC otrzymuje nową identity i własny Player/Knowledge state; nie dziedziczy automatycznie prywatnej wiedzy, memories, abilities, inventory ani relationship state starego PC;
- `SAME HUMAN USER != SAME CHARACTER KNOWLEDGE HOLDER`;
- NPC/organizations zachowują wiedzę i relacje dotyczące poprzedniej postaci po zmianie aktywnego PC;
- World Processes, economy, wars, projects, organizations, canon divergence, Event/Causal history i czas pozostają ciągłe;
- control transfer/retirement jest canonical committed operation z provenance i idempotency, nie flagą UI ani AI side effect;
- transfer/relinquish bieżącego `ACTIVE_PLAYER_CHARACTER` wymaga jawnej validated user command; MG/AI nie może samodzielnie odebrać graczowi kontroli ani przekazać aktualnej postaci do NPC autonomy;
- unique ownership nie może zostać zdublowane podczas zmiany PC;
- save/load/snapshot/replay/branching zachowują historię aktywnych/former PCs oraz dokładnie jeden spójny Campaign World;
- former PC może zostać później spotkany przez nową postać i działać przez NPC Brain/Decision Engine/Living World zgodnie z własnym stanem;
- death/retirement nie usuwa historycznych skutków byłej postaci.

Obowiązkowe testy Phase 71 obejmują co najmniej:
- `NEW_CHARACTER_SAME_WORLD`: old PC -> retire/control release -> new PC, World UID/history/state unchanged;
- old PC pozostaje poprawnym World Actorem z własnym state/knowledge;
- new PC nie posiada old-PC-only knowledge bez legalnej acquisition/bootstrap rule;
- NPC A pamięta/rozpoznaje old PC, ale nie zna automatycznie new PC;
- unique item/ownership nie duplikuje się przy zmianie PC;
- former PC autonomicznie ewoluuje w Living World po utracie player control;
- new PC może później spotkać former PC, a oba stany pozostają rozdzielone;
- snapshot/save/load/replay przed i po character succession daje authoritative equality;
- branch może zmienić wybór kolejnej PC bez przepisywania wspólnej historii przed branch point;
- rollback/retry control-transfer nie tworzy dwóch ACTIVE_PLAYER_CHARACTER ani phantom succession.

Wymagane m.in.:
- save/load/replay authoritative equality;
- branch history sharing without full DB duplication;
- recovery/backups and debug/replay evidence;
- `WORLD_WITHOUT_PLAYER`: wieloletnia autonomous simulation + causal history + save/load/replay equality;
- `SAME_WORLD_TWO_CAMPAIGNS`: same World Pack/initial seed + different player actions -> explainably different histories;
- world simulation budget/backlog does not linearly scan/full-freeze world/UI;
- cloud disabled/timeout/429/quota/provider removed -> local campaign continuation;
- malformed/late cloud candidate -> no mutation / no rewrite of past COMMIT;
- provider/model/runtime switch and credential removal -> no campaign migration/data loss;
- privacy/failover policy respected;
- Phase 79 separates workload policy, provider execution choice, ModelRouter and RuntimeBackendSelector; deterministic tasks bypass AI.
- Phase 78–79 implement `AdaptiveTurnRuntime`/equivalent performance orchestration without mutation authority: workload/AI-latency estimation, quality-budget allocation, parallel preparation, fast/deep paths and background-safe work;
- performance classes at least `CRITICAL`, `REQUIRED`, `QUALITY`, `BACKGROUND`; correctness/player-agency/transaction/replay validation cannot be skipped to hit latency target;
- default `ResponseTimeMode.AUTO`; normal interactive preferred target około 5 s, adaptively adjusted to model/device/thermal/workload rather than a hard correctness timeout;
- user settings may expose simple `AUTO`/`FAST`/`BALANCED`/`QUALITY`/`CUSTOM`; Custom may define preferred minimum response time;
- spare latency is spent `QUALITY FIRST -> IDLE ONLY LAST`; Auto may answer earlier when no useful quality work remains;
- mechanics/storage/retrieval overhead should remain small relative to AI latency; serial AI calls require justification;
- safe background computation may use player reading/thinking time, but speculative preparation has no COMMIT authority;
- performance acceptance records P50/P90/P95/P99 total latency plus AI/mechanics/DB/retrieval overhead, background lag and thermal behavior on target Android hardware.

# FAZA H — WORLD PACK HARDENING
- [ ] 80. Naruto WorldRuleProvider integration test pack
- [ ] 81. Bleach WorldRuleProvider integration test pack
- [ ] 82. Canon/divergence automated test scenarios
- [ ] 83. World-specific progression/evolution automated tests
- [ ] 84. World Pack update compatibility automated tests `[REF-ADAPTER]`

## Acceptance direction Phase 80–84
World Packs must prove final Core compatibility, canon/divergence behavior, world-specific rules/progression/evolution and update compatibility before World Pack Creator production work begins.

# POST-ROADMAP EXTENSION — WORLD PACK CREATOR

**STATUS: DEFERRED UNTIL GLOBAL PHASE 1–84 ACCEPTED**

Nie rezerwujemy obecnie Phase 85. Po Phase 84 wykonujemy `POST-ROADMAP AUDIT FIRST` przeciwko exact final repo/API/schema.

WPC jest authoring/compiler layer nad finalnym Core, nie drugim Event Store, Memory, Retriever, NPC Brain, World Simulation, Save/Load ani Transaction Engine.

Robocza sekwencja bez numerów kanonicznych faz:
- `WPC-A` — final Core audit;
- `WPC-B` — authoring contract / Build Workspace / provenance / compiler trace / activation boundary;
- `WPC-C` — Original World vertical slice;
- `WPC-D` — Historical Research;
- `WPC-E` — Rule Compilation / Impact Analysis;
- `WPC-F` — Scenario Templates + canonical campaign bootstrap;
- `WPC-G` — Progressive/JIT expansion przez candidate revision;
- `WPC-H` — QUICK/STANDARD/DEEP UX + explainability/repair;
- `WPC-I` — scale/security/offline/process-death/update/Android hardening;
- `WPC-J` — final acceptance.

WPC używa finalnego wspólnego AiProvider/workload routing. AI-assisted, imported, hand-authored i generated pack kończą w jednym validated runtime World Pack contract. Draft/build workspace != active canon/Campaign Repository.

# NONCANONICAL AI R&D
TEMP-GM/Termux/localhost/llama.cpp/Vulkan/Bielik i inne konkretne model/runtime/provider eksperymenty pozostają R&D/reference evidence, nie production architecture.

R&D może być prowadzone równolegle przed Phase 48 wyłącznie bez canonical integration, production provider, AI-owned durable memory i mutation authority.

# FRONTEND
- [x] ACTIVE DEVELOPMENT / STYLE PRESERVATION BY PROJECT DECISION

Frontend może rozwijać funkcjonalność równolegle, ale zachowuje zaakceptowany język wizualny i nie wykonuje niepowiązanego globalnego redesignu.

# CROSS-CUTTING GATES / TESTS
Już zamknięte przez Phase 1–37 pozostają historycznie zaakceptowane i nie są ponownie otwierane bez nowego findingu.

Przyszłe obowiązkowe gates obejmują co najmniej:
- [ ] money conservation / ledger auditability where not yet fully covered
- [ ] unique item / ownership integrity where not yet fully covered
- [ ] World Actor knowledge isolation + typed acquisition provenance + evidence lineage
- [ ] FACT without acquisition does not become holder knowledge
- [ ] institutional knowledge does not automatically become member knowledge
- [ ] expertise/knowledge about a skill or technique does not grant executable capability
- [ ] stale/contradictory/false information remains epistemically representable without mutating FACT
- [ ] military fog-of-war/intelligence estimate is not omniscient world state
- [ ] administration/market/science/medicine/investigation knowledge can be uncertain, delayed or wrong
- [ ] temporal historical truth
- [ ] cache/index delete/rebuild -> no data loss
- [ ] AI provider/model/runtime replacement -> no campaign migration/data loss
- [ ] local AI player-agency + actor/action/target conformance
- [ ] `ACTIVE_PLAYER_CHARACTER_CONTROL`: AI/MG/Director/NPC/World Simulation cannot generate or commit voluntary PC action without validated user command
- [ ] silence/missing user input never becomes consent; forced mechanical consequence remains typed separately from player volition
- [ ] character control transfer requires explicit validated user request + atomic commit; no autonomous relinquish/retirement by MG
- [ ] provider crash/cancel/process death -> no partial committed turn
- [ ] cloud failure -> local continuation
- [ ] cloud/Director candidate cannot mutate authority or rewrite past COMMIT
- [ ] same stimulus + different persisted NPC traits/relationships can yield different legal decisions
- [ ] personality adaptation requires committed cause/provenance
- [ ] reputation/rumor remains holder-scoped belief
- [ ] `WORLD_WITHOUT_PLAYER` causal evolution + save/load/replay equality
- [ ] `NEW_CHARACTER_SAME_WORLD`: nowy PC zachowuje ten sam Campaign World/history, old PC pozostaje aktorem świata, brak automatic knowledge/state inheritance
- [ ] former-PC -> autonomous World Actor continuity + later encounter with new PC
- [ ] character succession preserves unique ownership, holder-scoped knowledge, NPC relations, save/load/replay and exactly one active PC
- [ ] `SAME_WORLD_TWO_CAMPAIGNS` divergence explainable by player actions + world processes + controlled randomness
- [ ] background FACT does not automatically become player/NPC/organization knowledge
- [ ] world-process/domain conservation for supported economy/population/resources/projects
- [ ] `WORLD_CAUSALITY_LOOP`: actor knowledge/motivation/goals/plans produce explainable decisions/actions/consequences without random-event fabrication
- [ ] actor desires/dreams/goals can evolve through committed causes and survive off-screen/time-skip continuity
- [ ] conflicting motivations can produce different legal decisions without rewriting personality/history
- [ ] institutional agenda != every member personal desire/goal
- [ ] information delay/distortion can change decisions while objective FACT remains unchanged
- [ ] consequence propagation affects causally linked actors/processes without omniscient/global leakage
- [ ] active PC motivational state never authorizes autonomous voluntary PC action
- [ ] every material Living World enhancement has documented semantics, authority/invariants, LOD/performance, replay/migration compatibility and tests before acceptance
- [ ] suggestions use only active-PC epistemic/visible context; no hidden GM FACT leak through candidate actions
- [ ] clicking a suggestion is explicit user authorization; generating/showing a suggestion alone never creates PlayerCommand/COMMIT
- [ ] `CONTINUE_COMMAND` cannot invent new voluntary PC action and stops at the next meaningful Player Decision Point
- [ ] Continue during travel/training/waiting respects previously authorized intent and interrupts on significant threat/opportunity/choice
- [ ] `UNDO_CONFIRMATION`: first undo click/request cannot mutate committed state; separate confirmation is mandatory
- [ ] confirmed undo reconstructs/branches whole canonical turn state, not partial tables; knowledge/events/relations/resources remain consistent
- [ ] mobile default interaction remains usable with text input + three primary helpers (`Cofnij`, `Kontynuuj`, `Sugestie`) and progressive disclosure for advanced features
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
- [ ] available latency improves quality before idle waiting; speculative/background preparation never commits future player/world decisions

# AKTUALNA NAJBLIŻSZA ZALEŻNOŚĆ
`Exact-SHA CI + signed validation APK dla candidate Phase 39–47 oraz required Phase 48–54 vertical slice`

Obowiązkowa sekwencja:
`READ ARCHITECTURE + ROADMAP + MAPA PLIKÓW -> AUDIT FIRST -> classify COMPLETE/PARTIAL/MISSING/BLOCKED -> minimal implementation -> targeted tests -> compatibility -> full JVM -> PR -> exact-SHA CI -> coordinator acceptance`.

Phase 38: **GLOBALLY ACCEPTED / COMPLETE** na code-bearing SHA `db2f836fe3575204d045e5d3a861e07bb61cd5a9`; exact-SHA run `32776574352` — SUCCESS. Phase 39–47 oraz slice 48–54 pozostają candidate do czasu zielonego exact-SHA evidence.

Future Hybrid AI, NPC individuality, Living World i post-roadmap WPC nie zmieniają tej kolejności.

# ZASADA AKTUALIZACJI
Roadmap ma pozostać aktywnym planem, nie changelogiem. Historyczne SHA/CI/findings/rozbudowane wcześniejsze opisy przenosimy do `docs/Historia projektu.md` i phase acceptance records. Status zmieniamy tylko na podstawie aktualnego repo i pełnego evidence.

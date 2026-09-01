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
- Canonical globally accepted runtime remains Phase 38 at code-bearing SHA `db2f836fe3575204d045e5d3a861e07bb61cd5a9`. Candidate Phase 39–47 implementation and required Phase 48–54 vertical slice są zaimplementowane na code-bearing SHA `5ae6f0648704b114c6aa38ddea7f912006709d8d`; exact-SHA CI jest zielone, a global acceptance wymaga decyzji koordynatora.
- Phase 38 final audit: PASS; protected projection, trusted perception, sealed carrier/effective-access, persistence/replay oraz repository-wide consumer inventory findings są zamknięte.
- Exact-SHA GREEN evidence: Phase 38 `117/0/0`, full JVM `1004/0/0`, Actions run `32776574352`, job `97588891710`.
- Acceptance record: `docs/architecture/PHASE38_ACCEPTANCE.md`; pełne historyczne SHA/CI/artifacts/findingi pozostają w `docs/Historia projektu.md` i phase acceptance records.
- Exact-SHA evidence: `Validate RPG OS ALPHA` run `32889856844`, `Phase39-47 Audit3 Validation` run `32889856923` i `Phase38 AUD002 Forensic Gate` run `32889856858` — SUCCESS. Signed artifact `9579252027`, digest `sha256:0ad2e25010501b235695be0c1823a21e4f1f336d1e85f7e7e1a7ba39d48a841e`.
- Phase 48–54 mają zintegrowany targeted-acceptance repair opisany w `docs/architecture/PHASE48_54_FINAL_IMPLEMENTATION.md`. Trwały canonical mechanical state PC/NPC/group/unit, produkcyjny aggregate/AOE path, rzeczywista staged projection oraz restart-safe Phase54 recovery zamykają findingi wcześniejszego audytu. Lokalny pakiet acceptance jest zielony; globalny status pozostaje `[-]` do exact-SHA CI i końcowego audytu tego runu. Live-device/live-model evidence pozostaje odrębną bramką operacyjną, nie luką Core.
- Bekko a8m semantic-memory slice rozszerza Phase41/44/45 i dostarcza kandydat Phase59 bez zmiany canonical authority: osobny model GGUF, audience-scoped rebuildable sidecar, natychmiastowy post-commit catch-up, hybrydowy fallback i aktywne porty MG/Director/World Pack. Search i przełączanie kampanii podczas indeksowania są `GREEN` na fizycznej Motoroli/Android 14; każdy błąd cache jest typed fallbackiem i nie może kończyć procesu aplikacji. Exact-SHA CI, osobne wydania modelu/APK oraz wydajność, temperatura i współistnienie z Bielikiem na reprezentatywnej macierzy telefonów pozostają odrębnymi bramkami, dlatego slice nie jest jeszcze globalnie `COMPLETE`.
- Codex-first `labDebug` Bridge Etapów 1–3 ma lokalne acceptance `GREEN` na fizycznej Motoroli: prawdziwa kampania/postać, 10 tur jakościowych, 100/100 kolejnych narrated/committed tur, save/restart/continue, Bekko oraz 12/12 pakietów Directora. Zachowuje diagnostykę etapu 2 i dwa niezależne automatyczne workery `LAB_CODEX`: pełnego MG oraz Phase65 Directora. Director zapisuje wyłącznie rebuildable sidecar, a do kolejnego `GM_PROPOSAL` może wejść jedynie aktualna, audience-safe wskazówka; wszystkie odpowiedzi nadal przechodzą istniejący codec, walidację, mechanikę i Core. Bridge nie jest częścią release ani canonical authority; pełny kontrakt opisuje `docs/development/RPG_OS_LAB_BRIDGE.md`.
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

# FAZA D — GM ENGINE / ROLE-BASED AI FOUNDATION
- [-] 48. AI Provider & role-based Local/Cloud execution — universal LocalAiPort/CloudAiPort, Bielik profile/settings/admission, spakowany ExecuTorch Android runtime, pełny mobilny artefakt Bielik 1.5B v3 XNNPACK, natywny llama.cpp dla dowolnego GGUF z CPU/Vulkan, OpenRouter PKCE/Keystore/model discovery/strict workload JSON Schema inference i deterministic Auto/manual role routing są zintegrowane; wspólne Centrum AI i uniwersalny confirmation-gated character creator są dostępne w Android UI; live user authorization i real-device inference/performance pozostają zewnętrznymi bramkami
- [-] 49. Structured GM Output contract — strict proposal identity/provenance/actor/action/target/modality/dependency/player-agency validation zaimplementowane
- [-] 50. Universal Mechanics & Combat Resolution integration `[REF-ADAPTER]` — jeden production Combat Engine, trwały canonical PC/NPC/world-actor/group/unit state bez rerollowania z template, typed owner materialization, rzeczywista staged multi-action projection oraz individual/AOE/group-vs-group aggregate combat są lokalnie GREEN; oczekuje exact-SHA CI i końcowego acceptance
- [-] 51. Candidate-State Consistency Validator — pure projection oraz inventory/ownership/finance/progression/location/exclusion/temporal checks zaimplementowane
- [-] 52. Counterfactual/Factual Frontier Guard — FACT/BELIEF/NARRATIVE/future/counterfactual/support/scope isolation zaimplementowane
- [-] 53. Repair Pass for proposal and narrative — bounded, no-reroll, no-entitlement-expansion repair z pełną rewalidacją zaimplementowany
- [-] 54. Committed narrative after valid transaction — exact persisted receipt identity, replay-bound post-commit readback, semantic firewall, pełna fidelity delivery, trwały recovery marker i restart recovery bez ponownego planowania/mechaniki/assemblera/commita są lokalnie GREEN; oczekuje exact-SHA CI i końcowego acceptance

Status `[-]` jest celowy i nie jest ogólnym „vertical slice only”. Szczegółowe `IMPLEMENTATION_COMPLETE`, `CONCRETE_ADAPTER_GREEN`, `LIVE_EVIDENCE_PENDING_EXTERNAL_DEPENDENCY` i `ACTUAL_IMPLEMENTATION_BLOCKER` są rozdzielone w `docs/architecture/PHASE48_54_FINAL_IMPLEMENTATION.md`. Stary `PHASE48_54_VERTICAL_SLICE_ACCEPTANCE.md` pozostaje rekordem historycznym.

## Acceptance direction Phase 48–54
Phase 48 buduje provider/execution foundation, nie pełny Director.

Zaimplementowany final-plan candidate:
- `Chat/UI -> AiChatEngineFacade -> AiProvider -> IntentDocument -> GraphTurnPlanner -> Context -> StructuredGmProposal -> Mechanics/Guards/Repair -> canonical TurnTransaction -> persisted TurnCommitReceipt -> Narrative`;
- `AiProviderRegistry`, `AiCapabilityContract`, `LocalAiPort`, `CloudAiPort` i role-aware `ModelRouter` pozwalają zamienić provider/model bez zmian Phase 43–54 i bez migracji kampanii;
- Bielik 1.5B v3 Instruct w wersji ExecuTorch/XNNPACK jest lekkim domyślnym profilem Android, a historyczny Bielik 4.5B v3 pozostaje obsługiwanym profilem jakościowym i punktem odniesienia; oba są danymi/adapterami, nie osobnymi silnikami GM;
- emulator API 36 potwierdza pełne załadowanie Bielika 1.5B, rzeczywistą inferencję kreatora na katalogu Naruto i przejście do kolejnego pytania bez crasha; osobny production-root E2E potwierdza 100 kolejnych commitowanych tur Core, ale nie zastępuje końcowego benchmarku na fizycznym telefonie;
- OpenRouter jest pierwszym adapterem cloud; oficjalny callback OAuth PKCE raportuje sukces dopiero po udanej wymianie kodu na klucz i jego zaszyfrowanym zapisie, a panel pokazuje typed reason bez ujawniania credential;
- administracyjne przygotowanie nowej/aktywowanej/odtwarzanej kampanii jest wspólne i lifecycle-serialized, dzięki czemu szablon z aktywnymi guardami Phase32 nie blokuje definicji mechanik błędem SQLite 1811;
- tury tej samej kampanii są serializowane w jednym canonical commit order (różne kampanie nadal są niezależne), więc równoległy retry tej samej komendy daje jeden commit i replay zamiast błędu blokady SQLite;
- AI nie otrzymuje repository/DB ani mutation authority; proposal i narrative nie są rzeczywistością;
- narrative request wymaga niepodrabialnego w ścieżce aplikacji evidence wydanego po odczycie trwałego V3 receipt;
- provider failure, invalid structured output i cancellation przed commit kończą się bez mutacji; cancellation/failure po commit nie cofa rzeczywistości i zwraca typed `CommittedWithoutNarrative`.
- rozpoczęcie nowej kampanii ma provider-independent `CHARACTER_CREATION` workload: MG zbiera wybory gracza i tworzy kompletny draft postaci z identity/gender/stats/resources/talent/potential/skills/techniques/origins/innate features/start location, ale zapis następuje atomowo dopiero po osobnym jawnym potwierdzeniu gracza;
- kreator pobiera typed definitions aktywnego World Packa, stosuje wąski legacy import tylko dla starszych paczek i neutralny, namespaced fallback bez Naruto-specific statów lub zasobów; World Pack dostarcza treść, a Core workflow i authority są uniwersalne.

Otwarte bramki są sklasyfikowane precyzyjnie w finalnym rekordzie: pełny mobilny pakiet Bielik 1.5B i host-side ExecuTorch load są GREEN. Pierwsza próba fizycznej inferencji ujawniła native-process crash; od alpha14 lokalny runtime jest izolowany w `:local_ai`, aby taka awaria nie zamykała aplikacji ani nie naruszała canonical state, lecz zgodność PTE/runtime nadal wymaga ponownego testu urządzenia. Live OpenRouter otrzymał system-first szyfrowany DNS fallback po potwierdzonym błędzie resolvera Androida. Wcześniejsze blockery spakowanego local runtime i produkcyjnej kompozycji są zamknięte w repair candidate. Legacy `ViewModel -> StatePatch` został usunięty; compatibility backend może dać wyłącznie jawnie nieautorytatywną narrację i jego patch jest odrzucany.

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
- effects są typed/compositional i mogą obejmować HP/wounds/resources/status/movement/equipment/structure/morale/cohesion/formation/environment zamiast jednego damage number; katalog statusów (`BURNING`, `POISONED`, `PARALYZED`, `FROZEN` itd.) należy do uniwersalnego Core, natomiast World Pack definiuje zdolność, jej koszty i szanse zastosowania statusu;
- AoE jest semantic family/shape contractem, nie przypadkiem `FIREBALL`; World Pack może definiować blast/cone/line/zone/sweep i inne zdolności bez zmian Core;
- wielkie starcia używają bounded O(1) aggregate resolution dla individual-vs-group, group-vs-group i unit-vs-unit; ekstremalna przewaga siły może legalnie zamienić pojedynczy atak w bounded group impact bez rozwijania setek/tysięcy członków;
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
- [-] 59. Vector/Semantic Retrieval engine/index integration `[REF-ADAPTER]` — Bekko a8m Q8_0, oddzielny CPU/manual-Vulkan embedding runtime, audience-scoped exact FP16 sidecar, Phase41 provider, Phase44 capability i Phase45 budget/fallback są zaimplementowanym kandydatem; physical-device correctness jest zielone na Motoroli/Android 14, a exact-SHA CI/release i performance/thermal/coexistence na reprezentatywnej macierzy urządzeń pozostają otwarte
- [ ] 60. Time Skip Processor + Scheduler/WorldProcess orchestration `[REF-ADAPTER]`
- [-] 61. NPC Brain + persistent individuality/personality/values/goals/fears/emotional state/relationships `[REF-ADAPTER]`
- [-] 62. NPC Decision Engine + knowledge/memory/social-role constrained autonomy `[REF-ADAPTER]`
- [ ] 63. World Simulation LOD 0–3 + Universal Runtime World Materialization Protocol + World Actor mechanical materialization + Combat LOD integration `[REF-ADAPTER]` — minimalny `AggregateCombatStatePort`/aggregate population seam jest pulled-forward wyłącznie dla Phase50; uniwersalna produkcyjna materializacja brakujących elementów bez obowiązkowego katalogu szablonów, symulacja LOD, promotion/coarsening i background world loop nadal należą do Phase63
- [ ] 64. Background-world causal simulation: organizations/economy/projects/demography/wars/knowledge propagation/conflict resolution + controlled randomness `[REF-ADAPTER]`

## Acceptance direction Phase 55–64
Memory pozostaje RPG OS-owned i odtwarzalna po zmianie modelu/runtime.

### Bekko a8m — aktywny semantic retrieval candidate

Bekko jest wyłącznie lokalnym `SEARCH / MATCH / RANK / CLUSTER` helperem. Nie jest źródłem prawdy, właścicielem mechaniki, generatorem relacji `CAUSES`, aliasów, FACT ani zmian świata. Produkcyjny przepływ jest hybrydowy:

`Phase38 authorized projection -> exact/structured/temporal filters -> Bekko ranking -> Phase45 typed context budget`.

Aktywne są: pamięć MG, redukcja kontekstu, semantic scout istniejącego Directora i osobny namespace World Pack. Exact UID oraz REQUIRED/SAFETY context pozostają nadrzędne. Brak modelu, awaria procesu, niegotowy/stary indeks lub błąd wersji uruchamiają typed fallback do dotychczasowego retrieval oraz hot-tail structured reads, bez mutacji i bez blokowania tury.

Porty kandydatów dla Phase58, Phase61–64, Phase66 i Phase68 są przetestowanymi seamami bez ownershipu i bez aktywacji brakującej fazy. Nie zmieniają statusu tych faz i nie mogą samodzielnie konsolidować pamięci, podejmować decyzji NPC, symulować świata, tworzyć obietnic, rozstrzygać sprzeczności ani nadawać causal authority.

Indeks jest per-campaign `CACHE/REBUILDABLE`, pozostaje poza save hash/snapshot/canonical truth i przechowuje 256-wymiarowe Matryoshka FP16. Wersja wiąże model SHA, wymiar, normalizację, format oraz projector. Po legalnym `Committed`/`AlreadyCommitted` działa idempotentny post-commit catch-up; rollback niczego nie indeksuje. Nie istnieje cykliczny WorkManager. Otwieranie kampanii porównuje checkpoint z replay i domyka lukę po awarii.

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

### Universal Runtime World Materialization Protocol — owner Phase 63, consumer Phase 64

Swobodna gra nie może wymagać ani World Packa, ani katalogu szablonów obejmującego z góry każdy możliwy budynek, zawód, NPC, przedmiot, organizację lub zjawisko. Kampania posiada własny uniwersalny `Campaign World Model`; World Pack jest wyłącznie opcjonalnym źródłem początkowych faktów, ograniczeń, nazw i kanonu. Świat bez World Packa używa tego samego Core, tych samych kontraktów, tych samych inwariantów i tej samej ścieżki commit. Brak paczki albo dedykowanego szablonu nie może sam w sobie blokować logicznego elementu. Produkcyjny runtime używa jednego rozszerzalnego protokołu materializacji:

`unresolved player/world reference -> reference-shape classification (named instance/category/quantity/affordance) -> exact/structured lookup -> authorized Bekko ranking -> optional evidence providers -> universal typed draft -> campaign-world/era/topology/uniqueness validation -> Core identity/provenance/materialization -> canonical TurnTransaction COMMIT -> authorized projection -> post-commit Bekko indexing`.

Stan pull-forward wdrożony przed pełną Phase63: lokalny kontrakt intencji zachowuje dowolny czasownik gracza i niezależnie klasyfikuje go jako `MOVEMENT`, `COMBAT` albo bezpieczną `ACTION`; nieznane wcześniej czasowniki nie wypadają już przez allowlistę. Core dopiero po walidacji promuje nieufną etykietę modelu. Targeted i self-action mają rozłączne capability, a nierozwiązany target zatrzymuje wykonanie. Kategorie/role/affordance dla wszystkich bazowych kinds mogą tworzyć fingerprintowany latentny draft; named instance wymaga dowodu z zakotwiczeniem i sam wynik internetowy nie może teleportować bytu do bieżącej sceny.

Canonical `campaign_truth_records` pozostają jedyną prawdą. `campaign_world_elements_projection` jest per-campaign `CACHE/REBUILDABLE`, odświeżaną transakcyjnie z committed facts, stronicowaną przy rebuildzie i filtrowaną do `PLAYER_VISIBLE` na ścieżce gracza. Materializacja elementu i skutek gracza (np. zmiana lokacji lub interakcja) wchodzą do jednego `TurnTransaction`; rollback nie pozostawia elementu, projekcji ani ruchu. Po commit projector Bekko scala techniczne pola jednego elementu w pojedynczy dokument świata i emituje osobne legalne projekcje dla gracza i MG. Ten pull-forward nie oznacza ukończenia symulacji LOD, pełnej topologii ani Phase63/64.

Świat jest rozwijany w trzech warstwach:
1. `WORLD_SKELETON` — utworzony przy bootstrapie kampanii minimalny zestaw makro-ankrów, reguł topologii, campaign/world seed i niezmienników potrzebnych do spójnego rozwijania świata. Może powstać z opisu gracza, importu, World Packa, zweryfikowanych źródeł albo deterministycznego generatora; żadne z tych źródeł nie jest obowiązkowe ani nie otrzymuje osobnej mutation authority.
2. `LATENT_DETERMINISTIC_WORLD` — order-independent przestrzeń kandydatów wynikająca wyłącznie ze szkieletu, seedu, reguł i canonical as-of state. Nie jest jeszcze FACT ani player knowledge, lecz ten sam input zawsze rozwiązuje ją tak samo; pierwsze pytanie lub życzenie gracza nie może przesuwać morza, miasta, pustyni ani innego elementu w dogodne miejsce.
3. `JIT_MATERIALIZED_WORLD` — minimalny wycinek latentnego świata staje się canonical dopiero wtedy, gdy legalna akcja, percepcja albo World Process rzeczywiście go potrzebuje i przejdzie właściwy commit. Nie trzeba generować całej planety ani całej populacji na początku kampanii.

Globalne inwarianty:
- `NOT_MATERIALIZED != DOES_NOT_EXIST`;
- `PLAYER REQUEST != WORLD GENERATION RULE`;
- `LAZY MATERIALIZATION != PLAYER-WISH FULFILLMENT`;
- `WORLD PACK ABSENT != WORLD MODEL ABSENT`;
- `CATEGORY REFERENCE != UNIQUE NAMED ENTITY`;
- `EXTERNAL EVIDENCE != CANONICAL FACT`;
- `SAME WORLD SEED + SAME RULES + SAME AS-OF STATE = SAME WORLD RESOLUTION`, niezależnie od kolejności zapytań, retry, urządzenia i modelu AI.

Przed materializacją celu ruchu lub aktywności działa uniwersalny `World Feasibility & Topology Gate`. Sprawdza on aktualny spatial anchor, target kind, połączenia i reachability, odległość/czas/koszt podróży, teren/przeszkody, wymagane affordance (np. `SWIMMABLE`), dostępność czasową oraz wiedzę postaci. Typed wynik rozróżnia co najmniej `FEASIBLE_NEARBY`, `FEASIBLE_AS_JOURNEY`, `CONTRADICTED` i `UNKNOWN`. Odległy legalny cel może zostać rozwinięty do planu podróży, ale nie może zostać przeniesiony obok gracza; brak wiedzy postaci nie usuwa obiektu ze świata i jednocześnie nie może ujawnić graczowi ukrytej trasy.

Kontrakt obejmuje co najmniej:
- uniwersalne jądro rodzajów `PLACE`, `ACTOR`, `OBJECT`, `GROUP`, `ORGANIZATION`, `EVENT`, `PROCESS` i `CONCEPT` oraz otwarte typed tags/components; kampania może reprezentować nową klasę obiektu bez kodowania jej nazwy w Core i bez definicji World Packa;
- `WorldReferenceShape`/równoważny kontrakt rozróżniający named instance, category/class, quantity, role, affordance i relational target. „Poligon” jest klasą `PLACE` z affordance `TRAINING`, natomiast „Poligon nr 3” może być named instance; resolver nie może wymuszać jednego globalnego poligonu;
- jeden `WorldElementDraft`/równoważny kontrakt oparty na stabilnym jądrze (`kind`, identity hints, time/space scope, relations, properties, capabilities, knowledge/visibility, lifecycle, provenance) i otwartych typed components; nowe rodzaje elementów World Packa nie wymagają dodawania nowej ścieżki Core ani osobnego generatora;
- model, reguła deterministyczna, Director, World Process lub validated player intent mogą zaproponować draft przez ten sam port, ale żaden proposer nie nadaje canonical UID, nie ustanawia FACT, nie rozstrzyga mechaniki i nie posiada mutation authority;
- `WorldEvidenceProviderPort` może dostarczać bounded candidates/evidence z campaign facts, World Packa, lokalnej bazy wiedzy, opcjonalnego wyszukiwania internetowego, ręcznego opisu albo generatora deterministycznego. Provider nie materializuje obiektu i nie awansuje wyniku do FACT; Core zapisuje source URI/revision/hash, confidence, canon scope i classification;
- World Pack może dostarczyć constraints, definicje semantyczne, zależności czasowo-przestrzenne, uniqueness/canon rules i opcjonalne archetypy jakościowe, ale jest jednym z providerów i nigdy warunkiem działania protokołu;
- opcjonalny `Canon/Evidence Scout` może używać Internetu po zgodzie/polityce prywatności i tylko dla nierozwiązanych lub wymagających weryfikacji kandydatów. Wynik jest klasyfikowany co najmniej jako `SOURCE_CANON`, `CAMPAIGN_FACT`, `GENERATED_PLAUSIBLE`, `BELIEF/RUMOR`, `CONFLICTING_EVIDENCE` albo `UNKNOWN`; brak sieci zawsze wraca do lokalnego pipeline bez blokowania zwykłej gry;
- Core materializuje tylko pola wymagane przez bieżącą interakcję i pozostawia pozostałe jako jawne `UNKNOWN/NOT_MATERIALIZED`; późniejsze rozwinięcie dodaje komponenty do tego samego UID zamiast rerollować albo zastępować obiekt;
- każdy draft przechodzi obowiązkowy preflight sprawdzający campaign/branch, datę i epokę, region, relacje, zależności, rarity/uniqueness, canon/divergence, conservation, World Pack rules oraz legalny zakres wiedzy; sprzeczność jest odrzucana niezależnie od tego, kto zaproponował draft;
- Core nadaje stabilny deterministic UID, hierarchical seed, provenance, source evidence i poziom `SEED_ONLY/PARTIAL/FULL`; retry, concurrency, process death i ponowne otwarcie kampanii nie tworzą duplikatu ani nie zmieniają już ustalonej tożsamości;
- zwykłe, niskiego ryzyka elementy wynikające logicznie z istniejącego stanu mogą powstawać automatycznie bez pytania gracza; doprecyzowanie jest wymagane tylko wtedy, gdy nierozstrzygalność zmienia intencję gracza albo materializacja miałaby istotny wpływ na kanon, unikalność, mechanikę lub historię świata;
- NPC, lokacja, instytucja, organizacja, grupa, przedmiot, proces i dowolny World Pack-defined element korzystają z tego samego lifecycle; odpowiednie domeny nadal są właścicielami swoich typed components i walidacji, więc protokół nie staje się drugim silnikiem świata;
- NPC może rozpocząć jako minimalna trwała tożsamość z pozycją i rolą, a osobowość/pamięć/decyzje są rozwijane przez Phase61–62; element mechaniczny jest materializowany przez właściwego ownera dopiero wtedy, gdy mechanika rzeczywiście go potrzebuje;
- lokacje i instytucje otrzymują trwałą tożsamość, hierarchię/region, zakres czasowy, dostępność i relacje; runtime nie może stworzyć instytucji przed jej powstaniem ani w miejscu, w którym narusza ona stan lub prawa świata;
- żaden nowy element nie jest dostępny Bielikowi, Directorowi, NPC Brain ani graczowi przed udanym canonical commit i legalną Phase38 projection; rollback, anulowanie i awaria AI nie pozostawiają częściowego obiektu;
- Bekko wyłącznie znajduje podobne legalne elementy, fakty i ograniczenia oraz indeksuje wynik po `Committed`/`AlreadyCommitted`; Bekko nie tworzy draftu ani świata, a jego brak uruchamia structured fallback bez blokowania materializacji;
- brak dedykowanego szablonu nigdy nie jest reason code odrzucenia; odrzucenie lub typed clarification wymaga konkretnej przyczyny semantycznej, np. `TIMELINE_CONFLICT`, `CANON_CONFLICT`, `UNIQUENESS_CONFLICT`, `WORLD_RULE_REJECTED`, `REFERENCE_AMBIGUOUS` albo `INSUFFICIENT_REQUIRED_FACTS`;
- bootstrap kampanii materializuje minimalny spójny start (era, aktywna lokacja i elementy konieczne dla wybranego pochodzenia/roli) również bez World Packa, a późniejszy JIT rozszerza świat tylko wtedy, gdy wymaga tego legalna akcja, percepcja albo proces świata;
- category target resolution wybiera istniejącą legalną instancję według distance/access/affordance/knowledge/current purpose; jeśli żadna nie została materializowana, może wybrać deterministycznego latentnego kandydata. Nowa instancja nie zastępuje całej kategorii i nie blokuje późniejszego istnienia wielu podobnych obiektów;
- acceptance scenario: wzmianka o Akademii w poprawnie ustawionej epoce Naruto może utworzyć minimalną instytucję/lokację bez istniejącego `ACADEMY_TEMPLATE`, korzystając z uniwersalnego draftu i reguł świata; nauczyciel powstaje jako powiązany element dopiero wtedy, gdy jest potrzebny scenie. Ten sam request przed założeniem Konohy kończy się `TIMELINE_CONFLICT` przed proposal/commit/narration;
- acceptance scenario: „idę na poligon potrenować” jest grafem `TRAVEL -> TRAIN` z category target `PLACE{TRAINING}`. W osadzie mogą istnieć liczne poligony; resolver wybiera dostępny odpowiedni poligon albo deterministycznie materializuje jedną instancję zakotwiczoną w osadzie, nie pyta o UID i nie tworzy jednego globalnego obiektu `POLIGON`;
- acceptance scenario: jeżeli na początku kampanii materializowana jest tylko górska wioska, polecenie „idę nad morze” najpierw rozwiązuje order-independent latentną topologię. Istniejące odległe morze daje `FEASIBLE_AS_JOURNEY` i bezpieczne rozpoczęcie/confirm podróży, brak wiedzy postaci prowadzi do zdobycia informacji, a świat bez morza daje `CONTRADICTED`; request nigdy nie generuje wygodnej plaży obok wioski;
- testy obejmują kampanię bez World Packa, nowe kinds/tags bez zmian Core, named-vs-category reference, wiele instancji tej samej kategorii, materializację bez archetypu, offline/online evidence parity, source conflict, stopniowe uzupełnianie komponentów, identyczny świat przy różnych kolejnościach zapytań (`morze -> pustynia` oraz `pustynia -> morze`), idempotent retry/concurrency, cross-device/model determinism, rollback/process death, save/reopen/replay, cross-campaign i audience isolation, timeline/canon/uniqueness/topology rejection, brak knowledge leakage, stabilność UID/seedu, brak player-power scaling, brak duplikatów oraz post-commit catch-up Bekko.

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
Historyczny TEMP-GM/Termux jest nadal evidence, ale llama.cpp/GGUF/Vulkan ma już produkcyjny adapter Androida podpięty do wspólnego `LocalAiPort` i tego samego Core co ExecuTorch/OpenRouter. Profil Bielik 4.5B v3 GGUF Q4_K_M na Samsungu SM-S921B ustala domyślne wartości CTX8192, Vulkan/Xclipse, `-ngl 99`, KV f16 i krytyczne dla prefill `-b 64 -ub 64`. Użytkownik może zmienić je ręcznie bez admission limitów RAM/CTX/GPU dla GGUF; izolowany proces i canonical validation/commit pozostają właściwością aplikacji, nie ograniczeniem modelu. Real-device wydajność i kompatybilność konkretnego pliku GGUF nadal wymagają osobnego evidence.

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
`Coordinator acceptance dla candidate Phase 39–47 oraz required Phase 48–54 vertical slice`

Obowiązkowa sekwencja:
`READ ARCHITECTURE + ROADMAP + MAPA PLIKÓW -> AUDIT FIRST -> classify COMPLETE/PARTIAL/MISSING/BLOCKED -> minimal implementation -> targeted tests -> compatibility -> full JVM -> PR -> exact-SHA CI -> coordinator acceptance`.

Phase 38: **GLOBALLY ACCEPTED / COMPLETE** na code-bearing SHA `db2f836fe3575204d045e5d3a861e07bb61cd5a9`; exact-SHA run `32776574352` — SUCCESS. Phase 39–47 oraz slice 48–54 mają zielone exact-SHA evidence dla `5ae6f0648704b114c6aa38ddea7f912006709d8d`, lecz pozostają candidate do decyzji koordynatora.

Future Hybrid AI, NPC individuality, Living World i post-roadmap WPC nie zmieniają tej kolejności.

# ZASADA AKTUALIZACJI
Roadmap ma pozostać aktywnym planem, nie changelogiem. Historyczne SHA/CI/findings/rozbudowane wcześniejsze opisy przenosimy do `docs/Historia projektu.md` i phase acceptance records. Status zmieniamy tylko na podstawie aktualnego repo i pełnego evidence.

from pathlib import Path

ARCH = Path('docs/Architektura projektu.md')
ROAD = Path('docs/Roadmap.md')

arch = ARCH.read_text(encoding='utf-8')
road = ROAD.read_text(encoding='utf-8')

universal = '''## 0.1 Globalny Invariant Uniwersalności — OBOWIĄZUJE WSZYSTKIE PRZYSZŁE FAZY
RPG OS jest uniwersalnym Core dla dowolnego uniwersum, World Packa, gatunku gry, stylu rozgrywki i rodzaju odgrywanej postaci. **Każda przyszła faza, mechanizm, domena, API, schema, resolver, AI boundary i acceptance contract MUSI być projektowany najpierw jako rozwiązanie world-agnostic.**

Nie wolno projektować Core pod jeden lub dwa aktualnie używane World Packi ani traktować Naruto, Bleach, Wiedźmina, fantasy, sci-fi, strategii, horroru czy jakiegokolwiek innego świata jako ukrytego modelu referencyjnego Core. Przykłady z konkretnych światów są wyłącznie testami/fixtures; nie mogą stać się hardcoded authority, nazwą domeny ani warunkiem działania Core.

Globalne zasady:
- `CORE DEFINES UNIVERSAL CONTRACTS; WORLD PACK DEFINES SEMANTICS AND CONTENT`;
- `WORLD PACK MAY EXTEND DATA/RULE DEFINITIONS; IT MAY NOT REPLACE CORE AUTHORITY ENGINES`;
- future feature musi działać dla różnych typów aktorów: pojedynczej postaci, grupy, organizacji, państwa, armii, pojazdu, bytu nieludzkiego, kolektywu, AI/world-defined actor itd., jeżeli dana domena ma do nich zastosowanie;
- future feature musi uwzględniać różne style gry: character RPG, tactical/strategy, management, trading, science/research, medicine, investigation, espionage/politics, exploration i World Pack-defined styles;
- world-specific role, race, rank, ability, sense, secrecy class, resource, organization lub metafizyka jest `UID/definition/rule` dostarczanym przez World Pack, nie `if/else` zaszytym w Core;
- jeżeli rozwiązanie działa tylko w jednym/dwóch obecnych World Packach, faza jest jakościowo NIEGOTOWA do canonical acceptance, chyba że sama faza jest jawnie World-Pack-specific (np. Phase 80–84 integration packs);
- acceptance nowych Core phases MUSI zawierać multi-world / multi-style adversarial cases potwierdzające brak ukrytego world lock-in.

Ten invariant ma pierwszeństwo przed wygodą implementacyjną późniejszych faz i nie może zostać osłabiony przez prompt, AI provider, UI ani World Pack.

'''
anchor = 'Nie implementuj architektury z pamięci. Najpierw sprawdź repozytorium.\n\n## 1. Misja i granica odpowiedzialności'
if '## 0.1 Globalny Invariant Uniwersalności' not in arch:
    assert anchor in arch
    arch = arch.replace(anchor, 'Nie implementuj architektury z pamięci. Najpierw sprawdź repozytorium.\n\n' + universal + '## 1. Misja i granica odpowiedzialności', 1)

phase38_arch = '''### 10.9 Phase 38 — Universal Visibility, Access & Audience Boundary — CANONICAL FUTURE CONTRACT
Phase 38 jest drugim filarem epistemicznym po Phase 37. Phase 37 odpowiada `WHO KNOWS/THINKS WHAT AND WHY`; Phase 38 odpowiada `WHO MAY ACCESS / PERCEIVE / UNDERSTAND / RECEIVE WHICH INFORMATION FOR WHICH PURPOSE`. Nie tworzy nowej prawdy ani nowej wiedzy; buduje fail-closed projections nad authoritative state i Phase-37 epistemic state.

Globalny invariant:
`FACT != KNOWLEDGE != ACCESS != PERCEPTION != INTERPRETATION != DISCLOSURE != PRESENTATION`.
`DATA EXISTS` nigdy nie oznacza automatycznie `THIS AUDIENCE MAY SEE/USE IT`.

#### 10.9.1 World-agnostic Core / World Pack boundary
Core Phase 38 zna wyłącznie generic concepts: `AudienceContext`, `PurposeContext`, `VisibilitySubject/PropertyRef`, `AccessPolicy`, `AccessGrant/Revocation`, `Role/Organization/Clearance/Capability bindings`, `InformationCarrier`, `Signal`, `PerceptionAttempt`, `DisclosureLevel`, `VisibilityDecision/Projection` i provenance. Core nie zna nazw ról, klas tajności, zmysłów, technologii, magii ani metafizyki konkretnego świata.

World Pack może definiować role, organizations, clearance levels, carrier kinds, signal/detection channels, comprehension capabilities, protection/bypass rules i world-specific policies, ale **nie może implementować konkurencyjnego Visibility/Access Engine**. Unknown/unsupported world rule failuje zamknięcie zamiast awansować do PUBLIC.

#### 10.9.2 Audience i purpose są obowiązkowe
Chroniony odczyt posiada jawny `AudienceContext` związany co najmniej z campaign, audience kind oraz odpowiednimi holder/actor/organization/role/grant/capability bindings. `PurposeContext` ogranicza minimalny potrzebny widok, np. world simulation, actor decision, combat decision, dialogue, planning, player narration, player UI, player suggestion lub authorized debug.

Nie istnieje jeden omniscient `ContextBundle`, z którego consumer ma sam usuwać sekrety. Raw authoritative stores -> Phase38 projection -> purpose filter -> consumer context.

`WORLD_INTERNAL`, `GM_INTERNAL`, `ACTOR_INTERNAL`, `PC_INTERNAL`, `PLAYER_VISIBLE`, `ORGANIZATION/ROLE_CONTEXT`, `PUBLIC` są różnymi audience semantics, nie kopiami prawdy. Projection privilege może pozostać równy lub maleć; nie może implicit eskalować do szerszego audience.

#### 10.9.3 Policy access i effective access są rozdzielone
`AUTHORIZED TO ACCESS != CAN EFFECTIVELY OBTAIN`. Formalny role/clearance/grant nie jest jedyną drogą informacji: świat może pozwalać na theft, interception, physical/technical bypass, social engineering, telepathy, magical/technical penetration albo World Pack-defined mechanisms. Phase 38 musi reprezentować legalność/authorization oddzielnie od faktycznego effective access; konsekwencje prawne/moralne należą do odpowiednich domen świata.

Pipeline dostępu rozróżnia co najmniej: `AUTHORIZED -> REACHABLE/AVAILABLE -> OPEN/DECODE -> COMPREHEND -> DISCLOSE/OBSERVE -> possible Phase37 acquisition`. `ACCESS != ACQUISITION`; dostęp do biblioteki/bazy/archiwum nie daje wiedzy o całej zawartości.

#### 10.9.4 PolicyAccessResolver i PerceptionResolver
Pod wspólnym `VisibilityAuthorityService` istnieją co najmniej dwa typy rozstrzygnięć:
- policy/carrier access — role, organization, grant, clearance, ownership, credentials, protection/bypass, availability;
- observational perception — signal emission, channel compatibility, detection capability, conditions, attention/capacity hooks, recognition/interpretation.

Perception operuje na signals/evidence, nie na omniscient objective identity. Dzięki temu disguise, illusion, stealth, camouflage, decoy, encryption i false credentials mogą działać bez automatycznego poprawiania obserwatora hidden FACT-em.

`DETECT != LOCATE != RECOGNIZE != CLASSIFY != INTERPRET != UNDERSTAND`. Expertise Phase 37 może poprawiać recognition/interpretation, lecz nie generuje sygnału ani wiedzy z niczego.

#### 10.9.5 Granular subjects i disclosure
Chroniony subject może być FACT/claim/evidence/carrier/event/entity/location/effect/resource/army/project/stat/property albo World Pack-defined ref. Ochrona może działać na pojedynczej właściwości (`SubjectPropertyRef`), ponieważ odbiorca może znać istnienie obiektu bez jego lokalizacji, właściciela, siły, celu lub innych pól.

Disclosure nie jest boolean. Core wspiera semantyczne poziomy typu `DENY`, `EXISTENCE_ONLY`, `CATEGORY/QUALITATIVE`, `APPROXIMATE/RANGE`, `SUMMARY`, `REDACTED`, `DETAILED`, `FULL` lub równoważny data-driven contract. Projection zachowuje uncertainty/confidence/precision/completeness/freshness zamiast zamieniać szacunek w dokładny FACT.

`NOT_VISIBLE`, `UNKNOWN`, `KNOWN_ABSENT`, `REDACTED`, `ACCESS_DENIED` i `UNRESOLVED` nie są synonimami.

#### 10.9.6 Holder, actor i player są odrębnymi tożsamościami
`WORLD ACTOR != KNOWLEDGE HOLDER != HUMAN PLAYER`. Jeden shared/hive mind może być jednym holderem dla wielu aktorów; possession, split party, multi-character control i World Pack-defined cognition mogą wiązać audience z wieloma holderami bez kopiowania ich acquisitions.

`PC_INTERNAL != PLAYER_VISIBLE`. Domyślny character-RPG player view jest ograniczony przez PC knowledge/perception, ale jawna game-mode/world policy może legalnie pokazać graczowi więcej (np. strategic UI, controlled-party union, spectator/cutscene disclosure) **bez nadawania tej wiedzy PC**. Player disclosure i PC acquisition muszą pozostawać osobnymi semantykami.

Former PC po legalnym control transfer staje się zwykłym World Actorem/holderem; nowy PC nie dziedziczy jego prywatnego visibility/knowledge state.

#### 10.9.7 Organization, role, clearance i grants
Institutional knowledge nie staje się osobistą pamięcią członków. Role/clearance/grant może udostępnić institutional view; dopiero rzeczywiste observation/read/briefing może utworzyć Phase37 acquisition. Revocation usuwa przyszły dostęp, nie kasuje osobistej wiedzy zdobytej wcześniej.

Canonical access grants/revocations/role/clearance bindings są campaign-qualified, temporal-ready i posiadają provenance/cause. Global immutable definitions są jawne; `campaignUid=null` nie oznacza automatycznie globalności.

#### 10.9.8 InformationCarrier i communication
Carrier jest generic nośnikiem informacji, nie hardcoded dokumentem: może być materialny, cyfrowy, biologiczny, magiczny, sygnałowy lub World Pack-defined. Możliwe poziomy interakcji obejmują istnienie/lokalizację/reach/open/decode/comprehend/copy/share zgodnie z rules świata.

Authorization nie gwarantuje availability: zerwana komunikacja, brak nośnika, zniszczony carrier, brak klucza/języka/capability albo opóźnienie raportu mogą blokować aktualny dostęp. Zniszczenie carriera nie usuwa wcześniejszej osobistej acquisition holdera.

#### 10.9.9 Reputation i public belief
Reputation nie jest omniscient globalnym score. Jest holder/group/institution-scoped assessment/belief o subject i typed dimension, z confidence/evidence/lineage. Różne populacje/frakcje mogą legalnie posiadać przeciwne reputacje tej samej osoby. Group/population holders i LOD aggregation mogą ograniczać koszt bez materializowania wiedzy każdego mieszkańca.

#### 10.9.10 Context, AI, UI i anti-leak boundary
`PROMPT INSTRUCTION IS NOT ACCESS CONTROL`. Sekret nie może trafiać do promptu/NPC brain/player suggestions/UI tylko z instrukcją „nie ujawniaj”. Consumer otrzymuje już zminimalizowaną/redacted `VisibilityProjection`.

Presentation layer nie otrzymuje hidden raw fields tylko po to, by ukryć je wizualnie. Player suggestions, `Continue`, situation recap, dialogue, NPC decision, Combat reaction, local AI i cloud AI muszą używać odpowiedniego audience+purpose projection. Cloud może mieć inny format/compression, ale nie szersze semantic entitlement.

World Simulation/Combat physics może używać hidden FACT do rozstrzygnięcia rzeczywistych skutków; aktor Decision Engine nie może używać tego FACT do dobrowolnej decyzji bez legalnej perception/knowledge. `PHYSICS MAY KNOW; VOLITION MAY NOT CHEAT`.

#### 10.9.11 Persistence, replay i temporal readiness
Authoritative są co najmniej trwałe grants/revocations/bindings oraz inne world-state changes wpływające na access. `VisibilityDecision`, `VisibilityProjection` i większość query-time perception views mogą być derived, ale każde losowe/nieodwracalne perception result wpływające na historię musi być replay-safe przez Event/evidence/RNG provenance.

Phase 38 jest temporal-ready dla Phase 39: później musi dać się odtworzyć, kto miał legalny/effective access lub player disclosure w czasie T. Snapshot/load/replay/branch/undo zachowują access-authority state i nie pozostawiają wiedzy/disclosure z cofniętej linii.

#### 10.9.12 Performance i LOD
Visibility jest oceniane on-demand/batch dla relevant subjects/audiences, nie materializowane jako globalna macierz `all actors x all facts`. System wspiera group audience/aggregate, batch evaluation, cache/derived projections z bezpieczną invalidacją i lazy detail. Koszt skaluje się z relevant state, nie całym światem.

#### 10.9.13 Visibility Consumer Inventory — GLOBAL GUARD
Każdy runtime subsystem/UI/AI path konsumujący protected information musi być jawnie sklasyfikowany jako `ProtectedInformationConsumer`/równoważny wpis z dozwolonym audience/purpose/projection source. Brak klasyfikacji lub bezpośredni protected raw-query bypass failuje repository-wide validation/CI.

World Pack, plugin, UI ani przyszła faza nie może ominąć Phase38 przez własną tabelę/flagę `visible`, raw SQL ani prompt. Ten inventory jest odpowiednikiem writer-inventory guardu dla odczytów chronionych informacji.

#### 10.9.14 Fail-closed i explainability
Unknown audience/policy/role/grant/disclosure/cross-campaign ref/corrupted lineage/projection schema -> `DENY` lub typed corruption/error, nigdy fallback PUBLIC. Same-name/legacy `hidden`, `gm`, `visibility` fields są compatibility inputami do validated adaptera, nie canonical authority.

Authorized debug może wyjaśnić `WHY ALLOW/DENY/PARTIAL` przez policy/role/grant/capability/evidence bez przekazywania samej internal explanation niewłaściwemu audience.

Phase 38 nie implementuje jeszcze pełnego Living World, Decision Engine, Combat Engine, rumor propagation, Temporal Engine ani World Pack Creator. Dostarcza uniwersalny boundary, z którego te systemy korzystają.

'''
anchor38 = 'ContextBuilder nie jest authority wiedzy. Docelowo pobiera holder-scoped `KnowledgeContextProjection`/typed Knowledge API zamiast definiować własną semantykę bezpośrednimi SQL query.\n\n## 11. Temporal Engine, Scheduler i Time Skip'
if '### 10.9 Phase 38 — Universal Visibility' not in arch:
    assert anchor38 in arch
    arch = arch.replace(anchor38, 'ContextBuilder nie jest authority wiedzy. Docelowo pobiera holder-scoped `KnowledgeContextProjection`/typed Knowledge API zamiast definiować własną semantykę bezpośrednimi SQL query.\n\n' + phase38_arch + '## 11. Temporal Engine, Scheduler i Time Skip', 1)

# Roadmap: global universality gate
road_global = '''## Globalny invariant uniwersalności przyszłych faz
Każda przyszła Core phase musi być projektowana jako world-agnostic i style-agnostic. Nie może być jakościowo uznana za gotową tylko dlatego, że działa w jednym/dwóch aktualnych World Packach. Naruto/Bleach/Wiedźmin/fantasy/sci-fi i inne światy są fixtures/adversarial cases, nie hardcoded modelem Core.

`CORE DEFINES UNIVERSAL CONTRACTS; WORLD PACK DEFINES SEMANTICS/CONTENT`.

Każdy przyszły Core acceptance audit sprawdza co najmniej:
- brak world-specific hardcoded authority/ról/ras/ranków/abilities/senses/secrecy classes;
- możliwość rozszerzenia przez typed/data-driven World Pack definitions bez tworzenia konkurencyjnego Core engine;
- działanie dla różnych typów World Actor/holder/group/organization, jeżeli domena ma zastosowanie;
- działanie w więcej niż jednym stylu gry i adversarial cases z odmienną metafizyką/technologią/information model;
- fail-closed behavior dla unknown World Pack rule/extension.

Wyjątek stanowią jawnie World-Pack-specific fazy integracyjne (obecnie Phase 80–84), których zadaniem jest test konkretnego packa przeciwko uniwersalnemu Core, a nie zmiana Core pod ten pack.

'''
road_anchor = 'Globalny status zmienia koordynator po sprawdzeniu implementacji, integracji, persistence/migration safety, regresji, full JVM/build/CI i wymaganych audytów. Raport workera, sama klasa/tabela albo zielony pojedynczy test nie oznaczają COMPLETE.\n\n## Aktualny baseline'
if '## Globalny invariant uniwersalności przyszłych faz' not in road:
    assert road_anchor in road
    road = road.replace(road_anchor, 'Globalny status zmienia koordynator po sprawdzeniu implementacji, integracji, persistence/migration safety, regresji, full JVM/build/CI i wymaganych audytów. Raport workera, sama klasa/tabela albo zielony pojedynczy test nie oznaczają COMPLETE.\n\n' + road_global + '## Aktualny baseline', 1)

road = road.replace('- Następny implementacyjny gate: **Phase 38 — GM/NPC/PC/player-visible knowledge separation + belief/reputation/access visibility boundaries, AUDIT FIRST**.', '- Następny implementacyjny gate: **Phase 38 — Universal Visibility, Access & Audience Boundary, AUDIT FIRST**.')
road = road.replace('- [-] 38. GM/NPC/PC/player-visible knowledge separation + belief/reputation/access visibility boundaries', '- [-] 38. Universal Visibility, Access & Audience Boundary — GM/NPC/PC/player/access/perception/disclosure isolation')

phase38_road = '''Wymagane dla Phase 38 — Universal Visibility, Access & Audience Boundary:
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
'''
old_tail = '''Dalsze Phase 38–47:
- FACT/BELIEF/NARRATIVE i GM/NPC/PC/player-visible/access separation;
- historical truth queries są temporalne, nie present-state substitution;
- Scheduler owns future evaluation points/deadlines, not guaranteed outcomes;
- retrieval jest bounded/iterative i context actor/time/visibility-safe;
- cloud context, gdy później aktywny, jest minimalny i sanitised zamiast whole-save export.
'''
if 'Wymagane dla Phase 38 — Universal Visibility' not in road:
    assert old_tail in road
    road = road.replace(old_tail, phase38_road, 1)

ARCH.write_text(arch, encoding='utf-8')
ROAD.write_text(road, encoding='utf-8')
print('patched architecture and roadmap')

# Phase 38 — Universal Visibility, Access & Audience Boundary — Acceptance

Status: **ACCEPTED / COMPLETE**

Data akceptacji: 2026-08-24

## Kanoniczny kandydat

- code-bearing SHA: `db2f836fe3575204d045e5d3a861e07bb61cd5a9`;
- integration PR: `#75`;
- baza przed integracją: master `e4b539fa87113c5ec46da2facf282c8004dc7e44`;
- exact-SHA validation: GitHub Actions run `32776574352`, job `97588891710` — **SUCCESS**;
- Phase 38 targeted: `117` testów, `0` failures, `0` skipped;
- full JVM: `1004` testy, `0` failures, `0` skipped.

Log exact-SHA potwierdził zarówno `TESTED_SHA=db2f836f...`, jak i oba immutable GREEN markers dla targeted i full JVM.

## Zaakceptowany zakres

Phase 38 dostarcza jeden world-agnostic, fail-closed boundary dla:

- jawnego `AudienceContext` i `PurposeContext` na chronionych odczytach;
- rozdzielenia FACT, KNOWLEDGE, ACCESS, PERCEPTION, DISCLOSURE i PRESENTATION;
- trusted principal/control/cognition oraz privileged runtime capabilities;
- canonical role/organization/clearance/grant/revocation authority;
- formal authorization oddzielonego od effective access;
- zapieczętowanych, principal/campaign/carrier-bound ścieżek dostępu;
- etapów nośnika `REACHABLE -> AVAILABLE -> OPENED -> DECODED -> COMPREHENDED`;
- policy access oddzielonego od signal/evidence-based perception;
- granular disclosure, uncertainty oraz typed `DISCLOSED/NO_DATA/DENIED/NOT_DISCLOSED/UNKNOWN/CORRUPTION`;
- rozdzielenia human player, active PC, World Actora i Knowledge Holdera;
- chronionych ContextBuilder/UI/local/cloud/image-generation/image-edit paths;
- authoritative access persistence przez TurnTransaction/Event Store oraz rollback/snapshot/replay;
- bounded, on-demand/batch evaluation bez globalnej macierzy actor × fact;
- repository-wide Visibility Consumer Inventory z jawnym audience, purpose, capability i projection source;
- fail-closed unknown/cross-campaign/corrupt/unsupported policy, audience, carrier i projection contracts.

## Domknięte findingi

- Canon character reads używają validated Engine API 1 compatibility adaptera: wymagane identity columns failują jako typed corruption, a nieobowiązkowe profile columns posiadają jawne defaults.
- World Actor reasoning korzysta z trusted perception runtime; caller-owned signal/capability descriptors nie są authority, a decoy/presented identity nie przecieka objective identity.
- Protected high-level repository facade zachowuje typed result states; presentation compatibility spłaszcza je dopiero za typed boundary.
- `AccessPath`, `AuthorizationDecision` i `EffectiveAccessDecision` nie posiadają caller-visible konstruktorów; dozwolony bypass powstaje wyłącznie w sklasyfikowanym runtime authority.
- Encrypted/niezdekodowany lub niezrozumiały carrier nie otrzymuje full access nawet przy formalnej autoryzacji.
- `AccessAuthorityChange` jest event-bearing, codec/replay-covered i przetestowany przez commit, rollback, snapshot, revoke oraz reconstruction bez phantom visibility.
- CampaignRepository i runtime writer inventories klasyfikują aktualną, typowaną powierzchnię projekcji.
- Visibility Consumer Inventory obejmuje raw canon-character adapter oraz authority entry points i failuje dla nowego nieklasyfikowanego bypassu.
- Bundled World Pack i legalny brak wyniku nie są mylone z corruption; `NOT_DISCLOSED` fixture ma legalną active-player identity.

## Werdykt

Nie pozostał otwarty blocker Phase 38. Kandydat spełnia kontrakt architektury i roadmapy, przechodzi targeted/full JVM na exact SHA i jest gotowy do integracji. Tymczasowy branch-only workflow oraz materializer użyte wyłącznie do obserwowalnej walidacji nie należą do zaakceptowanego runtime i są usuwane w cleanupie acceptance.

Następny implementacyjny gate: **Phase 39 — Temporal Engine historical truth**.

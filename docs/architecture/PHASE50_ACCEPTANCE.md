# Phase50 — Universal Mechanics & Combat repair-candidate acceptance

Status: **IMPLEMENTATION/INTEGRATION COMPLETE; FOCUSED GREEN; GLOBAL ACCEPTANCE PENDING**

Branch: `codex/phase-48-54-repair`
Base: `0ea25f1abb4b9e7639058df5c48466e4f5f3d70e`

## Accepted candidate scope

- one `UniversalCombatEngine`, not a parallel Player Domain;
- immutable snapshot and actor kinds PC/NPC/former PC/monster/summon/vehicle/group/unit;
- persistent non-player mechanical state;
- deterministic generation without player scaling or retroactive reroll;
- spatial feasibility, timing phases, detection, reactions/interrupts, clashes, contests, protection/resistance, target components and objectives;
- compositional effects routed through existing canonical owners and one TurnTransaction;
- hierarchical deterministic evidence and replay;
- staged multi-action composition with rollback on dependent failure;
- generic AoE, extreme-power individual-vs-group, group-vs-group and unit-vs-unit aggregate resolution without member expansion;
- Core-owned universal status definitions; World-Pack-owned ability bindings/chances.

## Hard boundaries

- AI proposes intent/proposal but never computes authoritative combat outcome.
- World Pack defines ability/rule content but cannot introduce a parallel combat engine.
- Phase63 still owns World Simulation LOD lifecycle, promotion/coarsening, background simulation and conservation between LODs.
- Narrative begins only after persisted commit evidence.

## Evidence

`Phase48To54RepairPlanTest` covers 500-target AoE distributions, status chance bindings, extreme mismatch, insufficient mismatch rejection, group-vs-group aggregation, deterministic replay, materialization, universal character definitions, multi-action and production combat/restart E2E. The final exact SHA, CI run and signed artifact are reported outside this self-contained commit after push.

Global `[x]` requires coordinator audit; this document records the repair candidate only.

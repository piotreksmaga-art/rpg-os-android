# Phase63 — pulled-forward aggregate combat seam

Status: **MINIMAL REQUIRED SLICE IMPLEMENTED / FULL PHASE63 MISSING**

## Function → owner → reason → implementation → status

| Function | Canonical owner | Why required now | Implementation | Status |
|---|---|---|---|---|
| Read an aggregate actor's active population for Phase50 snapshot | Phase63 | Phase50 cannot resolve group/unit combat without bounded population state | `AggregateCombatStatePort`, `AggregateMechanicalPopulation`, production snapshot bridge | implemented minimal seam |
| Resolve group-vs-group/unit-vs-unit casualties | Phase50 | Combat outcome belongs to Universal Combat Engine | `AggregateGroupEngagementResolver` | implemented in Phase50 |
| Persist aggregate status/casualty effect identity | Phase50 + existing transaction owners | Commit/restart/replay must not lose aggregate result semantics | `Phase50MechanicalStateStore`, effect materializer | implemented |

## Explicitly not pulled forward

- LOD0–3 simulation loop;
- aggregate promotion/coarsening;
- conservation across LOD transitions;
- background wars/economy/demography;
- World Actor scheduling, motivation or decision making;
- causal propagation from local combat into full strategic simulation.

Therefore Roadmap Phase63 remains `[ ] MISSING`. This slice exists only as the adapter input required by Phase50 and does not claim Phase63 completion.

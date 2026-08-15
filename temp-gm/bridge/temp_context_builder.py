#!/usr/bin/env python3
"""Bounded TEMP Context Builder for native CTX=8192.

TEMP-only: prepares read-only model context and never mutates RPG OS state.
"""

from __future__ import annotations

import json
import math
from copy import deepcopy
from typing import Any

CTX_WINDOW = 8192
RESPONSE_RESERVE = 1024
INPUT_BUDGET = CTX_WINDOW - RESPONSE_RESERVE

SEGMENT_BUDGETS = {
    "systemContract": 900,
    "sceneState": 1100,
    "playerSceneState": 700,
    "relevantNpcSceneState": 700,
    "npcKnowledge": 1000,
    "recentDialogueActions": 1800,
    "retrievedChronicleMemory": 700,
    "serializationReserve": 268,
    "responseReserve": RESPONSE_RESERVE,
}

SYSTEM_PROMPT = """Jesteś tymczasowym, lokalnym MG testowym RPG OS.
RPG OS jest jedynym źródłem autorytatywnego stanu kampanii. Ty nie zmieniasz Save, DB, statystyk, zasobów, umiejętności, ekwipunku, pieniędzy, własności, projektów ani authoritative events.
Nie twórz StatePatch, COMMIT ani PlayerChangeSet. Opisuj wyłącznie narrację i wyniki jawnie potwierdzone przez silnik.
NPC może znać tylko informacje zawarte w jego sekcji knowledge: observed, heard, told albo inferred. Nie przenoś globalnych faktów sceny do wiedzy NPC.
Jeżeli mechanika nie jest dostępna, użyj zachowania zgodnego z TEST_FALLBACK, bez udawania zmiany stanu.
Odpowiadaj po polsku, chyba że gracz wyraźnie używa innego języka.
""".strip()


def estimate_tokens(value: Any) -> int:
    """Conservative tokenizer-independent estimate for Polish/JSON TEMP context."""
    if not isinstance(value, str):
        value = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return max(1, math.ceil(len(value) / 3))


def _bounded_list(items: Any, token_budget: int, newest_first: bool = False) -> list[Any]:
    if not isinstance(items, list):
        return []
    source = list(reversed(items)) if newest_first else list(items)
    selected: list[Any] = []
    used = 0
    for item in source:
        cost = estimate_tokens(item)
        if used + cost > token_budget:
            continue
        selected.append(deepcopy(item))
        used += cost
    if newest_first:
        selected.reverse()
    return selected


def _safe_knowledge(value: Any) -> dict[str, list[Any]]:
    src = value if isinstance(value, dict) else {}
    return {
        "observed": deepcopy(src.get("observed", [])) if isinstance(src.get("observed", []), list) else [],
        "heard": deepcopy(src.get("heard", [])) if isinstance(src.get("heard", []), list) else [],
        "told": deepcopy(src.get("told", [])) if isinstance(src.get("told", []), list) else [],
        "inferred": deepcopy(src.get("inferred", [])) if isinstance(src.get("inferred", []), list) else [],
    }


def _build_npcs(items: Any) -> list[dict[str, Any]]:
    if not isinstance(items, list):
        return []
    result: list[dict[str, Any]] = []
    scene_used = 0
    knowledge_used = 0
    for raw in items:
        if not isinstance(raw, dict):
            continue
        scene_facts = deepcopy(raw.get("sceneFacts", {}))
        knowledge = _safe_knowledge(raw.get("knowledge", {}))
        scene_cost = estimate_tokens(scene_facts)
        knowledge_cost = estimate_tokens(knowledge)
        if scene_used + scene_cost > SEGMENT_BUDGETS["relevantNpcSceneState"]:
            scene_facts = {}
            scene_cost = estimate_tokens(scene_facts)
        if knowledge_used + knowledge_cost > SEGMENT_BUDGETS["npcKnowledge"]:
            knowledge = {"observed": [], "heard": [], "told": [], "inferred": []}
            knowledge_cost = estimate_tokens(knowledge)
        result.append({
            "npcUid": raw.get("npcUid"),
            "sceneFacts": scene_facts,
            "knowledge": knowledge,
        })
        scene_used += scene_cost
        knowledge_used += knowledge_cost
    return result


def _assert_segment_budget(name: str, value: Any) -> None:
    if estimate_tokens(value) > SEGMENT_BUDGETS[name]:
        raise ValueError(f"{name}_budget_exceeded")


def _trim_optional_context(safe: dict[str, Any], extra_tokens: int = 0) -> int:
    def total() -> int:
        return estimate_tokens(SYSTEM_PROMPT) + estimate_tokens(safe) + extra_tokens

    if total() <= INPUT_BUDGET:
        return total()

    safe["retrievedChronicleMemory"] = []
    while safe["recentDialogueActions"] and total() > INPUT_BUDGET:
        safe["recentDialogueActions"].pop(0)

    return total()


def build_context(snapshot: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(snapshot, dict):
        raise ValueError("context_must_be_object")

    scene_state = deepcopy(snapshot.get("sceneState", {}))
    player_identity = deepcopy(snapshot.get("playerIdentity", {}))
    player_scene_state = deepcopy(snapshot.get("playerSceneState", {}))
    _assert_segment_budget("sceneState", scene_state)
    if estimate_tokens({"playerIdentity": player_identity, "playerSceneState": player_scene_state}) > SEGMENT_BUDGETS["playerSceneState"]:
        raise ValueError("playerSceneState_budget_exceeded")

    safe = {
        "campaignUid": snapshot.get("campaignUid"),
        "worldPackUid": snapshot.get("worldPackUid"),
        "playerIdentity": player_identity,
        "sceneState": scene_state,
        "playerSceneState": player_scene_state,
        "relevantNpcs": _build_npcs(snapshot.get("relevantNpcs", [])),
        "recentDialogueActions": _bounded_list(
            snapshot.get("recentDialogueActions", []),
            SEGMENT_BUDGETS["recentDialogueActions"],
            newest_first=True,
        ),
        "retrievedChronicleMemory": _bounded_list(
            snapshot.get("retrievedChronicleMemory", []),
            SEGMENT_BUDGETS["retrievedChronicleMemory"],
            newest_first=True,
        ),
        "availableTestCapabilities": deepcopy(snapshot.get("availableTestCapabilities", [])),
        "engineConfirmedResults": deepcopy(snapshot.get("engineConfirmedResults", [])),
    }

    total = _trim_optional_context(safe)
    if total > INPUT_BUDGET:
        raise ValueError("context_budget_exceeded_after_safe_trimming")

    safe["_tempBudget"] = {
        "contextWindow": CTX_WINDOW,
        "inputBudget": INPUT_BUDGET,
        "responseReserve": RESPONSE_RESERVE,
        "estimatedContextTokens": total,
    }
    return safe


def build_messages(snapshot: dict[str, Any], player_message: str, mode: str) -> list[dict[str, str]]:
    if not isinstance(player_message, str) or not player_message.strip():
        raise ValueError("player_message_required")

    context = build_context(snapshot)
    context.pop("_tempBudget", None)

    context_label = "\n\nREAD-ONLY TEMP CONTEXT:\n"
    mode_suffix = "\n\nTEMP response mode: " + mode + ". canonicalMutation=false."
    extra_tokens = estimate_tokens(context_label) + estimate_tokens(mode_suffix) + estimate_tokens(player_message)

    total = _trim_optional_context(context, extra_tokens=extra_tokens)
    if total > INPUT_BUDGET:
        raise ValueError("turn_budget_exceeded_after_safe_trimming")

    context["_tempBudget"] = {
        "contextWindow": CTX_WINDOW,
        "inputBudget": INPUT_BUDGET,
        "responseReserve": RESPONSE_RESERVE,
        "estimatedTurnInputTokens": total,
    }
    system = SYSTEM_PROMPT + context_label + json.dumps(context, ensure_ascii=False, separators=(",", ":")) + mode_suffix
    return [
        {"role": "system", "content": system},
        {"role": "user", "content": player_message},
    ]

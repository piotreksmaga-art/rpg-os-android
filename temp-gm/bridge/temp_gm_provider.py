#!/usr/bin/env python3
"""TEMP-only GM provider contract for WORK-20260815-001.

This module is intentionally isolated from canonical RPG OS AI/runtime contracts.
It has no authority to mutate campaign state.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any

RESPONSE_MODES = {"NARRATIVE_ONLY", "ENGINE_CONFIRMED", "TEST_FALLBACK"}


@dataclass(frozen=True)
class TempGmResponse:
    provider_id: str
    mode: str
    narrative: str
    usage: dict[str, Any]

    def as_dict(self) -> dict[str, Any]:
        return {
            "providerId": self.provider_id,
            "mode": self.mode,
            "narrative": self.narrative,
            "canonicalMutation": False,
            "usage": self.usage,
        }


class TempGmProvider:
    """Minimal non-authoritative TEMP provider contract."""

    provider_id: str

    def metadata(self) -> dict[str, Any]:
        raise NotImplementedError

    def status(self) -> str:
        raise NotImplementedError

    def generate(self, *, messages: list[dict[str, str]], mode: str, max_tokens: int | None = None) -> TempGmResponse:
        raise NotImplementedError


class LocalBielikTempGmProvider(TempGmProvider):
    provider_id = "BIELIK_4_5B_V3"

    def __init__(self, runtime_url: str, timeout_seconds: float = 180.0):
        self.runtime_url = runtime_url.rstrip("/")
        self.timeout_seconds = timeout_seconds

    def metadata(self) -> dict[str, Any]:
        return {
            "id": self.provider_id,
            "name": "Bielik 4.5B v3",
            "runtime": "llama.cpp",
            "backend": "Vulkan",
            "format": "GGUF",
            "quantization": "Q4_K_M",
            "contextWindow": 8192,
            "kvKey": "f16",
            "kvValue": "f16",
            "batch": 64,
            "ubatch": 64,
            "parallel": 1,
            "gpuLayers": 99,
        }

    def status(self) -> str:
        try:
            with urllib.request.urlopen(self.runtime_url + "/health", timeout=1.5) as response:
                body = json.loads(response.read().decode("utf-8"))
                return "READY" if response.status == 200 and body.get("status") == "ok" else "ERROR"
        except Exception:
            return "OFFLINE"

    def generate(self, *, messages: list[dict[str, str]], mode: str, max_tokens: int | None = None) -> TempGmResponse:
        safe_mode = mode if mode in RESPONSE_MODES else "NARRATIVE_ONLY"
        payload: dict[str, Any] = {
            "messages": messages,
            "temperature": 0.3,
            "stream": False,
            "cache_prompt": True,
        }
        if max_tokens is not None:
            payload["max_tokens"] = int(max_tokens)

        request = urllib.request.Request(
            self.runtime_url + "/v1/chat/completions",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
            body = json.loads(response.read().decode("utf-8"))

        narrative = str(body["choices"][0]["message"]["content"])
        return TempGmResponse(
            provider_id=self.provider_id,
            mode=safe_mode,
            narrative=narrative,
            usage=body.get("usage", {}),
        )


def provider_error_payload(provider_id: str, error: Exception | str) -> dict[str, Any]:
    detail = error if isinstance(error, str) else type(error).__name__
    return {
        "error": "runtime_failure",
        "detail": str(detail),
        "providerId": provider_id,
        "mode": "TEST_FALLBACK",
        "canonicalMutation": False,
    }

# RPG OS — Test GM Harness

Status: NON-PRODUCTION / READ-ONLY PLAYTEST HARNESS

Purpose: provide a clean entry point for a fresh ChatGPT session acting as a test Game Master against the current RPG OS repository.

This folder is NOT a second source of truth. It does not copy runtime contracts or replace canonical architecture. The GM must always read current canonical files from their original repository locations.

Start here:

1. `GM_TEST_BOOTSTRAP.md`
2. `GM_TEST_RULES.md`
3. `ACCEPTED_RUNTIME_GUIDE.md`

Repository priority remains:

`current runtime/repository + newest explicit user decision > MASTER > ROADMAP > older docs/TODO/chat memory`.

The Test GM is read-only. It may inspect repository code/docs and conduct a conversational playtest, but it must not modify production runtime, campaign databases, roadmap status, acceptance records, or other workers' files.

The Test GM should use accepted phases as implemented mechanics. For roadmap phases that are not globally ACCEPTED/COMPLETE, it may use the intended logic from `docs/RPG_OS_MASTER_ARCHITECTURE.md` only as a temporary playtest fallback, and must clearly distinguish that fallback from implemented runtime behavior.

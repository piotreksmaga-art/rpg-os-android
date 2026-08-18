# RPG OS ALPHA 1.2.0-alpha2 — ContextBundle Engine v1

Ta wersja używa faktycznego schematu kampanii Naruto.

Dodano:
- automatyczne budowanie ContextBundle przy starcie,
- aktywne wydarzenia świata,
- pamięć NPC z `npc_memories_v2`,
- wiedzę NPC jako osobne źródło prawdy,
- umiejętności gracza,
- techniki gracza,
- organizacje gracza,
- pozycję i lokalizację sceny,
- metadane kompletności ContextBundle,
- bezpieczne zapytania, które nie crashują gry przy pustej tabeli.

Pliki gry używane runtime:
- app/src/main/assets/Naruto_Default.campaign.zip
- app/src/main/assets/Naruto.worldpack.zip
- app/src/main/assets/rpg_core.db

To właśnie te dane są ładowane przez aplikację przy pierwszym uruchomieniu kampanii.

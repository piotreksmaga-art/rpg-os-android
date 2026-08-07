# Backend TODO — v0.2

Required endpoint:
POST /v1/gm/turn

Android sends:
- player input
- compact ContextBundle
- campaign/chapter id

Backend returns:
- narration
- 0–3 choices
- structured StatePatch
- chapter events

Before applying StatePatch on Android:
1. validate table against source-of-truth registry;
2. validate key/column allow-list;
3. open transaction;
4. apply operations;
5. update chapter manifest;
6. commit or rollback.

# Package Compatibility v1

Campaign import requires:
- campaign.db
- campaign.json
- SQLite integrity = ok
- core_api = 1

World Pack import requires:
- world.db
- worldpack.json
- SQLite integrity = ok
- engine_api = 1

Invalid imported packages are removed automatically.

Future:
- migration chains for API 2+
- cryptographic signatures for trusted world packs
- dependency resolution between packs

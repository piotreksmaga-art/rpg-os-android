# RPG OS Package Manager v1

World Pack:
- directory ending in `.worldpack`
- requires `world.db`
- may include manifest/assets/migrations

Campaign:
- directory ending in `.campaign`
- requires `campaign.db`
- may include backups/exports

Import rules:
- unpack into app-private storage
- reject ZIP path traversal
- validate required database
- later versions will validate manifest and core API compatibility

Export:
- recursively zip the campaign folder
- preserve backups and manifests

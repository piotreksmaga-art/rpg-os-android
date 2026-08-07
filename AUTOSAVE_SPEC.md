# Autosave v1

After a successful StatePatch:
1. update the campaign DB in one transaction;
2. create/update chapter manifest;
3. compute a SHA-256 state hash;
4. copy campaign.db to the campaign `backups/` directory;
5. refresh UI state.

A failed patch never creates a completed chapter save.

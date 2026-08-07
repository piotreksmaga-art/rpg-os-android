# Restore v1

Before replacing campaign.db with a selected backup:
1. copy current campaign.db to `backups/pre_restore_<timestamp>.db`;
2. copy selected backup over campaign.db;
3. refresh all visible state.

This provides one-step recovery if the selected backup was wrong.

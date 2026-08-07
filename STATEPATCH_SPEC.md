# StatePatch Engine v1

A patch is a list of insert/update/delete operations.

Validation:
- table must be writable by Source of Truth policy;
- legacy/reference tables are rejected;
- update/delete require keys;
- all operations run inside one transaction;
- any exception causes rollback.

The AI/backend never receives permission to execute arbitrary SQL.
It only returns structured operations.

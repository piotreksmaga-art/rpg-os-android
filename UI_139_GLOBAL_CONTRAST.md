# UI 139 — Global Contrast

Version: `1.2.0-alpha5-ui139-contrast`
VersionCode: `139`

## Changes

- Global light content color for the RPG OS dark theme.
- Gradient screens explicitly inherit `MaterialTheme.colorScheme.onBackground`.
- Legacy and future `Text()` components without an explicit color remain readable on the dark blue/teal RPG OS background.
- Existing explicit colors on buttons and special components remain unchanged.

## Validation

`:app:assembleDebug` completed successfully in GitHub Actions before merge.

# Legacy UI Functionality Inventory

This is the behavior inventory captured before replacing the XML/View frontend.
The Compose rewrite must preserve these capabilities, even if navigation and
presentation change completely.

## App shell

- Single activity with a home screen, editor screen, and About screen.
- Top-level session drawer can be opened from every screen.
- About can be opened from the top bar and returns to the previous screen.
- Back navigation: editor category -> editor categories -> exit confirmation;
  About -> previous screen; home -> system back.
- English and Simplified Chinese can be switched from About.
- Update check runs off the main thread and can open the release page.

## Home / import flows

- Open a save through Android's document picker.
- Receive a save by transfer code and PIN.
- Create a new save for EN, JP, TW, or KR.
- Import a save shared from another app.
- Root read from an installed game, including detected game packages and custom
  package entry.
- Show editor-area categories and the save safety notice.

## Session management

- Import/new session creates an independent working copy.
- Session list supports switching, renaming, long-press actions, and deletion.
- Working saves are persisted atomically in private app storage.
- Session history stores the initial state and up to 50 later operations.
- History supports undo, redo, and restoring a selected snapshot.

## Editor navigation

- Display file name, byte length, and a short SHA-256 display fingerprint.
- Show unsupported-version warning when appropriate.
- Browse categories and concrete features with search.
- Every editor mutates `SaveDocument`; edits persist to the active session.

## Editor capabilities

- Save management: export, upload/transfer, exit/keep session.
- Region conversion and game-version conversion.
- Cat Food, XP, tickets, shards, NP, Leadership, battle items, Catseyes,
  Catfruit, Catamins, event items, treasure chests, and related inventory.
- Cats, forms, levels, plus levels, talents, Cat Guide rewards, storage, and
  Basic Upgrades.
- Story, treasure, Aku, Challenge/Dojo, Enigma, Gauntlet, event, Legend Quest,
  Tower, Zero Legends, and other map data.
- Gamatoto, Ototo, Cat Shrine, gacha seeds, repairs, menu fixes, lineups,
  gambling, guides, rewards, missions, medals, Pass/restart data.
- Account inquiry code and password refresh token operations.
- Replacement-account creation and managed-item upload.
- Root write to installed game with region/custom-package selection.

## Export and automation

- Export current working document through Android document creation.
- If the document provider is unsupported, export through a FileProvider share
  fallback.
- Optional loopback-only API supports status, import, export, edit, reset, and
  CORS/download headers for local automation tests.

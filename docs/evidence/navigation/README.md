# Follow-up navigation regression

2026-10-02: all 13 WorkspaceUiTest cases pass after live recoverable cell drafts, explicit Revert confirmation, cell-level Back handling, search focus and contextual/minimum-height text editor accessibility changes.

Coverage includes tapping another cell, Back, sheet switch, home/resume, toolbar-open warning, incoming-document warning, reverting, search focus and search Back precedence. One initial test dispatched Back before Compose applied the callback state; waiting for the displayed field and Compose idle fixed test synchronization.

This is a source/test follow-up on `fix/cell-draft-navigation`, not an updated delivered APK. The 1,433,399-byte baseline remains unchanged. Full lint, all engine/safety tests and a new minified artifact have not been rerun for this branch. Real IME, TalkBack and physical-device behavior remain unverified.

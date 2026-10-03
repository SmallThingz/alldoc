# 0.1.1 upgrade fixtures

`recovery-v0.1.1-budget.xlsx` is the exact `fixtures/budget.xlsx` blob from alldoc commit `31ee2eaf` (the 0.1.1 baseline). `recovery-v0.1.1-budget.json` follows that version's real recovery schema (version 2). Its `b4` target was obtained by compiling and running that commit's actual `DocumentEngine.java` against the exact original: the old value was `Paper`.

`recovery-v0.1.1-external-workbook.xlsx` changes the same workbook's first sheet relationship to an external target. The actual 0.1.1 engine accepted it because it used numbered worksheet parts; the new engine rejects it. No external target is fetched. This verifies that upgrade quarantine happens before stricter new parsing and survives a rejection.

All values and document content are synthetic. Regression tests verify original-byte preservation, every draft value in JSON/text, archive deduplication, corrupt-archive refusal, independent archive lifetime, and the user-visible export route with saved-state restoration.

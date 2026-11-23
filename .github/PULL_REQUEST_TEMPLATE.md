## Summary

- What changed and why?
- Related issue(s) or companion `LLM-4-People/hermes-agent` PR(s) (if any):

## Verification

List the exact commands run and their results:

- [ ] `python3 scripts/fast_check.py` (includes `scripts/docs.py --check`, CI tests, server, deploy, and verification suites)
- [ ] `./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`
- [ ] `./gradlew :app:connectedDebugAndroidTest` (when touching Android UI, lifecycle, permissions, or storage)
- [ ] `go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12` and `shellcheck ci/*.sh` (when touching workflows or shell scripts)

## Architecture and quality checklist

- [ ] Native Android Views only (0 `WebView`, 0 unnecessary third-party Android dependencies)
- [ ] Centralized logging only (`Diagnostics` on Android, `server/logging_util.py` on Python; 0 direct `android.util.Log`)
- [ ] Runtime bounds read from `HermesConfig` / `config.xml` (no hardcoded static configurables)
- [ ] Bilingual FR / EN 1:1 XML parity (`values/` and `values-en/`) with everyday, jargon-free copy
- [ ] Fail-closed security & profile isolation preserved (opt-in permissions, zero-leak push/widgets/dialogs, profile wipe on forget/lock)
- [ ] Deterministic positive + negative regression tests added (0 `Thread.sleep`)
- [ ] Documentation templates updated in `docs/sources/` and rendered with `python3 scripts/docs.py`

<!-- Generated from docs/sources/.github/PULL_REQUEST_TEMPLATE.md.in by scripts/docs.py. -->

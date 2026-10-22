# libcrippy — Changelog

What happened, one entry per roadmap item. `AGENTS.md` is why, and an entry links to a decision rather
than restating it.

## Unreleased

### A client-only library, and its coordinates — 2026-10-22

- The artifacts are `com.systemhalted.crippy:crippy-client`, which is all an app declares, and
  `com.systemhalted.crippy:crippy-contract`, which the client brings with it and crippy's service
  compiles against. The modules are renamed to match, because a composite build substitutes on the
  project's name (L1).
- minSdk 27 is a hard limit and `com.systemhalted.crippy.api` is the contract's package, both confirmed
  by the user (L1, L2).

### Scaffold — 2026-10-22

- The Gradle project: `:contract` and `:client`, Android libraries at minSdk 27 in explicit API mode,
  as `com.systemhalted.libcrippy:contract` and `:client` (L1) — renamed the same day, above.
- The contract's first method: `ICrippyService.contractVersion()`, transaction 1, with
  `ICrippyService.VERSION = 1` beside it. `WireNamesTest` pins the interface token, the transaction
  code and the version (L2).
- crippy's toolchain and CI: the same wrapper, plugins and versions, ktlint, detekt, Kover, the
  lefthook hooks and `scripts/hooks/`, `ci.yml` and `commit-policy.yml` with crippy's pinned actions,
  and `assemble` added to CI (L3).
- Coverage gates on both modules, with the aidl tool's generated code excluded, and the diff-coverage
  gate on pull requests (L4).
- Dependency verification metadata, `sha256`, 541 artifacts.
- Apache-2.0 (crippy D115, flagged for veto there).

**Known issues.** The client has no code, so its gate passes over nothing until it does. GitHub
Actions has never run, because the repository has no remote.

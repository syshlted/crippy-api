# crippy-api — Changelog

What happened, one entry per roadmap item. `AGENTS.md` is why, and an entry links to a decision rather
than restating it.

## Unreleased

### The shared-memory spike — 2026-10-23

- `SharedMemorySpikeTest` and `SpikeService`: a region handed out read-only, and every way of writing through it
  tried, on the G6, the S9, the A20 and emulators at API 27 and 36. Refused everywhere; crippy's copy unchanged (L14).
- A 64 KiB block through the region against one through AppFuse: 3× to 13× faster as transport, about 2.5× to 3× with
  crippy's decrypt counted (L14).

**Known issues.** memfd-backed `SharedMemory`, which a vendor ROM at API 34+ may use, has not been measured: every
target here was ashmem. The read path is not built (L14, flagged).

### Device tests — 2026-10-23

- `CrossProcessTest`: the client over a real Binder against a stand-in service in a process of its own, and one built
  against an older contract. An unknown transaction reads as 0 over a real `BinderProxy` (L2); a process that dies
  ends its sessions and the connection comes back; a write refused part way is told the reason, and the broken
  pipe is `EPIPE`, not SIGPIPE (L13).
- `AgainstCrippyTest`: the client against the crippy on the phone, from another package, pressing crippy's unlock
  and consent screens through the accessibility tree, and only crippy's (L13).
- `instrumented.yml`, at API 27 and 36; `scripts/device/run-device-tests.sh` for phones.
- `androidx.test:runner` 1.7.0 and `androidx.test.ext:junit` 1.3.0: 14 components added to the verification metadata,
  none removed, all in crippy's file (L13).
- 10 of 10 stand-in cases on the G6, S9, A20 and the API 27 emulator; 10 of 10 against crippy on the S9 and the A20.

**Bugs found.**

- crippy answered a preview larger than the size asked, which the contract forbids; fixed in crippy (crippy D121).
- `CrippyConnectionFailuresTest` failed when the looper ran first: Robolectric disconnects with no name, and the
  client declared it non-null. The three callbacks that never read it accept null (L13).

**Known issues.** The suite against crippy has not run on the G6 or the S24, and `instrumented.yml` has not run on
GitHub.

### The first push, and commit-policy checks a force push — 2026-10-23

- **Bug fixed.** A push whose `before` is not in the runner's clone made `commit-policy` check no commit and pass.
  A force push is that push: its `before` is the commit it replaced, which the checkout does not fetch. `git
  rev-list` refused the range inside a `for` list, where `set -e` does not reach. Now such a push is checked from
  every commit its head reaches, and a range `git rev-list` refuses fails the job. Found before the first push,
  which replaces GitHub's initial commit (L6) and so is a force push. The workflow is crippy's (L3), which has the
  same fix.
- Measured with the workflow's own scripts, in a clone holding only what the runner fetches: a force push checks
  all 14 commits, a new branch 14, a push from three commits back 3, a pull request two commits ahead 2. The old
  scripts checked 0 on the force push and passed.
- **Pushed**, by a force push leased on GitHub's `c3b4c92`, which it replaced (L6). On GitHub the run logged
  that `c3b4c92` was not in its clone, checked all 15 commits, and GitHub reports every one signed. `ci`,
  `codeql` for both languages and `dependencies` passed, with no code-scanning alert.

### The client — 2026-10-23

- `Crippy.connect` checks crippy's signing certificate before it binds, against the app's own key or a pinned
  certificate, over crippy's signing history at every API level; then binds by action and asks the version (L12).
- `CrippyConnection.openSession` and `CrippySession`: every request the contract has, as a `suspend` function
  answering a `CrippyResult`; events as a `SharedFlow`; writes from a coroutine, abandoned if it fails or is
  cancelled; previews cancelled in crippy when the caller is (L12).
- `Crippy.authorizationIntent`, `authorizationResult` and `unlockIntent` for crippy's two screens (L12).
- kotlinx-coroutines 1.10.2, an `api` dependency, locked and in the verification metadata: five components added,
  none removed (L12).
- 83 tests at API 27 and 36, over the 90/85 coverage gates.

**Bugs fixed while building it.**

- A success crippy sent without its payload threw `IllegalArgumentException` at the app; it answers
  `Failure.FAILED` now (L12).
- Closing a connection left its sessions open in crippy (L12).
- A write crippy refused part way reported `CANCELLED` rather than crippy's reason (L12).
- `PublicSurfaceTest`, L5's guard, read no classes, because AGP hands a unit test a jar; reading it found an
  internal constructor that named the contract to Java callers (L12).

**Known issues.** Not run against a real crippy on a phone (roadmap item 6).

### The contract's first surface — 2026-10-22

- `ICrippyService` gains `openSession`; `ICrippySession` carries everything a file manager does: stat, list in
  pages, measure, read, write, create, rename, move, delete, set times, previews, handoffs, space and lock. Three
  oneway listeners report state, the end of a write and a preview (L11).
- Twelve structured parcelables, each with a status that is never 0 (L2, L11).
- `WireNamesTest` pins every interface token and every method's transaction code; `ParcelableEvolutionTest` runs
  the rule that a parcelable's fields are only ever appended.

### Dependency scanning — 2026-10-22

- `dependencies.yml`: OSV-Scanner over the two release runtime classpaths, failing on any advisory,
  and over every artifact the build pins, reported to the Security tab (L10).
- The release runtime classpaths are locked in each module's `gradle.lockfile`, in strict mode (L10).
- `gradle/osv-scanner.toml` accepts the 12 build-tooling advisories, each with why it cannot be reached
  from a library build, and each expiring on 2027-01-22 (L10).
- Both scans checked both ways locally: a planted vulnerable runtime dependency fails the first, and an
  advisory taken out of the triage file comes back in the second (L10).

### Kotlin 2.4.21 — 2026-10-22

- The build compiles with Kotlin 2.4.21 rather than the 2.2.10 AGP depends on, for GHSA-r937-wjx7-w2jp,
  code execution through the Kotlin build cache. The same move in crippy as its D119 (L9).
- The CodeQL build runs the Kotlin compiler in-process, which 2.4 needs and 2.2 did not (L7, L9).
- Dependency verification metadata regenerated from an empty file, because regenerating over it kept
  the whole Kotlin 2.2.10 toolchain: 348 components, all of them in crippy's (L9).

**Known issues.** The Kotlin an app needs to compile against the client is now 2.3 or newer by
default, still undecided (L3).

### The contract's behavior is tested — 2026-10-22

- `UnansweredTransactionTest` runs L2's claim through the generated proxy over a real `Parcel`: a
  binder that does not know transaction 1 answers 0 with no exception, beside a control that answers
  the version. At API 27 and 36, under Robolectric 4.17 and JUnit's vintage engine (L8).
- SDK 36 needs `--add-exports=java.base/jdk.internal.access=ALL-UNNAMED` on JDK 21; the test task
  carries it (L8).
- Measured by breaking them: a failing test fails `allUnitTests`, and untested client code fails
  `koverVerify` (L8).
- Dependency verification metadata: 619 artifacts, up from 541, all of them in crippy's.

### Code scanning — 2026-10-22

- `codeql.yml`: CodeQL's `security-extended` suite over the Kotlin and Java the release build compiles,
  and over the workflows, on pull requests, pushes to `master` and weekly. Checked locally with the CLI
  the pinned action uses: a planted weak cipher in Kotlin and a planted script injection in a workflow
  were both found, and today's code and workflows have no findings (L7).
- Secret scanning and push protection are on for the GitHub repository (L7).
- Dependency scanning is not added: all 13 known advisories in the frozen dependency set are in build
  tooling, and nothing that ships has one. Scanning what ships is in `ROADMAP.md` (L7).

### The repository is crippy-api — 2026-10-22

- Renamed from `libcrippy`: the repository, its directory and the root project's name. The artifacts,
  their group and both packages are unchanged (L6).
- The remote is `git@syshlt-github:syshlted/crippy-api.git`. Nothing has been pushed.

### The client hides the contract — 2026-10-22

- `crippy-client` depends on `crippy-contract` with `implementation`, so an app sees only the client's
  own types (L5). A guard that the client's public signatures name no contract type is owed before
  its first public type.
- The group stands, because the user owns `systemhalted.com`; the artifacts will go to Maven Central
  (L1).

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

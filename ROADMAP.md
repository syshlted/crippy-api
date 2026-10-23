# crippy-api — Roadmap

What is next, and in what order. `AGENTS.md` is why; `CHANGELOG.md` is what happened. When this file
and `AGENTS.md` disagree, `AGENTS.md` wins.

Severities, as in crippy: **S1** loses a user's data or lets the wrong app read it · **S2** a wrong
answer a user cannot tell from a right one · **S3** wrong under a narrow race, or a cost nobody chose ·
**S4** debt.

## Now

The scaffold is done (L1–L4). The design every step below implements is crippy's: D113 for the
transport, D114 for who may read the vault, D115 for the repositories, D117 and D118 for the handoff.
crippy's own half — the service, the consent screen, the grant table — is crippy's build-order step 13,
item 3.

1. **The spike crippy D113 owes**, on the four phones: hand a `SharedMemory` region over Binder
   read-only and confirm the client cannot make its mapping writable, read what `/proc/self/fd` calls
   it, and measure a Binder round trip. It decides whether the client gets a read path that skips
   AppFuse. Where it lives is an open question (`AGENTS.md`).
2. ~~**The contract's first real surface**: opening a session, the authorization request crippy's own
   Activity answers, listing in pages, and opening a document as a proxy descriptor (crippy D113,
   D114). Each method appended after `contractVersion()`, and each recorded here as it lands.~~ **Done
   2026-10-22 (L11)**, with writes, move, previews, handoffs, times and sizes as well. Three points are flagged
   for veto there.
3. ~~**The client library**~~ **Done 2026-10-23 (L12)**, binding by action rather than by component (L11) and
   reading crippy's signing history itself rather than through `hasSigningCertificate`, which no unit test can
   reach. Five points are flagged for veto there.
4. ~~**`PROTOCOL.md` grows with 2 and 3**~~ **Done 2026-10-23**: an author can integrate from it alone.
5. **The registry** (crippy D114, D115): its format, a validator CI runs on every pull request, the
   snapshot crippy builds in, and the fingerprints file inside a GPG-signed tag.
6. ~~**Device tests** for the client against a real crippy, which needs crippy's APK on the device or
   emulator; then `instrumented.yml`.~~ **Done 2026-10-23 (L13)**: a stand-in suite on three phones and the API 27
   emulator, the suite against crippy on the S9 and the A20, and `instrumented.yml` at API 27 and 36. Left: the
   suite against crippy on the G6, which has no crippy and a secure keyguard, and on the S24; and the workflow's
   first run on GitHub, which waits for a push.
7. ~~**Dependency scanning of what ships** (L7)~~ **Done 2026-10-22 (L10)**, with the build's tooling
   reported as well, against a triage file. The one advisory with a way in was fixed by Kotlin 2.4.21
   (L9).
8. **Re-read `gradle/osv-scanner.toml` by 2027-01-22**, when every entry in it expires and its
   advisory comes back as an alert. Renew an entry only if its reason still holds.

## Before the first release

- **The append-only gate** (L2): every interface's methods in the last released tag must be a prefix of
  its methods now, checked in CI. Until a release exists there is nothing to compare against.
- **An API dump check** for the Kotlin surface — the binary-compatibility validator, or Kotlin's own
  ABI validation once AGP's built-in Kotlin exposes it.
- **The Kotlin floor for consumers** (L3): `languageVersion`, `apiVersion`, `coreLibrariesVersion`. Since
  L9 the default is an app on Kotlin 2.3 or newer, receiving stdlib 2.4.21.
- **Publishing** (L1): Maven Central, with `com.systemhalted` verified by a DNS record on
  `systemhalted.com`; the signing of artifacts; and reproducible AARs checked as crippy's release is
  (crippy D116).
- **Consumer R8 rules**, if a `Parcelable` or anything reached by name needs them.

## When the repository is pushed

- ~~**GitHub's initial commit**, `c3b4c92`, shares no history with `master` (L6).~~ **Replaced by a force
  push, 2026-10-23.**
- **Branch protection on `master`**, **refusing a force push again**, which was turned off for the first
  push: signed commits, a review, `ci`, `commit-policy`, both `codeql` jobs and the `dependencies` job for
  what ships required, and code scanning merge protection, so a pull request that adds a high or critical
  alert — from CodeQL or from the build-tooling scan — cannot merge (L7, L10).
- **Merge commits or rebases only**, never a squash (`AGENTS.md`, *Repo conventions*).
- **Actions must be pinned to a full commit SHA**, a repository setting that is off: every workflow
  already pins (crippy D7), and the setting makes an unpinned one fail rather than run.
- **The first `codeql` run on `master` is the baseline** a pull request's results are compared with.
  ~~Check that both languages report~~ — **they do, from 2026-10-23's push, with no alert**; `java-kotlin`
  scanned all 9 of the client's main Kotlin files. **Still to check:** that it lists the aidl tool's Java
  (L7). The run log counts only Kotlin, so the answer is on the repository's CodeQL status page.

## Bug queue

Empty.

## After crippy-api

**The file manager**, in `../crippy-file-manager`, built against this library as any other app is
(crippy D112, D115).

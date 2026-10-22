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
2. **The contract's first real surface**: opening a session, the authorization request crippy's own
   Activity answers, listing in pages, and opening a document as a proxy descriptor (crippy D113,
   D114). Each method appended after `contractVersion()`, and each recorded here as it lands.
3. **The client library**: binding to crippy's explicit component; checking crippy's signing
   certificate — `hasSigningCertificate` from API 28 and a SHA-256 of `PackageInfo.signatures` at 27,
   against a certificate the library carries (crippy D114); refusing any method above the service's
   `contractVersion()`, because an older service answers zero rather than an error (L2); the session's
   `linkToDeath`; and launching the consent Activity with `startActivityForResult`. The contract's
   types are hidden behind the client's own (L5), so each contract type an app needs gets a client
   type beside it, and **before the first public type lands, a test that no public or protected
   signature in the client names `com.systemhalted.crippy.api`**.
4. **`PROTOCOL.md` grows with 2 and 3**, so an author can integrate without reading either decision log.
5. **The registry** (crippy D114, D115): its format, a validator CI runs on every pull request, the
   snapshot crippy builds in, and the fingerprints file inside a GPG-signed tag.
6. **Device tests** for the client against a real crippy, which needs crippy's APK on the device or
   emulator; then `instrumented.yml`.
7. **Dependency scanning of what ships** (L7): Gradle dependency locking on the two release runtime
   classpaths, and OSV-Scanner over those lockfiles with its SARIF in the Security tab. Today that is
   `kotlin-stdlib` and `annotations`, with no advisories, so it starts green and fails the day a
   vulnerable runtime dependency arrives. The scanner as a release binary checked against its
   published SHA-256: `osv-scanner-action` runs a Docker image by tag, which pinning the action's SHA
   does not pin (crippy D7). The 13 build-tooling advisories go with the next toolchain bump, made in
   step with crippy (L3).

## Before the first release

- **The append-only gate** (L2): every interface's methods in the last released tag must be a prefix of
  its methods now, checked in CI. Until a release exists there is nothing to compare against.
- **An API dump check** for the Kotlin surface — the binary-compatibility validator, or Kotlin's own
  ABI validation once AGP's built-in Kotlin exposes it.
- **The Kotlin floor for consumers** (L3): `languageVersion`, `apiVersion`, `coreLibrariesVersion`.
- **Publishing** (L1): Maven Central, with `com.systemhalted` verified by a DNS record on
  `systemhalted.com`; the signing of artifacts; and reproducible AARs checked as crippy's release is
  (crippy D116).
- **Consumer R8 rules**, if a `Parcelable` or anything reached by name needs them.

## When the repository is pushed

- **GitHub's initial commit**, `c3b4c92`, shares no history with `master` (L6). Replacing it with a
  force push loses only a `LICENSE` identical to ours; merging it with `--allow-unrelated-histories`
  keeps a commit signed by GitHub rather than the author, which `commit-policy` never checks because it
  is already on the remote. **Recommended: replace it**, before anything else lands on GitHub. The
  user decides.
- **Branch protection on `master`**: signed commits, a review, `ci`, `commit-policy` and both `codeql`
  jobs required, and code scanning merge protection, so a pull request that adds a high or critical
  alert cannot merge (L7).
- **Merge commits or rebases only**, never a squash (`AGENTS.md`, *Repo conventions*).
- **Actions must be pinned to a full commit SHA**, a repository setting that is off: every workflow
  already pins (crippy D7), and the setting makes an unpinned one fail rather than run.
- **The first `codeql` run on `master` is the baseline** a pull request's results are compared with;
  check that both languages report, and that `java-kotlin` lists the aidl tool's Java (L7).

## Bug queue

Empty.

## After crippy-api

**The file manager**, in `../crippy-file-manager`, built against this library as any other app is
(crippy D112, D115).

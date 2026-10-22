# libcrippy — Roadmap

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
   `linkToDeath`; and launching the consent Activity with `startActivityForResult`. Whether the
   contract's types are part of the client's public surface, as `api` makes them today, or hidden
   behind the client's own, is decided here: an app integrating a client-only library (L1) should not
   need to see AIDL types at all.
4. **`PROTOCOL.md` grows with 2 and 3**, so an author can integrate without reading either decision log.
5. **The registry** (crippy D114, D115): its format, a validator CI runs on every pull request, the
   snapshot crippy builds in, and the fingerprints file inside a GPG-signed tag.
6. **Device tests** for the client against a real crippy, which needs crippy's APK on the device or
   emulator; then `instrumented.yml`.

## Before the first release

- **The append-only gate** (L2): every interface's methods in the last released tag must be a prefix of
  its methods now, checked in CI. Until a release exists there is nothing to compare against.
- **An API dump check** for the Kotlin surface — the binary-compatibility validator, or Kotlin's own
  ABI validation once AGP's built-in Kotlin exposes it.
- **The Kotlin floor for consumers** (L3): `languageVersion`, `apiVersion`, `coreLibrariesVersion`.
- **Publishing** (L1): the repository, the signing of artifacts, and reproducible AARs checked as
  crippy's release is (crippy D116).
- **Consumer R8 rules**, if a `Parcelable` or anything reached by name needs them.

## When the repository is pushed

- **Branch protection on `master`**: signed commits, a review, `ci` and `commit-policy` required.
- **Merge commits or rebases only**, never a squash (`AGENTS.md`, *Repo conventions*).

## Bug queue

Empty.

## After libcrippy

**The file manager**, in `../crippy-file-manager`, built against this library as any other app is
(crippy D112, D115).

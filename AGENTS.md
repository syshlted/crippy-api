# libcrippy — Project Facts & Decision Log

> Living document. Append decisions as they're made; mark superseded ones rather than deleting them.
> Date format: YYYY-MM-DD.

## What this is

**The library an Android app uses to reach a crippy vault without SAF.** crippy exports a Binder
service for apps that are not a file picker (crippy D113), and this repository holds the client's
half of it:

- **the contract**: the `.aidl` files the service implements and every client calls, which are the
  wire specification;
- **the client library**: binding to crippy, the check that the service bound to really is crippy,
  the session crippy hands out, and the consent screen a session needs (crippy D114);
- **a written specification of the protocol**, `PROTOCOL.md`, for authors who do not read this file;
- later, **the registry** of recognized apps that each crippy release builds in (crippy D114, D115).

crippy's file manager, in `../crippy-file-manager`, is the first client and builds against this
library as any other app would (crippy D112). Licensed Apache-2.0, which crippy D115 chose and still
flags for veto.

What crosses the boundary, and why, is decided in crippy's `AGENTS.md` — D66, D81 and D112 to D118 —
and is not restated here. What this file owns is the contract's shape on the wire and everything on
the client's side of it.

## Status

**Scaffolded 2026-10-22.** Two modules build, the gates run, and the hooks are installed. What landed
is structure, not implementation: the contract has one method, the version query, and the client
has no code. **There is no remote**, so GitHub Actions has never run; until the repository is pushed,
the hooks and a local `./gradlew` are the only checks.

## Relationship to crippy

- **Decisions here are numbered L1, L2, …, and crippy's are cited as *crippy D113*.** Two logs both
  numbered from 1 would make "D3" mean two things in every conversation that spans both.
- **About the contract, this file wins.** crippy D115 moved the contract's version line here — it is
  crippy D6's plugin API line — so a change to the contract is recorded here and crippy's service
  follows it. About crippy's internals, crippy's file wins and this one links rather than restates.
- **The toolchain is crippy's, version for version** (L3). crippy compiles its service against the
  contract, so a bump lands in both repositories or in neither.

---

## Repo conventions

**Three files, and they do not overlap**, as in crippy. This one is the decision log: why a thing is
the way it is, what was rejected, what was measured. `ROADMAP.md` is what is next and in what order.
`CHANGELOG.md` is what happened. When they disagree, **this file wins** and the other two are the bug.

**Default branch is `master`.** A SystemHalted organization-wide policy, not a per-repo accident. Do
not rename it to `main`.

**Commits are signed and Conventional.** Repo-local config, the same as crippy's:

| Setting | Value |
|---|---|
| `user.signingkey` | `E483D747728C1C9EA213FB29615692825276E947` (RSA-4096, expires 2028-09-14) |
| `commit.gpgsign` | `true` |
| `tag.gpgsign` | `true` |
| `gpg.format` | `openpgp` |

`core.hooksPath` is deliberately unset: lefthook installs its own shims into `.git/hooks/` and refuses
to install while `core.hooksPath` points elsewhere. The versioned part is `lefthook.yml` and
`scripts/hooks/`.

| Hook | Job | Enforces |
|---|---|---|
| `pre-commit` | `signing-preflight` | `commit.gpgsign` true, `user.signingkey` set, key usable |
| `pre-commit` | `ktlint`, `detekt` | style and static analysis, over the working tree rather than the index |
| `commit-msg` | `conventional-commit` | Conventional Commits, subject <= 72 chars |
| `pre-push` | `verify-signatures` | every commit in the range has `%G?` in {G, U} |
| `pre-push` | `unit-tests` | `allUnitTests` |

Fresh clones bootstrap with `lefthook install`. Hooks are a fast local mirror of CI, never a
substitute: `--no-verify` and `LEFTHOOK=0` always exist, so every rule enforced locally is re-enforced
in `.github/workflows/`.

**Every commit builds and passes its own tests, not just the branch tip** — crippy's rule, for
crippy's reason: `verify-signatures` walks every commit in a push and `unit-tests` runs once, at the
tip, so a red commit in the middle is pushed silently and a `git bisect` later blames the wrong one.

**Merge with a merge commit or a rebase, never a squash** (crippy D7). GitHub's squash re-signs with
its own web-flow key, so `commit-policy` would be verifying GitHub rather than the author.

**Regenerating the dependency verification metadata**, after adding or bumping anything:

```sh
./gradlew --write-verification-metadata sha256 --refresh-dependencies --rerun-tasks \
  ktlintCheck detekt allUnitTests koverVerify koverXmlReport assemble
```

**`--rerun-tasks` is not optional, which is one step past crippy's note.** A task that is up to date
resolves nothing, so the first metadata written here missed `aapt2` — which only an *executing*
resource compile asks for — and the next clean build failed verification on it. The file is `sha256`
with signature verification off, so it freezes the dependency set rather than attesting it, as
crippy's does (crippy D7). All 541 of its artifacts are in crippy's file too, with the same hashes.

## Local development environment

The same machine as crippy, and crippy's notes on it apply: the JDKs, the devices, the emulators and
Waydroid are recorded there under *Local development environment*. Three facts matter here:

- **Set `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.** `gradle/gradle-daemon-jvm.properties` pins the
  daemon to 21 and `jvmToolchain(21)` pins the compiler; they are separate settings (crippy D11).
- **`~/.config/git/template` installs hooks of its own** into every repository `git init` or
  `git clone` makes. `lefthook install` renames them `.old`, and lefthook 2.1.14 does not run a `.old`
  hook (checked in a scratch repository, where only lefthook's job ran), so they are inert. They were
  deleted here so `.git/hooks/` matches crippy's.
- **`local.properties` is gitignored** and holds `sdk.dir=/home/coder/Android/Sdk`.

---

## Decisions

### L1 — A client-only library: `com.systemhalted.crippy:crippy-client`, with the contract beside it
*2026-10-22 · accepted; **minSdk 27 confirmed by the user the same day as a hard limit**; coordinates
revised the same day for a client-only library, and **flagged for veto on them***

**libcrippy is client-only** — the user's direction, 2026-10-22. It is the library an app uses to
connect to crippy's AIDL interface, and none of crippy's service is in it; the service is crippy's.

**Two modules all the same, and an app names one.** `crippy-client` is what an app declares.
`crippy-contract` is the `.aidl` files and the hand-written types they name; the client depends on it,
so it arrives transitively and no app names it. It is published separately because crippy's service
compiles against the same files: one copy of the wire specification, which crippy's build pins by
version and hash (crippy D7, D116), rather than a second copy in crippy kept in step by hand. The aidl
tool generates a `Stub` beside every proxy, so the contract alone could never be client-only; what makes
the library client-only is that nothing else in it serves anything.

*Rejected:* one artifact, with the contract folded into the client. AGP has no supported way to put one
library module's classes into another's AAR, and crippy would then link the whole client — binding,
the check that the service is crippy — to get an interface. *Rejected:* a copy of the `.aidl` files in
crippy. Two copies of a wire specification is how they come to disagree.

**Named for what they are, under the product's group.** Group `com.systemhalted.crippy`, so everything
first-party for crippy sits under one name; artifact IDs that say crippy and what they are, because a
bare `client` means nothing in an app's dependency list or in an AAR's filename. Each Gradle project is
named after its artifact, because a composite build substitutes on `group:project name`: the file
manager declares `com.systemhalted.crippy:crippy-client` and, while both repositories change, includes
this build instead of resolving it (crippy D115). The packages do not change with them —
`com.systemhalted.crippy.api` for the contract (L2), `com.systemhalted.crippy.client` for the client.
Version `0.1.0-SNAPSHOT`. *First set as `com.systemhalted.libcrippy:contract` and `:client`, and
renamed the same day before anything depended on them.*

**Where they are published is not decided, and the group depends on it.** Maven Central verifies a
namespace before it publishes under it, so `com.systemhalted` needs a DNS record on `systemhalted.com`;
if that domain is not ours, the group becomes `io.github.<org>`. GitHub Packages needs a token even to
read, which is a poor fit for a library meant to be easy to integrate. Nothing is published yet.
**Flagged** on the names and on that dependency.

**Both are Android libraries.** A Binder interface is `android.*` by definition, so the purity crippy
D5 keeps for `:core-*` has nothing to buy here. If something pure-JVM arrives — the registry's
validator is the likely first — it gets a module of its own, and `allUnitTests` already wires a
`kotlin.jvm` module under `test`.

**Explicit API mode in both.** A published library's surface is declared rather than inferred, which
is what crippy's `PublicSurfaceTest` does for `:provider` by scanning source. Here the compiler does it:
a public class without a visibility modifier fails `compileDebugKotlin`, checked by adding one.

**minSdk 27, crippy's floor, and a hard limit.** crippy cannot run below 27 (crippy D14), so a client
cannot find crippy there; and `SharedMemory`, which crippy D113 names for previews below API 30, is
itself API 27. It covers every phone here — API 28, 29, 30 and 36 — and the emulator is the only device
at 27. *Rejected:* a lower floor so that apps with an older minSdk can integrate without
`tools:overrideLibrary`. It would mean code paths on API levels where the only answer is *crippy is not
installed*, and tests at levels nobody runs crippy on. An app whose minSdk is below 27 raises it or
overrides the library's. **Confirmed by the user the same day.**

### L2 — The contract's names and positions are permanent, and transaction 1 asks for the version
*2026-10-22 · accepted; **the package name confirmed by the user the same day***

**The interface's package is a wire name.** Every transaction carries the interface's full name as its
token — `com.systemhalted.crippy.api.ICrippyService` — and the generated stub refuses a call whose token
does not match. Moving the `.aidl` file to another package therefore breaks every installed client,
which makes the package as permanent as crippy's documents authority (crippy D11). The package is
`com.systemhalted.crippy.api`: it names crippy rather than this library, because it is crippy's
interface, and nothing in crippy uses that package (checked). It is the one name here that can never
change, and the user confirmed it the same day.

**Methods are only ever appended** (crippy D115). The aidl tool numbers methods by position from
`FIRST_CALL_TRANSACTION`, so removing, reordering or inserting one changes what every installed client
is asking for. **Transaction 1 is `int contractVersion()`, and it takes nothing**, so no later version
can ever need to change how it is asked.

**The version is a constant in the `.aidl` file**, `ICrippyService.VERSION`, rather than a Kotlin
constant beside it: the file is the specification, and the version is the specification's. It starts
at **1**, which is not a formality — see the next paragraph.

**A method an older service does not have answers zero, not an error.** Read from the generated proxy:
it ignores what `transact` returns, so an unknown transaction leaves an empty reply, `readException()`
finds no exception in it, and the result reads as `0`, `false` or `null`. Not measured on a device; it
is the aidl tool's code at build-tools 36. Three consequences:

1. **A client never calls a method above the service's `contractVersion()`.** The client library will
   enforce it, because the failure is silent.
2. **Version 0 is never a valid answer**, which is why the line starts at 1: a binder that is not
   crippy's, or a service that does not implement transaction 1, reads as version 0.
3. **A later method should answer something whose default is not a success** — a nullable result, or
   a status in which 0 does not mean *done* — so a client bug that calls above the version cannot read
   as one. Guidance for the methods to come; nothing checks it yet.

**The contract freezes at its first release**, as crippy's formats freeze at the first beta (crippy
D6). Until something ships, every method here may still change; afterwards, only appending is allowed.
The check that enforces it — each released interface's methods a prefix of the current one's — is
owed before that release (`ROADMAP.md`).

`WireNamesTest` pins the token, transaction 1 and the version. Each assertion is a value an installed
client has compiled in, so a failure there is fixed by putting the contract back, never by updating
the expected value.

### L3 — The toolchain and CI are crippy's
*2026-10-22 · accepted*

The same Gradle 9.7.1 wrapper, AGP 9.4.1 with its built-in Kotlin 2.2.10, ktlint 1.8.0, detekt
1.23.8, Kover 0.9.9 and JUnit 5.14.4; the same daemon pin, `.editorconfig` and detekt deltas; the same
hooks; and the same pinned action SHAs. crippy's `ci.yml` and `commit-policy.yml` are carried across.
What is left out has nothing to run on yet: the manifest-surface gate (no application here), golden
fixtures (no format), fuzzing (no parser of untrusted bytes), the instrumented matrix (no device
tests), and the reproducible-build check (no release).

**Added: `assemble` in CI**, because the unit tests compile only the debug variant and the release AAR
is what a client links.

**One thing to decide before the first release: the Kotlin a consumer needs.** Built with Kotlin 2.2,
the client's metadata cannot be read by an app compiling with a much older Kotlin, and its
`kotlin-stdlib` dependency raises the app's. `languageVersion`, `apiVersion` and `coreLibrariesVersion`
are the levers. Java callers are unaffected either way. Filed in `ROADMAP.md`, not settled here.

### L4 — Coverage gates measure what was written here, never what the aidl tool generated
*2026-10-22 · accepted*

| Module | Line | Branch | Why |
|---|---|---|---|
| `crippy-contract` | 90% | — | its hand-written types will be few, and their layout is the wire |
| `crippy-client` | 90% | 85% | it will carry the check that the service is crippy, the code a reviewer reads first |

Plus **diff coverage >= 85% on changed lines** in every pull request, by crippy's
`scripts/ci/diff-coverage.py` (crippy D5, B4). No module is exempt.

**Generated stubs and proxies are excluded**, by `inheritedFrom("android.os.IInterface")`, because
counting them would make the number say how many methods the contract has. The filter is
load-bearing: with it removed, `crippy-contract` fails at 0%.

**A rule over no code passes, measured**: both modules are at 0 of 0 lines today and `koverVerify`
passes. So the gates are wired now rather than in the commit that adds the code they measure, and they
bite the moment that code arrives — the opposite order to crippy's `:provider`, whose gate landed with
the provider.

---

## Open questions

- **Where the artifacts are published** (L1), and how they are signed — and with it, whether the group
  stays `com.systemhalted.crippy`.
- **The Kotlin floor for consumers** (L3).
- **Where crippy D113's spike lives** — a read-only `SharedMemory` handoff and a Binder round trip on
  the four phones. It needs a service and a client in two processes, and either repository could hold
  it.

## Build order

1. ~~Scaffold: the Gradle project, both modules, the gates and the hooks.~~ **Done 2026-10-22** — L1
   to L4.
2. Everything after is in `ROADMAP.md`.

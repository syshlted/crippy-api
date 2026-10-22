# crippy-api — Project Facts & Decision Log

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
has no code. **The remote is `git@syshlt-github:syshlted/crippy-api.git` and nothing has been pushed
to it**, so GitHub Actions has never run; until the first push, the hooks and a local `./gradlew` are
the only checks.

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
rm gradle/verification-metadata.xml
./gradlew --write-verification-metadata sha256 --refresh-dependencies --rerun-tasks \
  ktlintCheck detekt allUnitTests koverVerify koverXmlReport assemble
```

**Start from an empty file, which is two steps past crippy's note.** `--write-verification-metadata`
merges into the file it finds and never removes an entry, so regenerating over it keeps everything
the build has stopped using. Measured at L9: after the move to Kotlin 2.4.21 a merged file still held
all 31 components of the Kotlin 2.2.10 toolchain, which a scan of the file then reports as though the
build still ran them. The header Gradle writes into a new file is the one this file had.

**`--rerun-tasks` is not optional, which is one step past crippy's note.** A task that is up to date
resolves nothing, so the first metadata written here missed `aapt2` — which only an *executing*
resource compile asks for — and the next clean build failed verification on it. The file is `sha256`
with signature verification off, so it freezes the dependency set rather than attesting it, as
crippy's does (crippy D7). All 624 of its artifacts are in crippy's file too, with the same hashes —
541 until Robolectric came in for L8, then 619 before the file was regenerated from empty at L9.

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
revised the same day for a client-only library, and **the group settled the same day: the user owns
`systemhalted.com`***

**crippy-api is client-only** — the user's direction, 2026-10-22. It is the library an app uses to
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

**They will be published to Maven Central, under a namespace the user owns.** Central verifies a
namespace before it publishes under it, by a DNS record on the matching domain, and the user owns
`systemhalted.com`, so `com.systemhalted` can be verified and the group stands. *Rejected:* GitHub
Packages, which needs a token even to read — a poor fit for a library meant to be easy to integrate —
and JitPack, which builds from source on its own machines, so the AAR an app receives is not one this
repository built and checked. Nothing is published yet, and how the artifacts are signed is open.

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
finds no exception in it, and the result reads as `0`, `false` or `null`. It is the aidl tool's code
at build-tools 36, **run under Robolectric at API 27 and 36 since L8** and still not measured on a
device. Three consequences:

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
**Moved by L9:** the build is Kotlin 2.4.21 now, so by default the metadata is 2.4 — which Kotlin's
compiler reads only from 2.3 on, by its rule of reading one version ahead — and the stdlib an app
receives is 2.4.21. Still the same three levers, and still not settled.

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
the provider. **That they bite is measured too (L8)**: one untested Kotlin file in `crippy-client`
fails `koverVerify` at 0% against 90%.

### L5 — The client hides the contract
*2026-10-22 · accepted, at the user's direction: "hide them if it makes it easier to create clients"*

**`crippy-client` depends on `crippy-contract` with `implementation`**, so no AIDL type is on an app's
compile classpath — not `ICrippyService`, not a `Parcelable` the contract will define, not `IBinder`.
An app codes against the client's own types and nothing else. Checked with a throwaway consumer
module: depending on the client alone, a reference to `ICrippyService.VERSION` fails with
*"Unresolved reference 'api'"*; declaring `crippy-contract` as well, it compiles; and either way the
contract is on the consumer's runtime classpath through the client.

It does make a client easier to write, for three reasons:

1. **L2's hazard is closed for every app at once.** A method an older crippy does not have answers zero
   rather than an error, and an app that cannot reach the AIDL cannot make that call. The client
   checks the service's version once, for everybody.
2. **Binder's failures become answers.** `RemoteException`, `DeadObjectException`, a service that goes
   away mid-call, a binder that is not crippy's: the client turns each into a result of its own, where
   an app holding the raw interface would have to catch them all itself.
3. **The wire is permanent and the client's API is not.** A method stays in the contract for ever once
   it has shipped (L2); a client type can be deprecated and replaced. With the wire hidden, what an
   app codes against can change without a contract version.

**What it costs**: a client type beside each contract type an app needs — a `Parcelable` on the wire,
a Kotlin class in the client — and a conversion between the two. Accepted.

**The contract is still published**, because the client needs it at runtime and crippy's service
compiles against it (L1). An author who wants the raw interface, to write a client of their own,
declares `crippy-contract` directly, and `PROTOCOL.md` is written for them.

**The rule needs a guard before the client has public types.** Kotlin does not stop a public signature
in the client naming a type from an `implementation` dependency; an app would then fail to compile
against that member with *"cannot access class"*. So no public or protected signature in
`crippy-client` may name `com.systemhalted.crippy.api`, checked by a test or by the API dump owed
before the first release (`ROADMAP.md`). Nothing checks it yet, because the client has no code.

### L6 — The repository is `crippy-api`
*2026-10-22 · accepted, at the user's direction*

**Renamed from `libcrippy` before anything outside it named it.** The `lib` prefix is a C and Unix
convention, which exists because the linker's `-l` adds it back, and here it pointed the wrong way:
crippy ships native libraries and crippy D113 rejected a native transport, so a reader auditing the
path who meets `libcrippy` could reasonably look for a `libcrippy.so`. `crippy-api` matches
`com.systemhalted.crippy.api`, the one name here that can never change (L2), and names what the
repository is rather than its form. *Rejected:* `crippy-sdk`, which also says *what you integrate* and
fits a repository whose most important file is a specification less well.

**The repository, its directory and the root project's name changed, and nothing else.** The
artifacts, their group and both packages are L1's: a composite build substitutes on the subprojects'
names and never the root's, so nothing that will depend on this library sees the rename.

**GitHub made a commit of its own when the repository was created**: `c3b4c92`, holding an Apache-2.0
`LICENSE` that differs from ours by one leading blank line. It shares no history with `master`, so the
first push either replaces it or merges it (`ROADMAP.md`).

### L7 — Code scanning is CodeQL, over the code and over the workflows, with secret scanning on
*2026-10-22 · accepted; **flagged for veto on the query suite and on leaving dependency scanning
out***

**`codeql.yml` analyzes two languages** on every pull request, every push to `master` and once a week,
because a new query release finds bugs in code nobody touched. `java-kotlin` is built by the workflow
itself — `assembleRelease`, the AAR a client links (L3) — and `actions` reads `.github/workflows` with
no build. Results go to the repository's Security tab. The suite is `security-extended`: quality
findings are detekt's, and detekt already fails the build.

**Checked before it ever ran in CI, with the CLI the pinned action uses** — CodeQL 2.27.3, which is
what `codeql-action` v4.39.1 downloads — in a throwaway clone:

| Check | Result |
|---|---|
| What the build captures today | the Java the aidl tool generates, and nothing else: no main source set holds Kotlin yet |
| A planted `Cipher.getInstance("DES")` in a Kotlin file in `crippy-client` | **found**, `java/weak-cryptographic-algorithm`, so AGP 9's built-in Kotlin 2.2.10 is extracted |
| `security-extended` over today's code | 0 findings |
| The three workflows, `codeql.yml` included | 0 findings from 23 rules |
| A planted `pull_request_target` workflow echoing a pull request's title | **found**, `actions/code-injection/critical` |

**`--no-build-cache` is load-bearing, measured.** This repository turns the build cache on, and
`setup-gradle` restores it between runs. With a warm cache, the Java compile came back `FROM-CACHE` and
the aidl tool's Java was missing from the database: a compile restored from a cache is a class file
CodeQL never saw. ~~**Running the Kotlin compiler in-process is not needed, also measured**: without
`kotlin.compiler.execution.strategy=in-process` the planted Kotlin file was still captured, so the flag
is not in the workflow.~~ **True of Kotlin 2.2 and false of 2.4 (L9), so the flag is in the
workflow.** Under 2.4.21 the planted file compiled and never reached the database without it; with it,
it was captured and the planted cipher found. `--no-daemon` stays as cheap insurance against a daemon
started before CodeQL's tracing began; nothing starts one today.

**The job does not leave the token in `.git/config`** (`persist-credentials: false`), because it holds
`security-events: write` and runs the build, and with it every Gradle plugin the build resolves.
`ci.yml` and `commit-policy.yml` keep crippy's form: their token can only read a public repository.

**Secret scanning and push protection are on**, set on GitHub on 2026-10-22 at the user's request: a
push that carries a recognized credential is refused before it lands. Non-provider patterns and
validity checks are part of the paid GitHub Secret Protection even on a public repository; asking for
them left them off without an error.

**Dependency scanning is left out, measured rather than assumed.** OSV-Scanner 2.7.0 over
`gradle/verification-metadata.xml` finds 13 advisories in 9 packages, every one build tooling —
AGP's Bouncy Castle 1.80.2, logback, freemarker, jose4j, jdom2, `kotlin-gradle-plugin` 2.2.10 itself.
What ships in the release AARs is `kotlin-stdlib` 2.2.10 and `annotations` 13.0, with none. Scanning the
whole frozen set would start at 13 alerts that nothing here can fix without bumping the toolchain in
step with crippy (L3), which is how a team learns to stop reading alerts. The shape that would work is
in `ROADMAP.md`. Dependabot alerts are on and see nothing in the meantime: GitHub reads no Gradle build
until a dependency graph is submitted to it.

*Rejected:* CodeQL's default setup, because its build is CodeQL's choice rather than ours, and both
things measured to matter — skipping the build cache, and since L9 the in-process compiler — are flags
on the build command. *Rejected:* detekt's SARIF in the Security tab,
because detekt fails the build, so its alerts would exist only on a red build. *Rejected:* a third-party
workflow scanner; CodeQL's own `actions` queries caught the planted injection.

### L8 — The contract's one behavior is run, under Robolectric at API 27 and 36
*2026-10-22 · accepted; **flagged for veto on bringing Robolectric forward and on SDK 36's JVM flag***

The question was whether the unit tests were properly defined before the first push. **The wiring
holds, measured by breaking it**: a failing test fails `allUnitTests`, which CI and the pre-push hook
both run; one untested Kotlin file in `crippy-client` fails `koverVerify` at 0% against 90%; and L4's
filter is already load-bearing. **The gap was a claim, not a gate.** L2's *a method an older service
does not have answers zero* is the only behavior the contract has, and the whole of why version 0 can
never be crippy's, and it had been read from the generated proxy and never run.

**`UnansweredTransactionTest` runs it through the generated proxy over a real `Parcel`.** A binder that
does not know transaction 1 answers `0` with no exception. The control beside it — a stub behind a
binder of its own, answering `VERSION` — is what makes the zero mean anything: a `Parcel` that read
zero for everything would pass the first case alone. The proxy assigns `transact`'s return to
`_status` and never reads it, so a build-tools release that turns an unanswered transaction into an
exception fails this test, and L2's three consequences get re-read rather than quietly changing.

**Robolectric is what makes it runnable at all.** The mockable `android.jar` has no `Parcel`: `obtain`
throws, or with `returnDefaultValues` answers null. So Robolectric 4.17, JUnit 4 and the vintage engine
come in now, crippy's versions in crippy's arrangement (crippy D13), and they were coming anyway: the
client's binding tests in `ROADMAP.md` item 3 need them. The dependency set went from 541 artifacts to
619, every one already in crippy's metadata with the same hash.

**At API 27 and 36, where crippy runs 27 and 34**: the floor, and the platform this library compiles
against. Two things had to be found to get there, both measured:

- **SDK 36 needs `--add-exports=java.base/jdk.internal.access=ALL-UNNAMED` on JDK 21.** Robolectric
  4.17 sets up API 36's `ApplicationSharedMemory` by writing a `FileDescriptor` through
  `jdk.internal.access.SharedSecrets`, which JDK 21 does not export. Without the flag every SDK 36 case
  fails before it starts, *"Failed to interact with raw FileDescriptor internals"*; `--add-opens` for
  `java.io`, the first guess, changes nothing. crippy has never run Robolectric at 36, and would need the
  same flag.
- **API 27's `Binder.queryLocalInterface` dereferences a descriptor a bare `Binder` never attached**,
  where API 28 and later check for null. The test's binder answers null, which is what a `BinderProxy`
  answers and what makes `asInterface` build a proxy at all.

**Still owed, and not tests for code that does not exist yet**: L5's guard before the client's first
public type, and the append-only gate before the first release (`ROADMAP.md`). And L2's claim on a
device, across a real `BinderProxy`, which waits for the client's device tests (item 6).

*Rejected:* waiting for item 3 to bring Robolectric; the claim was already load-bearing. *Rejected:* API
34 to match crippy, which would test neither end of the range this library promises.

### L9 — Kotlin 2.4.21, ahead of the 2.2.10 AGP depends on
*2026-10-22 · accepted, at the user's direction; made in crippy the same day, as crippy D119*

**For one advisory, and the only one in the build's tooling with a way in.** GHSA-r937-wjx7-w2jp is
code execution through deserialized Kotlin build-cache entries, in `kotlin-gradle-plugin` before
2.4.20. This repository turns the build cache on and CI restores it, so the path exists, though narrowly:
a pull request cannot write the cache `master` reads. **Waiting for AGP does not fix it**: 9.4.1 is the
newest stable AGP and 9.5.0-rc01 still depends on 2.2.10.

**One version on one classpath.** The Kotlin plugin is on the root build's classpath at 2.4.21, and
since one classpath holds one version of a plugin, AGP's built-in Kotlin compiles with it. Measured on
the class files rather than assumed: a test class's `kotlin.Metadata` went from `mv=[2,2,0]` to
`mv=[2,4,0]`, and the compile runs `kotlin-build-tools-impl` 2.4.21. The 2.4.0 artifacts in the
metadata belong to the plugin's ABI-validation tool, which pins its own version and runs only when
asked — a candidate for the API dump check `ROADMAP.md` owes before the first release.

**Two consequences, both measured.** CodeQL now needs the Kotlin compiler in-process (L7, amended).
And regenerating the metadata over the old file kept the whole 2.2.10 toolchain, so the file is now
regenerated from empty (*Repo conventions*): 379 components to 348, all of them still in crippy's.

**And one that is still open**: the Kotlin an app needs (L3), which the move changed by default to
2.3 or newer, with stdlib 2.4.21.

*Rejected:* building without the build cache in CI until AGP moves, which has no end date while AGP's
next release still pins 2.2.10. *Rejected:* a dependency constraint forcing the version instead of a
classpath entry; it does the same, and says less about why it is there.

---

## Open questions

- **How the artifacts are signed for Maven Central** (L1): the repository's signing key, or one of
  their own.
- **The Kotlin floor for consumers** (L3).
- **Where crippy D113's spike lives** — a read-only `SharedMemory` handoff and a Binder round trip on
  the four phones. It needs a service and a client in two processes, and either repository could hold
  it.

## Build order

1. ~~Scaffold: the Gradle project, both modules, the gates and the hooks.~~ **Done 2026-10-22** — L1
   to L5.
2. Everything after is in `ROADMAP.md`.

# encri-api — Project Facts & Decision Log

> Living document. Append decisions as they're made; mark superseded ones rather than deleting them.
> Date format: YYYY-MM-DD.

## What this is

**The library an Android app uses to reach an encri vault without SAF.** encri exports a Binder
service for apps that are not a file picker (encri D113), and this repository holds the client's
half of it:

- **the contract**: the `.aidl` files the service implements and every client calls, which are the
  wire specification;
- **the client library**: binding to encri, the check that the service bound to really is encri,
  the session encri hands out, and the consent screen a session needs (encri D114);
- **a written specification of the protocol**, `PROTOCOL.md`, for authors who do not read this file;
- **the registry** of recognized apps that each encri release builds in (encri D114, D115; L15).

encri's file manager, in `../encri-files`, is the first client and builds against this
library as any other app would (encri D112). Licensed Apache-2.0, which encri D115 chose and still
flags for veto.

What crosses the boundary, and why, is decided in encri's `AGENTS.md` — D66, D81 and D112 to D118 —
and is not restated here. What this file owns is the contract's shape on the wire and everything on
the client's side of it.

## Status

**Scaffolded 2026-10-22.** Two modules build, the gates run, and the hooks are installed. **The contract's
first surface is written (L11)**: a service, a session, three listeners and the parcelables they carry. **The
client is written (L12)**: connecting, trusting encri, opening a session, every request the contract has, and the
intents for the consent and unlock screens. encri implements the service as its D120. **They have run against
each other on two phones (L13)**, from a package that is not encri's, and the run found a preview encri made
larger than the contract allows, which encri now honours (encri D121). The shared-memory read path encri D113 asked about
is measured and left unbuilt (L14). **The registry exists (L15)** and encri builds it in, empty until an app has a
release key. **What a first release needs is checked, but for publishing**: the public API is dumped (L16), an app
needs Kotlin 2.1 (L17), the contract can only grow from the last tag and a consumer needs no R8 rules (L18). **Pushed 2026-10-23** to `git@syshlt-github:syshlted/encri-api.git`, replacing
GitHub's initial commit (L6). The first runs of `ci`, `commit-policy`, `codeql` and `dependencies` all passed, with
no code-scanning alert.

## Relationship to encri

- **Decisions here are numbered L1, L2, …, and encri's are cited as *encri D113*.** Two logs both
  numbered from 1 would make "D3" mean two things in every conversation that spans both.
- **About the contract, this file wins.** encri D115 moved the contract's version line here — it is
  encri D6's plugin API line — so a change to the contract is recorded here and encri's service
  follows it. About encri's internals, encri's file wins and this one links rather than restates.
- **The toolchain is encri's, version for version** (L3). encri compiles its service against the
  contract, so a bump lands in both repositories or in neither.

---

## Repo conventions

**Three files, and they do not overlap**, as in encri. This one is the decision log: why a thing is
the way it is, what was rejected, what was measured. `ROADMAP.md` is what is next and in what order.
`CHANGELOG.md` is what happened. When they disagree, **this file wins** and the other two are the bug.

**Default branch is `master`.** A SystemHalted organization-wide policy, not a per-repo accident. Do
not rename it to `main`.

**Commits are signed and Conventional.** Repo-local config, the same as encri's:

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

**Every commit builds and passes its own tests, not just the branch tip** — encri's rule, for
encri's reason: `verify-signatures` walks every commit in a push and `unit-tests` runs once, at the
tip, so a red commit in the middle is pushed silently and a `git bisect` later blames the wrong one.

**Merge with a merge commit or a rebase, never a squash** (encri D7). GitHub's squash re-signs with
its own web-flow key, so `commit-policy` would be verifying GitHub rather than the author.

**Regenerating the dependency verification metadata**, after adding or bumping anything:

```sh
rm gradle/verification-metadata.xml
./gradlew --write-verification-metadata sha256 --refresh-dependencies --rerun-tasks \
  ktlintCheck detekt allUnitTests koverVerify koverXmlReport assemble
```

**Start from an empty file, which is two steps past encri's note.** `--write-verification-metadata`
merges into the file it finds and never removes an entry, so regenerating over it keeps everything
the build has stopped using. Measured at L9: after the move to Kotlin 2.4.21 a merged file still held
all 31 components of the Kotlin 2.2.10 toolchain, which a scan of the file then reports as though the
build still ran them. The header Gradle writes into a new file is the one this file had.

**Changing what an app receives** means updating the lockfiles too, which STRICT mode demands (L10):

```sh
./gradlew assembleRelease --write-locks
```

That also writes an empty `settings-gradle.lockfile` for the version catalog, which is ignored.

**`--rerun-tasks` is not optional, which is one step past encri's note.** A task that is up to date
resolves nothing, so the first metadata written here missed `aapt2` — which only an *executing*
resource compile asks for — and the next clean build failed verification on it. The file is `sha256`
with signature verification off, so it freezes the dependency set rather than attesting it, as
encri's does (encri D7). All 624 of its artifacts are in encri's file too, with the same hashes —
541 until Robolectric came in for L8, then 619 before the file was regenerated from empty at L9.

## Local development environment

The same machine as encri, and encri's notes on it apply: the JDKs, the devices, the emulators and
Waydroid are recorded there under *Local development environment*. Three facts matter here:

- **Set `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.** `gradle/gradle-daemon-jvm.properties` pins the
  daemon to 21 and `jvmToolchain(21)` pins the compiler; they are separate settings (encri D11).
- **`~/.config/git/template` installs hooks of its own** into every repository `git init` or
  `git clone` makes. `lefthook install` renames them `.old`, and lefthook 2.1.14 does not run a `.old`
  hook (checked in a scratch repository, where only lefthook's job ran), so they are inert. They were
  deleted here so `.git/hooks/` matches encri's.
- **`local.properties` is gitignored** and holds `sdk.dir=/home/coder/Android/Sdk`.

---

## Decisions

### L1 — A client-only library: `com.systemhalted.encri:encri-client`, with the contract beside it
*2026-10-22 · accepted; **minSdk 27 confirmed by the user the same day as a hard limit**; coordinates
revised the same day for a client-only library, and **the group settled the same day: the user owns
`systemhalted.com`***

**encri-api is client-only** — the user's direction, 2026-10-22. It is the library an app uses to
connect to encri's AIDL interface, and none of encri's service is in it; the service is encri's.

**Two modules all the same, and an app names one.** `encri-client` is what an app declares.
`encri-contract` is the `.aidl` files and the hand-written types they name; the client depends on it,
so it arrives transitively and no app names it. It is published separately because encri's service
compiles against the same files: one copy of the wire specification, which encri's build pins by
version and hash (encri D7, D116), rather than a second copy in encri kept in step by hand. The aidl
tool generates a `Stub` beside every proxy, so the contract alone could never be client-only; what makes
the library client-only is that nothing else in it serves anything.

*Rejected:* one artifact, with the contract folded into the client. AGP has no supported way to put one
library module's classes into another's AAR, and encri would then link the whole client — binding,
the check that the service is encri — to get an interface. *Rejected:* a copy of the `.aidl` files in
encri. Two copies of a wire specification is how they come to disagree.

**Named for what they are, under the product's group.** Group `com.systemhalted.encri`, so everything
first-party for encri sits under one name; artifact IDs that say encri and what they are, because a
bare `client` means nothing in an app's dependency list or in an AAR's filename. Each Gradle project is
named after its artifact, because a composite build substitutes on `group:project name`: the file
manager declares `com.systemhalted.encri:encri-client` and, while both repositories change, includes
this build instead of resolving it (encri D115). The packages do not change with them —
`com.systemhalted.encri.api` for the contract (L2), `com.systemhalted.encri.client` for the client.
Version `0.1.0-SNAPSHOT`. *First set as `com.systemhalted.libencri:contract` and `:client`, and
renamed the same day before anything depended on them.*

**They will be published to Maven Central, under a namespace the user owns.** Central verifies a
namespace before it publishes under it, by a DNS record on the matching domain, and the user owns
`systemhalted.com`, so `com.systemhalted` can be verified and the group stands. *Rejected:* GitHub
Packages, which needs a token even to read — a poor fit for a library meant to be easy to integrate —
and JitPack, which builds from source on its own machines, so the AAR an app receives is not one this
repository built and checked. Nothing is published yet, and how the artifacts are signed is open.

**Both are Android libraries.** A Binder interface is `android.*` by definition, so the purity encri
D5 keeps for `:core-*` has nothing to buy here. If something pure-JVM arrives — the registry's
validator is the likely first — it gets a module of its own, and `allUnitTests` already wires a
`kotlin.jvm` module under `test`.

**Explicit API mode in both.** A published library's surface is declared rather than inferred, which
is what encri's `PublicSurfaceTest` does for `:provider` by scanning source. Here the compiler does it:
a public class without a visibility modifier fails `compileDebugKotlin`, checked by adding one.

**minSdk 27, encri's floor, and a hard limit.** encri cannot run below 27 (encri D14), so a client
cannot find encri there; and `SharedMemory`, which encri D113 names for previews below API 30, is
itself API 27. It covers every phone here — API 28, 29, 30 and 36 — and the emulator is the only device
at 27. *Rejected:* a lower floor so that apps with an older minSdk can integrate without
`tools:overrideLibrary`. It would mean code paths on API levels where the only answer is *encri is not
installed*, and tests at levels nobody runs encri on. An app whose minSdk is below 27 raises it or
overrides the library's. **Confirmed by the user the same day.**

### L2 — The contract's names and positions are permanent, and transaction 1 asks for the version
*2026-10-22 · accepted; **the package name confirmed by the user the same day***

**The interface's package is a wire name.** Every transaction carries the interface's full name as its
token — `com.systemhalted.encri.api.IEncriService` — and the generated stub refuses a call whose token
does not match. Moving the `.aidl` file to another package therefore breaks every installed client,
which makes the package as permanent as encri's documents authority (encri D11). The package is
`com.systemhalted.encri.api`: it names encri rather than this library, because it is encri's
interface, and nothing in encri uses that package (checked). It is the one name here that can never
change, and the user confirmed it the same day.

**Methods are only ever appended** (encri D115). The aidl tool numbers methods by position from
`FIRST_CALL_TRANSACTION`, so removing, reordering or inserting one changes what every installed client
is asking for. **Transaction 1 is `int contractVersion()`, and it takes nothing**, so no later version
can ever need to change how it is asked.

**The version is a constant in the `.aidl` file**, `IEncriService.VERSION`, rather than a Kotlin
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
   encri's, or a service that does not implement transaction 1, reads as version 0.
3. **A later method should answer something whose default is not a success** — a nullable result, or
   a status in which 0 does not mean *done* — so a client bug that calls above the version cannot read
   as one. Guidance for the methods to come; nothing checks it yet.

**The contract freezes at its first release**, as encri's formats freeze at the first beta (encri
D6). Until something ships, every method here may still change; afterwards, only appending is allowed.
The check that enforces it — each released interface's methods a prefix of the current one's — is
owed before that release (`ROADMAP.md`).

`WireNamesTest` pins the token, transaction 1 and the version. Each assertion is a value an installed
client has compiled in, so a failure there is fixed by putting the contract back, never by updating
the expected value.

### L3 — The toolchain and CI are encri's
*2026-10-22 · accepted*

The same Gradle 9.7.1 wrapper, AGP 9.4.1 with its built-in Kotlin 2.2.10, ktlint 1.8.0, detekt
1.23.8, Kover 0.9.9 and JUnit 5.14.4; the same daemon pin, `.editorconfig` and detekt deltas; the same
hooks; and the same pinned action SHAs. encri's `ci.yml` and `commit-policy.yml` are carried across.
What is left out has nothing to run on yet: the manifest-surface gate (no application here), golden
fixtures (no format), fuzzing (no parser of untrusted bytes), ~~the instrumented matrix (no device
tests)~~ (**added by L13**, at API 27 and 36), and the reproducible-build check (no release).

**Added: `assemble` in CI**, because the unit tests compile only the debug variant and the release AAR
is what a client links.

**One thing to decide before the first release: the Kotlin a consumer needs.** **Decided by L17: Kotlin 2.1.** Built with Kotlin 2.2,
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
| `encri-contract` | 90% | — | its hand-written types will be few, and their layout is the wire |
| `encri-client` | 90% | 85% | it will carry the check that the service is encri, the code a reviewer reads first |
| `encri-registry` | 90% | 85% | it decides what encri's consent screen says about an app, and which version it refuses (L15) |

Plus **diff coverage >= 85% on changed lines** in every pull request, by encri's
`scripts/ci/diff-coverage.py` (encri D5, B4). No module is exempt.

**Generated stubs and proxies are excluded**, ~~by `inheritedFrom("android.os.IInterface")`~~ **by name since
L11: every class an `.aidl` file names, with its inner classes**, because counting them would make the number say
how many methods the contract has. The filter is load-bearing: with it removed, `encri-contract` fails at 0%.
L11 is what moved it. The aidl tool generates structured parcelables too, which implement no Binder interface, and
the first filter let them through at 13.9% against 90%. A filter on `Parcelable` would also sweep out a hand-written
one, so the rule names what the tool generated rather than what it inherits.

**A rule over no code passes, measured**: both modules are at 0 of 0 lines today and `koverVerify`
passes. So the gates are wired now rather than in the commit that adds the code they measure, and they
bite the moment that code arrives — the opposite order to encri's `:provider`, whose gate landed with
the provider. **That they bite is measured too (L8)**: one untested Kotlin file in `encri-client`
fails `koverVerify` at 0% against 90%.

### L5 — The client hides the contract
*2026-10-22 · accepted, at the user's direction: "hide them if it makes it easier to create clients"*

**`encri-client` depends on `encri-contract` with `implementation`**, so no AIDL type is on an app's
compile classpath — not `IEncriService`, not a `Parcelable` the contract will define, not `IBinder`.
An app codes against the client's own types and nothing else. Checked with a throwaway consumer
module: depending on the client alone, a reference to `IEncriService.VERSION` fails with
*"Unresolved reference 'api'"*; declaring `encri-contract` as well, it compiles; and either way the
contract is on the consumer's runtime classpath through the client.

It does make a client easier to write, for three reasons:

1. **L2's hazard is closed for every app at once.** A method an older encri does not have answers zero
   rather than an error, and an app that cannot reach the AIDL cannot make that call. The client
   checks the service's version once, for everybody.
2. **Binder's failures become answers.** `RemoteException`, `DeadObjectException`, a service that goes
   away mid-call, a binder that is not encri's: the client turns each into a result of its own, where
   an app holding the raw interface would have to catch them all itself.
3. **The wire is permanent and the client's API is not.** A method stays in the contract for ever once
   it has shipped (L2); a client type can be deprecated and replaced. With the wire hidden, what an
   app codes against can change without a contract version.

**What it costs**: a client type beside each contract type an app needs — a `Parcelable` on the wire,
a Kotlin class in the client — and a conversion between the two. Accepted.

**The contract is still published**, because the client needs it at runtime and encri's service
compiles against it (L1). An author who wants the raw interface, to write a client of their own,
declares `encri-contract` directly, and `PROTOCOL.md` is written for them.

**The rule needs a guard before the client has public types.** Kotlin does not stop a public signature
in the client naming a type from an `implementation` dependency; an app would then fail to compile
against that member with *"cannot access class"*. So no public or protected signature in
`encri-client` may name `com.systemhalted.encri.api`, checked by a test or by the API dump owed
before the first release (`ROADMAP.md`). Nothing checks it yet, because the client has no code.

### L6 — The repository is `encri-api`
*2026-10-22 · accepted, at the user's direction*

**Renamed from `libencri` before anything outside it named it.** The `lib` prefix is a C and Unix
convention, which exists because the linker's `-l` adds it back, and here it pointed the wrong way:
encri ships native libraries and encri D113 rejected a native transport, so a reader auditing the
path who meets `libencri` could reasonably look for a `libencri.so`. `encri-api` matches
`com.systemhalted.encri.api`, the one name here that can never change (L2), and names what the
repository is rather than its form. *Rejected:* `encri-sdk`, which also says *what you integrate* and
fits a repository whose most important file is a specification less well.

**The repository, its directory and the root project's name changed, and nothing else.** The
artifacts, their group and both packages are L1's: a composite build substitutes on the subprojects'
names and never the root's, so nothing that will depend on this library sees the rename.

**GitHub made a commit of its own when the repository was created**: `c3b4c92`, holding an Apache-2.0
`LICENSE` that differs from ours by one leading blank line. It shares no history with `master`, so the
first push either replaces it or merges it (`ROADMAP.md`). **Replaced, at the user's direction, by a force push
on 2026-10-23**, leased on `c3b4c92` so that nothing else could be overwritten. That push found a hole in
`commit-policy`, fixed before it went (`CHANGELOG.md`).

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
| A planted `Cipher.getInstance("DES")` in a Kotlin file in `encri-client` | **found**, `java/weak-cryptographic-algorithm`, so AGP 9's built-in Kotlin 2.2.10 is extracted |
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
`ci.yml` and `commit-policy.yml` keep encri's form: their token can only read a public repository.

**Secret scanning and push protection are on**, set on GitHub on 2026-10-22 at the user's request: a
push that carries a recognized credential is refused before it lands. Non-provider patterns and
validity checks are part of the paid GitHub Secret Protection even on a public repository; asking for
them left them off without an error.

**Superseded the same day by L10**, at the user's direction, which builds the shape this paragraph
pointed at. Kept because the measurement in it is what L10 is built on.

**Dependency scanning is left out, measured rather than assumed.** OSV-Scanner 2.7.0 over
`gradle/verification-metadata.xml` finds 13 advisories in 9 packages, every one build tooling —
AGP's Bouncy Castle 1.80.2, logback, freemarker, jose4j, jdom2, `kotlin-gradle-plugin` 2.2.10 itself.
What ships in the release AARs is `kotlin-stdlib` 2.2.10 and `annotations` 13.0, with none. Scanning the
whole frozen set would start at 13 alerts that nothing here can fix without bumping the toolchain in
step with encri (L3), which is how a team learns to stop reading alerts. The shape that would work is
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
both run; one untested Kotlin file in `encri-client` fails `koverVerify` at 0% against 90%; and L4's
filter is already load-bearing. **The gap was a claim, not a gate.** L2's *a method an older service
does not have answers zero* is the only behavior the contract has, and the whole of why version 0 can
never be encri's, and it had been read from the generated proxy and never run.

**`UnansweredTransactionTest` runs it through the generated proxy over a real `Parcel`.** A binder that
does not know transaction 1 answers `0` with no exception. The control beside it — a stub behind a
binder of its own, answering `VERSION` — is what makes the zero mean anything: a `Parcel` that read
zero for everything would pass the first case alone. The proxy assigns `transact`'s return to
`_status` and never reads it, so a build-tools release that turns an unanswered transaction into an
exception fails this test, and L2's three consequences get re-read rather than quietly changing.

**Robolectric is what makes it runnable at all.** The mockable `android.jar` has no `Parcel`: `obtain`
throws, or with `returnDefaultValues` answers null. So Robolectric 4.17, JUnit 4 and the vintage engine
come in now, encri's versions in encri's arrangement (encri D13), and they were coming anyway: the
client's binding tests in `ROADMAP.md` item 3 need them. The dependency set went from 541 artifacts to
619, every one already in encri's metadata with the same hash.

**At API 27 and 36, where encri runs 27 and 34**: the floor, and the platform this library compiles
against. Two things had to be found to get there, both measured:

- **SDK 36 needs `--add-exports=java.base/jdk.internal.access=ALL-UNNAMED` on JDK 21.** Robolectric
  4.17 sets up API 36's `ApplicationSharedMemory` by writing a `FileDescriptor` through
  `jdk.internal.access.SharedSecrets`, which JDK 21 does not export. Without the flag every SDK 36 case
  fails before it starts, *"Failed to interact with raw FileDescriptor internals"*; `--add-opens` for
  `java.io`, the first guess, changes nothing. encri has never run Robolectric at 36, and would need the
  same flag.
- **API 27's `Binder.queryLocalInterface` dereferences a descriptor a bare `Binder` never attached**,
  where API 28 and later check for null. The test's binder answers null, which is what a `BinderProxy`
  answers and what makes `asInterface` build a proxy at all.

**Still owed, and not tests for code that does not exist yet**: L5's guard before the client's first
public type, and the append-only gate before the first release (`ROADMAP.md`). ~~And L2's claim on a
device, across a real `BinderProxy`, which waits for the client's device tests (item 6).~~ **Run by L13 on three
phones and an emulator.**

*Rejected:* waiting for item 3 to bring Robolectric; the claim was already load-bearing. *Rejected:* API
34 to match encri, which would test neither end of the range this library promises.

### L9 — Kotlin 2.4.21, ahead of the 2.2.10 AGP depends on
*2026-10-22 · accepted, at the user's direction; made in encri the same day, as encri D119*

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
regenerated from empty (*Repo conventions*): 379 components to 348, all of them still in encri's.

**And one that is still open**: the Kotlin an app needs (L3), which the move changed by default to
2.3 or newer, with stdlib 2.4.21.

*Rejected:* building without the build cache in CI until AGP moves, which has no end date while AGP's
next release still pins 2.2.10. *Rejected:* a dependency constraint forcing the version instead of a
classpath entry; it does the same, and says less about why it is there.

### L10 — Dependency scanning: what ships is a gate, what builds it is a report
*2026-10-22 · accepted, at the user's direction; supersedes L7 on leaving it out*

**Two questions with different answers**, so `dependencies.yml` has two jobs.

**What an app receives fails the build on any advisory.** Gradle's dependency locking holds each
module's `releaseRuntimeClasspath` in a `gradle.lockfile` beside it, in STRICT mode, and OSV-Scanner
reads the two lockfiles with no triage file at all: nothing that ships is accepted. Today that is
`kotlin-stdlib` 2.4.21 and `annotations` 13.0, with no advisories. Measured both ways: strict mode
refuses to build with a lockfile missing, and a lockfile with `commons-lang3` 3.16.0 planted in it fails
the scan on GHSA-j288-q9x7-2f5v. The lock also makes a new runtime dependency a diff somebody reads:
it changes only by `--write-locks`, like the verification metadata changes only by regenerating it.

**What builds it is reported to the Security tab, and does not fail the job.** OSV-Scanner reads every
artifact `gradle/verification-metadata.xml` pins — which is why that file is now regenerated from empty
(L9) — against `gradle/osv-scanner.toml`, which accepts the 12 advisories left after L9. Each entry
says why the flaw cannot be reached from a library build; every one was checked against the advisory's
own details, not its summary line. Each expires on 2027-01-22, so the alert comes back for review
rather than staying ignored. Measured: with the file, 0 results; with freemarker's entry removed, that
advisory reappears. **A report rather than a gate** because an advisory published this week is not
something a pull request did, and failing unrelated pull requests teaches people to stop reading the
check. A pull request that brings a vulnerable tool in is still caught: it is a new alert, and code
scanning merge protection refuses one (`ROADMAP.md`).

**The scanner is a release binary checked against Google's published SHA-256**, not
`osv-scanner-action`, which runs a Docker image by tag: pinning the action's SHA does not pin the image
(encri D7). The job fails if the scanner fails, so a scan that did not run cannot pass as a clean one.
OSV-Scanner writes absolute paths into its SARIF, and the pinned `upload-sarif` sends the checkout path
as `checkout_uri`, which is what maps them back onto the repository; read from the action's source at
that SHA. Every step of the workflow was run locally as written before it was committed.

*Rejected:* forcing newer versions under AGP, Kover and ktlint, which would clear the list by swapping a
combination their authors tested for one nobody did, to patch code a library build never runs; it would
also break the property that every artifact here is in encri's verified set. *Rejected:* gating the
whole set, for the reason above. *Rejected:* Dependabot through a submitted dependency graph, which needs
a job holding `contents: write` to run the build, and a Gradle plugin the verified set would have to
admit.

### L11 — Contract v1: a session binder per app, results that are never 0, and writes over a pipe
*2026-10-22 · accepted; **flagged for veto on three points** (below). The user chose the surface: browse and
read, write operations, move, previews, the open-with and share handoff, and modification times and sizes.*

**Two steps, and the second is a capability.** `IEncriService` has two methods: `contractVersion()` and
`openSession(IVaultListener)`. A session is an `IEncriSession` binder encri hands to one app (encri D114). Every
call on it checks that the caller's uid is the uid it was opened for, so an app cannot pass its session to
another, and the session dies with the app's process. A locked vault cannot say which apps it allows, so
`openSession` answers `STATUS_LOCKED` to every app and hands out nothing.

**Every result is a parcelable carrying a status, and no status is 0.** L2 found that a method an older encri
lacks answers 0 or null. A null result therefore means *not answered*, and `STATUS_OK` is 1. A bare `int` would
read 0 for *not answered*, so even the operations with nothing to return give back an `Outcome`, never an `int`.

**A parcelable's fields are only ever appended**, as methods are. The aidl tool writes a structured parcelable's
length before its fields: a reader skips what a newer writer appended and finds what an older writer did not send
at its default. `ParcelableEvolutionTest` runs both halves.

**The vocabulary is constants in `IEncriService`**: the package, the bind action, the consent and unlock actions,
the rights, the states, the statuses, one flag, and the folder MIME type. A client binds by action with the package
set, rather than by a class name, so encri can move the service without breaking a client. `ACTION_UNLOCK` is
the action encri's unlock screen has answered since encri D33.

**The rights are five bits**: read, write (replace content, rename, set times), create, delete and move. Every
grant carries read: listing, previews and handoffs all need it.

**A grant is rooted at the whole vault or at one folder.** An id outside it answers `STATUS_NOT_FOUND`, the same as
an id that does not exist, so a session cannot probe what lies beyond its folder. That is encri D13's ordering
for `openDocument`. The root's own parent reads as null.

**Writes go over a pipe, not a proxy descriptor.** `openWrite` returns the write end of
`ParcelFileDescriptor.createReliablePipe()`, and an `IWriteListener` reports whether the content was saved. Three
reasons:

1. **The commit is reported.** A proxy descriptor commits in `onRelease`, which has no errno, so a failed save
   reaches nobody (encri D26, D87). Here the listener says so.
2. **No AppFuse on the write path.** encri D88 measured a single FUSE request over ~99 KiB closing encri's whole
   mount on one ROM and wedging AppFuse for every app on another. A pipe never crosses that bridge.
3. **A crash is told from a finish.** A reliable pipe carries how its writer closed. A clean close commits;
   `closeWithError`, or a writer whose process died, abandons the write and leaves the document as it was.

It costs what a pipe cannot do: seek. A write through this path is sequential, so it never leaves a hole (encri
D63). Reads stay proxy descriptors, because they must seek, may be mapped, and may be handed to another app.

**What takes time is answered on a listener**, so no call holds one of encri's binder threads, which also serve the
file picker (encri D17, D113). That covers the end of a write and a preview. A preview is queued with a ticket,
cancellable, and delivered as an encoded image on a descriptor: a sealed `memfd` from API 30 and a proxy
descriptor below it (encri D48).

**Listing is paged by name.** Entries come in name order, which encri's `UNIQUE (parent_id, display_name)` index
already yields, and `next` is an opaque cursor. encri caps the page size, because a binder buffer is ~1 MB and
shared across the process (hard constraint 4 in encri).

**Times and sizes, as the user asked.** Every entry carries its created and modified times, a document its size,
and a folder the count of its direct children. `measure` counts everything under a folder: bytes, documents and
folders. `setTimes` sets either time, with -1 leaving it as it is, so a file manager copying a file in can keep the
file's dates.

**Flagged for veto:**

1. **Writes over a pipe**, so a companion can neither seek while writing nor leave a hole.
2. **An app may lock the vault**, for everyone. That is a file manager's lock button. The worst a granted app can
   do with it is lock the vault over and over, which costs the user an unlock and nothing else.
3. **Ids outside a grant answer *not found*.** An app that was given one folder cannot tell an id outside it from
   an id that does not exist.

*Rejected:* `int` returns with `out` parcelables, which read as success from an older encri. *Rejected:* binding by
class name, which makes the service's class permanent. *Rejected:* a proxy descriptor for writes, for the three
reasons above. *Rejected:* synchronous previews, which would let a file manager scrolling a grid of photos occupy
the binder threads the file picker also needs.

### L12 — The client: a result for every request, trust before the bind, and a write that is told why it stopped
*2026-10-23 · accepted; **flagged for veto on five points** (below). The user chose coroutines over callbacks.
**Amends `ROADMAP.md` item 3**, which named `hasSigningCertificate` and a bind to encri's explicit component.*

**Every request is a `suspend` function that answers a `EncriResult`**: `Ok` with a value, or `Failed` with a
`Failure` and a message for a developer. Nothing a request meets is thrown at the app — not a status encri sent,
not encri's process going away mid-call, not a session that ended, and not a success that arrived without what it
should carry. That last one was thrown until a test asked: every transform `require`d its field, and an
`IllegalArgumentException` went straight to the app. The one place every request passes through now answers it as
`Failure.FAILED`. The single exception that does reach the app is its own cancellation, which structured
concurrency needs. Each request moves its work to `Dispatchers.IO`, so any of them may be called from the main
thread.

**Coroutines are an `api` dependency**, because `suspend` and the session's `SharedFlow` of events are in public
signatures. kotlinx-coroutines 1.10.2 is the client's first runtime dependency besides the standard library, so it
is in the lockfile and L10 scans it.

**encri is checked before anything is bound** (encri D114). An app trusts an encri whose signing-certificate
history holds a certificate it pinned, or one signed by its own key. The second is on by default, and it is how
encri's file manager trusts encri without naming a key, debug builds included (encri D115). The history is
read from `SigningInfo` from API 28 and from `PackageInfo.signatures` below it, and the library compares digests
itself. `hasSigningCertificate`, which the roadmap named, checks the same history, and it would have made API 28+
a second code path that no unit test can reach, because Robolectric does not shadow it.
`EncriTrust.RELEASE_CERTIFICATES` is empty until encri has a release key, so an app signed with another key pins
a certificate itself for now.

**The bind is by action with the package set**, as L11 settled, not by component. The callback checks the package
again, a service answering version 0 is not encri's (L2), and one older than this client is `TooOld`. A bind that
does not connect within ten seconds is `Unavailable`: a cold start of encri's process is the longest an honest one
takes.

**A connection owns its sessions.** Closing it ends every session opened through it and tells encri. The first
draft only unbound, which left encri holding sessions for a client that had let go of them. A session watches its
binder, so encri's process dying ends it, emits `VaultEvent.Ended`, and answers any request waiting on a listener
with `Failure.DISCONNECTED`.

**A write is told encri's reason when encri stops it.** When encri refuses a write part way — the disk is full,
the vault locked — it closes its end of the pipe, so the app's next write fails with a broken pipe, and encri's
reason reaches the write listener a moment later. A client that answered at once would report every refusal as
`CANCELLED`. So a writer that fails with an `IOException` waits up to two seconds for encri's answer, and reports
it if there is one. A writer that fails any other way, or a caller that cancels, abandons the write with the
pipe's error channel, and encri saves nothing (L11).

**Events are a `SharedFlow` that drops its oldest event past 64**, because encri calls the listener oneway on a
binder thread and a listener that blocked would hold that thread. A slow collector can therefore miss a
`FolderChanged`, which is best effort anyway, and in principle a `Locked` or `Unlocked`, which a request answers
correctly regardless.

**L5's guard had been reading nothing.** `PublicSurfaceTest` looked for the client's classes in a directory, and
AGP hands a unit test a jar. Its own sanity case, *the scan sees the client's classes at all*, failed on the first
run, which is what it is for. It reads both now, and found three leaks once it could see. Two were not real: a
listener object handed to encri, which no app can name, and a synthetic accessor. The third was real:
`EncriSession`'s `internal` constructor is public in bytecode and named `IEncriSession`, so Java could reach the
contract through it. The constructor is private now, behind a `@JvmSynthetic` factory, which neither language can
call from outside the module.

**The library declares `<queries>` for encri's package**, which merges into the app's manifest, because from API 30
an app cannot see a package it does not declare.

**Measured off a phone**: 83 tests against a fake service and a fake session, at API 27 and 36, over the client's
90/85 coverage gates. Two mutations, one per fix above, each fail their test. **Not measured**: a real encri over
a real binder, which is roadmap item 6.

**Flagged for veto:**

1. **Coroutines in the public API**, so an app receives kotlinx-coroutines 1.10.2 and cannot go below it.
2. **An encri signed by the app's own key is trusted by default.** An app that shares encri's key shares encri's
   trust already (encri D115), and a malicious app signed with that key could not be stopped by pinning.
3. **A certificate encri rotated away from is still trusted**, as Android trusts it for an update.
4. **Events are dropped oldest first** rather than holding encri's binder thread.
5. **A writer that fails on the pipe waits up to two seconds** for encri's reason before answering.

*Rejected:* callbacks, which the user declined. *Rejected:* `hasSigningCertificate`, for the reason above.
*Rejected:* throwing `RemoteException`, `IllegalStateException` or a failure type of the library's own at the app,
which would make every call site a `try` and would put the contract's exception types in the app's sight (L5).
*Rejected:* paging as a `Flow`. `listPage` answers one page, `list` gathers them, and a `Flow` would have to decide
what a failure halfway through a folder means.

### L13 — Device tests: the client over a real Binder, a stand-in in CI and encri itself on a phone
*2026-10-23 · accepted; **flagged for veto on one point** (below). Closes `ROADMAP.md` item 6, L8's owed device
claim, and the different-uid check encri's D120 owed.*

**Two classes, because there are two questions.** `CrossProcessTest` runs the client against `StandInEncri`, a
service in a process of its own (`:standin`), and `OlderEncri`, one built against an older contract (`:older`).
It needs no encri, so it is what `instrumented.yml` runs. `AgainstEncriTest` runs it against the encri installed
on the phone, from this test's own package: a different uid, the real service, the real consent screen and the
vault's own AppFuse descriptors. Without encri it skips itself, which is what CI sees, encri being closed
source (encri D116).

**One seam, and nothing public.** `ServiceBinding.connect` takes the bind intent and the package to expect, so the
stand-in suite binds a stand-in and then follows exactly the path an app takes. The public `Encri.connect` still
binds encri's action in encri's package.

**What a real Binder adds, each measured rather than assumed:**

| Claim | Before | Now |
|---|---|---|
| A method an older encri lacks reads as 0 or null, never an error (L2) | Robolectric | over a real `BinderProxy` |
| encri's process dying ends the session and answers a request waiting on a listener (L12) | a fake death recipient | `Process.killProcess`; Android rebinds the connection by itself and a new session opens |
| A write encri refuses part way is told encri's reason (L12) | a JVM pipe | a reliable pipe across processes. The writer gets `EPIPE (Broken pipe)` and **no SIGPIPE**: the app survives |
| A writer that fails saves nothing (L11) | JVM | `closeWithError` read by the other process's `checkError` |
| A preview arrives oneway on a descriptor; listing pages; events cross oneway | fakes | across the kernel |

Two client mutations were run on the G6, one at a time, to check the device tests can fail. Dropping the
two-second wait for encri's reason fails the refused write, which then answers `CANCELLED` with `write failed:
EPIPE`. Dropping the death recipient makes the process-death case time out.

**Against encri itself**, in ten ordered steps sharing one session: trust by the same key (`checkSignatures` across
two real packages), connect, the grant, a 1 MB write read back through `/mnt/appfuse/…` with a `pread` across a block
boundary, paging and `measure`, rename, move into a subfolder, `setTimes`, a PNG's preview, a handoff read through
`ContentResolver` and refused once revoked, refusals as answers, a lock — an open descriptor goes on serving across
it, the session answers `LOCKED`, and after the unlock the same session works — and the folder deleted. It leaves
nothing in the vault but the grant encri keeps for this install, so a second run needs no consent screen.

| | API | `CrossProcessTest` | `AgainstEncriTest` |
|---|---|---|---|
| LG G6 | 28 | 10 of 10 | not run: no encri installed, and a secure keyguard |
| Galaxy S9 | 29 | 10 of 10 | **10 of 10**, 8.4 s |
| Galaxy A20 | 30 | 10 of 10 | **10 of 10**, 11.3 s, from a locked vault and no grant |
| API 27 emulator | 27 | 10 of 10 | skipped: no encri, as CI |

**It found that encri broke the contract, and the contract stands.** Asked for a preview no larger than 64x64,
encri answered 150x100. L11, `PROTOCOL.md` and the client all promise *no larger than*; encri's companion path
reused the file picker's rule, *at least the hint on both axes* (encri D50), and that rule leaves the long side
unbounded — a panorama comes back at full width. encri honours the contract now (encri D121). The wording is not
changed to match what encri did, because the promise is what lets a client bound what a grid of previews costs
without knowing how large each picture is. Reverting encri's fix fails step 6.

**How it presses encri's buttons.** Through the accessibility tree (`EncriScreens`), which `FLAG_SECURE` does not
hide. **It types and presses only in a window whose package is encri's**, after the first draft typed a passphrase
into whatever was in front: a run that failed had left encri's unlock screen in the test's task, and a new start
only brought that task forward, so the host now starts into a cleared task too. Matched on encri's English words,
case aside, because the theme draws button labels in capitals and the tree reports what is drawn.

**And a press through the tree is a click, not a touch**, so encri's consent screen's touch filter (encri D114)
does not see it. That is how this suite can press *Allow*, and it is how any accessibility service the user has
enabled could too. Filed in encri's roadmap as B110 rather than decided here: encri's screen is encri's.

**`am instrument` rather than `connectedDebugAndroidTest`** on a phone (`scripts/device/run-device-tests.sh`): the
Gradle task uninstalls the test APK when it finishes, which drops the grant encri keyed on the install, and over
wireless adb its exit status lies (encri's notes). CI uses the Gradle task, where neither applies.

**What it brought in** is `androidx.test:runner` 1.7.0 and `androidx.test.ext:junit` 1.3.0, encri's versions. The
metadata, regenerated from empty with `assembleDebugAndroidTest` and `connectedDebugAndroidTest` added, went from
353 components to 367: 14 added, none removed, all 14 in encri's file. Of its 655 artifacts every one has encri's
hash, and one, `annotation-jvm-1.7.0.jar`, is not in encri's file at all.

**A flaky unit test, found by running the suite under load.** Robolectric's unbind calls `onServiceDisconnected`
with no name, and the client declared it non-null, so a connection test failed whenever the looper ran the
disconnect before the test's own thread had finished. The three callbacks that never read the name accept null.

**Flagged for veto:**

1. **The vault's passphrase reaches the phone as an instrumentation argument**, so it is in the phone's process list
   while the run lasts. For a test vault and nothing else; the runner refuses a passphrase it cannot quote.

*Rejected:* UiAutomator, which would bring artifacts encri's verified set does not hold, for what the platform's
own `UiAutomation` already does. *Rejected:* a test-only way into encri that grants a package, which a debug build
would carry and a release build must not — a backdoor that exists in some builds is one that ships by accident.
*Rejected:* requiring a person at the phone, which is what made the different-uid check owed for a day.

### L14 — The shared-memory read path: measured safe on ashmem and about three times faster per block, and not built yet
*2026-10-23 · accepted; **flagged for veto on not building it** (below). Closes `ROADMAP.md` item 1, the spike encri
D113 owed, and answers the open question of where it lives: here, beside the device tests, which already run a
service and a client in two processes.*

**The spike** (`SharedMemorySpikeTest`, `SpikeService` in a `:spike` process) is encri D113's in miniature. A service
creates a 1 MiB `SharedMemory`, keeps a read-write mapping, narrows the region to `PROT_READ` and hands it over a raw
Binder transaction; it also opens a proxy descriptor over the same bytes. Both routes serve a 64 KiB block by
copying one from memory, standing in for encri's decrypt, so what differs is the transport.

**The client cannot write through the region, on every target:**

| Attempt | G6 · 28 | S9 · 29 | A20 · 30 | emulator · 27 | emulator · 36 |
|---|---|---|---|---|---|
| `/proc/self/fd` calls it | `/dev/ashmem` | `/dev/ashmem` | `/dev/ashmem<uuid>` | `/dev/ashmem` | `/dev/ashmem<uuid>` |
| `mapReadWrite`, `map(READ or WRITE)` | EPERM | EPERM | EPERM | EPERM | EPERM |
| `setProtect` widened | refused | refused | refused | refused | refused |
| `mmap` of the raw descriptor, shared or private, writable | EPERM | EPERM | EPERM | EPERM | EPERM |
| `pwrite`, `ftruncate` | EINVAL | EINVAL | EINVAL | EINVAL | EINVAL |
| reopen `/proc/self/fd/<n>` read-write | allowed | **EACCES** | allowed | allowed | allowed |
| …then map it | EINVAL | EACCES | EINVAL | EINVAL | EINVAL |

The reopen that is allowed reaches nothing: opening ashmem's device makes a new region of no size, which no mapping
fits. And the assertion that decides it: **encri's copy checksums the same after every attempt**. encri's writes
after the hand-over reach the client's view, and zeroing the region clears it — encri D113's *zero it when a session
ends* works as planned.

**What is not measured is memfd.** Every target backs `SharedMemory` with ashmem, the API 36 emulator included
(`sys.use_memfd` is false there). A vendor ROM at API 34 or above may use memfd, sealed with `F_SEAL_FUTURE_WRITE`
rather than ashmem's protection mask, and the S24 is the phone that would say. The read path does not ship before it
has.

**What a block costs, median of 1024 blocks after a warm-up, beside a 64 KiB memcpy:**

| | Binder round trip | a block through the region | a block through AppFuse | AppFuse ÷ region | memcpy |
|---|---|---|---|---|---|
| S9 · 29 | 46 µs | 83 µs | 1.08 ms | 13× | 3.1 µs |
| A20 · 30 | 307 µs | 673 µs | 2.12 ms | 3.2× | 6.6 µs |
| G6 · 28, screen off | 767 µs | 1.55 ms | 4.68 ms | 3.0× | 3.8 µs |
| emulator · 27 | 14 µs | 44 µs | 114 µs | 2.6× | 1.8 µs |

The AppFuse reads are each block once, four blocks apart and shuffled, with the warm-up on blocks the timed reads
never touch, so none is a page-cache hit (encri D22). These are isolated reads: a sequential reader gets readahead
through AppFuse, and the region path, one round trip per block, does not. And they are transport only. **With encri's
decrypt added** — 400 µs a block on the S9 and 580 on the G6 (encri D40) — a block costs 1.5 ms against 0.48 ms on
the S9 and 5.3 ms against 2.1 ms on the G6: **about 2.5 to 3 times** per block, which is encri D113's estimate
measured. The slow round trips on the G6 and the A20 are the phones' state, a little core woken for each call, which
encri D12 and D40 both warn moves a figure more than code does.

**Not built now, and that is a judgment rather than a measurement.** Three reasons:

1. **It is additive.** A new method appended after the last (L2) breaks nothing installed, so building it later costs
   no contract version beyond the one it would cost now.
2. **Nothing reads content through the library yet.** The file manager hands documents to viewers by descriptor or by
   handoff, and its previews come through `thumbnail`. The first client that reads a document's bytes in its own
   process — an in-app viewer, a player through a `MediaDataSource` — is what the path is for, and is its trigger.
3. **memfd is unmeasured** on the one kind of ROM that would use it.

**When it is built**, it is encri D113's shape with what the spike found: lengths and offsets in the Binder message
and never in the region, encri copying out of the region once if it ever reads from it, the region zeroed when the
session ends, and the region handed out only after `setProtect(PROT_READ)` has succeeded.

**Flagged for veto:**

1. **The read path waits for a client that needs it**, though it is measured at about three times faster per block.

*Rejected:* building the path on the ashmem result alone, when the phone most likely to use memfd has not run it.

### L15 — The registry: a text file per app, a module with no dependencies, and a snapshot in the jar
*2026-10-23 · accepted; **flagged for veto on two points** (below). Builds `ROADMAP.md` item 5, encri D114's
registry, in the place encri D115 put it. encri reads it as its D122.*

**One file per app, `registry/<package>.app`**, of `key: value` lines: `package`, `developer`, `source`, one or more
`certificate`, `rights`, `minimum-version` and an optional `refused-versions` — encri D114's list of what an entry
holds, with the developer added because the label claims *we know who wrote this*. `registry/README.md` is the format
and what a reviewer checks.

**Strict, because a mistake is a label on a stranger's app.** An unknown key is an error rather than ignored, so a
misspelt `refused-versions` cannot refuse nothing; every key but `certificate` appears once; the file is named after
its package, so a pull request cannot list one app under another's name; a certificate is 64 hex digits, read with or
without `apksigner`'s colons; `rights` is from the contract's five and always includes `read`; version codes are
positive. A registry that lists a package twice is refused whole.

**`encri-registry`, the pure-JVM module L1 foresaw**, with no dependency but the standard library. *Rejected:* TOML or
JSON, which would bring a parser into encri's verified set and into the jar encri builds in, for a format of seven
keys a reviewer reads in a diff. It answers a recognition per app asking: **reviewed**, with the rights it asked for
beyond review named; **signed with another key**, the package listed and the certificate not — a lookalike using the
name, or a build somebody signed themselves; **refused**, a version below the floor or listed as refused; or **not
listed**.

**The snapshot is a resource in the module's jar**, every entry in name order, so the registry an encri recognizes is
the one in the encri-api commit it was built from (encri D114, D116). encri depends on the artifact as it does on
the contract, so no copy is kept in step by hand.

**CI validates it on every pull request**: a unit test reads the repository's own `registry/` and checks it is the
snapshot the jar carries, and `:encri-registry:validateRegistry` says each entry back for the author. The module's
runtime classpath is locked and scanned with the others (L10). Coverage gates 90% line and 85% branch, which caught
that nothing had read a non-empty snapshot while the registry was empty.

**It is empty.** No app has a release key to list; encri's file manager is the first expected. The fingerprints file
encri D115 asks for — every first-party app's package, certificate and code-transparency key, inside a GPG-signed
tag — waits on the same key, and on a first release to tag.

**Flagged for veto:**

1. **A plain-text format of our own** rather than a standard one, for the reason above.
2. **A version the registry refuses is not let in at all** (encri D122), where everything else it says is words.

*Rejected:* listing first-party apps by their shared key rather than one by one — encri D115 has encri and the file
manager signed alike, and an entry per app is what lets one be refused without the other.

### L16 — The public API is dumped by kotlin-reflect and checked on every test run
*2026-10-23 · accepted. Builds the API dump `ROADMAP.md` owed before the first release, and is L5's guard grown
into a full record.*

**`api/encri-client.api` and `api/encri-registry.api` are the public API**, as an app or encri compiles against
it: every public and protected class with its kind, type parameters and supertypes, and every constructor, function
and property with its parameter names and which have defaults, sorted. `PublicApiTest` in each module compares the
module with its file; a difference fails with the lines removed and added, and `-PupdateApi` writes the new dump. A
change to what an app compiles against is then a diff in review. Checked by renaming one parameter in the client's
dump: the test named both lines.

**Read through kotlin-reflect, because the bytecode cannot tell `internal` from public**: an internal declaration is
public there, with a mangled name or, at the top level, none. The first dump of the registry listed its internal
`validate` until top-level functions were mapped back to their Kotlin declarations; the module's own check is what
said so. Parameter names are in it because a renamed parameter breaks a caller using named arguments.

**Kotlin's own ABI validation was tried first and does not work here yet.** `abiValidation {}` registers
`checkKotlinAbi` and `updateKotlinAbi` on an AGP library with the built-in Kotlin, and the dump task depends on no
compilation and writes nothing: KGP 2.4.21 does not wire AGP 9.4.1's compilations into it. *Rejected:* the
binary-compatibility-validator plugin, which brings a set of artifacts encri's verified set does not hold, for what
one unpublished module of a hundred lines does.

**`:api-dump` is that module**, shared by the tests and never published. It brings kotlin-reflect 2.4.21, test-only,
the one component the metadata gained; encri's file holds 2.4.0.

**The contract is not dumped**: its Java is the aidl tool's, and its wire is L2's to guard (L18).

### L17 — An app needs Kotlin 2.1, and receives kotlin-stdlib 2.2.10
*2026-10-23 · accepted; **flagged for veto** (below). Settles L3's open question.*

**Every published module compiles its main sources at language and API version 2.2 and declares kotlin-stdlib
2.2.10**, where the compiler's defaults were 2.4 and 2.4.21. What an app needs follows from the metadata: a compiler
reads one version ahead, so 2.2 metadata wants Kotlin 2.1 or newer, where 2.4 wanted 2.3.

**Measured with `kotlinc`, three ways**, compiling one consumer file that connects, opens a session and lists a
folder:

| Compiler | Against | Result |
|---|---|---|
| 2.1.21 | the client with the floor | compiles |
| 2.0.21 | the client with the floor | refused: *metadata is 2.2.0, expected version is 2.0.0* |
| 2.1.21 | the client before the floor | refused: *metadata is 2.4.0, expected version is 2.1.0* |

**2.2 rather than 2.1**, because 2.1 compiles and the compiler warns that 2.1 is deprecated and its support will be
removed; a floor that breaks at the next compiler bump is one the next bump has to move. 2.1 would have bought
consumers on Kotlin 2.0, which kotlinx-coroutines 1.10.2, the client's `api` dependency, can serve but the 2.4
compiler cannot be made to target without the warning.

**kotlin-stdlib 2.2.10, the oldest the 2.2 API allows that the verified set already holds**, from AGP's toolchain:
the metadata did not change. An app resolves the newer of its own and this. The three release-classpath lockfiles
move to it, and OSV knows no advisory for 2.2.10 or 2.4.21. Main sources only: the tests use what they like.

**Flagged for veto:**

1. **Kotlin 2.1 as the floor**, which an app older than that meets as a metadata error at its first build.

### L18 — The contract only grows, checked against the last release; and a consumer needs no R8 rules
*2026-10-23 · accepted. Builds the two remaining release checks `ROADMAP.md` owed that need no release.*

**`scripts/ci/append-only.py` is L2's rule as a check.** It reads the `.aidl` files at a git ref and in the tree:
every interface's methods at the ref must be a prefix of today's, unchanged; every parcelable's fields likewise;
every constant keeps its value, except `VERSION`, which may only rise; nothing is removed or changes kind, `oneway`
included. CI runs it against the newest `v*` tag, and until there is one says there is nothing to compare with.
Checked against the contract before L11, which it accepts, and against planted breaches — two methods swapped, a
field retyped, a status renumbered, `VERSION` lowered, `oneway` dropped — each of which it names. `WireNamesTest`
stays: it pins what an installed client has compiled in, and this pins what a release promised.

**A consumer needs no R8 rules, measured.** A throwaway app with R8 on, built against this repository as a composite
build, ran every request the client has against encri on the A20: connecting, the consent screen, a write and its
read back, listing, `stat`, a preview, a handoff read through `ContentResolver` and revoked, `measure`, `space`,
`setTimes`, rename and delete. R8 renamed the contract's classes — `Entry`, the session proxy — and nothing broke,
because the aidl tool reaches every `CREATOR` statically, a transaction carries its interface's name as a literal,
and the client reads nothing by name. The same run is the composite-build substitution L1 describes, from outside
this build.

*Not settled here:* publishing (L1), which needs `com.systemhalted` verified on Maven Central and the signing of
artifacts decided.

---

## Open questions

- **How the artifacts are signed for Maven Central** (L1): the repository's signing key, or one of
  their own.
- ~~**The Kotlin floor for consumers** (L3).~~ **Kotlin 2.1 (L17).**
- ~~**Where encri D113's spike lives** — a read-only `SharedMemory` handoff and a Binder round trip on
  the four phones. It needs a service and a client in two processes, and either repository could hold
  it.~~ **Here, beside the device tests (L14).**

## Build order

1. ~~Scaffold: the Gradle project, both modules, the gates and the hooks.~~ **Done 2026-10-22** — L1
   to L5.
2. Everything after is in `ROADMAP.md`.

# encri-api

A client-only Android library for reaching an encri vault without the system file picker. It binds to
encri's companion service over AIDL; the service itself is part of encri, not of this library.

**Pre-release. Nothing is published.** The client has run against encri on two phones (`AGENTS.md` L13). See
[`PROTOCOL.md`](PROTOCOL.md) for the protocol and [`ROADMAP.md`](ROADMAP.md) for what comes next.

An app declares one dependency, the client, and codes against its types alone. The contract comes
with it at runtime and is never on the app's compile classpath.

| Module | Artifact | What it is |
|---|---|---|
| `encri-client` | `com.systemhalted.encri:encri-client` | what an app integrates |
| `encri-contract` | `com.systemhalted.encri:encri-contract` | the `.aidl` files, which are the specification |
| `encri-registry` | `com.systemhalted.encri:encri-registry` | the apps encri recognizes, which encri builds in; see [`registry/`](registry/README.md) |

## Using it

```kotlin
when (val connected = Encri.connect(context)) {
    is ConnectResult.Connected -> connected.connection.use { connection ->
        when (val outcome = connection.openSession()) {
            is SessionOutcome.Opened -> outcome.session.use { session ->
                val top = session.list(session.grant.scope.id)
            }
            SessionOutcome.NotAuthorized ->
                startActivityForResult(Encri.authorizationIntent(setOf(Right.READ, Right.WRITE)), ASK)
            SessionOutcome.Locked -> startActivityForResult(Encri.unlockIntent(), UNLOCK)
            else -> Unit
        }
    }
    else -> Unit // not installed, not trusted, or too old: each says which
}
```

Backups stay in encri: `Encri.backupsIntent()`, `saveBackupFileIntent()` and `restoreIntent()` open encri's own
screens for them with `startActivity`, and need no session (`AGENTS.md` L20).

An app needs Kotlin 2.1 or newer (`AGENTS.md` L17), and R8 needs no rules for the library (L18).

Every request is a `suspend` function that answers a `EncriResult` and never throws. Before binding,
the library checks that the encri installed is signed by a key the app trusts: by default, the app's
own key; otherwise one the app pins with `EncriTrust.alsoTrusting`.

## Building

JDK 21 and the Android SDK (compileSdk 36). Then:

```sh
lefthook install    # once per clone: signed, Conventional commits and the local gates
./gradlew ktlintCheck detekt allUnitTests koverVerify assemble
```

A change to the public API fails a test until the dump in `api/` is rewritten with `-PupdateApi` (L16).

The device tests run on a phone with `scripts/device/run-device-tests.sh <serial>`, and against the encri installed
there with its vault's passphrase in `ENCRI_PASSPHRASE`; CI runs them on emulators, where encri is not installed.

Licensed under the [Apache License 2.0](LICENSE).

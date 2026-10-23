# crippy-api

A client-only Android library for reaching a crippy vault without the system file picker. It binds to
crippy's companion service over AIDL; the service itself is part of crippy, not of this library.

**Pre-release. Nothing is published, and neither half has run against the other on a phone yet.** See
[`PROTOCOL.md`](PROTOCOL.md) for the protocol and [`ROADMAP.md`](ROADMAP.md) for what comes next.

An app declares one dependency, the client, and codes against its types alone. The contract comes
with it at runtime and is never on the app's compile classpath.

| Module | Artifact | What it is |
|---|---|---|
| `crippy-client` | `com.systemhalted.crippy:crippy-client` | what an app integrates |
| `crippy-contract` | `com.systemhalted.crippy:crippy-contract` | the `.aidl` files, which are the specification |

## Using it

```kotlin
when (val connected = Crippy.connect(context)) {
    is ConnectResult.Connected -> connected.connection.use { connection ->
        when (val outcome = connection.openSession()) {
            is SessionOutcome.Opened -> outcome.session.use { session ->
                val top = session.list(session.grant.scope.id)
            }
            SessionOutcome.NotAuthorized ->
                startActivityForResult(Crippy.authorizationIntent(setOf(Right.READ, Right.WRITE)), ASK)
            SessionOutcome.Locked -> startActivityForResult(Crippy.unlockIntent(), UNLOCK)
            else -> Unit
        }
    }
    else -> Unit // not installed, not trusted, or too old: each says which
}
```

Every request is a `suspend` function that answers a `CrippyResult` and never throws. Before binding,
the library checks that the crippy installed is signed by a key the app trusts: by default, the app's
own key; otherwise one the app pins with `CrippyTrust.alsoTrusting`.

## Building

JDK 21 and the Android SDK (compileSdk 36). Then:

```sh
lefthook install    # once per clone: signed, Conventional commits and the local gates
./gradlew ktlintCheck detekt allUnitTests koverVerify assemble
```

Licensed under the [Apache License 2.0](LICENSE).

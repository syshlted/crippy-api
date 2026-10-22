# crippy-api

A client-only Android library for reaching a crippy vault without the system file picker. It binds to
crippy's companion service over AIDL; the service itself is part of crippy, not of this library.

**Pre-release. Nothing is published, and the contract has one method.** See
[`PROTOCOL.md`](PROTOCOL.md) for what the protocol says so far and [`ROADMAP.md`](ROADMAP.md) for
what comes next.

An app declares one dependency, the client, and codes against its types alone. The contract comes
with it at runtime and is never on the app's compile classpath.

| Module | Artifact | What it is |
|---|---|---|
| `crippy-client` | `com.systemhalted.crippy:crippy-client` | what an app integrates |
| `crippy-contract` | `com.systemhalted.crippy:crippy-contract` | the `.aidl` files, which are the specification |

## Building

JDK 21 and the Android SDK (compileSdk 36). Then:

```sh
lefthook install    # once per clone: signed, Conventional commits and the local gates
./gradlew ktlintCheck detekt allUnitTests koverVerify assemble
```

Licensed under the [Apache License 2.0](LICENSE).

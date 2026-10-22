# libcrippy

An Android library for reaching a crippy vault without the system file picker: the Binder contract
crippy's companion service implements, and a client library that binds to it.

**Pre-release. Nothing is published, and the contract has one method.** See
[`PROTOCOL.md`](PROTOCOL.md) for what the protocol says so far and [`ROADMAP.md`](ROADMAP.md) for
what comes next.

| Module | Artifact | What it is |
|---|---|---|
| `contract` | `com.systemhalted.libcrippy:contract` | the `.aidl` files, which are the specification |
| `client` | `com.systemhalted.libcrippy:client` | what an app integrates |

## Building

JDK 21 and the Android SDK (compileSdk 36). Then:

```sh
lefthook install    # once per clone: signed, Conventional commits and the local gates
./gradlew ktlintCheck detekt allUnitTests koverVerify assemble
```

Licensed under the [Apache License 2.0](LICENSE).

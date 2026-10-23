# The registry of recognized apps

crippy's consent screen says who wrote an app asking to reach the vault when the app is listed here. Each
crippy release builds in the registry of the crippy-api release it was built against, so the check needs no
network (crippy D114, D115; `AGENTS.md` L15).

**Being listed never grants access.** It changes what the consent screen says, and the user still decides.
An app that is not listed can be let in too, without the label — a build somebody made from source and
signed with their own key, for one. The one thing an entry takes away is a version it refuses: crippy does
not let that build in at all.

**It is empty**, until an app has a release key to list: crippy's own file manager is the first expected.

## Adding an app

One file per app, `registry/<package>.app`, in a pull request:

```
# Comments start with a hash. Blank lines are ignored.
package: com.example.files
developer: Example Ltd
source: https://github.com/example/files
certificate: 3f1c…
rights: read write create
minimum-version: 12
refused-versions: 14, 15
```

| Key | What it holds |
|---|---|
| `package` | The app's package. The file must be named after it. |
| `developer` | Who wrote it, as the consent screen will name them. |
| `source` | Where its source is, an `https` address: what a reviewer read, and what a user can. |
| `certificate` | The SHA-256 of its signing certificate, as `apksigner verify --print-certs` prints it. Once per key: a key rotated with both builds recognized is listed twice. |
| `rights` | What it was reviewed for, from `read write create delete move`. Always `read`. Anything more an app asks for is called unreviewed. |
| `minimum-version` | The lowest version code recognized. |
| `refused-versions` | Optional: version codes crippy refuses outright, for a build known to be bad. |

Every key but `certificate` appears once, and a key the format does not know is an error rather than
ignored, so a misspelt `refused-versions` cannot refuse nothing.

Check an entry before opening the pull request:

```sh
./gradlew :crippy-registry:validateRegistry
```

CI runs the same rules on every pull request.

## What a reviewer checks

The label claims *we know who wrote this and read its source*, not *this build was audited*, so:

- that the developer is who they say, and the source at `source` builds the app named by `package`;
- that the certificate is the one the developer's published releases are signed with;
- that the rights listed are the ones the app's source uses the client library for;
- for a refused version, why, in the pull request.

A stolen key is answered by refusing the versions it signed and shipping a crippy release, or by removing
the certificate; a compromised developer, by removing the entry.

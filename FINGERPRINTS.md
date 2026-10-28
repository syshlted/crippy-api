# Signing fingerprints

The keys SystemHalted's encri apps are signed with, so that a build can be checked against them (encri D115, L22).
Every release of this repository is inside a tag signed with GPG key `E483D747728C1C9EA213FB29615692825276E947`,
which is published on keys.openpgp.org and on GitHub, so this file cannot be changed without the tag showing it.
The same fingerprints are in Encri Files' repository, on each app's Play listing and on the website; they should
all agree.

## The app signing certificate

encri and Encri Files are signed with one key, the same on Google Play and on GitHub, so an app can move between
them without being uninstalled.

| App | Package | SHA-256 of the signing certificate |
|---|---|---|
| encri | `com.systemhalted.encri` | `BA:30:D5:EE:37:95:21:AA:A4:C4:C7:38:12:8A:DA:6A:29:4F:AC:41:1F:A4:79:AF:B0:7D:78:B5:EE:4C:CC:49` |
| Encri Files | `com.systemhalted.encri.files` | `BA:30:D5:EE:37:95:21:AA:A4:C4:C7:38:12:8A:DA:6A:29:4F:AC:41:1F:A4:79:AF:B0:7D:78:B5:EE:4C:CC:49` |

Check an installed app with [AppVerifier](https://github.com/soupslurpr/AppVerifier), which accepts these lines:

```
com.systemhalted.encri
BA:30:D5:EE:37:95:21:AA:A4:C4:C7:38:12:8A:DA:6A:29:4F:AC:41:1F:A4:79:AF:B0:7D:78:B5:EE:4C:CC:49

com.systemhalted.encri.files
BA:30:D5:EE:37:95:21:AA:A4:C4:C7:38:12:8A:DA:6A:29:4F:AC:41:1F:A4:79:AF:B0:7D:78:B5:EE:4C:CC:49
```

or a downloaded APK with `apksigner verify --print-certs <file>.apk`.

## The code transparency key

Google Play re-signs what it serves, so its builds also carry a code transparency file, signed with a key only
SystemHalted holds and used for nothing else. It shows that the code Play serves is the code that was uploaded
(encri D115, D140).

| Apps | SHA-256 of the code transparency key certificate |
|---|---|
| encri, Encri Files | `D2:2C:14:60:E5:51:7A:2C:51:7B:BD:83:DF:00:A5:CA:4C:4D:C1:49:4A:93:17:63:3A:62:01:64:5F:27:72:65` |

Check an app installed from Play with
`bundletool check-transparency --mode=connected_device --package-name=<package>`, and compare the fingerprint
it prints with this one.

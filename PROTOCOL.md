# The crippy companion protocol

**Draft. Nothing here has shipped, and until it does any of it may change.** After the first release,
methods are only ever added.

crippy is an encrypted file vault for Android. Besides the system file picker, it offers a Binder
service that an app can bind to and read the vault through, without the picker and without Android
keeping a record of which document went to which app. The client library in this repository does all
of the below for you; this file is for anyone who wants to know what it does, or to write their own.

## The service

- The interface is `com.systemhalted.crippy.api.ICrippyService`, specified by
  [`ICrippyService.aidl`](contract/src/main/aidl/com/systemhalted/crippy/api/ICrippyService.aidl).
  The `.aidl` file is normative; where it and this page disagree, the `.aidl` file is right.
- It is exported by the app `com.systemhalted.crippy`. The component a client binds to is not
  defined yet.

## Versions

- **The first call is `contractVersion()`**, transaction 1. It returns the newest version of this
  contract the installed crippy implements.
- A version only ever adds methods after the last one. A client may call a method if its version is
  at most what `contractVersion()` returned.
- **Never call a method above that version.** An older crippy does not refuse it: the call returns
  `0`, `false` or `null` as though it had worked.
- `contractVersion()` never returns 0 from crippy. A 0 means the binder is not crippy's service.

## Not specified yet

Which apps may connect and how a user allows one, sessions, listing, opening a document, and checking
that the service really is crippy. See [`ROADMAP.md`](ROADMAP.md).

# The encri companion protocol

**Draft. Nothing here has shipped, and until it does any of it may change.** After the first release,
methods are only ever added.

encri is an encrypted file vault for Android. Besides the system file picker, it offers a Binder
service that an app can bind to and read the vault through, without the picker and without Android
keeping a record of which document went to which app. The client library in this repository does all
of the below for you, and keeps the AIDL types out of an app's sight. This file is for anyone who
wants to know what it does, or to write a client of their own against `encri-contract`.

The `.aidl` files in [`encri-contract`](encri-contract/src/main/aidl/com/systemhalted/encri/api) are
normative; where they and this page disagree, the `.aidl` files are right.

## Finding encri, and trusting it

- encri's package is `com.systemhalted.encri`. From API 30 an app must declare it in `<queries>` to
  see it; the client library declares it for you.
- **Check encri's signing certificate before binding.** An app named `com.systemhalted.encri` can be
  installed by anybody once encri is not, and a client that binds to it hands it everything it writes.
  Compare the SHA-256 of a certificate in encri's signing history with one you trust, or ask
  `PackageManager.checkSignatures` whether encri is signed by your own key.

## The service

- Bind with action `com.systemhalted.encri.api.action.BIND` and the package set to encri's. Not by
  class name: the class may move.
- The service is `IEncriService`. It is exported with no permission. What stands between an app and the
  vault is the user's permission, given on encri's own screen, and a switch the user controls.

## Versions

- **The first call is `contractVersion()`**, transaction 1. It returns the newest version of this
  contract the installed encri implements. This document describes version 1.
- A version only ever adds methods after the last one. A client may call a method if its version is
  at most what `contractVersion()` returned.
- **Never call a method above that version.** An older encri does not refuse it: the call returns `0`,
  `false` or `null` as though it had worked.
- `contractVersion()` never returns 0 from encri. A 0 means the binder is not encri's service.

## Results

- **Every method returns a parcelable with a `status`**, and `STATUS_OK` is 1. A null result means the
  method was not answered — an older encri that lacks it — and is not success.
- `message` says why, in English, for a developer. Do not show it to a user as it is.
- A parcelable's fields are only ever appended. A reader skips fields a newer writer added and sees
  defaults for fields an older one did not send.

## Sessions

1. `openSession(listener)` with an `IVaultListener`. encri answers, in this order:
   - `STATUS_ACCESS_OFF` — the user turned access for other apps off in encri's settings;
   - `STATUS_LOCKED` — the vault is locked. A locked vault cannot say which apps it allows, so every
     app hears this alike;
   - `STATUS_NOT_AUTHORIZED` — the user has not allowed this app in, or allowed a different install or
     version of it;
   - `STATUS_OK`, with an `IEncriSession` and the `Grant` it carries.
2. **The session is this app's alone.** encri checks on every call that the caller is the app the
   session was opened for. It ends when the app calls `end()`, the app's process dies, the user turns
   the app out, or the user turns access for other apps off. **It survives a lock**: while the vault is
   locked every call answers `STATUS_LOCKED`, and the listener hears the lock and the unlock.

### Asking the user

- **Allowing an app in:** start an Intent with action `com.systemhalted.encri.api.action.AUTHORIZE`,
  the package set to encri's, and `com.systemhalted.encri.api.extra.RIGHTS` holding the rights asked
  for, **with `startActivityForResult`**. encri names the app from the system's record of who started
  the screen, which only `startActivityForResult` sets; started any other way, the screen refuses. The
  user chooses whether to allow the whole vault or one folder. `RESULT_OK` carries the rights allowed in
  the same extra.
- **Unlocking:** start action `com.systemhalted.encri.action.UNLOCK` for a result, the package set to
  encri's. It answers `RESULT_OK` once the vault is unlocked, at once if it already is.

### Backups are encri's own screens

A backup file and a restore from one are not requests a session makes. Their passphrases open the whole
vault, so they are only ever typed on encri's own screen. A client offers an entry that opens that screen:

| Action | The screen |
|---|---|
| `com.systemhalted.encri.api.action.SAVE_BACKUP_FILE` | save a backup file of the whole vault where the user picks |
| `com.systemhalted.encri.api.action.RESTORE` | restore a backup file into the vault, beside what is there |

Start each with `startActivity` and the package set to encri's. No session or grant is needed. encri
reads nothing from the Intent and answers no result: where a file goes or comes from is picked in the
system picker, and what happened is encri's to say. An encri that predates these actions does not answer
them, so resolve the Intent before offering the entry.

### Rights

| Bit | Name | Allows |
|---|---|---|
| 1 | `RIGHT_READ` | listing, looking up, reading, previews and handoffs. Every grant carries it |
| 2 | `RIGHT_WRITE` | replacing a document's content, renaming it, setting its times |
| 4 | `RIGHT_CREATE` | creating documents and folders |
| 8 | `RIGHT_DELETE` | deleting documents and folders, a folder with everything in it |
| 16 | `RIGHT_MOVE` | moving documents and folders |

A grant is rooted at the whole vault or at one folder. **An id outside it answers `STATUS_NOT_FOUND`**,
the same as an id that does not exist. The folder a grant is rooted at cannot be renamed, moved or
deleted through it, and its parent reads as null.

## What a session can do

- **`stat(id)`** — one document or folder. Every entry carries its name, MIME type (folders are
  `vnd.android.document/directory`), created and modified times, a document's size, and a folder's
  count of direct children.
- **`list(folderId, after, limit)`** — a page of a folder's children in name order. Pass `next` back as
  `after` for the following page; encri caps the page size.
- **`measure(folderId)`** — everything under a folder, counted: bytes, documents and folders.
- **`openRead(id)`** — a read-only descriptor that seeks and may be mapped or handed to another app. It
  fails at its next read when the session ends or access for other apps is turned off. It goes on serving
  across a lock, as an open file does: the lock stops new requests, not a document already open.
- **`openWrite(id, listener)`** — the write end of a reliable pipe. Write the new content and close it
  cleanly to save it; close it with an error, or let your process die, and nothing is saved. The
  listener says whether it was saved, and if not, why. A write through a pipe cannot seek.
- **`create`, `rename`, `move`** take `FLAG_FREE_NAME` to pick the first free name rather than answer
  `STATUS_NAME_TAKEN`. **`delete`** deletes a folder with everything in it.
- **`setTimes(id, created, modified)`** — -1 leaves a time as it is.
- **`requestThumbnail(id, width, height, listener)`** — a ticket now, and later an encoded PNG or JPEG
  on a descriptor, no larger than the size asked on either side, its aspect kept, and never larger than the
  picture itself. `cancelThumbnail(requestId)` if it is no longer wanted.
- **`handOff(ids)`** — a `content://` URI per document, granted to your app for reading, to pass to
  another app with `FLAG_GRANT_READ_URI_PERMISSION`: *open with*, or the share sheet. It reveals neither
  the document's id nor its name; the app it reaches reads the name through `OpenableColumns`. Every
  handoff ends when the vault locks, the session ends or `revokeHandoffs` is called.
- **`space()`** — how much more the vault can hold, after encri's framing.
- **`lock()`** — locks the vault, for every app and for encri.

## Listeners

All three are `oneway`: encri never waits for a client to answer.

- `IVaultListener.onStateChanged(state)` — `STATE_LOCKED`, `STATE_UNLOCKED`, or `STATE_ENDED` when the
  session is over. Ignore a state you do not know.
- `IVaultListener.onFolderChanged(folderId)` — best effort, as SAF's notifications are.
- `IWriteListener.onWriteFinished(result)` and `IThumbnailListener.onThumbnail(requestId, result)`.

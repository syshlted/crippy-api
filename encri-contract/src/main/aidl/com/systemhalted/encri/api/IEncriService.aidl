// The service encri exports to apps that reach the vault without SAF (encri D113). This file and
// the others in this directory are the wire specification: what is declared here is what crosses
// the boundary.
//
// Two rules make it a contract rather than an interface, and both are in AGENTS.md L2:
//
//  - The interface's name is permanent. Every transaction carries "com.systemhalted.encri.api.
//    IEncriService" as its interface token, and encri refuses one that does not match, so moving
//    this file to another package breaks every installed client.
//  - Methods are only ever appended. A method's transaction code is its position here, so removing,
//    reordering or inserting one changes what every installed client is asking for.
//
// The constants below are the protocol's vocabulary, shared by every interface here (L11). They
// are values an installed client compiles in, so a constant never changes once it has shipped.
package com.systemhalted.encri.api;

import com.systemhalted.encri.api.IVaultListener;
import com.systemhalted.encri.api.SessionResult;

interface IEncriService {
    /**
     * The contract version this file describes. A version adds methods after the last one and
     * changes nothing above it, so a client may call any method whose version is at most what
     * contractVersion() returns.
     */
    const int VERSION = 1;

    // ---- Where the service is ----------------------------------------------------------------

    /** encri's package. A client binds only to a service in this package. */
    const String PACKAGE = "com.systemhalted.encri";

    /** The action a client binds with, with its package set to PACKAGE. */
    const String ACTION_BIND = "com.systemhalted.encri.api.action.BIND";

    /**
     * The Activity a client starts for a result to ask the user for access (encri D114). It
     * answers Activity.RESULT_OK with EXTRA_RIGHTS holding what the user allowed, or
     * RESULT_CANCELED. It must be started with startActivityForResult: encri reads who is asking
     * from the system, and refuses a request it cannot attribute.
     */
    const String ACTION_AUTHORIZE = "com.systemhalted.encri.api.action.AUTHORIZE";

    /** The rights asked for, in ACTION_AUTHORIZE's request; the rights allowed, in its result. */
    const String EXTRA_RIGHTS = "com.systemhalted.encri.api.extra.RIGHTS";

    /**
     * The Activity a client starts to have the user unlock the vault. Started for a result, it
     * answers RESULT_OK once the vault is unlocked and RESULT_CANCELED if the user backs out.
     */
    const String ACTION_UNLOCK = "com.systemhalted.encri.action.UNLOCK";

    // ---- encri's backup screens (encri D123, L20) --------------------------------------------
    //
    // Backup files, restores and the scheduled backup stay in encri, and a client opens encri's own
    // screens for them. Start each with startActivity and the package set to PACKAGE. They need no
    // session and no grant; they read nothing from the Intent and answer no result. The user does the
    // work on encri's screen, which asks for every passphrase itself and says what the vault's state
    // allows. An encri that predates them does not answer them, so resolve the Intent before offering
    // the entry that starts one.

    /** The automatic backups screen: set one up, run, check or restore one, or stop them (encri D95). */
    const String ACTION_BACKUPS = "com.systemhalted.encri.api.action.BACKUPS";

    /** The screen that saves a backup file of the whole vault where the user picks (encri D78). */
    const String ACTION_SAVE_BACKUP_FILE = "com.systemhalted.encri.api.action.SAVE_BACKUP_FILE";

    /** The screen that restores a backup file into the vault, beside what is there (encri D84). */
    const String ACTION_RESTORE = "com.systemhalted.encri.api.action.RESTORE";

    // ---- Rights a grant carries --------------------------------------------------------------

    /** List, look up, read, preview and hand off documents. Every grant carries it. */
    const int RIGHT_READ = 1;
    /** Replace a document's content, rename it and set its times. */
    const int RIGHT_WRITE = 2;
    /** Create documents and folders. */
    const int RIGHT_CREATE = 4;
    /** Delete documents and folders, a folder with everything in it. */
    const int RIGHT_DELETE = 8;
    /** Move documents and folders to another folder. */
    const int RIGHT_MOVE = 16;

    // ---- Vault states, as IVaultListener reports them ----------------------------------------

    const int STATE_LOCKED = 1;
    const int STATE_UNLOCKED = 2;
    /** The session is over: access was revoked, turned off, or the session was ended. */
    const int STATE_ENDED = 3;

    // ---- Statuses every result carries -------------------------------------------------------
    //
    // None is 0. An older encri answers a method it does not have with an empty reply, which
    // reads as status 0, so 0 must never mean done (L2).

    const int STATUS_OK = 1;
    const int STATUS_LOCKED = 2;
    /** This app has no grant, the grant does not cover what was asked, or it was revoked. */
    const int STATUS_NOT_AUTHORIZED = 3;
    /** The user turned access for other apps off in encri's settings. */
    const int STATUS_ACCESS_OFF = 4;
    /** No such document, or one outside what this app was given: the two are not told apart. */
    const int STATUS_NOT_FOUND = 5;
    /** A document of that name already exists in that folder. */
    const int STATUS_NAME_TAKEN = 6;
    /** The request itself is wrong: a bad name, a file where a folder is needed, a cycle. */
    const int STATUS_INVALID = 7;
    /** The device does not have room for it. */
    const int STATUS_NO_SPACE = 8;
    /** encri does not do this for that document, such as a preview of a type it cannot render. */
    const int STATUS_UNSUPPORTED = 9;
    /** Something went wrong inside encri; the message says what. */
    const int STATUS_FAILED = 10;
    /** The session is over; open a new one. */
    const int STATUS_ENDED = 11;
    /** The request was cancelled before it finished. */
    const int STATUS_CANCELLED = 12;

    // ---- Flags ---------------------------------------------------------------------------------

    /** On a name already taken, use the first free name instead of answering STATUS_NAME_TAKEN. */
    const int FLAG_FREE_NAME = 1;

    /** The MIME type of a folder, as SAF spells it. */
    const String MIME_TYPE_FOLDER = "vnd.android.document/directory";

    /**
     * The newest contract version this service implements: the VERSION of the contract encri was
     * built against. Transaction 1, and it takes nothing, so no later version can ever need to
     * change how it is asked. A client's first call.
     */
    int contractVersion();

    /**
     * Opens a session for the calling app. Returns STATUS_OK with a session and the grant it
     * holds, or STATUS_LOCKED, STATUS_NOT_AUTHORIZED or STATUS_ACCESS_OFF with neither.
     *
     * A locked vault cannot say which apps it allows, so every app hears STATUS_LOCKED. listener
     * is told when the vault locks and unlocks and when a folder in the grant changes, for as long
     * as the session lasts.
     */
    SessionResult openSession(IVaultListener listener);
}

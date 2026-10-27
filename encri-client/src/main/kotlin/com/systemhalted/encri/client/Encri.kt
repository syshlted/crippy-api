package com.systemhalted.encri.client

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.systemhalted.encri.client.internal.Contract
import com.systemhalted.encri.client.internal.PackageIdentity
import com.systemhalted.encri.client.internal.ServiceBinding
import com.systemhalted.encri.client.internal.bitsOf
import com.systemhalted.encri.client.internal.rightsOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Where an app starts: is encri here, can it be trusted, and how to connect to it (encri D113, D114;
 * L11, L12).
 *
 * The sequence an app follows:
 *
 * 1. [connect], which checks that the encri installed is signed by a key this app trusts before it
 *    binds anything (L12), and gives a [EncriConnection].
 * 2. [EncriConnection.openSession]. The first time, it answers [SessionOutcome.NotAuthorized]: start
 *    [authorizationIntent] with `startActivityForResult`, and encri asks the user on its own screen.
 * 3. A vault that is locked answers [SessionOutcome.Locked]: start [unlockIntent] the same way.
 *
 * Backup files are not requests: [saveBackupFileIntent] and [restoreIntent] open encri's own screens for
 * them, and need no connection at all.
 *
 * Nothing here is safe on the main thread except the intents: [connect] and everything after it is
 * `suspend` and moves its own work off the caller's thread.
 */
public object Encri {
    /** encri's package. Pinned here rather than looked up, so a lookalike cannot answer in its place. */
    public const val PACKAGE: String = Contract.PACKAGE

    /** Whether encri is installed here, and signed by a key [trust] accepts. */
    public fun availability(
        context: Context,
        trust: EncriTrust = EncriTrust.DEFAULT,
    ): Availability = PackageIdentity(context).availability(trust)

    /**
     * Connects to encri's service: [ConnectResult.Connected] holds the connection, which the app closes
     * when it is done with it.
     *
     * encri is checked before it is bound to, so an app never hands a lookalike anything (L12): a package
     * named like encri and signed by another key is [ConnectResult.NotTrusted].
     */
    public suspend fun connect(
        context: Context,
        trust: EncriTrust = EncriTrust.DEFAULT,
    ): ConnectResult = connect(context, trust, Dispatchers.IO)

    internal suspend fun connect(
        context: Context,
        trust: EncriTrust,
        io: CoroutineDispatcher,
    ): ConnectResult =
        when (val available = PackageIdentity(context).availability(trust)) {
            Availability.NotInstalled -> ConnectResult.NotInstalled
            is Availability.NotTrusted -> ConnectResult.NotTrusted(available.certificateSha256)
            Availability.Ready -> ServiceBinding.connect(context.applicationContext ?: context, io)
        }

    /**
     * What an app starts with `startActivityForResult` to ask the user for access with [rights]
     * (encri D114). [Right.READ] is always asked for: every grant carries it. Read the answer with
     * [authorizationResult].
     *
     * The user chooses on encri's own screen whether to allow the whole vault or one folder, and encri
     * names the app from the system, never from this request.
     */
    public fun authorizationIntent(rights: Set<Right>): Intent =
        Intent(Contract.ACTION_AUTHORIZE)
            .setPackage(PACKAGE)
            .putExtra(Contract.EXTRA_RIGHTS, bitsOf(rights + Right.READ))

    /** The rights the user allowed, or null if they did not allow the app in. */
    public fun authorizationResult(
        resultCode: Int,
        data: Intent?,
    ): Set<Right>? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        val bits = data.getIntExtra(Contract.EXTRA_RIGHTS, 0)
        return if (bits == 0) null else rightsOf(bits)
    }

    /**
     * What an app starts with `startActivityForResult` to have the user unlock the vault, on encri's own
     * screen. It answers `RESULT_OK` once the vault is unlocked.
     */
    public fun unlockIntent(): Intent = Intent(Contract.ACTION_UNLOCK).setPackage(PACKAGE)

    /**
     * encri's screen that saves a backup file of the whole vault, for a *Save a backup file* entry (encri D123,
     * L20). The user picks where it goes in the system picker and types its passphrase on encri's screen.
     *
     * Backup files stay in encri, so the passphrase that opens one is typed on encri's screen and never in
     * this app. Start it with `startActivity`; it needs no session, reads nothing from the Intent and answers
     * no result. An encri older than this library may not have the screen: resolve the Intent with
     * `PackageManager.resolveActivity` before offering the entry.
     */
    public fun saveBackupFileIntent(): Intent = Intent(Contract.ACTION_SAVE_BACKUP_FILE).setPackage(PACKAGE)

    /**
     * encri's screen that restores a backup file into the vault, beside what is there, for a *Restore*
     * entry. The user picks the file in the system picker. As [saveBackupFileIntent].
     */
    public fun restoreIntent(): Intent = Intent(Contract.ACTION_RESTORE).setPackage(PACKAGE)
}

/** Whether encri can be connected to. */
public sealed interface Availability {
    /** encri is not installed, or this app cannot see it. */
    public data object NotInstalled : Availability

    /**
     * A package named encri is installed and is signed by a key this app does not trust: a lookalike, or
     * a build of encri signed by someone else. [certificateSha256] is its certificate, for a message.
     */
    public data class NotTrusted(
        val certificateSha256: String,
    ) : Availability

    public data object Ready : Availability
}

/** What [Encri.connect] found. */
public sealed interface ConnectResult {
    public data class Connected(
        val connection: EncriConnection,
    ) : ConnectResult

    public data object NotInstalled : ConnectResult

    /** See [Availability.NotTrusted]. Nothing was bound. */
    public data class NotTrusted(
        val certificateSha256: String,
    ) : ConnectResult

    /** encri is installed and older than this library needs: it implements [serviceVersion]. */
    public data class TooOld(
        val serviceVersion: Int,
    ) : ConnectResult

    /** encri would not be bound, or did not answer as encri does. */
    public data class Unavailable(
        val reason: String,
    ) : ConnectResult
}

/** What [EncriConnection.openSession] found. */
public sealed interface SessionOutcome {
    public data class Opened(
        val session: EncriSession,
    ) : SessionOutcome

    /** The vault is locked: start [Encri.unlockIntent], then open the session again. */
    public data object Locked : SessionOutcome

    /** This app has no grant, or the one it had was revoked: start [Encri.authorizationIntent]. */
    public data object NotAuthorized : SessionOutcome

    /** The user turned access for other apps off in encri's settings. */
    public data object AccessOff : SessionOutcome

    public data class Failed(
        val failure: Failure,
        val message: String?,
    ) : SessionOutcome
}

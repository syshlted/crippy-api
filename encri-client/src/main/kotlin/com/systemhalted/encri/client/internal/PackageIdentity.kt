package com.systemhalted.encri.client.internal

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.systemhalted.encri.client.Availability
import com.systemhalted.encri.client.EncriTrust
import java.security.MessageDigest

/**
 * Whether the encri installed here is one this app trusts (encri D114; L12).
 *
 * A pinned certificate is compared against encri's signing-certificate history, at every API level:
 * the certificates `SigningInfo` lists from API 28 — encri's current one and any it rotated away from,
 * which Android accepts for an update and so should this — and `PackageInfo.signatures` below it. That
 * is what `hasSigningCertificate` checks at API 28 and above, done here so one path serves every level.
 * An encri signed by the same key as this app is asked of `checkSignatures`, at every level.
 */
internal class PackageIdentity(
    private val context: Context,
) {
    private val packages: PackageManager get() = context.packageManager

    @Suppress("SwallowedException") // An app that is not installed is the answer, not a failure.
    fun availability(trust: EncriTrust): Availability {
        val installed =
            try {
                packages.getPackageInfo(Contract.PACKAGE, certificateFlag())
            } catch (absent: PackageManager.NameNotFoundException) {
                return Availability.NotInstalled
            }
        return if (trusted(installed, trust)) Availability.Ready else Availability.NotTrusted(digestOf(installed))
    }

    private fun trusted(
        installed: PackageInfo,
        trust: EncriTrust,
    ): Boolean {
        val sameSigner =
            trust.sameSigner &&
                packages.checkSignatures(context.packageName, Contract.PACKAGE) == PackageManager.SIGNATURE_MATCH
        return sameSigner || historyOf(installed).any { sha256Hex(it) in trust.certificates }
    }

    /** encri's current certificate, for a message. Empty when Android gave none. */
    private fun digestOf(installed: PackageInfo): String =
        currentOf(installed).firstOrNull()?.let(::sha256Hex).orEmpty()

    private companion object {
        fun certificateFlag(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION") // The only way to ask at API 27.
                PackageManager.GET_SIGNATURES
            }

        /** The certificates encri is signed with now. */
        @Suppress("DEPRECATION") // `signatures` is the only field at API 27.
        fun currentOf(info: PackageInfo): List<ByteArray> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo
                    ?.apkContentsSigners
                    .orEmpty()
                    .map { it.toByteArray() }
            } else {
                info.signatures.orEmpty().map { it.toByteArray() }
            }

        /**
         * Every certificate a pin may name: the current ones, and those encri rotated away from. A
         * package with several signers has no rotation history, and is named by all of them.
         */
        fun historyOf(info: PackageInfo): List<ByteArray> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return currentOf(info)
            val signing = info.signingInfo
            val certificates =
                when {
                    signing == null -> null
                    signing.hasMultipleSigners() -> signing.apkContentsSigners
                    else -> signing.signingCertificateHistory
                }
            return certificates.orEmpty().map { it.toByteArray() }
        }
    }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

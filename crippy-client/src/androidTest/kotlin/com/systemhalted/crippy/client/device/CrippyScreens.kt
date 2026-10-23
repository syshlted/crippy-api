package com.systemhalted.crippy.client.device

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.systemhalted.crippy.api.ICrippyService

/**
 * Presses crippy's buttons for a test, the way a person would, through the accessibility tree (L13).
 *
 * crippy's screens set `FLAG_SECURE`, so a screenshot is black, and its consent screen drops touches that
 * arrive through another app's window; neither stops the tree, which is how `uiautomator dump` reads
 * them too, and a press through the tree is a click rather than a touch, so the touch filter does not see
 * it either. **Only crippy's own window is ever pressed or typed into**: the passphrase goes nowhere but
 * crippy, whatever happens to be in front.
 *
 * **Matched on crippy's English words**, so this runs on a phone set to English and nowhere else: the
 * words are crippy's `strings.xml`, and a change there is a change here. Case aside, because the theme
 * draws a button's label in capitals and the tree reports what is drawn.
 */
internal class CrippyScreens(
    instrumentation: Instrumentation,
) {
    private val automation = instrumentation.uiAutomation

    /** Whether a button with exactly [label] is on screen now. */
    fun showing(label: String): Boolean = button(label) != null

    /** Waits for a button with exactly [label] and presses it. */
    fun press(
        label: String,
        millis: Long = WAIT_MILLIS,
    ) {
        val found = waitFor(millis) { button(label) } ?: throw AssertionError("crippy never showed “$label”")
        check(found.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "“$label” did not take the press" }
    }

    /** Types [passphrase] into the passphrase field on crippy's locked screen and unlocks with it. */
    fun unlockWith(passphrase: String) {
        val field = waitFor(WAIT_MILLIS) { editable() } ?: throw AssertionError("crippy showed no passphrase field")
        val typed =
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, passphrase)
            }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, typed)) { "the passphrase field took nothing" }
        press(UNLOCK_WITH_PASSPHRASE)
    }

    /** Waits until one of [labels] is on screen, and answers which. */
    fun whichOf(
        vararg labels: String,
        millis: Long = WAIT_MILLIS,
    ): String =
        waitFor(millis) { labels.firstOrNull(::showing) }
            ?: throw AssertionError("crippy showed none of ${labels.toList()}")

    /** The window in front, if it is crippy's; null for anything else, which is never touched. */
    private fun crippys(): AccessibilityNodeInfo? =
        automation.rootInActiveWindow?.takeIf { it.packageName?.toString() == ICrippyService.PACKAGE }

    private fun button(label: String): AccessibilityNodeInfo? =
        crippys()
            ?.findAccessibilityNodeInfosByText(label)
            .orEmpty()
            // Case aside: the theme draws a button's label in capitals, and the tree reports what is drawn.
            .firstOrNull {
                it.text?.toString().equals(label, ignoreCase = true) && it.isClickable && it.isVisibleToUser &&
                    it.isEnabled
            }

    private fun editable(): AccessibilityNodeInfo? {
        val pending = ArrayDeque(listOfNotNull(crippys()))
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.isEditable && node.isVisibleToUser) return node
            (0 until node.childCount).mapNotNullTo(pending) { node.getChild(it) }
        }
        return null
    }

    private fun <T : Any> waitFor(
        millis: Long,
        probe: () -> T?,
    ): T? {
        val until = SystemClock.uptimeMillis() + millis
        while (SystemClock.uptimeMillis() < until) {
            probe()?.let { return it }
            SystemClock.sleep(POLL_MILLIS)
        }
        return null
    }

    companion object {
        // crippy's strings.xml, in English.
        const val UNLOCK_WITH_PASSPHRASE = "Unlock with passphrase"
        const val UNLOCK_THE_VAULT = "Unlock the vault"
        const val TURN_ACCESS_ON = "Turn on access for other apps"
        const val ALLOW_THE_WHOLE_VAULT = "Allow the whole vault"

        private const val WAIT_MILLIS = 20_000L
        private const val POLL_MILLIS = 250L
    }
}

package com.systemhalted.encri.api

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * L11's rule for the parcelables, run: fields are only ever appended, because a structured parcelable
 * writes its own length first. A reader skips a field a newer writer added, and finds a field an older
 * writer did not send at its default. Either half failing would make appending a field a break.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 36])
class ParcelableEvolutionTest {
    @Test
    fun `an entry survives the wire`() {
        val sent =
            Entry().apply {
                documentId = "0123456789abcdef0123456789abcdef"
                parentId = "fedcba9876543210fedcba9876543210"
                name = "notes.txt"
                mimeType = "text/plain"
                folder = false
                sizeBytes = 1234
                createdUtcMillis = 1_000
                modifiedUtcMillis = 2_000
                childCount = -1
                thumbnailable = false
            }
        val received = roundTrip(sent) { Entry.CREATOR.createFromParcel(it) }
        assertEquals(sent.documentId, received.documentId)
        assertEquals(sent.parentId, received.parentId)
        assertEquals(sent.name, received.name)
        assertEquals(sent.mimeType, received.mimeType)
        assertEquals(sent.sizeBytes, received.sizeBytes)
        assertEquals(sent.createdUtcMillis, received.createdUtcMillis)
        assertEquals(sent.modifiedUtcMillis, received.modifiedUtcMillis)
        assertEquals(sent.childCount, received.childCount)
    }

    @Test
    fun `a reader skips a field a newer writer appended`() {
        val parcel = Parcel.obtain()
        try {
            // An Outcome as a later contract might write it: its two fields, and one more after them.
            val start = parcel.dataPosition()
            parcel.writeInt(0)
            parcel.writeInt(IEncriService.STATUS_NAME_TAKEN)
            parcel.writeString("taken")
            parcel.writeLong(NEWER_FIELD)
            val end = parcel.dataPosition()
            parcel.setDataPosition(start)
            parcel.writeInt(end - start)
            parcel.setDataPosition(end)
            parcel.writeInt(SENTINEL)
            parcel.setDataPosition(0)

            val read = Outcome.CREATOR.createFromParcel(parcel)
            assertEquals(IEncriService.STATUS_NAME_TAKEN, read.status)
            assertEquals("taken", read.message)
            // The appended field was skipped, so what follows the parcelable is read where it is.
            assertEquals(SENTINEL, parcel.readInt())
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `a reader finds a field an older writer did not send at its default`() {
        val parcel = Parcel.obtain()
        try {
            // An Entry as an older contract might have written it: only its first field.
            val start = parcel.dataPosition()
            parcel.writeInt(0)
            parcel.writeString("0123456789abcdef0123456789abcdef")
            val end = parcel.dataPosition()
            parcel.setDataPosition(start)
            parcel.writeInt(end - start)
            parcel.setDataPosition(end)
            parcel.setDataPosition(0)

            val read = Entry.CREATOR.createFromParcel(parcel)
            assertEquals("0123456789abcdef0123456789abcdef", read.documentId)
            assertNull(read.name)
            assertFalse(read.folder)
            assertEquals(-1, read.childCount)
        } finally {
            parcel.recycle()
        }
    }

    private fun <T : android.os.Parcelable> roundTrip(
        sent: T,
        read: (Parcel) -> T,
    ): T {
        val parcel = Parcel.obtain()
        return try {
            sent.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            read(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private companion object {
        const val NEWER_FIELD = 0x0123_4567_89ab_cdefL
        const val SENTINEL = 0x5eed
    }
}

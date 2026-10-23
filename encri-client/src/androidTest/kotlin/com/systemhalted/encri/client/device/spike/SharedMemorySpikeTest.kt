package com.systemhalted.encri.client.device.spike

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * encri D113's spike, on a phone (L14): can encri hand a client a region of shared memory the client
 * can read and cannot write, and what does a block cost through it, against the same block through an
 * AppFuse proxy descriptor.
 *
 * **The protection is asserted; the timings are reported.** A client that could make its view writable
 * could change bytes encri reads back, so every way of trying is tried and, last, encri's own copy is
 * checksummed. The timings go to logcat as one JSON line, tag `EncriSpike`, beside a 64 KiB memcpy
 * measured the same way (encri D40: a figure without a control beside it is not one).
 */
@RunWith(AndroidJUnit4::class)
class SharedMemorySpikeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var connection: ServiceConnection
    private lateinit var spike: IBinder

    @Before
    fun bindTheSpike() {
        val bound = LinkedBlockingQueue<IBinder>()
        connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    bound.offer(service)
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
        assertTrue(context.bindService(Intent(context, SpikeService::class.java), connection, Context.BIND_AUTO_CREATE))
        spike = requireNotNull(bound.poll(BIND_SECONDS, TimeUnit.SECONDS)) { "the spike did not answer" }
        ask(SpikeService.REFILL)
    }

    @After
    fun unbind() = context.unbindService(connection)

    @Test
    fun aRegionHandedOverReadOnlyCannotBeWrittenThroughByTheClient() {
        val before = openFds()
        val region = askForRegion()
        // Only descriptors that name shared memory are ever written at: another descriptor that happened to
        // open meanwhile could be anything, and an ftruncate on it would not be a test.
        val received =
            (openFds() - before).filter { fd ->
                readlinkOrNull(fd).orEmpty().let {
                    "ashmem" in it ||
                        "memfd" in it
                }
            }
        assertTrue("no descriptor named the region", received.isNotEmpty())
        val report = JSONObject()
        report.put("api", Build.VERSION.SDK_INT)
        report.put("device", Build.MODEL)
        report.put("names", received.map { readlinkOrNull(it) }.distinct().joinToString())

        // What encri wrote, read through the client's own mapping.
        val view = region.mapReadOnly()
        assertArrayEquals(SpikeService.BLOCK, ByteArray(SpikeService.BLOCK_BYTES).also { view.get(it) })

        report.put("mapReadWrite", attempt { region.mapReadWrite() })
        report.put(
            "mapWritable",
            attempt {
                region.map(OsConstants.PROT_READ or OsConstants.PROT_WRITE, 0, SpikeService.REGION_BYTES)
            },
        )
        report.put(
            "widenProtect",
            attempt {
                check(region.setProtect(OsConstants.PROT_READ or OsConstants.PROT_WRITE)) { "refused" }
            },
        )
        report.put("viewPut", attempt { view.duplicate().put(0, 1) })
        for (fd in received) {
            val raw = ParcelFileDescriptor.fromFd(fd)
            report.put("fd$fd.mmapShared", attempt { mmapWritable(raw, OsConstants.MAP_SHARED) })
            report.put("fd$fd.mmapPrivate", attempt { mmapWritable(raw, OsConstants.MAP_PRIVATE) })
            report.put("fd$fd.pwrite", attempt { Os.pwrite(raw.fileDescriptor, byteArrayOf(1), 0, 1, 0) })
            report.put("fd$fd.ftruncate", attempt { Os.ftruncate(raw.fileDescriptor, 0) })
            report.put("fd$fd.reopenRdwr", attempt { Os.close(Os.open("/proc/self/fd/$fd", OsConstants.O_RDWR, 0)) })
            // What a reopen reaches: on ashmem, opening the device makes a new region of no size, which no
            // mapping fits; on a memfd it would be the same file, under the seal.
            report.put("fd$fd.reopenThenMapShared", attempt { mapAfterReopen(fd) })
            raw.close()
        }
        Log.i(TAG, report.toString())

        // The assertion that matters: nothing the client tried changed what encri holds.
        assertEquals("the client changed encri's copy", pristine(), checksum())

        // encri's writes after the hand-over reach the client's view, and zeroing it clears that view.
        ask(SpikeService.ZERO)
        assertEquals(0, view.get(SpikeService.BLOCK_BYTES / 2).toInt())
        SharedMemory.unmap(view)
        region.close()
    }

    @Test
    fun whatABlockCostsThroughTheRegionAndThroughAppFuse() {
        val region = askForRegion()
        val view = region.mapReadOnly()
        val into = ByteArray(SpikeService.BLOCK_BYTES)
        val nothing = measure(ROUND_TRIPS) { ask(SpikeService.NOTHING) }
        var index = 0
        val shared =
            measure(BLOCKS) {
                val at = index++ % (SpikeService.REGION_BYTES / SpikeService.BLOCK_BYTES)
                ask(SpikeService.BLOCK_INTO_REGION, at)
                view.position(at * SpikeService.BLOCK_BYTES)
                view.get(into)
            }
        val copy = measure(BLOCKS) { System.arraycopy(SpikeService.BLOCK, 0, into, 0, SpikeService.BLOCK_BYTES) }
        val proxied = proxyReads(into)
        Log.i(
            TAG,
            JSONObject()
                .put("api", Build.VERSION.SDK_INT)
                .put("device", Build.MODEL)
                .put("binderRoundTrip", nothing.json())
                .put("blockThroughRegion", shared.json())
                .put("blockThroughAppFuse", proxied.json())
                .put("memcpyControl", copy.json())
                .toString(),
        )
        assertArrayEquals("the region served the wrong bytes", SpikeService.BLOCK, into)
        SharedMemory.unmap(view)
        region.close()
    }

    /**
     * Blocks of a large proxy descriptor once each, in shuffled order and four apart, so no timed read is a
     * cache hit: not of an earlier read, and not of the readahead a read before it pulled in (encri D22,
     * which stepped two apart for the G6's 128 KiB requests). The warm-up reads the far half of the
     * descriptor, which the timed reads never touch.
     */
    private fun proxyReads(into: ByteArray): Latencies {
        val reply = Parcel.obtain()
        val data = Parcel.obtain()
        val descriptor =
            try {
                spike.transact(SpikeService.PROXY, data, reply, 0)
                ParcelFileDescriptor.dup(reply.readFileDescriptor().fileDescriptor)
            } finally {
                data.recycle()
                reply.recycle()
            }
        return descriptor.use {
            val half = (SpikeService.PROXY_BYTES / SpikeService.BLOCK_BYTES / 2).toInt()
            var warm = 0
            val until = SystemClock.uptimeMillis() + WARM_UP_MILLIS
            while (SystemClock.uptimeMillis() < until) {
                val at = (half + warm++ % half).toLong() * SpikeService.BLOCK_BYTES
                Os.pread(it.fileDescriptor, into, 0, SpikeService.BLOCK_BYTES, at)
            }
            val blocks = (0 until half step STRIDE).shuffled()
            val nanos =
                LongArray(blocks.size) { i ->
                    val started = System.nanoTime()
                    Os.pread(
                        it.fileDescriptor,
                        into,
                        0,
                        SpikeService.BLOCK_BYTES,
                        blocks[i].toLong() * SpikeService.BLOCK_BYTES,
                    )
                    System.nanoTime() - started
                }
            assertArrayEquals("AppFuse served the wrong bytes", SpikeService.BLOCK, into)
            Latencies(nanos)
        }
    }

    // ---- How -------------------------------------------------------------------------------------

    private fun askForRegion(): SharedMemory {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            spike.transact(SpikeService.REGION, data, reply, 0)
            SharedMemory.CREATOR.createFromParcel(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun ask(
        code: Int,
        argument: Int? = null,
    ) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            argument?.let(data::writeInt)
            check(spike.transact(code, data, reply, 0)) { "the spike did not know $code" }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun checksum(): Long {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            spike.transact(SpikeService.CHECKSUM, data, reply, 0)
            reply.readLong()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun pristine(): Long {
        val all = ByteArray(SpikeService.REGION_BYTES)
        for (i in 0 until SpikeService.REGION_BYTES / SpikeService.BLOCK_BYTES) {
            System.arraycopy(SpikeService.BLOCK, 0, all, i * SpikeService.BLOCK_BYTES, SpikeService.BLOCK_BYTES)
        }
        return java.util.zip
            .CRC32()
            .apply { update(all) }
            .value
    }

    private fun mmapWritable(
        raw: ParcelFileDescriptor,
        sharing: Int,
    ) {
        val address =
            Os.mmap(
                0,
                SpikeService.REGION_BYTES.toLong(),
                OsConstants.PROT_READ or OsConstants.PROT_WRITE,
                sharing,
                raw.fileDescriptor,
                0,
            )
        Os.munmap(address, SpikeService.REGION_BYTES.toLong())
    }

    private fun mapAfterReopen(fd: Int) {
        val reopened = Os.open("/proc/self/fd/$fd", OsConstants.O_RDWR, 0)
        try {
            val address =
                Os.mmap(
                    0,
                    SpikeService.REGION_BYTES.toLong(),
                    OsConstants.PROT_READ or OsConstants.PROT_WRITE,
                    OsConstants.MAP_SHARED,
                    reopened,
                    0,
                )
            Os.munmap(address, SpikeService.REGION_BYTES.toLong())
        } finally {
            Os.close(reopened)
        }
    }

    /** What an attempt came to: `allowed`, or the errno or exception that refused it. */
    private fun attempt(block: () -> Unit): String =
        try {
            block()
            "allowed"
        } catch (refused: ErrnoException) {
            OsConstants.errnoName(refused.errno) ?: refused.errno.toString()
        } catch (refused: Exception) {
            refused.javaClass.simpleName + (refused.message?.let { ": $it" } ?: "")
        }

    private fun openFds(): Set<Int> =
        File("/proc/self/fd")
            .list()
            .orEmpty()
            .mapNotNull(String::toIntOrNull)
            .toSet()

    private fun readlinkOrNull(fd: Int): String? = runCatching { Os.readlink("/proc/self/fd/$fd") }.getOrNull()

    private class Latencies(
        private val nanos: LongArray,
    ) {
        fun json(): JSONObject {
            val sorted = nanos.sorted()

            fun micros(q: Double) = sorted[((sorted.size - 1) * q).toInt()] / NANOS_PER_MICRO
            return JSONObject()
                .put("n", sorted.size)
                .put("p50us", micros(MEDIAN))
                .put("p90us", micros(P90))
                .put("p99us", micros(P99))
        }
    }

    /** [n] timed runs of [block], after the same loop has run long enough for ART to compile it (encri D40). */
    private fun measure(
        n: Int,
        block: () -> Unit,
    ): Latencies {
        val until = SystemClock.uptimeMillis() + WARM_UP_MILLIS
        while (SystemClock.uptimeMillis() < until) block()
        val nanos = LongArray(n)
        for (i in 0 until n) {
            val started = System.nanoTime()
            block()
            nanos[i] = System.nanoTime() - started
        }
        return Latencies(nanos)
    }

    private fun <R> ParcelFileDescriptor.use(block: (ParcelFileDescriptor) -> R): R =
        try {
            block(this)
        } finally {
            close()
        }

    private companion object {
        const val BIND_SECONDS = 10L
        const val ROUND_TRIPS = 5_000
        const val BLOCKS = 1_024
        const val WARM_UP_MILLIS = 1_500L
        const val NANOS_PER_MICRO = 1_000.0
        const val MEDIAN = 0.5
        const val P90 = 0.9
        const val P99 = 0.99
        const val TAG = "EncriSpike"
        const val STRIDE = 4
    }
}

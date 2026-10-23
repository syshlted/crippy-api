package com.systemhalted.encri.client.device.spike

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.SharedMemory
import android.os.storage.StorageManager
import android.system.OsConstants
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * encri's half of encri D113's spike, in a process of its own (`:spike`): a region of shared memory it
 * keeps a writable mapping of and hands out read-only, and, for comparison, a proxy descriptor over the
 * same bytes (L14). Every transaction is raw, with no AIDL, because the spike is about the transport.
 *
 * Both routes serve one 64 KiB block by copying it from [BLOCK], so the work beside the transport is the
 * same: a memcpy, standing in for the decrypt encri would do. What differs is how the block gets to the
 * client.
 */
class SpikeService : Service() {
    private val region: SharedMemory = SharedMemory.create("encri-spike", REGION_BYTES)
    private val writable: ByteBuffer = region.mapReadWrite()
    private val fuse = HandlerThread("spike-fuse").apply { start() }

    init {
        fill()
        // Narrowed before it is ever handed out. The mapping above was made first and keeps its write
        // access, which is what encri D113 needs: encri writes, the client reads.
        check(region.setProtect(OsConstants.PROT_READ)) { "the region would not be made read-only" }
    }

    private val binder =
        object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                when (code) {
                    NOTHING -> Unit
                    REGION -> region.writeToParcel(requireNotNull(reply), 0)
                    BLOCK_INTO_REGION -> blockAt(data.readInt())
                    CHECKSUM -> requireNotNull(reply).writeLong(checksum())
                    ZERO -> zero()
                    REFILL -> fill()
                    PROXY -> proxy().also { requireNotNull(reply).writeFileDescriptor(it.fileDescriptor) }.close()
                    else -> return super.onTransact(code, data, reply, flags)
                }
                return true
            }
        }

    override fun onBind(intent: Intent): IBinder = binder

    /** The block at [index] of the region, copied in as encri would decrypt one into it. */
    private fun blockAt(index: Int) {
        synchronized(writable) {
            writable.position(index * BLOCK_BYTES)
            writable.put(BLOCK)
        }
    }

    private fun fill() {
        synchronized(writable) {
            for (i in 0 until REGION_BYTES / BLOCK_BYTES) {
                writable.position(i * BLOCK_BYTES)
                writable.put(BLOCK)
            }
        }
    }

    private fun zero() {
        synchronized(writable) {
            writable.position(0)
            repeat(REGION_BYTES / BLOCK_BYTES) { writable.put(ByteArray(BLOCK_BYTES)) }
        }
    }

    private fun checksum(): Long =
        synchronized(writable) {
            val all = ByteArray(REGION_BYTES)
            writable.position(0)
            writable.get(all)
            CRC32().apply { update(all) }.value
        }

    /** A proxy descriptor serving [PROXY_BYTES] of [BLOCK] repeated, as encri's documents are served. */
    private fun proxy(): ParcelFileDescriptor =
        getSystemService(StorageManager::class.java).openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = PROXY_BYTES

                override fun onRead(
                    offset: Long,
                    size: Int,
                    data: ByteArray,
                ): Int {
                    var done = 0
                    while (done < size) {
                        val at = ((offset + done) % BLOCK_BYTES).toInt()
                        val run = minOf(size - done, BLOCK_BYTES - at)
                        System.arraycopy(BLOCK, at, data, done, run)
                        done += run
                    }
                    return size
                }

                override fun onRelease() = Unit
            },
            Handler(fuse.looper),
        )

    companion object {
        const val NOTHING = IBinder.FIRST_CALL_TRANSACTION
        const val REGION = IBinder.FIRST_CALL_TRANSACTION + 1
        const val BLOCK_INTO_REGION = IBinder.FIRST_CALL_TRANSACTION + 2
        const val CHECKSUM = IBinder.FIRST_CALL_TRANSACTION + 3
        const val ZERO = IBinder.FIRST_CALL_TRANSACTION + 4
        const val REFILL = IBinder.FIRST_CALL_TRANSACTION + 5
        const val PROXY = IBinder.FIRST_CALL_TRANSACTION + 6

        /** encri's block (encri D2): one block per request on either route. */
        const val BLOCK_BYTES = 64 * 1024
        const val REGION_BYTES = 16 * BLOCK_BYTES

        /**
         * Large enough that every block timed through the proxy is read once and the warm-up reads others, so
         * no timed read is a cache hit. Nothing is stored: the callback makes every block as it is asked.
         */
        const val PROXY_BYTES = 4096L * BLOCK_BYTES

        val BLOCK = ByteArray(BLOCK_BYTES) { (it * 31 + 7).toByte() }
    }
}

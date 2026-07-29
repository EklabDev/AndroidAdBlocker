package com.eklab.adblocker.vpn.tun

import java.io.FileOutputStream
import java.io.IOException

/**
 * Serialized writer for the TUN device. Many relay threads (UDP readers, TCP
 * readers, the packet loop itself) write reply packets into the TUN, so all
 * writes are funnelled through a single lock. Callers may therefore reuse
 * their own scratch buffers safely: the bytes are handed to the stream before
 * [write] returns.
 *
 * I/O errors (e.g. the TUN fd being closed during shutdown) are swallowed:
 * a failed write simply drops a packet, which is always safe to do.
 */
class TunWriter(private val out: FileOutputStream) {

    private val lock = Any()

    fun write(buf: ByteArray, len: Int) {
        try {
            synchronized(lock) { out.write(buf, 0, len) }
        } catch (e: IOException) {
            // TUN is gone (shutdown) — drop the packet.
        }
    }
}

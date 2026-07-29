package com.eklab.adblocker.vpn.tun

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import com.eklab.adblocker.repository.AppsRepository
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Best-effort attribution of a flow to the app that owns it.
 *
 * UID resolution:
 * - API 29+: [ConnectivityManager.getConnectionOwnerUid] (no extra permission
 *   needed). It is given the 5-tuple exactly as seen on the TUN — the local
 *   address is the TUN address and the remote is the real destination, which
 *   is how the system tracks the underlying socket. Falls back to /proc when
 *   the API returns [Process.INVALID_UID].
 * - API 26–28: parse `/proc/net/tcp` and `/proc/net/udp` (hex IPv4 fields,
 *   little-endian address, uid in column 8).
 *
 * UID -> package goes through [PackageManager.getPackagesForUid]; a `null`
 * result (uninstalled / shared uid quirk) means the flow stays unattributed
 * (`appPackage`/`appName` null, uid -1). UID->package and package->label
 * results are cached for the lifetime of the VPN session.
 */
class AppAttribution(
    context: Context,
    private val appsRepository: AppsRepository,
) {

    private val packageManager = context.packageManager
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    /** uid -> package name, [NONE] sentinel when unknown (ConcurrentHashMap holds no nulls). */
    private val uidToPackage = ConcurrentHashMap<Int, String>()

    /** package name -> display label, [NONE] when unknown. */
    private val packageToLabel = ConcurrentHashMap<String, String>()

    /**
     * Resolves the owning UID for the given 5-tuple, or [Process.INVALID_UID]
     * when attribution fails. Never throws.
     */
    fun resolveUid(protocol: Int, srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cm = connectivityManager
            if (cm != null) {
                try {
                    val uid = cm.getConnectionOwnerUid(
                        protocol,
                        InetSocketAddress(InetAddress.getByAddress(Packets.ipToBytes(srcIp)), srcPort),
                        InetSocketAddress(InetAddress.getByAddress(Packets.ipToBytes(dstIp)), dstPort),
                    )
                    if (uid != Process.INVALID_UID) return uid
                } catch (t: Throwable) {
                    // Best-effort only; fall through to /proc.
                }
            }
        }
        return uidFromProc(protocol, srcIp, srcPort, dstIp, dstPort)
    }

    /** First package name for [uid], cached; `null` when unresolvable. */
    fun packageForUid(uid: Int): String? {
        if (uid < 0) return null
        uidToPackage[uid]?.let { return it.ifEmpty { null } }
        val pkg = try {
            packageManager.getPackagesForUid(uid)?.firstOrNull() ?: NONE
        } catch (t: Throwable) {
            NONE
        }
        uidToPackage[uid] = pkg
        return pkg.ifEmpty { null }
    }

    /** Display label for [packageName] via [AppsRepository], cached; `null` when uninstalled. */
    fun labelForPackage(packageName: String): String? {
        packageToLabel[packageName]?.let { return it.ifEmpty { null } }
        val label = try {
            appsRepository.appLabel(packageName) ?: NONE
        } catch (t: Throwable) {
            NONE
        }
        packageToLabel[packageName] = label
        return label.ifEmpty { null }
    }

    /**
     * Scans /proc/net/{tcp,udp} for a socket whose local *and* remote
     * address:port match the flow, returning the uid column. IPv4 addresses in
     * these files are hex-encoded little-endian.
     */
    private fun uidFromProc(protocol: Int, srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int): Int {
        val local = procHex(srcIp, srcPort)
        val remote = procHex(dstIp, dstPort)
        val paths = if (protocol == Packets.PROTO_TCP) {
            arrayOf("/proc/net/tcp", "/proc/net/tcp6")
        } else {
            arrayOf("/proc/net/udp", "/proc/net/udp6")
        }
        for (path in paths) {
            try {
                File(path).useLines { lines ->
                    for (line in lines) {
                        val f = line.trim().split(WHITESPACE)
                        // sl local rem st txq:rxq tr:tmwhen retrnsmt uid ...
                        if (f.size > 7 && f[1].equals(local, ignoreCase = true) &&
                            f[2].equals(remote, ignoreCase = true)
                        ) {
                            return@useLines f[7].toIntOrNull()
                        }
                    }
                    null
                }?.let { return it }
            } catch (t: Throwable) {
                // Unreadable on this device/API — keep trying other tables.
            }
        }
        return Process.INVALID_UID
    }

    private fun procHex(ip: Int, port: Int): String =
        "%08X:%04X".format(Integer.reverseBytes(ip), port)

    private companion object {
        const val NONE = ""
        val WHITESPACE = Regex("\\s+")
    }
}

package org.filezilla.ftp.protocol

import java.util.concurrent.ConcurrentHashMap

/** Tri-state answer for a capability, from `servercapabilities.h`. */
enum class Capability { UNKNOWN, YES, NO }

/**
 * The capability names the engine tracks, ported from `capabilityNames` in
 * `servercapabilities.h`. Only the ones the FTP path actually consults are
 * kept; the SFTP and Storj entries are left out.
 */
enum class CapabilityName {
    /** Server mishandles resume offsets past 2 GB. */
    RESUME_2GB_BUG,

    /** Server mishandles resume offsets past 4 GB. */
    RESUME_4GB_BUG,

    SYST_COMMAND,
    FEAT_COMMAND,
    CLNT_COMMAND,
    UTF8_COMMAND,
    MLSD_COMMAND,
    OPTS_MLST_COMMAND,
    MFMT_COMMAND,
    MDTM_COMMAND,
    SIZE_COMMAND,
    MODE_Z_SUPPORT,
    TVFS_SUPPORT,
    LIST_HIDDEN_SUPPORT,

    /** Server supports `REST`+`STOR` for uploads, not just `APPE`. */
    REST_STREAM,

    EPSV_COMMAND,

    /** Server's own timezone offset, inferred from `MLSD` versus `LIST`. */
    INFERRED_TIMEZONE_OFFSET,

    AUTH_TLS_COMMAND,
    AUTH_SSL_COMMAND,

    /** Server requires the data connection to resume the control TLS session. */
    TLS_RESUMPTION,
}

/** A capability answer together with the option string or number it carried. */
data class CapabilityValue(
    val capability: Capability,
    val textOption: String? = null,
    val intOption: Int? = null,
)

/**
 * Per-host cache of what a server can and cannot do, ported from
 * `CServerCapabilities`.
 *
 * Caching matters most for the resume bug probes: testing whether a server
 * honours `REST` past 2 GB costs a whole extra data connection, so the verdict
 * is remembered and the probe is not repeated for that host
 * (`filetransfer.cpp:196-231`, `451-475`).
 *
 * Instances are shared across transfers, so every method is thread safe.
 */
class ServerCapabilities {

    /** Identity of a server for caching purposes: host, port and username. */
    data class ServerKey(val host: String, val port: Int, val user: String)

    private val cache = ConcurrentHashMap<ServerKey, MutableMap<CapabilityName, CapabilityValue>>()

    fun get(server: ServerKey, name: CapabilityName): Capability =
        getValue(server, name).capability

    fun getValue(server: ServerKey, name: CapabilityName): CapabilityValue =
        cache[server]?.get(name) ?: CapabilityValue(Capability.UNKNOWN)

    fun set(
        server: ServerKey,
        name: CapabilityName,
        capability: Capability,
        textOption: String? = null,
        intOption: Int? = null,
    ) {
        val forServer = cache.computeIfAbsent(server) { ConcurrentHashMap() }
        forServer[name] = CapabilityValue(capability, textOption, intOption)
    }

    /** Drops everything known about [server]; used when a probe is invalidated. */
    fun forget(server: ServerKey) {
        cache.remove(server)
    }

    /**
     * Applies a `FEAT` reply, a port of the feature matching in
     * `logon.cpp:780-845`. [featureLines] are the reply's body lines, without
     * the surrounding `211-`/`211` lines.
     */
    fun applyFeatures(server: ServerKey, featureLines: List<String>) {
        set(server, CapabilityName.FEAT_COMMAND, Capability.YES)
        for (raw in featureLines) {
            val feature = raw.trim().uppercase()
            when {
                feature == "UTF8" -> set(server, CapabilityName.UTF8_COMMAND, Capability.YES)
                feature == "CLNT" || feature.startsWith("CLNT ") ->
                    set(server, CapabilityName.CLNT_COMMAND, Capability.YES)
                feature.startsWith("MLST") -> {
                    set(server, CapabilityName.MLSD_COMMAND, Capability.YES, textOption = raw.trim().drop(4).trim())
                    // MLSD times are UTC, so no timezone guessing is needed.
                    set(server, CapabilityName.INFERRED_TIMEZONE_OFFSET, Capability.NO, intOption = 0)
                }
                feature == "MLSD" -> {
                    set(server, CapabilityName.MLSD_COMMAND, Capability.YES)
                    set(server, CapabilityName.INFERRED_TIMEZONE_OFFSET, Capability.NO, intOption = 0)
                }
                feature.startsWith("MODE Z") -> set(server, CapabilityName.MODE_Z_SUPPORT, Capability.YES)
                feature == "MFMT" -> set(server, CapabilityName.MFMT_COMMAND, Capability.YES)
                feature == "MDTM" -> set(server, CapabilityName.MDTM_COMMAND, Capability.YES)
                feature == "SIZE" -> set(server, CapabilityName.SIZE_COMMAND, Capability.YES)
                feature == "TVFS" -> set(server, CapabilityName.TVFS_SUPPORT, Capability.YES)
                feature == "REST STREAM" -> set(server, CapabilityName.REST_STREAM, Capability.YES)
                feature == "EPSV" -> set(server, CapabilityName.EPSV_COMMAND, Capability.YES)
            }
        }
    }
}

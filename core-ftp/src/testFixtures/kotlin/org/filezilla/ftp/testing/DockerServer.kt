package org.filezilla.ftp.testing

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * A real server run in a throwaway Docker container for the length of a test.
 *
 * The SFTP engine proves itself against an in-process MINA server, and the FTP
 * engine against vsftpd and Pure-FTPd binaries on the host. WebDAV and SMB have
 * no comfortable in-process Java server, so they are proven against the real
 * thing in a container instead -- Apache `mod_dav` for WebDAV, Samba for SMB.
 *
 * Like the FTP compatibility tests, these skip themselves when their server is
 * not there: [dockerAvailable] is false when Docker is not installed or its
 * daemon is not running, and a test that needs one calls `assumeTrue` on it.
 * `core-ftp/src/test/resources/net-servers/test-servers.sh` is the same thing
 * by hand, for running a server while developing the client against it.
 */
class DockerServer(
    private val name: String,
    private val image: String,
    /** host port to container port, e.g. 18080 to 80. */
    private val ports: Map<Int, Int>,
    private val env: Map<String, String> = emptyMap(),
    /** The container command's own arguments, after the image name. */
    private val args: List<String> = emptyList(),
) {
    /** The host port the container's [containerPort] is published on. */
    fun hostPort(containerPort: Int): Int =
        ports.entries.first { it.value == containerPort }.key

    fun start() {
        run("docker", "rm", "-f", name)
        val command = buildList {
            addAll(listOf("docker", "run", "-d", "--name", name))
            for ((host, container) in ports) addAll(listOf("-p", "$host:$container"))
            for ((k, v) in env) addAll(listOf("-e", "$k=$v"))
            add(image)
            addAll(args)
        }
        val started = run(*command.toTypedArray())
        check(started.code == 0) { "could not start $name: ${started.output}" }
        for (host in ports.keys) waitForPort(host)
    }

    fun stop() {
        run("docker", "rm", "-f", name)
    }

    private fun waitForPort(port: Int, timeoutSeconds: Long = 60) {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1000) }
                return
            }
            Thread.sleep(300)
        }
        error("$name did not open port $port within ${timeoutSeconds}s")
    }

    companion object {
        /** Whether Docker is installed and its daemon is reachable. */
        fun dockerAvailable(): Boolean =
            runCatching { run("docker", "info").code == 0 }.getOrDefault(false)

        private data class Result(val code: Int, val output: String)

        private fun run(vararg command: String): Result {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(90, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return Result(-1, "timed out: ${command.joinToString(" ")}")
            }
            return Result(process.exitValue(), output)
        }
    }
}

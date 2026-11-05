package io.github.dogkeeper886.cleancam

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * TCP server that sends a stream, the video or the PCM audio, to every connected client, or receives one, the PC's
 * speaker audio. Over USB the PC reaches it through `adb forward`; over WiFi a PC connects to the phone's IP directly.
 */
class StreamServer(private val port: Int) {
    var onClientsChanged: ((Int) -> Unit)? = null
    /** When set, called on the client's reader thread with whatever a client sends. */
    var onReceive: ((ByteArray, Int) -> Unit)? = null

    private class Client(val socket: Socket) {
        val queue = LinkedBlockingQueue<ByteArray>()
        val pending = AtomicInteger(0)
        @Volatile var waitingForKeyframe = true
    }

    private val clients = CopyOnWriteArrayList<Client>()
    private val lock = Any()
    @Volatile private var running = false
    @Volatile private var server: ServerSocket? = null
    private var peakBacklog = 0

    val clientCount: Int get() = clients.size

    /** The most sends any client had waiting since the last call, up to [MAX_PENDING_SENDS]. */
    fun takePeakBacklog(): Int = synchronized(lock) { peakBacklog.also { peakBacklog = 0 } }

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        Thread({ acceptLoop() }, "cleancam.server.$port").start()
        onClientsChanged?.invoke(clients.size)
    }

    fun stop() {
        synchronized(lock) { running = false }
        try { server?.close() } catch (_: IOException) {}
        server = null
        for (client in clients) remove(client)
        onClientsChanged?.invoke(clients.size)
    }

    /** Closes every client's connection; a client that connects again starts at the next keyframe. */
    fun disconnectAll() {
        for (client in clients) remove(client)
    }

    fun broadcast(data: ByteArray, isKeyframe: Boolean) {
        var backlog = 0
        for (client in clients) {
            if (client.waitingForKeyframe) {
                if (!isKeyframe) continue
                client.waitingForKeyframe = false
            }
            val pending = client.pending.get()
            backlog = maxOf(backlog, pending)
            // A client further behind skips ahead to the next keyframe instead of lagging more.
            if (pending >= MAX_PENDING_SENDS) {
                client.waitingForKeyframe = true
                continue
            }
            client.pending.incrementAndGet()
            client.queue.offer(data)
        }
        synchronized(lock) { peakBacklog = maxOf(peakBacklog, backlog) }
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                val listener = server ?: ServerSocket().also {
                    it.reuseAddress = true
                    it.bind(InetSocketAddress(port))
                    server = it
                }
                listener.accept()
            } catch (e: IOException) {
                server?.let { try { it.close() } catch (_: IOException) {} }
                server = null
                // The port is busy, or the server stopped; try again in a second while running.
                if (running) Thread.sleep(1000)
                continue
            }
            socket.tcpNoDelay = true
            val client = Client(socket)
            clients.add(client)
            Thread({ writeLoop(client) }, "cleancam.send.$port").start()
            Thread({ readLoop(client) }, "cleancam.read.$port").start()
            onClientsChanged?.invoke(clients.size)
        }
    }

    private fun writeLoop(client: Client) {
        try {
            val output = client.socket.getOutputStream()
            while (true) {
                val data = client.queue.take()
                if (data.isEmpty()) break
                output.write(data)
                client.pending.decrementAndGet()
            }
        } catch (_: Exception) {
        }
        remove(client)
    }

    /** Reads what the client sends, and notices when it closes the connection. */
    private fun readLoop(client: Client) {
        val buffer = ByteArray(65_536)
        try {
            val input = client.socket.getInputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) onReceive?.invoke(buffer, count)
            }
        } catch (_: IOException) {
        }
        remove(client)
    }

    private fun remove(client: Client) {
        if (!clients.remove(client)) return
        try { client.socket.close() } catch (_: IOException) {}
        client.queue.offer(ByteArray(0))
        onClientsChanged?.invoke(clients.size)
    }

    companion object {
        /** About one second of video or half a second of audio. */
        const val MAX_PENDING_SENDS = 30
    }
}

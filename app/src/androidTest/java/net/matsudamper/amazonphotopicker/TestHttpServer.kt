package net.matsudamper.amazonphotopicker

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** 計装テスト用の最小限のHTTPサーバー */
class TestHttpServer(
    private val routes: Map<String, Pair<String, ByteArray>>,
) : AutoCloseable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}"

    init {
        thread(isDaemon = true) {
            while (!serverSocket.isClosed) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { handle(socket) }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val path = requestLine.split(" ").getOrNull(1)?.substringBefore("?") ?: "/"
            val route = routes[path]
            val out = socket.getOutputStream()
            if (route == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } else {
                val (contentType, body) = route
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                )
                out.write(body)
            }
            out.flush()
        }
    }

    override fun close() {
        serverSocket.close()
    }
}

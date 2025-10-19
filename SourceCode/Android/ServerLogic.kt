package com.example.nowplayer

import android.util.Log
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.server.websocket.DefaultWebSocketServerSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.net.NetworkInterface
import java.util.Collections
import java.time.Duration

class WebSocketServer {
    private var server: io.ktor.server.engine.ApplicationEngine? = null

    // CRITICAL: Use a thread-safe set to track active sessions for sending commands.
    private val connectedSessions: MutableSet<DefaultWebSocketServerSession> =
        Collections.synchronizedSet(LinkedHashSet())

    private val _nowPlayingData = MutableStateFlow<NowPlayingData?>(null)
    val nowPlayingData: StateFlow<NowPlayingData?> = _nowPlayingData

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _serverStatus = MutableStateFlow("Stopped")
    val serverStatus: StateFlow<String> = _serverStatus

    private val _localIpAddress = MutableStateFlow(getLocalIpAddress())
    val localIpAddress: StateFlow<String> = _localIpAddress

    // Helper function to update the status text based on connection/server state
    private fun updateStatus(isConnected: Boolean, isRunning: Boolean) {
        when {
            isConnected -> _serverStatus.value = "Connected"
            isRunning -> _serverStatus.value = "Listening on ${_localIpAddress.value}:8080/ws"
            else -> _serverStatus.value = "Stopped"
        }
    }

    fun start() {
        if (server != null) {
            Log.w("WebSocketServer", "Server already running")
            return
        }

        try {
            _localIpAddress.value = getLocalIpAddress()

            server = embeddedServer(CIO, port = 8080, host = "0.0.0.0") {
                install(WebSockets) {
                    pingPeriodMillis = 15000L
                    // FIX: Reduced timeout to detect silent disconnects much faster (15 seconds)
                    timeoutMillis = 15000L
                    maxFrameSize = Long.MAX_VALUE
                    masking = false
                }

                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        prettyPrint = true
                    })
                }

                routing {
                    webSocket("/ws") {
                        val currentSession = this as DefaultWebSocketServerSession

                        // 1. ADD SESSION
                        connectedSessions.add(currentSession)

                        // Update UI state based on session count
                        _isConnected.value = connectedSessions.isNotEmpty()
                        updateStatus(isConnected = _isConnected.value, isRunning = true)
                        Log.i("WebSocketServer", "Client connected. Total sessions: ${connectedSessions.size}")

                        try {
                            for (frame in incoming) {
                                when (frame) {
                                    is Frame.Text -> {
                                        val text = frame.readText()
                                        Log.d("WebSocketServer", "Received RAW TEXT: $text")
                                        try {
                                            // NOTE: NowPlayingData must be defined in ServerUI.kt
                                            // The class definition is omitted here for file brevity but must exist in your project.
                                            val data = Json.decodeFromString<NowPlayingData>(text)
                                            _nowPlayingData.value = data
                                            Log.i("WebSocketServer", "Updated now playing: ${data.title}")
                                        } catch (e: Exception) {
                                            Log.e("WebSocketServer", "Parse error: ${e.message}")
                                        }
                                    }
                                    is Frame.Close -> break
                                    else -> {}
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("WebSocketServer", "WebSocket error: ${e.message}")
                        } finally {
                            // 2. REMOVE SESSION
                            connectedSessions.remove(currentSession)

                            // Update UI state based on the NEW session count
                            val stillConnected = connectedSessions.isNotEmpty()
                            _isConnected.value = stillConnected
                            updateStatus(isConnected = stillConnected, isRunning = true)

                            Log.i("WebSocketServer", "Client disconnected. Remaining sessions: ${connectedSessions.size}")
                        }
                    }
                }
            }

            server?.start(wait = false)
            updateStatus(isConnected = false, isRunning = true)
            Log.i("WebSocketServer", "Server started on port 8080")
        } catch (e: Exception) {
            Log.e("WebSocketServer", "Failed to start server: ${e.message}")
            _serverStatus.value = "Error: ${e.message}"
        }
    }

    suspend fun sendCommand(command: String) {
        val commandFrame = Frame.Text(command)
        Log.d("WebSocketServer", "Attempting to send command: $command to ${connectedSessions.size} clients.")

        val sessionsToSend = connectedSessions.toSet()

        if (sessionsToSend.isEmpty()) {
            Log.w("WebSocketServer", "No active client sessions found to send command. (Size was 0)")
        }

        for (session in sessionsToSend) {
            try {
                session.send(commandFrame)
                Log.d("WebSocketServer", "Successfully sent command: $command")
            } catch (e: Exception) {
                // Log the exact error to diagnose why the send failed
                Log.e("WebSocketServer", "FAILED to send command $command: ${e.message}", e)
            }
        }
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        _isConnected.value = false
        connectedSessions.clear()
        updateStatus(isConnected = false, isRunning = false)
        Log.i("WebSocketServer", "Server stopped")
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress
                        if (sAddr != null && sAddr.indexOf(':') < 0) {
                            return sAddr
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WebSocketServer", "Error getting IP: ${e.message}")
        }
        return "Unknown"
    }
}
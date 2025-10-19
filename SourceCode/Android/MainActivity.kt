package com.example.nowplayer

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val webSocketServer = WebSocketServer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NowPlayerApp(webSocketServer) {
                        finish()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocketServer.stop()
    }
}

@Composable
fun NowPlayerApp(
    server: WebSocketServer,
    onStopServer: () -> Unit
) {
    // We use the simpler UI scope, removing explicit Dispatchers.IO calls
    // that caused instability for the command sending logic.
    val scope = rememberCoroutineScope()

    val isConnected by server.isConnected.collectAsState()
    val serverStatus by server.serverStatus.collectAsState()
    val nowPlayingData by server.nowPlayingData.collectAsState()
    val localIpAddress by server.localIpAddress.collectAsState()

    LaunchedEffect(Unit) {
        // Run server start in the main coroutine scope
        scope.launch {
            server.start()
        }
    }

    DashboardScreen(
        isConnected = isConnected,
        serverStatus = serverStatus,
        nowPlayingData = nowPlayingData,
        localIpAddress = localIpAddress,
        onStopServer = {
            // Stop logic remains on the current scope
            scope.launch {
                server.stop()
                onStopServer()
            }
        },
        // RESTORED SIMPLE, WORKING COMMAND SENDING LOGIC
        onSendCommand = { command ->
            Log.d("MainActivity", "Button pressed - Command: $command")

            // Launch command sending directly into the existing scope
            scope.launch {
                try {
                    Log.d("MainActivity", "Calling server.sendCommand($command)")
                    server.sendCommand(command)
                    Log.d("MainActivity", "server.sendCommand() completed")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error sending command: ${e.message}", e)
                }
            }
        }
    )
}
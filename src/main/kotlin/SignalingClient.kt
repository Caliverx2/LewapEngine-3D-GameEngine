package org.lewapnoob.gridMap

import SignalingMessage
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SignalingClient(
    private val url: String,
    private val onConnect: () -> Unit,
    private val onMessage: (SignalingMessage) -> Unit,
    private val onFailure: () -> Unit = {} // Domyślnie puste, aby nie psuć innych wywołań
) {
    private val client = HttpClient(CIO) {
        install(WebSockets)
    }
    // Osobny scope dla sieci, żeby nie blokować wątku gry
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var session: DefaultClientWebSocketSession? = null

    fun connect() {
        scope.launch {
            try {
                println("Łączenie z serwerem sygnalizacyjnym: $url")
                client.webSocket(urlString = url) {
                    session = this
                    onConnect()

                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            try {
                                val text = frame.readText()
                                val msg = Json.decodeFromString<SignalingMessage>(text)
                                onMessage(msg)
                            } catch (e: Exception) {
                                println("Błąd dekodowania wiadomości: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("Błąd połączenia z serwerem: ${e.message}")
                onFailure() // Wywołujemy callback błędu
            }
        }
    }

    fun send(msg: SignalingMessage) {
        scope.launch {
            try {
                val json = Json.encodeToString(msg)
                session?.send(Frame.Text(json))
            } catch (e: Exception) {
                println("Błąd wysyłania: ${e.message}")
            }
        }
    }

    fun close() {
        scope.cancel()
        client.close()
    }
}
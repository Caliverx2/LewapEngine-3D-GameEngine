package org.lewapnoob.gridMapServer

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

// Struktura pokoju w pamięci serwera
data class GameRoom(
    val hostSession: DefaultWebSocketServerSession,
    var clientSession: DefaultWebSocketServerSession? = null
)

// Mapa: Kod Pokoju -> Obiekt Pokoju
val rooms = ConcurrentHashMap<String, GameRoom>()

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    routing {
        webSocket("/signaling") {
            // Zmienna przechowująca kod pokoju przypisany do tej konkretnej sesji WebSocket
            var currentRoomCode: String? = null

            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()

                        // Deserializacja wiadomości
                        val message = try {
                            Json.decodeFromString<SignalingMessage>(text)
                        } catch (e: Exception) {
                            println("Błąd deserializacji: ${e.message}")
                            continue
                        }

                        when (message) {
                            // --- 1. Host tworzy pokój ---
                            is SignalingMessage.CreateRoom -> {
                                val newCode = generateUniqueCode()
                                val room = GameRoom(hostSession = this)
                                rooms[newCode] = room
                                currentRoomCode = newCode

                                println("Utworzono pokój: $newCode")
                                sendSerialized(SignalingMessage.RoomCreated(newCode))
                            }

                            // --- 2. Klient dołącza do pokoju ---
                            is SignalingMessage.JoinRoom -> {
                                val room = rooms[message.roomCode]
                                if (room != null) {
                                    if (room.clientSession == null) {
                                        room.clientSession = this
                                        currentRoomCode = message.roomCode

                                        println("Klient dołączył do pokoju: ${message.roomCode}")

                                        // Powiadom hosta, że ktoś wszedł (Host zacznie WebRTC Offer)
                                        // Generujemy tymczasowe ID dla klienta (np. hash sesji)
                                        val clientId = this.hashCode().toString()
                                        room.hostSession.sendSerialized(SignalingMessage.PlayerJoined(clientId))
                                    } else {
                                        sendSerialized(SignalingMessage.Error("Pokój jest pełny."))
                                    }
                                } else {
                                    sendSerialized(SignalingMessage.Error("Nie znaleziono pokoju o kodzie: ${message.roomCode}"))
                                }
                            }

                            // --- 3. Przekazywanie WebRTC (SDP Offer/Answer/ICE) ---
                            is SignalingMessage.SdpOffer,
                            is SignalingMessage.SdpAnswer,
                            is SignalingMessage.IceCandidate -> {
                                val code = currentRoomCode
                                if (code != null) {
                                    val room = rooms[code]
                                    if (room != null) {
                                        // Logika "Swatki": Przekaż do drugiego gracza
                                        val targetSession = if (this == room.hostSession) {
                                            room.clientSession
                                        } else {
                                            room.hostSession
                                        }

                                        targetSession?.sendSerialized(message)
                                    }
                                }
                            }

                            else -> {} // Ignoruj inne/błędy
                        }
                    }
                }
            } catch (e: Exception) {
                println("Błąd sesji: ${e.localizedMessage}")
            } finally {
                // --- Sprzątanie po rozłączeniu ---
                val code = currentRoomCode
                if (code != null) {
                    val room = rooms[code]
                    if (room != null) {
                        // Jeśli rozłączył się Host -> Usuń pokój i wywal klienta
                        if (this == room.hostSession) {
                            println("Host wyszedł. Zamykanie pokoju $code")
                            room.clientSession?.sendSerialized(SignalingMessage.Error("Host zakończył sesję."))
                            room.clientSession?.close(CloseReason(CloseReason.Codes.NORMAL, "Host left"))
                            rooms.remove(code)
                        }
                        // Jeśli rozłączył się Klient -> Powiadom Hosta, zwolnij slot
                        else if (this == room.clientSession) {
                            println("Klient wyszedł z pokoju $code")
                            room.hostSession.sendSerialized(SignalingMessage.Error("Klient się rozłączył."))
                            room.clientSession = null
                            // Opcjonalnie: Host może czekać na nowego gracza, więc nie usuwamy pokoju
                        }
                    }
                }
            }
        }
    }
}

// Pomocnicza funkcja do wysyłania JSON
suspend fun DefaultWebSocketServerSession.sendSerialized(msg: SignalingMessage) {
    try {
        send(Frame.Text(Json.encodeToString(msg)))
    } catch (e: Exception) {
        println("Nie udało się wysłać wiadomości: ${e.message}")
    }
}

// Generowanie unikalnego 6-cyfrowego kodu
fun generateUniqueCode(): String {
    var code: String
    do {
        code = Random.nextInt(100000, 999999).toString()
    } while (rooms.containsKey(code))
    return code
}
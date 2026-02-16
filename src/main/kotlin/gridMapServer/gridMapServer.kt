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
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

// Struktura pokoju w pamięci serwera
data class GameRoom(
    val hostSession: DefaultWebSocketServerSession,
    val hostId: String,
    // Lista klientów (graczy) w pokoju. Klucz to unikalne ID sesji (np. hash).
    val clients: ConcurrentHashMap<String, DefaultWebSocketServerSession> = ConcurrentHashMap()
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
            // ID tej sesji (używane do identyfikacji gracza)
            val mySessionId = this.hashCode().toString()

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
                                val room = GameRoom(hostSession = this, hostId = mySessionId)
                                rooms[newCode] = room
                                currentRoomCode = newCode

                                println("Utworzono pokój: $newCode (Host: $mySessionId)")
                                sendSerialized(SignalingMessage.RoomCreated(newCode))
                            }

                            // --- 2. Klient dołącza do pokoju ---
                            is SignalingMessage.JoinRoom -> {
                                val room = rooms[message.roomCode]
                                if (room != null) {
                                    // Dodajemy klienta do listy
                                    room.clients[mySessionId] = this
                                    currentRoomCode = message.roomCode

                                    println("Klient dołączył do pokoju: ${message.roomCode} (ID: $mySessionId)")

                                    // Powiadom hosta, że ktoś wszedł (Host zacznie WebRTC Offer dla tego konkretnego gracza)
                                    room.hostSession.sendSerialized(SignalingMessage.PlayerJoined(mySessionId))
                                } else {
                                    sendSerialized(SignalingMessage.Error("Nie znaleziono pokoju o kodzie: ${message.roomCode}"))
                                }
                            }

                            // --- 3. Przekazywanie WebRTC (SDP Offer/Answer/ICE) ---
                            // Teraz musimy wiedzieć DO KOGO wysłać wiadomość (targetId)
                            is SignalingMessage.SdpOffer -> {
                                val code = currentRoomCode
                                if (code != null) {
                                    val room = rooms[code]
                                    if (room != null) {
                                        // Jeśli Host wysyła ofertę -> wysyłamy do konkretnego klienta (targetId)
                                        if (this == room.hostSession) {
                                            val targetId = message.targetId
                                            if (targetId != null) {
                                                val clientSession = room.clients[targetId]
                                                // Dodajemy senderId, żeby klient wiedział od kogo (od Hosta)
                                                clientSession?.sendSerialized(message.copy(senderId = "HOST"))
                                            }
                                        } 
                                        // Jeśli Klient wysyła ofertę (rzadkie, ale możliwe przy renegocjacji) -> do Hosta
                                        else {
                                            // Klient zawsze wysyła do Hosta
                                            room.hostSession.sendSerialized(message.copy(senderId = mySessionId))
                                        }
                                    }
                                }
                            }

                            is SignalingMessage.SdpAnswer -> {
                                val code = currentRoomCode
                                if (code != null) {
                                    val room = rooms[code]
                                    if (room != null) {
                                        // Jeśli Host odpowiada -> do konkretnego klienta
                                        if (this == room.hostSession) {
                                            val targetId = message.targetId
                                            if (targetId != null) {
                                                val clientSession = room.clients[targetId]
                                                clientSession?.sendSerialized(message.copy(senderId = "HOST"))
                                            }
                                        } 
                                        // Jeśli Klient odpowiada -> do Hosta
                                        else {
                                            room.hostSession.sendSerialized(message.copy(senderId = mySessionId))
                                        }
                                    }
                                }
                            }

                            is SignalingMessage.IceCandidate -> {
                                val code = currentRoomCode
                                if (code != null) {
                                    val room = rooms[code]
                                    if (room != null) {
                                        if (this == room.hostSession) {
                                            val targetId = message.targetId
                                            if (targetId != null) {
                                                val clientSession = room.clients[targetId]
                                                clientSession?.sendSerialized(message.copy(senderId = "HOST"))
                                            }
                                        } else {
                                            room.hostSession.sendSerialized(message.copy(senderId = mySessionId))
                                        }
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
                        // Jeśli rozłączył się Host -> Usuń pokój i wywal wszystkich klientów
                        if (mySessionId == room.hostId) {
                            println("Host wyszedł. Zamykanie pokoju $code")
                            room.clients.values.forEach { client ->
                                client.sendSerialized(SignalingMessage.Error("Host zakończył sesję."))
                                client.close(CloseReason(CloseReason.Codes.NORMAL, "Host left"))
                            }
                            rooms.remove(code)
                        }
                        // Jeśli rozłączył się Klient -> Usuń go z listy i powiadom Hosta
                        else {
                            if (room.clients.containsKey(mySessionId)) {
                                println("Klient wyszedł z pokoju $code (ID: $mySessionId)")
                                room.clients.remove(mySessionId)
                                // Opcjonalnie: Można dodać wiadomość PlayerLeft, aby Host usunął gracza z gry
                                room.hostSession.sendSerialized(SignalingMessage.Error("Gracz $mySessionId się rozłączył."))
                            }
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
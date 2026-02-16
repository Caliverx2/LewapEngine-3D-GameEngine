import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Główna hierarchia wiadomości sygnalizacyjnych do obsługi WebRTC i zarządzania pokojami.
 * Przeznaczona do przesyłania przez WebSockety (Ktor).
 */
@Serializable
sealed class SignalingMessage {

    // ------------------------------------------------------------------------
    // Zarządzanie Pokojami (Room Management)
    // ------------------------------------------------------------------------

    /**
     * Wysłane przez Hosta (przycisk "Open Server" w menu PAUSED).
     * Żądanie utworzenia nowej sesji gry.
     */
    @Serializable
    @SerialName("create_room")
    data object CreateRoom : SignalingMessage()

    /**
     * Odpowiedź serwera dla Hosta.
     * Zawiera wygenerowany kod (np. "ABCD"), który Host wyświetla na ekranie.
     */
    @Serializable
    @SerialName("room_created")
    data class RoomCreated(val roomCode: String) : SignalingMessage()

    /**
     * Wysłane przez Klienta (przycisk "Join Server" w menu MULTIPLAYER).
     * @param roomCode Kod wpisany w polu `codeJoinFieldComponent`.
     */
    @Serializable
    @SerialName("join_room")
    data class JoinRoom(val roomCode: String) : SignalingMessage()

    /**
     * Powiadomienie wysyłane do Hosta, gdy ktoś dołączy do pokoju.
     * To sygnał dla Hosta, aby rozpocząć procedurę WebRTC (stworzyć ofertę).
     * @param playerId Unikalny ID klienta, który dołączył (np. UUID sesji WebSocket).
     */
    @Serializable
    @SerialName("player_joined")
    data class PlayerJoined(val playerId: String) : SignalingMessage()

    /**
     * Powiadomienie o błędzie (np. "Nie znaleziono pokoju", "Pokój pełny").
     */
    @Serializable
    @SerialName("error")
    data class Error(val message: String) : SignalingMessage()

    // ------------------------------------------------------------------------
    // Sygnalizacja WebRTC (SDP & ICE)
    // ------------------------------------------------------------------------

    /**
     * Session Description Protocol - Oferta.
     * Wysyłana zazwyczaj przez Hosta po otrzymaniu `PlayerJoined`.
     * @param sdp Treść SDP (ciąg znaków opisujący kodeki, media itp.).
     * @param targetId ID odbiorcy (opcjonalne w modelu 1v1, wymagane przy wielu graczach).
     * @param senderId ID nadawcy (wypełniane przez serwer lub nadawcę).
     */
    @Serializable
    @SerialName("sdp_offer")
    data class SdpOffer(
        val sdp: String,
        val targetId: String? = null,
        val senderId: String? = null
    ) : SignalingMessage()

    /**
     * Session Description Protocol - Odpowiedź.
     * Wysyłana przez Klienta po otrzymaniu `SdpOffer`.
     */
    @Serializable
    @SerialName("sdp_answer")
    data class SdpAnswer(
        val sdp: String,
        val targetId: String? = null,
        val senderId: String? = null
    ) : SignalingMessage()

    /**
     * ICE Candidate (Interactive Connectivity Establishment).
     * Informacje o kandydatach sieciowych (IP:Port), wysyłane asynchronicznie (Trickle ICE).
     */
    @Serializable
    @SerialName("ice_candidate")
    data class IceCandidate(
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int?,
        val targetId: String? = null,
        val senderId: String? = null
    ) : SignalingMessage()
}
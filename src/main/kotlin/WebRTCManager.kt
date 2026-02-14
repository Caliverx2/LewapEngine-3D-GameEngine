package org.lewapnoob.gridMap

import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.media.MediaStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

class WebRTCManager(
    private val onIceCandidate: (RTCIceCandidate) -> Unit,
    private val onSdpOffer: (RTCSessionDescription) -> Unit,
    private val onSdpAnswer: (RTCSessionDescription) -> Unit,
    private val onDataReceived: (ByteBuffer) -> Unit
) {
    private val factory: PeerConnectionFactory
    private var peerConnection: RTCPeerConnection? = null
    private var dataChannel: RTCDataChannel? = null

    // Kolejka kandydatów ICE, którzy pojawili się przed utworzeniem PC
    private val pendingIceCandidates = ConcurrentLinkedQueue<RTCIceCandidate>()

    init {
        factory = PeerConnectionFactory()
    }

    fun startConnection(isHost: Boolean) {
        val iceServer = RTCIceServer()
        iceServer.urls.add("stun:stun.l.google.com:19302")

        val rtcConfig = RTCConfiguration()
        rtcConfig.iceServers.add(iceServer)

        // Obserwator zdarzeń PeerConnection
        val pcObserver = object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) {
                println("WebRTC: GENERATED ICE CANDIDATE: ${candidate.sdpMid} -> ${candidate.sdp}")
                this@WebRTCManager.onIceCandidate(candidate)
            }

            override fun onDataChannel(dc: RTCDataChannel) {
                // To wywoła się u klienta (nie-hosta), gdy host otworzy kanał
                setupDataChannel(dc)
            }

            override fun onIceConnectionChange(newState: RTCIceConnectionState) {
                println("WebRTC ICE State: $newState")
            }

            override fun onSignalingChange(state: RTCSignalingState?) {
                println("WebRTC Signaling State: $state")
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(state: RTCIceGatheringState?) {
                println("WebRTC ICE Gathering State: $state")
            }
            override fun onIceCandidatesRemoved(p0: Array<out RTCIceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RTCRtpReceiver?, p1: Array<out MediaStream>?) {}
            override fun onTrack(transceiver: RTCRtpTransceiver?) {}
        }

        peerConnection = factory.createPeerConnection(rtcConfig, pcObserver)

        if (isHost) {
            // Host tworzy DataChannel
            val init = RTCDataChannelInit()
            // Zmiana na Reliable (TCP-like) dla pewności przesyłu chunków i bloków
            // Domyślne wartości: ordered=true, maxRetransmits=-1 (nieograniczone)
            
            val dc = peerConnection?.createDataChannel("gameData", init)
            setupDataChannel(dc)

            createOffer()
        }
    }

    private fun setupDataChannel(dc: RTCDataChannel?) {
        this.dataChannel = dc
        dc?.registerObserver(object : RTCDataChannelObserver {
            override fun onMessage(buffer: RTCDataChannelBuffer) {
                // println("WebRTC RX: ${buffer.data.remaining()} bytes") // Debug
                // Przekazujemy bajty do logiki gry (nie blokując wątku WebRTC)
                val data = buffer.data
                // Kopiujemy buffer, bo WebRTC może go zrecyklować
                val copy = ByteBuffer.allocate(data.remaining())
                copy.put(data)
                copy.flip()
                onDataReceived(copy)
            }

            override fun onStateChange() {
                println("DataChannel State: ${dataChannel?.state}")
            }

            override fun onBufferedAmountChange(amount: Long) {}
        })
    }

    private fun createOffer() {
        val options = RTCOfferOptions()
        
        peerConnection?.createOffer(options, object : CreateSessionDescriptionObserver {
            override fun onSuccess(desc: RTCSessionDescription) {
                // Ustawiamy Local Description
                peerConnection?.setLocalDescription(desc, object : SetSessionDescriptionObserver {
                    override fun onSuccess() {
                        // Gdy sukces, wysyłamy ofertę
                        onSdpOffer(desc)
                    }
                    override fun onFailure(error: String?) { println("Set Local Desc Failed: $error") }
                })
            }
            override fun onFailure(error: String?) { println("Create Offer Failed: $error") }
        })
    }

    fun handleRemoteOffer(sdp: String) {
        val desc = RTCSessionDescription(RTCSdpType.OFFER, sdp)
        
        peerConnection?.setRemoteDescription(desc, object : SetSessionDescriptionObserver {
            override fun onSuccess() {
                createAnswer()
            }
            override fun onFailure(error: String?) { println("Set Remote Offer Failed: $error") }
        })
    }

    private fun createAnswer() {
        val options = RTCAnswerOptions()
        
        peerConnection?.createAnswer(options, object : CreateSessionDescriptionObserver {
            override fun onSuccess(desc: RTCSessionDescription) {
                peerConnection?.setLocalDescription(desc, object : SetSessionDescriptionObserver {
                    override fun onSuccess() {
                        onSdpAnswer(desc)
                    }
                    override fun onFailure(error: String?) { println("Set Local Answer Failed: $error") }
                })
            }
            override fun onFailure(error: String?) { println("Create Answer Failed: $error") }
        })
    }

    fun handleRemoteAnswer(sdp: String) {
        val desc = RTCSessionDescription(RTCSdpType.ANSWER, sdp)
        peerConnection?.setRemoteDescription(desc, object : SetSessionDescriptionObserver {
            override fun onSuccess() {}
            override fun onFailure(error: String?) { println("Set Remote Answer Failed: $error") }
        })
    }

    fun addIceCandidate(candidate: RTCIceCandidate) {
        println("WebRTC: ADDING REMOTE ICE CANDIDATE: ${candidate.sdpMid} -> ${candidate.sdp}")
        peerConnection?.addIceCandidate(candidate)
    }

    fun sendData(data: ByteBuffer) {
        val dc = dataChannel
        // Wysyłamy tylko, gdy kanał istnieje i jest w stanie OPEN.
        // W przeciwnym razie ignorujemy pakiet (drop) - to normalne podczas łączenia.
        if (dc != null && dc.state == RTCDataChannelState.OPEN) {
            val buffer = RTCDataChannelBuffer(data, true) // true = binary
            dc.send(buffer)
        }
    }

    fun bufferedAmount(): Long {
        return dataChannel?.bufferedAmount ?: 0
    }

    fun close() {
        dataChannel?.close()
        peerConnection?.close()
        factory.dispose()
    }
}
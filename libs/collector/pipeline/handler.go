package pipeline

import (
	"log"

	"github.com/ao-data/albiondata-collector/photon"
)

// Handler encapsulates the full Albion data pipeline: Photon parsing,
// event/operation decoding, and HTTP upload. It is safe to call ReceivePayload
// from multiple goroutines; all dispatch happens on the internal router goroutine.
type Handler struct {
	router *Router
	parser *photon.PhotonParser
}

// NewHandler creates a new Handler using the current ConfigGlobal settings.
// Call Start() to begin processing dispatched operations.
func NewHandler() *Handler {
	createDispatcher()
	r := newRouter()
	h := &Handler{router: r}
	p := photon.NewPhotonParser(h.onRequest, h.onResponse, h.onEvent)
	p.OnEncrypted = h.onEncrypted
	h.parser = p
	return h
}

// Start launches the internal router goroutine. Must be called before ReceivePayload.
func (h *Handler) Start() {
	go h.router.run()
}

// Stop shuts down the router goroutine.
func (h *Handler) Stop() {
	h.router.quit <- true
}

// SetGameServerIP records the source IP of the current packet so the handler
// can determine which Albion server the client is connected to.
func (h *Handler) SetGameServerIP(ip string) {
	h.router.albionstate.GameServerIP = ip
	h.router.albionstate.AODataServerID, h.router.albionstate.AODataIngestBaseURL = h.router.albionstate.GetServer()
}

// ReceivePayload hands a raw Photon UDP/TCP payload to the parser.
func (h *Handler) ReceivePayload(payload []byte) {
	h.parser.ReceivePacket(payload)
}

// RecordPayload queues payload for raw-packet recording (when RecordPath is set).
func (h *Handler) RecordPayload(payload []byte) {
	h.router.recordRawPacket <- photon.RawPacket{Payload: payload}
}

// ReceiveRawPacket replays a previously-recorded RawPacket.
func (h *Handler) ReceiveRawPacket(raw photon.RawPacket) {
	h.parser.ReceivePacket(raw.Payload)
}

func (h *Handler) onEncrypted() {
	if h.router.albionstate.WaitingForMarketData {
		h.router.albionstate.WaitingForMarketData = false
		log.Println("Market data is encrypted. Please see https://www.albion-online-data.com/client/encryption.html for more information.")
	}
}

func (h *Handler) onRequest(opCode byte, params map[byte]interface{}) {
	if _, ok := params[253]; !ok {
		params[253] = uint16(opCode)
	}

	op, err := decodeRequest(params)
	if params[253] != nil {
		if number, ok := toUint16(params[253]); ok {
			shouldDebug, exists := ConfigGlobal.DebugOperations[int(number)]
			if (exists && shouldDebug) || (!exists && ConfigGlobal.DebugOperationsString == "") {
				log.Printf("OperationRequest: [%v]%v - %s", number, OperationType(number), formatDebugPhotonParams(params))
			}
		} else {
			log.Printf("OperationRequest: unexpected type for params[253]: %T = %s", params[253], formatDebugValue(params[253], 0))
		}
	} else if !ConfigGlobal.DebugIgnoreDecodingErrors {
		log.Printf("OperationRequest: ERROR - %s", formatDebugPhotonParams(params))
	}
	h.dispatchOperation(op, err, params)
}

func (h *Handler) onResponse(opCode byte, returnCode int16, _ string, params map[byte]interface{}) {
	if _, ok := toUint16(params[253]); !ok {
		params[253] = uint16(opCode)
	}

	if _, ok := params[0].([]string); ok {
		params[253] = uint16(opAuctionGetOffers)
	}

	if returnCode != 0 {
		rc := uint16(returnCode)
		name := returnCodeName(rc)
		if name == "" {
			name = "Unknown"
		}
		if number, ok := toUint16(params[253]); ok {
			log.Printf("OperationResponse: rc=%d(%s) op=[%v]%v", returnCode, name, number, OperationType(number))
		} else {
			log.Printf("OperationResponse: rc=%d(%s)", returnCode, name)
		}
	}

	op, err := decodeResponse(params)
	if params[253] != nil {
		if number, ok := toUint16(params[253]); ok {
			shouldDebug, exists := ConfigGlobal.DebugOperations[int(number)]
			if (exists && shouldDebug) || (!exists && ConfigGlobal.DebugOperationsString == "") {
				log.Printf("OperationResponse: [%v]%v - %s", number, OperationType(number), formatDebugPhotonParams(params))
			}
		} else {
			log.Printf("OperationResponse: unexpected type for params[253]: %T = %s", params[253], formatDebugValue(params[253], 0))
		}
	} else if !ConfigGlobal.DebugIgnoreDecodingErrors {
		log.Printf("OperationResponse: ERROR - %s", formatDebugPhotonParams(params))
	}
	h.dispatchOperation(op, err, params)
}

func (h *Handler) onEvent(code byte, params map[byte]interface{}) {
	if _, ok := toUint16(params[252]); !ok {
		params[252] = uint16(code)
	}

	op, err := decodeEvent(params)
	if params[252] != nil {
		if number, ok := toUint16(params[252]); ok {
			shouldDebug, exists := ConfigGlobal.DebugEvents[int(number)]
			if (exists && shouldDebug) || (!exists && ConfigGlobal.DebugEventsString == "") {
				log.Printf("EventDataType: [%v]%v - %s", number, EventType(number), formatDebugPhotonParams(params))
			}
		} else {
			log.Printf("EventDataType: unexpected type for params[252]: %T = %s", params[252], formatDebugValue(params[252], 0))
		}
	} else if !ConfigGlobal.DebugIgnoreDecodingErrors {
		log.Printf("EventDataType: ERROR - %s", formatDebugPhotonParams(params))
	}
	h.dispatchOperation(op, err, params)
}

func (h *Handler) dispatchOperation(op operation, err error, params map[byte]interface{}) {
	if err != nil && !ConfigGlobal.DebugIgnoreDecodingErrors {
		log.Printf("Error while decoding an event or operation: %v - params: %s", err, formatDebugPhotonParams(params))
		return
	}
	if op != nil {
		h.router.newOperation <- op
	}
}

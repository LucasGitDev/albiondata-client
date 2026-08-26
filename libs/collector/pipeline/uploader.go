package pipeline

type uploader interface {
	sendToIngest(body []byte, topic string, state *albionState, identifier string)
}

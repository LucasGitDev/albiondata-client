package pipeline

import (
	"log"
)

type operationRealEstateBidOnAuction struct {
}

func (op operationRealEstateBidOnAuction) Process(state *albionState) {
	log.Println("Got RealEstateBidOnAuction operation...")
}

type operationRealEstateBidOnAuctionResponse struct {
}

func (op operationRealEstateBidOnAuctionResponse) Process(state *albionState) {
	log.Println("Got response to RealEstateBidOnAuction operation...")
}

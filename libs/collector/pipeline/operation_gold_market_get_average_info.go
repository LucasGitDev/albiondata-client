package pipeline

import (
	"github.com/ao-data/albiondata-collector/lib"
	"log"
	uuid "github.com/nu7hatch/gouuid"
)

type operationGoldMarketGetAverageInfo struct {
}

func (op operationGoldMarketGetAverageInfo) Process(state *albionState) {
	log.Println("Got GoldMarketGetAverageInfo operation...")
}

type operationGoldMarketGetAverageInfoResponse struct {
	GoldPrices []int   `mapstructure:"0"`
	TimeStamps []int64 `mapstructure:"1"`
}

func (op operationGoldMarketGetAverageInfoResponse) Process(state *albionState) {
	log.Println("Got response to GoldMarketGetAverageInfo operation...")

	upload := lib.GoldPricesUpload{
		Prices:     op.GoldPrices,
		TimeStamps: op.TimeStamps,
	}

	identifier, _ := uuid.NewV4()
	log.Printf("Sending gold prices to ingest (Identifier: %s)", identifier)
	sendMsgToPublicUploaders(upload, lib.NatsGoldPricesIngest, state, identifier.String())
}

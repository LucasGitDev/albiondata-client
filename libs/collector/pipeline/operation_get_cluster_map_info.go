package pipeline

import (
	"github.com/ao-data/albiondata-collector/lib"
	"strconv"

	"log"
	uuid "github.com/nu7hatch/gouuid"
)

type operationGetClusterMapInfo struct {
}

func (op operationGetClusterMapInfo) Process(state *albionState) {
	log.Println("Got GetClusterMapInfo operation...")
}

type operationGetClusterMapInfoResponse struct {
	ZoneID          string   `mapstructure:"0"`
	BuildingType    []int    `mapstructure:"17"`
	AvailableFood   []int    `mapstructure:"22"`
	Reward          []int    `mapstructure:"23"`
	AvailableSilver []int    `mapstructure:"24"`
	Owners          []string `mapstructure:"25"`
	PublicFee       []int    `mapstructure:"34"`
	AssociateFee    []int    `mapstructure:"33"`
	Coordinates     [][]int  `mapstructure:"18"`
	Durability      []int    `mapstructure:"20"`
	Permission      []int    `mapstructure:"31"`
}

func (op operationGetClusterMapInfoResponse) Process(state *albionState) {
	log.Println("Got response to GetClusterMapInfo operation...")

	zoneInt, err := strconv.Atoi(op.ZoneID)
	if err != nil {
		log.Printf("Unable to convert zoneID to int. Probably an instance.. ZoneID: %v", op.ZoneID)
		return
	}

	upload := lib.MapDataUpload{
		ZoneID:          zoneInt,
		BuildingType:    op.BuildingType,
		AvailableFood:   op.AvailableFood,
		Reward:          op.Reward,
		AvailableSilver: op.AvailableSilver,
		Owners:          op.Owners,
		PublicFee:       op.PublicFee,
		AssociateFee:    op.AssociateFee,
		Coordinates:     op.Coordinates,
		Durability:      op.Durability,
		Permission:      op.Permission,
	}

	identifier, _ := uuid.NewV4()
	log.Printf("Sending map data to ingest (Identifier: %s)", identifier)
	sendMsgToPublicUploaders(upload, lib.NatsMapDataIngest, state, identifier.String())
}

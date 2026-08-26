package pipeline

import (
	"github.com/ao-data/albiondata-collector/lib"
	"log"
)

type operationJoinResponse struct {
	CharacterID   lib.CharacterID `mapstructure:"1"`
	CharacterName string          `mapstructure:"2"`
	Location      string          `mapstructure:"8"`
	GuildID       lib.CharacterID `mapstructure:"56"`
	GuildName     string          `mapstructure:"58"`
}

//CharacterPartsJSON string          `mapstructure:"6"`
//Edition            string          `mapstructure:"38"`

func (op operationJoinResponse) Process(state *albionState) {
	log.Printf("Got JoinResponse operation...")

	// Reset the AODataServerID here. This leads to a fresh execution
	// of SetServerID() incase the player switched servers
	state.AODataServerID = 0

	location := normalizeLocationID(op.Location)
	if location != "" {
		log.Printf("Updating player location to %v.", location)
		state.LocationId = location
	} else {
		log.Printf("Ignoring implausible join location value: %q", op.Location)
	}

	if state.CharacterId != op.CharacterID {
		log.Printf("Updating player ID to %v.", op.CharacterID)
	}
	state.CharacterId = op.CharacterID

	if state.CharacterName != op.CharacterName {
		log.Printf("Updating player to %v.", op.CharacterName)
	}
	state.CharacterName = op.CharacterName
}

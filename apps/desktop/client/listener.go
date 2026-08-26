package client

import (
	"encoding/gob"
	"fmt"
	"io"
	"os"

	"github.com/ao-data/albiondata-collector/photon"
	"github.com/ao-data/albiondata-collector/pipeline"
	"github.com/ao-data/albiondata-client/log"
	"github.com/google/gopacket"
	"github.com/google/gopacket/layers"
	"github.com/google/gopacket/pcap"
)

type listener struct {
	handle        *pcap.Handle
	sourcePackets chan gopacket.Packet
	rawCommands   chan photon.RawPacket
	displayName   string
	handler       *pipeline.Handler
	quit          chan bool
}

func newListener(handler *pipeline.Handler) *listener {
	return &listener{
		rawCommands: make(chan photon.RawPacket, 1),
		quit:        make(chan bool, 1),
		handler:     handler,
	}
}

func (l *listener) startOnline(device string, port int) {
	handle, err := pcap.OpenLive(device, 2048, false, pcap.BlockForever)
	if err != nil {
		log.Panic(err)
	}
	l.handle = handle

	err = l.handle.SetBPFFilter(fmt.Sprintf("tcp port %d || udp port %d", port, port))
	if err != nil {
		log.Panic(err)
	}

	source := gopacket.NewPacketSource(l.handle, l.handle.LinkType())
	l.sourcePackets = source.Packets()

	l.displayName = fmt.Sprintf("online: %s:%d", device, port)
	l.run()
}

func (l *listener) startOfflinePcap(path string) {
	handle, err := pcap.OpenOffline(path)
	if err != nil {
		log.Panicf("Problem creating offline source. Error: %v", err)
	}
	l.handle = handle

	source := gopacket.NewPacketSource(handle, handle.LinkType())
	l.sourcePackets = source.Packets()

	l.displayName = fmt.Sprintf("Offline Pcap: %s", path)
	l.run()
}

func (l *listener) startOfflineCommandGob(path string) {
	l.sourcePackets = make(chan gopacket.Packet, 1)

	var decoder *gob.Decoder
	file, err := os.Open(path)
	if err != nil {
		log.Panic("Could not open commands input file ", err)
	} else {
		decoder = gob.NewDecoder(file)
	}

	go func() {
		for {
			raw := &photon.RawPacket{}
			if decoder == nil {
				break
			}
			err = decoder.Decode(raw)
			if err != nil {
				if err == io.EOF {
					break
				}
				log.Error("Could not decode raw packet ", err)
				continue
			}
			l.rawCommands <- *raw
		}

		err = file.Close()
		if err != nil {
			log.Error("Could not close commands input file ", err)
		}
		log.Info("All offline commands should processed now.")
	}()

	l.displayName = fmt.Sprintf("Offline Commands: %s", path)
	l.run()
}

func (l *listener) run() {
	log.Debugf("Starting listener (%s)...", l.displayName)

	for {
		select {
		case <-l.quit:
			log.Debugf("Listener shutting down (%s)...", l.displayName)
			l.handle.Close()
			return
		case packet := <-l.sourcePackets:
			if packet != nil {
				l.processPacket(packet)
			} else {
				l.handle.Close()
				return
			}
		case raw := <-l.rawCommands:
			l.handler.ReceiveRawPacket(raw)
		}
	}
}

func (l *listener) stop() {
	l.quit <- true
	l.handle.Close()
}

func (l *listener) processPacket(packet gopacket.Packet) {
	ipLayer := packet.Layer(layers.LayerTypeIPv4)
	if ipLayer == nil {
		return
	}

	ipv4 := ipLayer.(*layers.IPv4)
	log.Tracef("Packet came from: %s", ipv4.SrcIP)

	if ipv4.SrcIP == nil {
		log.Trace("No IPv4 detected")
		return
	}

	l.handler.SetGameServerIP(ipv4.SrcIP.String())

	var payload []byte
	if udpLayer := packet.Layer(layers.LayerTypeUDP); udpLayer != nil {
		payload = udpLayer.(*layers.UDP).Payload
	} else if tcpLayer := packet.Layer(layers.LayerTypeTCP); tcpLayer != nil {
		payload = tcpLayer.(*layers.TCP).Payload
	}

	if len(payload) == 0 {
		return
	}

	if ConfigGlobal.RecordPath != "" {
		l.handler.RecordPayload(payload)
	}

	l.handler.ReceivePayload(payload)
}

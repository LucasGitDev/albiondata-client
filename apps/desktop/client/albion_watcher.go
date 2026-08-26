package client

import (
	"time"

	"github.com/ao-data/albiondata-collector/pipeline"
	"github.com/ao-data/albiondata-client/log"
)

type albionProcessWatcher struct {
	known     []int
	devices   []string
	listeners map[int][]*listener
	quit      chan bool
	handler   *pipeline.Handler
}

func newAlbionProcessWatcher() *albionProcessWatcher {
	return &albionProcessWatcher{
		listeners: make(map[int][]*listener),
		quit:      make(chan bool),
		handler:   pipeline.NewHandler(),
	}
}

func (apw *albionProcessWatcher) run() error {
	log.Print("Watching Albion")
	physicalInterfaces, err := getAllPhysicalInterface()
	if err != nil {
		return err
	}
	apw.devices = physicalInterfaces
	log.Debugf("Will listen to these devices: %v", apw.devices)
	apw.handler.Start()

	for {
		select {
		case <-apw.quit:
			apw.closeWatcher()
			return nil
		default:
			if len(apw.listeners) == 0 {
				apw.createListeners()
			}
			time.Sleep(time.Second)
		}
	}
}

func (apw *albionProcessWatcher) closeWatcher() {
	log.Print("Albion watcher closed")

	for port := range apw.listeners {
		for _, l := range apw.listeners[port] {
			l.stop()
		}
		delete(apw.listeners, port)
	}

	apw.handler.Stop()
}

func (apw *albionProcessWatcher) createListeners() {
	filtered := [1]int{5056}

	for _, port := range filtered {
		for _, device := range apw.devices {
			l := newListener(apw.handler)
			go l.startOnline(device, port)

			apw.listeners[port] = append(apw.listeners[port], l)
		}
	}
}

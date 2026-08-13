package log

import (
	"context"
	"time"

	"github.com/sirupsen/logrus"
	"github.com/wailsapp/wails/v2/pkg/runtime"
)

// LogEntry is the payload emitted to the frontend via Wails event "log:entry".
type LogEntry struct {
	Level   string `json:"level"`
	Message string `json:"message"`
	Time    string `json:"time"`
}

// WailsHook is a logrus hook that emits log entries as Wails events.
// Must be registered after the Wails context is available (in App.startup).
type WailsHook struct {
	ctx    context.Context
	levels []logrus.Level
	ch     chan LogEntry
}

// NewWailsHook creates a hook for the given Wails context.
// Pass nil for levels to use the default set: Info, Warn, Error.
func NewWailsHook(ctx context.Context, levels []logrus.Level) *WailsHook {
	if levels == nil {
		levels = []logrus.Level{logrus.InfoLevel, logrus.WarnLevel, logrus.ErrorLevel}
	}
	h := &WailsHook{
		ctx:    ctx,
		levels: levels,
		ch:     make(chan LogEntry, 256),
	}
	go h.drain()
	return h
}

func (h *WailsHook) Levels() []logrus.Level { return h.levels }

// Fire is called by logrus on each matching log entry. Non-blocking: drops if buffer full.
func (h *WailsHook) Fire(entry *logrus.Entry) error {
	e := LogEntry{
		Level:   entry.Level.String(),
		Message: entry.Message,
		Time:    entry.Time.Format(time.RFC3339),
	}
	select {
	case h.ch <- e:
	default:
		// buffer full — drop rather than block the caller
	}
	return nil
}

func (h *WailsHook) drain() {
	for e := range h.ch {
		runtime.EventsEmit(h.ctx, "log:entry", e)
	}
}

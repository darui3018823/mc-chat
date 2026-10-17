// Package hub fans out encoded messages to the subscribers of a channel.
package hub

import "sync"

// Sub is one subscriber (a WebSocket connection). Messages are delivered
// on C; if C fills up the subscriber is considered too slow and Dropped
// is closed so its owner can disconnect it.
type Sub struct {
	C       chan []byte
	Dropped chan struct{}

	dropOnce sync.Once
	channel  string // guarded by Hub.mu
}

// NewSub creates a subscriber with the given queue size.
func NewSub(queue int) *Sub {
	return &Sub{C: make(chan []byte, queue), Dropped: make(chan struct{})}
}

// Offer queues msg without blocking, dropping the subscriber on overflow.
func (s *Sub) Offer(msg []byte) {
	select {
	case s.C <- msg:
	default:
		s.dropOnce.Do(func() { close(s.Dropped) })
	}
}

// Hub maps channel names to their subscribers.
type Hub struct {
	mu   sync.RWMutex
	subs map[string]map[*Sub]struct{}
}

// New creates an empty Hub.
func New() *Hub {
	return &Hub{subs: make(map[string]map[*Sub]struct{})}
}

// Subscribe moves s to channel, leaving its previous channel if any.
func (h *Hub) Subscribe(s *Sub, channel string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.removeLocked(s)
	m := h.subs[channel]
	if m == nil {
		m = make(map[*Sub]struct{})
		h.subs[channel] = m
	}
	m[s] = struct{}{}
	s.channel = channel
}

// Unsubscribe removes s from its channel.
func (h *Hub) Unsubscribe(s *Sub) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.removeLocked(s)
}

func (h *Hub) removeLocked(s *Sub) {
	if s.channel == "" {
		return
	}
	if m := h.subs[s.channel]; m != nil {
		delete(m, s)
		if len(m) == 0 {
			delete(h.subs, s.channel)
		}
	}
	s.channel = ""
}

// Publish offers msg to every subscriber of channel.
func (h *Hub) Publish(channel string, msg []byte) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	for s := range h.subs[channel] {
		s.Offer(msg)
	}
}

// Count returns the number of subscribers in channel.
func (h *Hub) Count(channel string) int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.subs[channel])
}

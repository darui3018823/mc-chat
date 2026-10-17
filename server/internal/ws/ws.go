// Package ws serves the chat WebSocket used by the Minecraft client mod.
package ws

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"

	"mcchat/internal/auth"
	"mcchat/internal/config"
	"mcchat/internal/hub"
	"mcchat/internal/sanitize"
	"mcchat/internal/store"
)

const (
	readLimit    = 4096
	queueSize    = 64
	writeTimeout = 10 * time.Second
	// pingInterval keeps idle connections alive through Cloudflare (100s idle timeout).
	pingInterval = 30 * time.Second
)

// Server handles /ws and /healthz.
type Server struct {
	cfg     config.Config
	store   *store.Store
	hub     *hub.Hub
	limiter *limiter
	log     *slog.Logger

	// baseCtx is canceled on shutdown to close hijacked connections,
	// which http.Server.Shutdown does not track.
	baseCtx context.Context

	// sendMu serializes "store then publish" and "subscribe then load
	// history", so subscribers see message IDs in increasing order with
	// no gap or duplicate between history and live messages.
	sendMu sync.Mutex
}

// New creates a Server. ctx bounds the lifetime of all connections.
func New(ctx context.Context, cfg config.Config, st *store.Store, log *slog.Logger) *Server {
	return &Server{
		cfg:     cfg,
		store:   st,
		hub:     hub.New(),
		limiter: newLimiter(cfg.RateBurst, cfg.RatePerSec),
		log:     log,
		baseCtx: ctx,
	}
}

// Handler returns the HTTP routes.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /ws", s.handleWS)
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("ok\n"))
	})
	return mux
}

// clientIP prefers the address forwarded by Cloudflare/nginx.
func clientIP(r *http.Request) string {
	if ip := r.Header.Get("CF-Connecting-IP"); ip != "" {
		return ip
	}
	if ip := r.Header.Get("X-Real-IP"); ip != "" {
		return ip
	}
	return r.RemoteAddr
}

var errRevokedClose = errors.New("token revoked")

type conn struct {
	ws      *websocket.Conn
	sub     *hub.Sub
	token   store.Token
	channel string
	log     *slog.Logger
}

func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	log := s.log.With("ip", clientIP(r))
	tok := auth.BearerToken(r)
	if tok == "" {
		http.Error(w, "missing bearer token", http.StatusUnauthorized)
		return
	}
	t, err := s.store.TokenByHash(r.Context(), auth.Hash(tok))
	if errors.Is(err, store.ErrNotFound) {
		log.Info("rejected unknown token")
		http.Error(w, "invalid token", http.StatusUnauthorized)
		return
	}
	if err != nil {
		log.Error("token lookup", "err", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	wsc, err := websocket.Accept(w, r, nil)
	if err != nil {
		log.Info("websocket accept", "err", err)
		return
	}
	wsc.SetReadLimit(readLimit)

	c := &conn{ws: wsc, sub: hub.NewSub(queueSize), token: t, log: log.With("name", t.Name)}
	c.log.Info("connected")
	ctx, cancel := context.WithCancel(s.baseCtx)
	defer cancel()
	defer s.hub.Unsubscribe(c.sub)

	go s.writeLoop(ctx, cancel, c)
	s.enqueue(c, helloFrame{Type: "hello", Name: t.Name})
	err = s.readLoop(ctx, c)

	switch {
	case errors.Is(err, errRevokedClose):
		// Clients must not reconnect after 1008.
		wsc.Close(websocket.StatusPolicyViolation, "token revoked")
	case s.baseCtx.Err() != nil:
		wsc.Close(websocket.StatusGoingAway, "server shutting down")
	default:
		wsc.Close(websocket.StatusNormalClosure, "")
	}
	c.log.Info("disconnected", "reason", err)
}

func (s *Server) writeLoop(ctx context.Context, cancel context.CancelFunc, c *conn) {
	defer cancel()
	ping := time.NewTicker(pingInterval)
	defer ping.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-c.sub.Dropped:
			c.log.Warn("dropping slow client")
			return
		case b := <-c.sub.C:
			wctx, wcancel := context.WithTimeout(ctx, writeTimeout)
			err := c.ws.Write(wctx, websocket.MessageText, b)
			wcancel()
			if err != nil {
				return
			}
		case <-ping.C:
			pctx, pcancel := context.WithTimeout(ctx, writeTimeout)
			err := c.ws.Ping(pctx)
			pcancel()
			if err != nil {
				return
			}
		}
	}
}

// enqueue sends a frame to this connection only.
func (s *Server) enqueue(c *conn, v any) {
	b, err := json.Marshal(v)
	if err != nil {
		c.log.Error("marshal", "err", err)
		return
	}
	c.sub.Offer(b)
}

func (s *Server) readLoop(ctx context.Context, c *conn) error {
	for {
		typ, data, err := c.ws.Read(ctx)
		if err != nil {
			return err
		}
		var f inFrame
		if typ != websocket.MessageText || json.Unmarshal(data, &f) != nil {
			s.enqueue(c, errorFrame{"error", errBadRequest})
			continue
		}
		switch f.Type {
		case "join":
			s.join(ctx, c, f.Channel)
		case "send":
			if !s.send(ctx, c, f.Text) {
				return errRevokedClose
			}
		default:
			s.enqueue(c, errorFrame{"error", errBadRequest})
		}
	}
}

func toFrame(m store.Message) msgFrame {
	return msgFrame{Type: "msg", ID: m.ID, Channel: m.Channel, Name: m.Name, Text: m.Text, TS: m.TS.UnixMilli()}
}

func (s *Server) join(ctx context.Context, c *conn, addr string) {
	ch, err := s.cfg.ResolveChannel(addr)
	if err != nil {
		s.enqueue(c, errorFrame{"error", errBadChannel})
		return
	}
	s.sendMu.Lock()
	defer s.sendMu.Unlock()
	s.hub.Subscribe(c.sub, ch)
	c.channel = ch
	msgs, err := s.store.Recent(ctx, ch, s.cfg.History)
	if err != nil {
		c.log.Error("load history", "channel", ch, "err", err)
		s.enqueue(c, errorFrame{"error", errInternal})
		msgs = nil
	}
	h := historyFrame{Type: "history", Channel: ch, Messages: make([]msgFrame, 0, len(msgs))}
	for _, m := range msgs {
		h.Messages = append(h.Messages, toFrame(m))
	}
	s.enqueue(c, h)
	c.log.Info("joined", "channel", ch)
}

// send stores and broadcasts a message. It returns false if the
// connection must be closed because its token was revoked.
func (s *Server) send(ctx context.Context, c *conn, text string) bool {
	if c.channel == "" {
		s.enqueue(c, errorFrame{"error", errNotJoined})
		return true
	}
	text = sanitize.Text(text, s.cfg.MaxRunes)
	if text == "" {
		s.enqueue(c, errorFrame{"error", errEmpty})
		return true
	}
	if !s.limiter.allow(c.token.ID) {
		s.enqueue(c, errorFrame{"error", errRateLimited})
		return true
	}
	if ok, err := s.store.TokenActive(ctx, c.token.ID); err == nil && !ok {
		return false
	}

	s.sendMu.Lock()
	defer s.sendMu.Unlock()
	m, err := s.store.AddMessage(ctx, store.Message{
		Channel: c.channel, TokenID: c.token.ID, Name: c.token.Name, Text: text,
	})
	if err != nil {
		c.log.Error("store message", "err", err)
		s.enqueue(c, errorFrame{"error", errInternal})
		return true
	}
	b, err := json.Marshal(toFrame(m))
	if err != nil {
		c.log.Error("marshal", "err", err)
		return true
	}
	s.hub.Publish(c.channel, b)
	return true
}

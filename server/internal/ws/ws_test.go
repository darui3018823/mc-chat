package ws

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"mcchat/internal/auth"
	"mcchat/internal/config"
	"mcchat/internal/store"
)

type env struct {
	t   *testing.T
	srv *httptest.Server
	st  *store.Store
}

func newEnv(t *testing.T, mutate func(*config.Config)) *env {
	t.Helper()
	c, _ := store.NewCipher(1, map[byte][]byte{1: bytes.Repeat([]byte{7}, 32)})
	st, err := store.Open(filepath.Join(t.TempDir(), "t.db"), c)
	if err != nil {
		t.Fatal(err)
	}
	cfg := config.Default()
	cfg.RateBurst = 100
	if mutate != nil {
		mutate(&cfg)
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := New(ctx, cfg, st, slog.New(slog.NewTextHandler(io.Discard, nil)))
	srv := httptest.NewServer(s.Handler())
	t.Cleanup(func() { cancel(); srv.Close(); st.Close() })
	return &env{t: t, srv: srv, st: st}
}

func (e *env) token(name string) string {
	tok, hash := auth.NewToken()
	if _, err := e.st.AddToken(context.Background(), name, hash); err != nil {
		e.t.Fatal(err)
	}
	return tok
}

type client struct {
	t *testing.T
	c *websocket.Conn
}

func (e *env) dial(tok string) *client {
	e.t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	url := "ws" + strings.TrimPrefix(e.srv.URL, "http") + "/ws"
	c, _, err := websocket.Dial(ctx, url, &websocket.DialOptions{
		HTTPHeader: http.Header{"Authorization": {"Bearer " + tok}},
	})
	if err != nil {
		e.t.Fatal(err)
	}
	e.t.Cleanup(func() { c.CloseNow() })
	cl := &client{t: e.t, c: c}
	if h := cl.next(); h["type"] != "hello" {
		e.t.Fatalf("first frame = %v, want hello", h)
	}
	return cl
}

func (c *client) write(v any) {
	c.t.Helper()
	b, _ := json.Marshal(v)
	if err := c.c.Write(context.Background(), websocket.MessageText, b); err != nil {
		c.t.Fatal(err)
	}
}

func (c *client) next() map[string]any {
	c.t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_, b, err := c.c.Read(ctx)
	if err != nil {
		c.t.Fatalf("read: %v", err)
	}
	var m map[string]any
	json.Unmarshal(b, &m)
	return m
}

// expectNone asserts nothing arrives within a short window.
func (c *client) expectNone() {
	c.t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	if _, b, err := c.c.Read(ctx); err == nil {
		c.t.Fatalf("unexpected frame %s", b)
	}
}

func (c *client) join(ch string) map[string]any {
	c.t.Helper()
	c.write(map[string]string{"type": "join", "channel": ch})
	h := c.next()
	if h["type"] != "history" {
		c.t.Fatalf("join reply = %v", h)
	}
	return h
}

func TestUnauthorized(t *testing.T) {
	e := newEnv(t, nil)
	url := "ws" + strings.TrimPrefix(e.srv.URL, "http") + "/ws"
	for _, hdr := range []http.Header{nil, {"Authorization": {"Bearer mcc_wrong"}}} {
		_, resp, err := websocket.Dial(context.Background(), url, &websocket.DialOptions{HTTPHeader: hdr})
		if err == nil || resp == nil || resp.StatusCode != http.StatusUnauthorized {
			t.Fatalf("dial with %v: err=%v resp=%v", hdr, err, resp)
		}
	}
}

func TestChatFlow(t *testing.T) {
	e := newEnv(t, func(c *config.Config) {
		c.Aliases = map[string]string{"play.daruks.com": "mc.daruks.com"}
	})
	alice := e.dial(e.token("alice"))
	bob := e.dial(e.token("bob"))
	carol := e.dial(e.token("carol"))

	if h := alice.join("MC.daruks.com:25565"); h["channel"] != "mc.daruks.com" {
		t.Fatalf("channel not normalized: %v", h["channel"])
	}
	if h := bob.join("play.daruks.com"); h["channel"] != "mc.daruks.com" {
		t.Fatalf("alias not applied: %v", h["channel"])
	}
	carol.join("other.example.com")

	alice.write(map[string]string{"type": "send", "text": "hello\n§cworld"})
	for _, c := range []*client{alice, bob} {
		m := c.next()
		if m["type"] != "msg" || m["name"] != "alice" || m["text"] != "hello cworld" {
			t.Fatalf("got %v", m)
		}
	}
	carol.expectNone()

	// A late joiner receives the message as history.
	dave := e.dial(e.token("dave"))
	h := dave.join("mc.daruks.com")
	msgs := h["messages"].([]any)
	if len(msgs) != 1 || msgs[0].(map[string]any)["text"] != "hello cworld" {
		t.Fatalf("history = %v", msgs)
	}
}

func TestErrors(t *testing.T) {
	e := newEnv(t, func(c *config.Config) { c.RateBurst = 1; c.RatePerSec = 0.001 })
	c := e.dial(e.token("alice"))

	c.write(map[string]string{"type": "send", "text": "hi"})
	if m := c.next(); m["code"] != errNotJoined {
		t.Fatalf("got %v", m)
	}
	c.write(map[string]string{"type": "join", "channel": "bad host!"})
	if m := c.next(); m["code"] != errBadChannel {
		t.Fatalf("got %v", m)
	}
	c.write(map[string]string{"type": "nope"})
	if m := c.next(); m["code"] != errBadRequest {
		t.Fatalf("got %v", m)
	}
	c.join("mc.daruks.com")
	c.write(map[string]string{"type": "send", "text": " \n "})
	if m := c.next(); m["code"] != errEmpty {
		t.Fatalf("got %v", m)
	}
	c.write(map[string]string{"type": "send", "text": "one"})
	if m := c.next(); m["type"] != "msg" {
		t.Fatalf("got %v", m)
	}
	c.write(map[string]string{"type": "send", "text": "two"})
	if m := c.next(); m["code"] != errRateLimited {
		t.Fatalf("got %v", m)
	}
}

func TestRevokedTokenDisconnected(t *testing.T) {
	e := newEnv(t, nil)
	c := e.dial(e.token("alice"))
	c.join("mc.daruks.com")

	toks, _ := e.st.ListTokens(context.Background())
	e.st.RevokeToken(context.Background(), toks[0].ID)

	c.write(map[string]string{"type": "send", "text": "hi"})
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_, b, err := c.c.Read(ctx)
	if got := websocket.CloseStatus(err); got != websocket.StatusPolicyViolation {
		t.Fatalf("close status = %v (frame %s, err %v), want 1008", got, b, err)
	}
}

func TestLimiter(t *testing.T) {
	l := newLimiter(2, 1)
	now := time.Unix(0, 0)
	l.now = func() time.Time { return now }
	if !l.allow(1) || !l.allow(1) || l.allow(1) {
		t.Fatal("burst not enforced")
	}
	if !l.allow(2) {
		t.Fatal("keys must be independent")
	}
	now = now.Add(time.Second)
	first, second := l.allow(1), l.allow(1)
	if !first || second {
		t.Fatal("refill not applied")
	}
}

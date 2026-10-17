package ws

// Client → server frames.
type inFrame struct {
	Type    string `json:"type"` // "join" | "send"
	Channel string `json:"channel,omitempty"`
	Text    string `json:"text,omitempty"`
}

// Server → client frames.

type msgFrame struct {
	Type    string `json:"type"` // "msg"
	ID      int64  `json:"id"`
	Channel string `json:"channel"`
	Name    string `json:"name"`
	Text    string `json:"text"`
	TS      int64  `json:"ts"` // unix milliseconds
}

type historyFrame struct {
	Type     string     `json:"type"` // "history"
	Channel  string     `json:"channel"`
	Messages []msgFrame `json:"messages"`
}

type helloFrame struct {
	Type string `json:"type"` // "hello"
	Name string `json:"name"`
}

type errorFrame struct {
	Type string `json:"type"` // "error"
	Code string `json:"code"`
}

// Error codes sent in errorFrame.Code.
const (
	errBadRequest  = "bad_request"
	errBadChannel  = "bad_channel"
	errNotJoined   = "not_joined"
	errEmpty       = "empty"
	errRateLimited = "rate_limited"
	errInternal    = "internal"
)

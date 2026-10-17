// Package sanitize normalizes user-supplied text and channel names.
package sanitize

import (
	"errors"
	"net"
	"strconv"
	"strings"
	"unicode"
)

// Text strips control characters, Minecraft formatting codes (§) and
// bidi overrides, folds line breaks/tabs into single spaces, trims, and
// truncates the result to maxRunes runes.
func Text(s string, maxRunes int) string {
	s = strings.ToValidUTF8(s, "�")
	var b strings.Builder
	n := 0
	lastSpace := false
	for _, r := range s {
		switch {
		case r == '\n' || r == '\r' || r == '\t':
			r = ' '
		case r == '§', unicode.IsControl(r), isBidiControl(r):
			continue
		}
		if r == ' ' {
			if lastSpace || n == 0 {
				continue
			}
			lastSpace = true
		} else {
			lastSpace = false
		}
		if n >= maxRunes {
			break
		}
		b.WriteRune(r)
		n++
	}
	return strings.TrimRight(b.String(), " ")
}

func isBidiControl(r rune) bool {
	return (r >= 0x202A && r <= 0x202E) || (r >= 0x2066 && r <= 0x2069) ||
		r == 0x200E || r == 0x200F || r == 0x061C
}

// ErrChannel is returned for addresses that cannot be used as a channel.
var ErrChannel = errors.New("invalid channel")

// DefaultPort is the Minecraft port omitted from canonical channel names.
const DefaultPort = "25565"

// Channel converts a Minecraft server address as typed by a player
// ("MC.Example.com:25565", "1.2.3.4", "[::1]:25570") into a canonical
// channel name: lower-case host, no trailing dot, default port dropped.
func Channel(addr string) (string, error) {
	a := strings.ToLower(strings.TrimSpace(addr))
	host, port := a, ""
	if h, p, err := net.SplitHostPort(a); err == nil {
		host, port = h, p
	} else if strings.HasPrefix(a, "[") && strings.HasSuffix(a, "]") {
		host = a[1 : len(a)-1]
	}
	host = strings.TrimSuffix(host, ".")
	if host == "" || len(host) > 253 {
		return "", ErrChannel
	}
	for _, r := range host {
		if !(r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '.' || r == '-' || r == '_' || r == ':') {
			return "", ErrChannel
		}
	}
	if port != "" {
		p, err := strconv.Atoi(port)
		if err != nil || p < 1 || p > 65535 {
			return "", ErrChannel
		}
		port = strconv.Itoa(p)
		if port == DefaultPort {
			port = ""
		}
	}
	if strings.Contains(host, ":") {
		host = "[" + host + "]"
	}
	if port != "" {
		return host + ":" + port, nil
	}
	return host, nil
}

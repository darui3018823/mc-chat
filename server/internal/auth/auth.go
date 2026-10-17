// Package auth issues and verifies bearer tokens.
//
// Tokens are random and only their SHA-256 hash is stored, so a leaked
// database does not leak usable tokens.
package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"net/http"
	"strings"
)

// Prefix marks mcchat tokens so they are recognizable in config files.
const Prefix = "mcc_"

// NewToken returns a fresh token and its hash.
func NewToken() (token string, hash []byte) {
	b := make([]byte, 32)
	rand.Read(b) // never returns an error
	token = Prefix + base64.RawURLEncoding.EncodeToString(b)
	return token, Hash(token)
}

// Hash returns the value stored in the database for token.
func Hash(token string) []byte {
	h := sha256.Sum256([]byte(token))
	return h[:]
}

// BearerToken extracts the token from an "Authorization: Bearer" header.
func BearerToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	scheme, tok, ok := strings.Cut(h, " ")
	if !ok || !strings.EqualFold(scheme, "Bearer") {
		return ""
	}
	return strings.TrimSpace(tok)
}

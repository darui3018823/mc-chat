package store

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
)

// Cipher encrypts message bodies with AES-256-GCM.
//
// Sealed layout: keyID(1) || nonce(12) || ciphertext+tag. The key ID lets
// old rows stay readable after the current key is rotated.
type Cipher struct {
	cur   byte
	aeads map[byte]cipher.AEAD
}

var errUnknownKey = errors.New("store: ciphertext uses unknown key id")

// NewCipher builds a Cipher that seals with keys[curID] and can open any
// ciphertext produced with a key in keys. Every key must be 32 bytes.
func NewCipher(curID byte, keys map[byte][]byte) (*Cipher, error) {
	if _, ok := keys[curID]; !ok {
		return nil, fmt.Errorf("store: current key id %d not provided", curID)
	}
	c := &Cipher{cur: curID, aeads: make(map[byte]cipher.AEAD, len(keys))}
	for id, k := range keys {
		if len(k) != 32 {
			return nil, fmt.Errorf("store: key %d must be 32 bytes, got %d", id, len(k))
		}
		block, err := aes.NewCipher(k)
		if err != nil {
			return nil, err
		}
		gcm, err := cipher.NewGCM(block)
		if err != nil {
			return nil, err
		}
		c.aeads[id] = gcm
	}
	return c, nil
}

// CipherFromEnv reads keys from the environment:
//
//	MCCHAT_KEY       base64 32-byte key used for new rows (required)
//	MCCHAT_KEY_ID    id of MCCHAT_KEY, 1-255 (default 1)
//	MCCHAT_OLD_KEYS  "id:base64,id:base64" keys kept only for decryption
func CipherFromEnv() (*Cipher, error) {
	raw := os.Getenv("MCCHAT_KEY")
	if raw == "" {
		return nil, errors.New("MCCHAT_KEY is not set (generate one with: mcchat-server keygen)")
	}
	cur := byte(1)
	if s := os.Getenv("MCCHAT_KEY_ID"); s != "" {
		id, err := strconv.Atoi(s)
		if err != nil || id < 1 || id > 255 {
			return nil, fmt.Errorf("MCCHAT_KEY_ID must be 1-255, got %q", s)
		}
		cur = byte(id)
	}
	keys := map[byte][]byte{}
	k, err := decodeKey(raw)
	if err != nil {
		return nil, fmt.Errorf("MCCHAT_KEY: %w", err)
	}
	keys[cur] = k
	if old := os.Getenv("MCCHAT_OLD_KEYS"); old != "" {
		for part := range strings.SplitSeq(old, ",") {
			idStr, kStr, ok := strings.Cut(strings.TrimSpace(part), ":")
			id, err := strconv.Atoi(idStr)
			if !ok || err != nil || id < 1 || id > 255 {
				return nil, fmt.Errorf("MCCHAT_OLD_KEYS: bad entry %q", part)
			}
			k, err := decodeKey(kStr)
			if err != nil {
				return nil, fmt.Errorf("MCCHAT_OLD_KEYS id %d: %w", id, err)
			}
			if byte(id) == cur {
				return nil, fmt.Errorf("MCCHAT_OLD_KEYS id %d collides with MCCHAT_KEY_ID", id)
			}
			keys[byte(id)] = k
		}
	}
	return NewCipher(cur, keys)
}

// NewKey returns a random key encoded for MCCHAT_KEY.
func NewKey() string {
	b := make([]byte, 32)
	rand.Read(b)
	return base64.StdEncoding.EncodeToString(b)
}

func decodeKey(s string) ([]byte, error) {
	s = strings.TrimSpace(s)
	if k, err := base64.StdEncoding.DecodeString(s); err == nil {
		return k, nil
	}
	return base64.RawURLEncoding.DecodeString(s)
}

// Seal encrypts plain, binding it to aad.
func (c *Cipher) Seal(plain, aad []byte) []byte {
	gcm := c.aeads[c.cur]
	out := make([]byte, 1+gcm.NonceSize(), 1+gcm.NonceSize()+len(plain)+gcm.Overhead())
	out[0] = c.cur
	rand.Read(out[1:])
	return gcm.Seal(out, out[1:], plain, aad)
}

// Open decrypts data produced by Seal with the same aad.
func (c *Cipher) Open(data, aad []byte) ([]byte, error) {
	if len(data) < 1 {
		return nil, errors.New("store: ciphertext too short")
	}
	gcm, ok := c.aeads[data[0]]
	if !ok {
		return nil, errUnknownKey
	}
	ns := gcm.NonceSize()
	if len(data) < 1+ns+gcm.Overhead() {
		return nil, errors.New("store: ciphertext too short")
	}
	return gcm.Open(nil, data[1:1+ns], data[1+ns:], aad)
}

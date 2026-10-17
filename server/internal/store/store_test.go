package store

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"path/filepath"
	"testing"
)

func testCipher(t *testing.T) *Cipher {
	t.Helper()
	c, err := NewCipher(1, map[byte][]byte{1: bytes.Repeat([]byte{1}, 32)})
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func openTest(t *testing.T, c *Cipher) (*Store, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "test.db")
	s, err := Open(path, c)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s, path
}

func TestCipherRoundTrip(t *testing.T) {
	c := testCipher(t)
	sealed := c.Seal([]byte("こんにちは"), []byte("aad"))
	if sealed[0] != 1 {
		t.Fatalf("key id = %d, want 1", sealed[0])
	}
	if bytes.Contains(sealed, []byte("こんにちは")) {
		t.Fatal("plaintext visible in ciphertext")
	}
	got, err := c.Open(sealed, []byte("aad"))
	if err != nil || string(got) != "こんにちは" {
		t.Fatalf("Open = %q, %v", got, err)
	}
	if _, err := c.Open(sealed, []byte("other")); err == nil {
		t.Fatal("Open with wrong aad succeeded")
	}
	sealed[len(sealed)-1] ^= 1
	if _, err := c.Open(sealed, []byte("aad")); err == nil {
		t.Fatal("Open of tampered ciphertext succeeded")
	}
	if a, b := c.Seal([]byte("x"), nil), c.Seal([]byte("x"), nil); bytes.Equal(a, b) {
		t.Fatal("nonce reused")
	}
}

func TestCipherRotation(t *testing.T) {
	k1, k2 := bytes.Repeat([]byte{1}, 32), bytes.Repeat([]byte{2}, 32)
	old, _ := NewCipher(1, map[byte][]byte{1: k1})
	sealed := old.Seal([]byte("old"), nil)

	rotated, err := NewCipher(2, map[byte][]byte{1: k1, 2: k2})
	if err != nil {
		t.Fatal(err)
	}
	if got, err := rotated.Open(sealed, nil); err != nil || string(got) != "old" {
		t.Fatalf("old row unreadable after rotation: %q, %v", got, err)
	}
	if rotated.Seal(nil, nil)[0] != 2 {
		t.Fatal("new rows must use the current key")
	}
	onlyNew, _ := NewCipher(2, map[byte][]byte{2: k2})
	if _, err := onlyNew.Open(sealed, nil); !errors.Is(err, errUnknownKey) {
		t.Fatalf("err = %v, want errUnknownKey", err)
	}
}

func TestCipherFromEnv(t *testing.T) {
	t.Setenv("MCCHAT_KEY", NewKey())
	t.Setenv("MCCHAT_KEY_ID", "3")
	t.Setenv("MCCHAT_OLD_KEYS", "1:"+NewKey())
	c, err := CipherFromEnv()
	if err != nil {
		t.Fatal(err)
	}
	if c.cur != 3 || len(c.aeads) != 2 {
		t.Fatalf("cur=%d keys=%d", c.cur, len(c.aeads))
	}
	t.Setenv("MCCHAT_KEY", "c2hvcnQ=")
	if _, err := CipherFromEnv(); err == nil {
		t.Fatal("short key accepted")
	}
}

func TestTokens(t *testing.T) {
	ctx := context.Background()
	s, _ := openTest(t, nil)

	id, err := s.AddToken(ctx, "alice", []byte("h1"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.AddToken(ctx, "alice", []byte("h2")); !errors.Is(err, ErrNameTaken) {
		t.Fatalf("duplicate name: err = %v", err)
	}
	tok, err := s.TokenByHash(ctx, []byte("h1"))
	if err != nil || tok.ID != id || tok.Name != "alice" {
		t.Fatalf("TokenByHash = %+v, %v", tok, err)
	}
	if _, err := s.TokenByHash(ctx, []byte("nope")); !errors.Is(err, ErrNotFound) {
		t.Fatalf("unknown hash: err = %v", err)
	}
	if err := s.RevokeToken(ctx, id); err != nil {
		t.Fatal(err)
	}
	if _, err := s.TokenByHash(ctx, []byte("h1")); !errors.Is(err, ErrNotFound) {
		t.Fatalf("revoked token still valid: %v", err)
	}
	if ok, _ := s.TokenActive(ctx, id); ok {
		t.Fatal("TokenActive true after revoke")
	}
	if err := s.RevokeToken(ctx, id); !errors.Is(err, ErrNotFound) {
		t.Fatalf("double revoke: err = %v", err)
	}
	// The name is free again once the old token is revoked.
	if _, err := s.AddToken(ctx, "alice", []byte("h3")); err != nil {
		t.Fatal(err)
	}
	list, _ := s.ListTokens(ctx)
	if len(list) != 2 || list[0].RevokedAt == nil || list[1].RevokedAt != nil {
		t.Fatalf("ListTokens = %+v", list)
	}
}

func TestMessages(t *testing.T) {
	ctx := context.Background()
	s, path := openTest(t, testCipher(t))
	tid, _ := s.AddToken(ctx, "bob", []byte("h"))

	for _, txt := range []string{"one", "two", "three"} {
		if _, err := s.AddMessage(ctx, Message{Channel: "a", TokenID: tid, Name: "bob", Text: txt}); err != nil {
			t.Fatal(err)
		}
	}
	s.AddMessage(ctx, Message{Channel: "b", TokenID: tid, Name: "bob", Text: "other"})

	got, err := s.Recent(ctx, "a", 2)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 || got[0].Text != "two" || got[1].Text != "three" || got[0].ID >= got[1].ID {
		t.Fatalf("Recent = %+v", got)
	}

	// Bodies must be encrypted at rest.
	raw, _ := sql.Open("sqlite", "file:"+path)
	defer raw.Close()
	var body []byte
	raw.QueryRow(`SELECT body FROM messages WHERE id = ?`, got[1].ID).Scan(&body)
	if len(body) == 0 || bytes.Contains(body, []byte("three")) {
		t.Fatalf("body not encrypted: %q", body)
	}

	// A row moved to another channel must fail to decrypt (AAD binds channel).
	raw.Exec(`UPDATE messages SET channel = 'b' WHERE id = ?`, got[1].ID)
	if _, err := s.Recent(ctx, "b", 10); err == nil {
		t.Fatal("ciphertext moved across channels decrypted")
	}
}

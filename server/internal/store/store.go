// Package store persists tokens and chat messages in SQLite.
// Message bodies are encrypted with a Cipher before they reach the database.
package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strconv"
	"time"

	_ "modernc.org/sqlite"
)

// ErrNotFound is returned when a token or row does not exist.
var ErrNotFound = errors.New("store: not found")

// ErrNameTaken is returned when an active token already uses the name.
var ErrNameTaken = errors.New("store: name already used by an active token")

// Token is an issued credential. The token itself is never stored.
type Token struct {
	ID        int64
	Name      string
	CreatedAt time.Time
	RevokedAt *time.Time
}

// Message is one chat line in a channel.
type Message struct {
	ID      int64
	Channel string
	TokenID int64
	Name    string
	Text    string
	TS      time.Time
}

// Store wraps the database. cipher may be nil for token-only use (CLI).
type Store struct {
	db     *sql.DB
	cipher *Cipher
}

// Open opens (creating if needed) the database at path and migrates it.
func Open(path string, c *Cipher) (*Store, error) {
	dsn := "file:" + path + "?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(1)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	// SQLite allows a single writer; one connection avoids SQLITE_BUSY.
	db.SetMaxOpenConns(1)
	s := &Store{db: db, cipher: c}
	if err := s.migrate(context.Background()); err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}

// Close closes the database.
func (s *Store) Close() error { return s.db.Close() }

var migrations = []string{
	`CREATE TABLE tokens (
		id         INTEGER PRIMARY KEY,
		name       TEXT    NOT NULL,
		hash       BLOB    NOT NULL UNIQUE,
		created_at INTEGER NOT NULL,
		revoked_at INTEGER
	);
	CREATE TABLE messages (
		id       INTEGER PRIMARY KEY,
		channel  TEXT    NOT NULL,
		token_id INTEGER NOT NULL REFERENCES tokens(id),
		name     TEXT    NOT NULL,
		body     BLOB    NOT NULL,
		ts       INTEGER NOT NULL
	);
	CREATE INDEX messages_channel_id ON messages(channel, id);`,
}

func (s *Store) migrate(ctx context.Context) error {
	var v int
	if err := s.db.QueryRowContext(ctx, "PRAGMA user_version").Scan(&v); err != nil {
		return err
	}
	for i := v; i < len(migrations); i++ {
		tx, err := s.db.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, migrations[i]); err != nil {
			tx.Rollback()
			return fmt.Errorf("store: migration %d: %w", i+1, err)
		}
		if _, err := tx.ExecContext(ctx, "PRAGMA user_version = "+strconv.Itoa(i+1)); err != nil {
			tx.Rollback()
			return err
		}
		if err := tx.Commit(); err != nil {
			return err
		}
	}
	return nil
}

// AddToken registers a token hash under name.
func (s *Store) AddToken(ctx context.Context, name string, hash []byte) (int64, error) {
	var n int
	err := s.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM tokens WHERE name = ? AND revoked_at IS NULL`, name).Scan(&n)
	if err != nil {
		return 0, err
	}
	if n > 0 {
		return 0, ErrNameTaken
	}
	res, err := s.db.ExecContext(ctx,
		`INSERT INTO tokens (name, hash, created_at) VALUES (?, ?, ?)`,
		name, hash, time.Now().UnixMilli())
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

// TokenByHash returns the active token with the given hash.
func (s *Store) TokenByHash(ctx context.Context, hash []byte) (Token, error) {
	var t Token
	var created int64
	err := s.db.QueryRowContext(ctx,
		`SELECT id, name, created_at FROM tokens WHERE hash = ? AND revoked_at IS NULL`, hash).
		Scan(&t.ID, &t.Name, &created)
	if errors.Is(err, sql.ErrNoRows) {
		return Token{}, ErrNotFound
	}
	t.CreatedAt = time.UnixMilli(created)
	return t, err
}

// TokenActive reports whether token id exists and is not revoked.
func (s *Store) TokenActive(ctx context.Context, id int64) (bool, error) {
	var n int
	err := s.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM tokens WHERE id = ? AND revoked_at IS NULL`, id).Scan(&n)
	return n > 0, err
}

// ListTokens returns all tokens, including revoked ones.
func (s *Store) ListTokens(ctx context.Context) ([]Token, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id, name, created_at, revoked_at FROM tokens ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Token
	for rows.Next() {
		var t Token
		var created int64
		var revoked sql.NullInt64
		if err := rows.Scan(&t.ID, &t.Name, &created, &revoked); err != nil {
			return nil, err
		}
		t.CreatedAt = time.UnixMilli(created)
		if revoked.Valid {
			r := time.UnixMilli(revoked.Int64)
			t.RevokedAt = &r
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// RevokeToken revokes the active token with the given id.
func (s *Store) RevokeToken(ctx context.Context, id int64) error {
	res, err := s.db.ExecContext(ctx,
		`UPDATE tokens SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL`,
		time.Now().UnixMilli(), id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func messageAAD(channel string) []byte {
	return []byte("mcchat/message\x00" + channel)
}

// AddMessage encrypts and stores m, returning it with ID and TS set.
func (s *Store) AddMessage(ctx context.Context, m Message) (Message, error) {
	if s.cipher == nil {
		return Message{}, errors.New("store: no cipher configured")
	}
	m.TS = time.Now()
	body := s.cipher.Seal([]byte(m.Text), messageAAD(m.Channel))
	res, err := s.db.ExecContext(ctx,
		`INSERT INTO messages (channel, token_id, name, body, ts) VALUES (?, ?, ?, ?, ?)`,
		m.Channel, m.TokenID, m.Name, body, m.TS.UnixMilli())
	if err != nil {
		return Message{}, err
	}
	m.ID, err = res.LastInsertId()
	return m, err
}

// Recent returns up to limit latest messages of channel, oldest first.
func (s *Store) Recent(ctx context.Context, channel string, limit int) ([]Message, error) {
	if s.cipher == nil {
		return nil, errors.New("store: no cipher configured")
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, token_id, name, body, ts FROM messages
		 WHERE channel = ? ORDER BY id DESC LIMIT ?`, channel, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Message
	for rows.Next() {
		m := Message{Channel: channel}
		var body []byte
		var ts int64
		if err := rows.Scan(&m.ID, &m.TokenID, &m.Name, &body, &ts); err != nil {
			return nil, err
		}
		plain, err := s.cipher.Open(body, messageAAD(channel))
		if err != nil {
			return nil, fmt.Errorf("store: message %d: %w", m.ID, err)
		}
		m.Text = string(plain)
		m.TS = time.UnixMilli(ts)
		out = append(out, m)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	return out, nil
}

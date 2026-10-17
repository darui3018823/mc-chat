// Package config loads the server configuration file.
package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"

	"mcchat/internal/sanitize"
)

// Config is the JSON configuration. Zero values are replaced by defaults.
type Config struct {
	// Listen is the address the HTTP server binds to. nginx proxies to it.
	Listen string `json:"listen"`
	// DB is the SQLite database path.
	DB string `json:"db"`
	// History is how many recent messages are sent on join.
	History int `json:"history"`
	// MaxRunes caps the length of a chat message.
	MaxRunes int `json:"maxRunes"`
	// RateBurst and RatePerSec limit messages per token.
	RateBurst  int     `json:"rateBurst"`
	RatePerSec float64 `json:"ratePerSec"`
	// Aliases maps alternative server addresses to one channel,
	// e.g. {"play.example.com": "mc.example.com"}.
	Aliases map[string]string `json:"aliases"`
}

// Default returns the built-in configuration.
func Default() Config {
	return Config{
		Listen:     "127.0.0.1:25566",
		DB:         "mcchat.db",
		History:    50,
		MaxRunes:   256,
		RateBurst:  3,
		RatePerSec: 1,
	}
}

// Load reads path over the defaults. A missing file yields the defaults.
func Load(path string) (Config, error) {
	c := Default()
	data, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return c, c.normalize()
	}
	if err != nil {
		return c, err
	}
	if err := json.Unmarshal(data, &c); err != nil {
		return c, fmt.Errorf("config %s: %w", path, err)
	}
	return c, c.normalize()
}

func (c *Config) normalize() error {
	d := Default()
	if c.Listen == "" {
		c.Listen = d.Listen
	}
	if c.DB == "" {
		c.DB = d.DB
	}
	if c.History <= 0 {
		c.History = d.History
	}
	if c.MaxRunes <= 0 {
		c.MaxRunes = d.MaxRunes
	}
	if c.RateBurst <= 0 {
		c.RateBurst = d.RateBurst
	}
	if c.RatePerSec <= 0 {
		c.RatePerSec = d.RatePerSec
	}
	aliases := make(map[string]string, len(c.Aliases))
	for from, to := range c.Aliases {
		f, err := sanitize.Channel(from)
		if err != nil {
			return fmt.Errorf("config: alias %q: %w", from, err)
		}
		t, err := sanitize.Channel(to)
		if err != nil {
			return fmt.Errorf("config: alias target %q: %w", to, err)
		}
		aliases[f] = t
	}
	c.Aliases = aliases
	return nil
}

// ResolveChannel canonicalizes a server address and applies aliases.
func (c *Config) ResolveChannel(addr string) (string, error) {
	ch, err := sanitize.Channel(addr)
	if err != nil {
		return "", err
	}
	if to, ok := c.Aliases[ch]; ok {
		return to, nil
	}
	return ch, nil
}

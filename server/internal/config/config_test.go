package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLoadMissingUsesDefaults(t *testing.T) {
	c, err := Load(filepath.Join(t.TempDir(), "none.json"))
	if err != nil {
		t.Fatal(err)
	}
	if c.Listen != "127.0.0.1:25566" || c.History != 50 {
		t.Fatalf("defaults not applied: %+v", c)
	}
}

func TestLoadAliases(t *testing.T) {
	p := filepath.Join(t.TempDir(), "c.json")
	os.WriteFile(p, []byte(`{"listen":":9999","aliases":{"Play.Example.com:25565":"mc.example.com"}}`), 0o600)
	c, err := Load(p)
	if err != nil {
		t.Fatal(err)
	}
	if c.Listen != ":9999" || c.MaxRunes != 256 {
		t.Fatalf("merge failed: %+v", c)
	}
	for in, want := range map[string]string{
		"play.example.com":      "mc.example.com",
		"PLAY.example.com.":     "mc.example.com",
		"mc.example.com":        "mc.example.com",
		"other.example.com:123": "other.example.com:123",
	} {
		if got, err := c.ResolveChannel(in); err != nil || got != want {
			t.Errorf("ResolveChannel(%q) = %q, %v; want %q", in, got, err, want)
		}
	}
}

func TestLoadBadAlias(t *testing.T) {
	p := filepath.Join(t.TempDir(), "c.json")
	os.WriteFile(p, []byte(`{"aliases":{"bad host":"x"}}`), 0o600)
	if _, err := Load(p); err == nil {
		t.Fatal("invalid alias accepted")
	}
}

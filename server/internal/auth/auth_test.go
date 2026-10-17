package auth

import (
	"bytes"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestNewToken(t *testing.T) {
	a, ha := NewToken()
	b, _ := NewToken()
	if a == b {
		t.Fatal("tokens must be unique")
	}
	if !strings.HasPrefix(a, Prefix) || len(a) != len(Prefix)+43 {
		t.Fatalf("unexpected token format %q", a)
	}
	if !bytes.Equal(ha, Hash(a)) {
		t.Fatal("returned hash does not match Hash(token)")
	}
}

func TestBearerToken(t *testing.T) {
	tests := map[string]string{
		"Bearer mcc_abc": "mcc_abc",
		"bearer mcc_abc": "mcc_abc",
		"Basic xyz":      "",
		"mcc_abc":        "",
		"":               "",
	}
	for h, want := range tests {
		r := httptest.NewRequest("GET", "/ws", nil)
		if h != "" {
			r.Header.Set("Authorization", h)
		}
		if got := BearerToken(r); got != want {
			t.Errorf("BearerToken(%q) = %q, want %q", h, got, want)
		}
	}
}

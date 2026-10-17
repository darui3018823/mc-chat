package sanitize

import "testing"

func TestText(t *testing.T) {
	tests := []struct {
		name, in string
		max      int
		want     string
	}{
		{"plain", "hello", 256, "hello"},
		{"japanese", "こんにちは 世界", 256, "こんにちは 世界"},
		{"newlines folded", "a\nb\r\nc\td", 256, "a b c d"},
		{"spaces collapsed and trimmed", "   a    b   ", 256, "a b"},
		{"section sign removed", "§cred§r text", 256, "credr text"},
		{"control chars removed", "a\x00b\x1bc\x7fd", 256, "abcd"},
		{"bidi override removed", "abc‮def", 256, "abcdef"},
		{"truncate by runes", "あいうえお", 3, "あいう"},
		{"truncate then trim", "ab cd", 3, "ab"},
		{"invalid utf8", "a\xffb", 256, "a�b"},
		{"emoji kept", "👍🏽", 256, "👍🏽"},
		{"only junk", "\n\t§ \x00", 256, ""},
		{"json-ish kept", `"},{"text":"x`, 256, `"},{"text":"x`},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := Text(tt.in, tt.max); got != tt.want {
				t.Errorf("Text(%q, %d) = %q, want %q", tt.in, tt.max, got, tt.want)
			}
		})
	}
}

func TestChannel(t *testing.T) {
	tests := []struct {
		in, want string
		wantErr  bool
	}{
		{"mc.daruks.com", "mc.daruks.com", false},
		{"MC.Daruks.com", "mc.daruks.com", false},
		{" mc.daruks.com. ", "mc.daruks.com", false},
		{"mc.daruks.com:25565", "mc.daruks.com", false},
		{"mc.daruks.com:25570", "mc.daruks.com:25570", false},
		{"mc.daruks.com:025570", "mc.daruks.com:25570", false},
		{"1.2.3.4", "1.2.3.4", false},
		{"[::1]:25565", "[::1]", false},
		{"[::1]:25570", "[::1]:25570", false},
		{"[::1]", "[::1]", false},
		{"", "", true},
		{"mc.daruks.com:0", "", true},
		{"mc.daruks.com:99999", "", true},
		{"mc.daruks.com:abc", "", true},
		{"evil/../path", "", true},
		{"spa ce.com", "", true},
	}
	for _, tt := range tests {
		got, err := Channel(tt.in)
		if (err != nil) != tt.wantErr || got != tt.want {
			t.Errorf("Channel(%q) = %q, %v; want %q, err=%v", tt.in, got, err, tt.want, tt.wantErr)
		}
	}
}

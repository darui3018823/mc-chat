package hub

import "testing"

func recv(s *Sub) (string, bool) {
	select {
	case m := <-s.C:
		return string(m), true
	default:
		return "", false
	}
}

func TestChannelIsolation(t *testing.T) {
	h := New()
	a1, a2, b := NewSub(4), NewSub(4), NewSub(4)
	h.Subscribe(a1, "a")
	h.Subscribe(a2, "a")
	h.Subscribe(b, "b")

	h.Publish("a", []byte("hi"))
	for _, s := range []*Sub{a1, a2} {
		if m, ok := recv(s); !ok || m != "hi" {
			t.Fatalf("subscriber of a got %q, %v", m, ok)
		}
	}
	if m, ok := recv(b); ok {
		t.Fatalf("subscriber of b got %q", m)
	}
}

func TestSwitchAndUnsubscribe(t *testing.T) {
	h := New()
	s := NewSub(4)
	h.Subscribe(s, "a")
	h.Subscribe(s, "b")
	if h.Count("a") != 0 || h.Count("b") != 1 {
		t.Fatalf("counts a=%d b=%d", h.Count("a"), h.Count("b"))
	}
	h.Publish("a", []byte("old"))
	if _, ok := recv(s); ok {
		t.Fatal("received message from previous channel")
	}
	h.Unsubscribe(s)
	h.Unsubscribe(s) // idempotent
	if h.Count("b") != 0 || len(h.subs) != 0 {
		t.Fatal("subscriber not removed")
	}
}

func TestSlowSubscriberDropped(t *testing.T) {
	h := New()
	s := NewSub(1)
	h.Subscribe(s, "a")
	h.Publish("a", []byte("1"))
	h.Publish("a", []byte("2"))
	h.Publish("a", []byte("3")) // must not panic on double close
	select {
	case <-s.Dropped:
	default:
		t.Fatal("overflowing subscriber not dropped")
	}
}

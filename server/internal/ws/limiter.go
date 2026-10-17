package ws

import (
	"sync"
	"time"
)

// limiter is a token bucket per key, shared by all connections of a token.
type limiter struct {
	mu      sync.Mutex
	burst   float64
	perSec  float64
	buckets map[int64]*bucket
	now     func() time.Time
}

type bucket struct {
	tokens float64
	last   time.Time
}

func newLimiter(burst int, perSec float64) *limiter {
	return &limiter{
		burst:   float64(burst),
		perSec:  perSec,
		buckets: make(map[int64]*bucket),
		now:     time.Now,
	}
}

// allow consumes one token for key, reporting whether it was available.
func (l *limiter) allow(key int64) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	b, ok := l.buckets[key]
	if !ok {
		b = &bucket{tokens: l.burst, last: now}
		l.buckets[key] = b
	}
	b.tokens = min(l.burst, b.tokens+now.Sub(b.last).Seconds()*l.perSec)
	b.last = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

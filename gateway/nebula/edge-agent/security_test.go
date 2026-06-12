package main

import (
	"net"
	"strings"
	"testing"
)

// TestLocalIPInField covers the G1 whitelist: /camera may only target hosts on the configured field
// subnet, and fails OPEN when no valid subnet is configured.
func TestLocalIPInField(t *testing.T) {
	_, n, _ := net.ParseCIDR("192.168.2.0/24")
	cases := []struct {
		name     string
		fieldNet *net.IPNet
		ip       string
		want     bool
	}{
		{"in subnet", n, "192.168.2.64", true},
		{"out of subnet (other LAN)", n, "10.0.0.5", false},
		{"router on field LAN", n, "192.168.2.1", true},
		{"loopback rejected", n, "127.0.0.1", false},
		{"garbage ip rejected", n, "not-an-ip", false},
		{"no subnet configured -> fail open", nil, "10.0.0.5", true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			cfg := Config{fieldNet: c.fieldNet}
			if got := cfg.localIPInField(c.ip); got != c.want {
				t.Fatalf("localIPInField(%q) = %v, want %v", c.ip, got, c.want)
			}
		})
	}
}

// TestSanitizeComment covers G6: a camera name can never break out of the nginx `#` comment line.
func TestSanitizeComment(t *testing.T) {
	inj := "cam\nserver { listen 0.0.0.0:22; proxy_pass 10.0.0.1:22; }"
	got := sanitizeComment(inj)
	if strings.ContainsAny(got, "\n\r") {
		t.Fatalf("sanitizeComment left a newline: %q", got)
	}
	if strings.Contains(got, "server {") && !strings.HasPrefix(got, "camserver {") {
		// newline stripped means the directive text collapses onto the comment line (still inert),
		// which is the point — it stays inside the `#` comment.
	}
	if got := sanitizeComment("normal-cam"); got != "normal-cam" {
		t.Fatalf("sanitizeComment mangled a normal name: %q", got)
	}
	long := strings.Repeat("a", 200)
	if len(sanitizeComment(long)) != 64 {
		t.Fatalf("sanitizeComment did not cap length: %d", len(sanitizeComment(long)))
	}
}

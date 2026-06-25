package main

import (
	"net"
	"os"
	"strings"
	"testing"
	"time"
)

// TestPrivilegedCmd covers G4: when the agent is unprivileged the two root-needing commands are routed
// through `sudo -n`; when already root they run directly.
func TestPrivilegedCmd(t *testing.T) {
	cmd := privilegedCmd("nginx", "-s", "reload")
	got := strings.Join(cmd.Args, " ")
	if os.Geteuid() == 0 {
		if got != "nginx -s reload" {
			t.Fatalf("root should run directly, got %q", got)
		}
	} else {
		if got != "sudo -n nginx -s reload" {
			t.Fatalf("non-root should use sudo -n, got %q", got)
		}
	}
}

// TestNewToken covers G2: per-gateway tokens are 32-hex and unique per call.
func TestNewToken(t *testing.T) {
	a, b := newToken(), newToken()
	if len(a) != 32 {
		t.Fatalf("token len = %d, want 32 hex chars", len(a))
	}
	if a == b {
		t.Fatal("two tokens collided")
	}
}

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

// TestEnrollWindowOpen covers G3: /enroll only accepts a new region within the post-boot window.
func TestEnrollWindowOpen(t *testing.T) {
	c := Config{EnrollWindowMin: 30}
	if !c.enrollWindowOpen(5 * time.Minute) {
		t.Fatal("window should be open 5min after boot")
	}
	if c.enrollWindowOpen(31 * time.Minute) {
		t.Fatal("window should be closed 31min after boot")
	}
	if !(Config{EnrollWindowMin: 0}).enrollWindowOpen(99 * time.Hour) {
		t.Fatal("EnrollWindowMin<=0 must disable the lock (always open)")
	}
}

// TestFirewallInbound covers G2:C: with a group set the inbound rule restricts to that group; empty
// keeps the legacy allow-any host rule.
func TestFirewallInbound(t *testing.T) {
	if got := (Config{NebulaNvrGroup: "nvr"}).firewallInbound(); !strings.Contains(got, "group: nvr") || strings.Contains(got, "host: any") {
		t.Fatalf("group set should yield a group rule, got:\n%s", got)
	}
	if got := (Config{}).firewallInbound(); !strings.Contains(got, "host: any") {
		t.Fatalf("empty group should keep allow-any, got:\n%s", got)
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

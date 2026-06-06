package main

import (
	"os/exec"
	"strings"
	"sync"
	"time"
)

// ztSnap is a cached view of the local ZeroTier state so we don't fork `zerotier-cli` on every
// /health or /identity hit (the NVR scanner + app can poll). Refreshed at most every ztTTL.
type ztSnap struct {
	address string // node address (10-hex), stable identity of this gateway's ZT node
	online  bool   // node reached the planet roots (zerotier-cli info ... ONLINE)
	joined  bool   // joined the fleet network AND config is OK (listnetworks ... OK)
	at      time.Time
}

const ztTTL = 3 * time.Second

var (
	ztMu    sync.Mutex
	ztCache ztSnap
)

// ztStatus returns the cached ZeroTier status, refreshing via zerotier-cli when stale.
func ztStatus(networkID string) ztSnap {
	ztMu.Lock()
	defer ztMu.Unlock()
	if time.Since(ztCache.at) < ztTTL && !ztCache.at.IsZero() {
		return ztCache
	}
	addr, online := ztInfo()
	ztCache = ztSnap{address: addr, online: online, joined: ztJoined(networkID), at: time.Now()}
	return ztCache
}

// ztInfo parses `zerotier-cli info` → "200 info <address> <version> ONLINE".
func ztInfo() (address string, online bool) {
	out, err := exec.Command("zerotier-cli", "info").Output()
	if err != nil {
		return "", false
	}
	f := strings.Fields(strings.TrimSpace(string(out)))
	if len(f) >= 3 && f[1] == "info" {
		address = f[2]
	}
	if len(f) >= 5 {
		online = strings.EqualFold(f[4], "ONLINE")
	}
	return address, online
}

// ztJoined reports whether the node joined networkID and got an OK config.
// `zerotier-cli listnetworks` lines: "200 listnetworks <nwid> <name> <mac> <status> ...".
// The header line carries the literal "<nwid>" so it never matches a real id.
func ztJoined(networkID string) bool {
	if networkID == "" {
		return false
	}
	out, err := exec.Command("zerotier-cli", "listnetworks").Output()
	if err != nil {
		return false
	}
	for _, line := range strings.Split(string(out), "\n") {
		f := strings.Fields(line)
		if len(f) >= 6 && f[1] == "listnetworks" && f[2] == networkID {
			return strings.EqualFold(f[5], "OK")
		}
	}
	return false
}

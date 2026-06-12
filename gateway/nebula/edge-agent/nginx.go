package main

import (
	"bytes"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// renderStreamConf builds the full /etc/nginx/stream.d/cameras.conf body from state.
// One pair of server{} blocks per camera: ONVIF (->:80) and RTSP (->:554), both listening on the
// ZeroTier IP only (never the field LAN) — matching the doc's `bind=<ztip>` security rule.
func renderStreamConf(ztIP string, cams []Camera) string {
	var b bytes.Buffer
	b.WriteString("# Managed by nvr-edge-agent — DO NOT EDIT BY HAND.\n")
	b.WriteString("# Regenerated from state on every change and on boot.\n\n")
	for _, c := range sortedCameras(cams) {
		// G6: the name is attacker-influenced (set via /camera) and only used in this comment — strip
		// anything that could break out of the `#` line and inject nginx directives. LocalIP is already
		// IP-validated at ingest, so it's safe in the proxy_pass below.
		fmt.Fprintf(&b, "# %s (%s)\n", sanitizeComment(c.Name), c.LocalIP)
		fmt.Fprintf(&b, "server {\n    listen %s:%d;\n    proxy_pass %s:80;\n}\n", ztIP, c.OnvifPort, c.LocalIP)
		fmt.Fprintf(&b, "server {\n    listen %s:%d;\n    proxy_pass %s:554;\n}\n\n", ztIP, c.RtspPort, c.LocalIP)
	}
	return b.String()
}

// sanitizeComment removes newlines and control characters so an attacker-supplied camera name cannot
// break out of an nginx `#` comment line and inject directives (G6). Also caps length to keep the
// config readable.
func sanitizeComment(s string) string {
	s = strings.Map(func(r rune) rune {
		if r == '\n' || r == '\r' || r < 0x20 {
			return -1
		}
		return r
	}, s)
	if len(s) > 64 {
		s = s[:64]
	}
	return s
}

// writeAndReload writes the stream config atomically and reloads nginx. If the reload fails it
// returns the error (caller surfaces it to the API) but the file is left in place.
func writeAndReload(confPath, ztIP string, cams []Camera) error {
	if err := os.MkdirAll(filepath.Dir(confPath), 0o755); err != nil {
		return fmt.Errorf("mkdir conf dir: %w", err)
	}
	body := renderStreamConf(ztIP, cams)
	tmp := confPath + ".tmp"
	if err := os.WriteFile(tmp, []byte(body), 0o644); err != nil {
		return fmt.Errorf("write conf: %w", err)
	}
	if err := os.Rename(tmp, confPath); err != nil {
		return fmt.Errorf("swap conf: %w", err)
	}
	return reloadNginx()
}

// reloadNginx validates then reloads. `nginx -t` first so a bad config never takes down running
// proxies (reload is skipped if the test fails).
func reloadNginx() error {
	if out, err := exec.Command("nginx", "-t").CombinedOutput(); err != nil {
		return fmt.Errorf("nginx -t failed: %v: %s", err, bytes.TrimSpace(out))
	}
	if out, err := exec.Command("nginx", "-s", "reload").CombinedOutput(); err != nil {
		return fmt.Errorf("nginx reload failed: %v: %s", err, bytes.TrimSpace(out))
	}
	return nil
}

// nginxOk reports whether nginx config currently validates (used by /health).
func nginxOk() bool {
	return exec.Command("nginx", "-t").Run() == nil
}

// resyncOnBoot rebuilds the stream config from persisted state and reloads, so proxies come back
// after a Pi reboot without any NVR involvement.
func resyncOnBoot(confPath, ztIP string, cams []Camera) {
	if ztIP == "" {
		log.Println("resync: ZeroTier IP not yet available, skipping nginx rebuild for now")
		return
	}
	if err := writeAndReload(confPath, ztIP, cams); err != nil {
		log.Printf("resync: %v", err)
		return
	}
	log.Printf("resync: rebuilt %d camera proxies on %s", len(cams), ztIP)
}

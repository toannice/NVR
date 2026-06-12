package main

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// nebula.go is the overlay module — the Nebula replacement for zerotier.go. Whereas the ZeroTier agent
// shelled out to `zerotier-cli join/info/listnetworks`, the Nebula agent:
//   - generates an X25519 keypair locally (private key NEVER leaves the Pi),
//   - writes /etc/nebula/{host.crt,ca.crt,config.yml} from what the HQ signing service returns,
//   - manages the `nebula` systemd service, and
//   - reports overlay status (cert present, service active, has overlay IP, cert expiry) for /health.
//
// File layout under cfg.NebulaDir (default /etc/nebula):
//   host.key   private key (keygen, mode 0600, stays here)
//   host.pub   public key (sent to HQ for signing)
//   host.crt   signed cert (from HQ)
//   ca.crt     CA cert (from HQ, for trust)
//   config.yml nebula node config

func (c Config) path(name string) string { return filepath.Join(c.NebulaDir, name) }

// ensureKeypair generates host.key/host.pub via `nebula-cert keygen` if the key doesn't exist yet, and
// returns the public key PEM. Idempotent: an existing key is reused so re-enroll keeps the same identity.
func (c Config) ensureKeypair() (pubPem string, err error) {
	if err := os.MkdirAll(c.NebulaDir, 0o755); err != nil {
		return "", err
	}
	keyPath, pubPath := c.path("host.key"), c.path("host.pub")
	if !fileNonEmpty(keyPath) || !fileNonEmpty(pubPath) {
		cmd := exec.Command(c.NebulaCertBin, "keygen", "-out-key", keyPath, "-out-pub", pubPath)
		if out, err := cmd.CombinedOutput(); err != nil {
			return "", fmt.Errorf("nebula-cert keygen: %v: %s", err, strings.TrimSpace(string(out)))
		}
		_ = os.Chmod(keyPath, 0o600)
	}
	b, err := os.ReadFile(pubPath)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// writeCertAndConfig persists the signed cert + CA from HQ and writes config.yml, then (re)starts the
// nebula service. overlayIPCIDR is e.g. "10.10.20.12/24".
func (c Config) writeCertAndConfig(certPem, caPem, overlayIP, lighthouse, lighthouseOverlayIP string) error {
	if certPem == "" || caPem == "" {
		return fmt.Errorf("empty cert/ca from signing service")
	}
	if err := os.WriteFile(c.path("host.crt"), []byte(certPem), 0o644); err != nil {
		return err
	}
	if err := os.WriteFile(c.path("ca.crt"), []byte(caPem), 0o644); err != nil {
		return err
	}
	if err := os.WriteFile(c.path("config.yml"), []byte(c.renderConfig(lighthouse, lighthouseOverlayIP)), 0o644); err != nil {
		return err
	}
	return nil
}

// firewallInbound returns the inbound firewall rule body for the node config. With NebulaNvrGroup set
// (G2:C) only that group (the NVR's cert group) may reach the agent API + proxied ONVIF/RTSP ports over
// the overlay — so one gateway (group "gateways") can't reach another's. Empty = legacy allow-any.
func (c Config) firewallInbound() string {
	if c.NebulaNvrGroup != "" {
		return fmt.Sprintf(`    - port: any
      proto: any
      group: %s`, c.NebulaNvrGroup)
	}
	return `    - port: any
      proto: any
      host: any`
}

// renderConfig builds a Nebula node (non-lighthouse) config.yml. The overlay IP itself comes from the
// cert (Nebula reads it from there), so it isn't repeated here. Relays are enabled and point at the
// lighthouse overlay IP — effective only if that node runs am_relay (see HQ install).
func (c Config) renderConfig(lighthousePublic, lighthouseOverlayIP string) string {
	return fmt.Sprintf(`# Managed by nvr-edge-agent (Nebula). Do not edit by hand — rewritten on enroll.
pki:
  ca: %[1]s/ca.crt
  cert: %[1]s/host.crt
  key: %[1]s/host.key

static_host_map:
  "%[2]s": ["%[3]s"]

lighthouse:
  am_lighthouse: false
  interval: 60
  hosts:
    - "%[2]s"

listen:
  host: 0.0.0.0
  port: 0          # gateway is a client; ephemeral port (lighthouse uses 4242)

punchy:
  punch: true
  respond: true

relay:
  use_relays: true
  relays:
    - "%[2]s"      # effective only if the lighthouse node runs am_relay: true

tun:
  dev: %[4]s
  mtu: 1300

firewall:
  conntrack:
    tcp_timeout: 12m
    udp_timeout: 3m
    default_timeout: 10m
  outbound:
    - port: any
      proto: any
      host: any
  inbound:
    # G2:C — restricted to the NVR group when NEBULA_FW_NVR_GROUP is set; else allow-any (legacy).
%[5]s
`, c.NebulaDir, lighthouseOverlayIP, lighthousePublic, c.NebulaIface, c.firewallInbound())
}

// restartNebula (re)starts the systemd nebula service so it picks up a freshly written config/cert.
func restartNebula() (string, error) {
	out, err := exec.Command("systemctl", "restart", "nebula").CombinedOutput()
	return strings.TrimSpace(string(out)), err
}

// --- status (cached, like ztStatus) ----------------------------------------

type nebStatus struct {
	hasCert    bool
	serviceUp  bool
	overlayIP  string
	certExpiry string // RFC3339, best-effort from `nebula-cert print`
	at         time.Time
}

const nebTTL = 3 * time.Second

var (
	nebMu    sync.Mutex
	nebCache nebStatus
)

func (c Config) status() nebStatus {
	nebMu.Lock()
	defer nebMu.Unlock()
	if time.Since(nebCache.at) < nebTTL && !nebCache.at.IsZero() {
		return nebCache
	}
	nebCache = nebStatus{
		hasCert:    fileNonEmpty(c.path("host.crt")),
		serviceUp:  serviceActive("nebula"),
		overlayIP:  c.resolveOverlayIP(),
		certExpiry: c.certExpiry(),
		at:         time.Now(),
	}
	return nebCache
}

// certExpiry returns the cert NotAfter (RFC3339) via `nebula-cert print -json`, best-effort ("" on any
// failure). Used by /health so the NVR/watchdog can warn before a cert lapses.
func (c Config) certExpiry() string {
	crt := c.path("host.crt")
	if !fileNonEmpty(crt) {
		return ""
	}
	out, err := exec.Command(c.NebulaCertBin, "print", "-json", "-path", crt).Output()
	if err != nil {
		return ""
	}
	var parsed struct {
		Details struct {
			NotAfter string `json:"notAfter"`
		} `json:"details"`
	}
	if err := json.Unmarshal(out, &parsed); err != nil {
		return ""
	}
	return parsed.Details.NotAfter
}

func serviceActive(unit string) bool {
	out, _ := exec.Command("systemctl", "is-active", unit).Output()
	return strings.TrimSpace(string(out)) == "active"
}

func fileNonEmpty(p string) bool {
	fi, err := os.Stat(p)
	return err == nil && fi.Size() > 0
}

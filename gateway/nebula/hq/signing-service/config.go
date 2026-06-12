package main

import (
	"log"
	"os"
	"strings"
)

// Config is loaded entirely from environment (signing.env via systemd EnvironmentFile). It mirrors
// the ergonomics of the edge-agent: sane defaults so the service runs on a freshly-installed HQ box
// with only an admin token + lighthouse address set.
//
// Roles of the HQ box this service runs on (see gateway/nebula-migration-va-trien-khai.md §4.1):
//   - Nebula CA(s): ca.crt + ca.key per "khách"/trust-domain under CADir (default.{crt,key} for PoC).
//   - Lighthouse: a separate nebula process (not this service) reachable at Lighthouse over UDP 4242.
//   - Signing service (THIS): the control plane that replaces ztncui — mints one-time enrollment
//     tokens, accepts a gateway's public key + token, and (after admin "Cấp phép") signs a cert.
type Config struct {
	Listen string // NEBULA_SIGN_LISTEN (default :9443) — bind for the REST API

	NebulaCert string // NEBULA_CERT_BIN — path to the `nebula-cert` binary (default: looked up in PATH)
	CADir      string // NEBULA_CA_DIR — dir holding <caName>.crt/.key (default /etc/nebula-hq/ca)
	StateFile  string // NEBULA_SIGN_STATE — JSON state (tokens + requests) (default /var/lib/nebula-hq/signing-state.json)

	// AdminToken gates the NVR-only endpoints (mint token, list pending, approve, reject). It is the
	// fleet-admin secret, carried as `Authorization: Bearer <token>` by the NVR. Loaded from
	// NEBULA_SIGN_TOKEN or NEBULA_SIGN_TOKEN_FILE (Docker/secret style).
	AdminToken string

	// Lighthouse is the PUBLIC endpoint agents must reach (host:port, UDP 4242), advertised back to a
	// gateway after it enrolls so it can write its config.yml static_host_map. e.g. "203.0.113.10:4242".
	Lighthouse string // NEBULA_LIGHTHOUSE
	// LighthouseOverlayIP is the lighthouse's address ON the overlay (e.g. 10.10.20.1) — goes into the
	// agent's lighthouse.hosts + static_host_map key.
	LighthouseOverlayIP string // NEBULA_LIGHTHOUSE_OVERLAY_IP (default 10.10.20.1)

	// ReservedIPs are overlay IPs the allocator must NEVER hand to a gateway because they belong to
	// infra nodes signed OUTSIDE this service (e.g. the NVR at 10.10.20.2, set up by hand with
	// `nebula-cert sign`). Without this, the first gateway of a pool collides with the NVR. Comma-list,
	// from NEBULA_RESERVED_IPS. The lighthouse (.1), network (.0) and broadcast (.255) are always skipped.
	ReservedIPs []string // NEBULA_RESERVED_IPS, e.g. "10.10.20.2"

	// CertDuration is passed to `nebula-cert sign -duration` (V3 short-lived certs). e.g. "2160h" (90d).
	// Empty = nebula-cert default (1 year). Shorter → faster revoke via expiry, more renewal traffic.
	CertDuration string // NEBULA_CERT_DURATION

	// BlocklistFile is where /revoke appends a revoked cert's fingerprint (V3 fast-cut). The operator
	// wires these into the lighthouse pki.blocklist and reloads it. Empty disables the file write.
	BlocklistFile string // NEBULA_BLOCKLIST_FILE, e.g. /etc/nebula-hq/blocklist.txt
}

func loadConfig() Config {
	c := Config{
		Listen:              envOr("NEBULA_SIGN_LISTEN", ":9443"),
		NebulaCert:          envOr("NEBULA_CERT_BIN", "nebula-cert"),
		CADir:               envOr("NEBULA_CA_DIR", "/etc/nebula-hq/ca"),
		StateFile:           envOr("NEBULA_SIGN_STATE", "/var/lib/nebula-hq/signing-state.json"),
		AdminToken:          loadSecret("NEBULA_SIGN_TOKEN", "NEBULA_SIGN_TOKEN_FILE"),
		Lighthouse:          os.Getenv("NEBULA_LIGHTHOUSE"),
		LighthouseOverlayIP: envOr("NEBULA_LIGHTHOUSE_OVERLAY_IP", "10.10.20.1"),
		ReservedIPs:         splitCSV(os.Getenv("NEBULA_RESERVED_IPS")),
		CertDuration:        envOr("NEBULA_CERT_DURATION", "2160h"),
		BlocklistFile:       os.Getenv("NEBULA_BLOCKLIST_FILE"),
	}
	if c.AdminToken == "" {
		log.Println("WARN: NEBULA_SIGN_TOKEN empty — admin endpoints (mint/approve) will reject every call")
	}
	if c.Lighthouse == "" {
		log.Println("WARN: NEBULA_LIGHTHOUSE empty — agents cannot learn where the lighthouse is")
	}
	return c
}

// splitCSV parses a comma-separated env value into a trimmed, non-empty slice.
func splitCSV(v string) []string {
	out := []string{}
	for _, p := range strings.Split(v, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

// loadSecret reads an inline env var first, then a *_FILE path (trimmed) — same convention the NVR
// uses for Docker secrets (secrets/nebula_sign_token).
func loadSecret(envKey, fileKey string) string {
	if v := os.Getenv(envKey); v != "" {
		return strings.TrimSpace(v)
	}
	if p := os.Getenv(fileKey); p != "" {
		if b, err := os.ReadFile(p); err == nil {
			return strings.TrimSpace(string(b))
		}
	}
	return ""
}

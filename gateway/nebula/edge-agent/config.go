package main

import (
	"log"
	"net"
	"os"
	"strconv"
	"strings"
)

// Config is loaded entirely from environment (agent.env via systemd EnvironmentFile). Compared to the
// ZeroTier agent it drops the ZT_NETWORK_ID (region is chosen at enroll time via signing URL + token)
// and replaces ZeroTier IP detection with Nebula interface detection.
type Config struct {
	Token          string // EDGE_AGENT_TOKEN — required for non-loopback callers (shared fleet secret)
	GatewayName    string // EDGE_AGENT_NAME — human label shown in NVR
	Port           int    // EDGE_AGENT_PORT (default 9797)
	OverlayIP      string // EDGE_AGENT_OVERLAY_IP — override autodetected Nebula IP
	NebulaDir      string // NEBULA_DIR (default /etc/nebula) — ca/host cert+key + config.yml live here
	NebulaCertBin  string // NEBULA_CERT_BIN (default "nebula-cert") — used for keygen on the Pi
	NebulaIface    string // NEBULA_IFACE (default nebula1) — tun device name to detect overlay IP on
	FieldSubnet    string // EDGE_AGENT_FIELD_SUBNET — informational + WS-Discovery hint
	NginxStreamCfg string // EDGE_AGENT_NGINX_CONF (default /etc/nginx/stream.d/cameras.conf)
	StateFile      string // EDGE_AGENT_STATE (default /var/lib/nvr-edge-agent/state.json)
	PortPoolStart  int    // EDGE_AGENT_POOL_START (default 8080)

	fieldNet *net.IPNet // parsed FieldSubnet; nil when unset/invalid (then the /camera whitelist is off)
}

func loadConfig() Config {
	c := Config{
		Token:          os.Getenv("EDGE_AGENT_TOKEN"),
		GatewayName:    envOr("EDGE_AGENT_NAME", hostnameOr("gateway")),
		Port:           envInt("EDGE_AGENT_PORT", 9797),
		OverlayIP:      os.Getenv("EDGE_AGENT_OVERLAY_IP"),
		NebulaDir:      envOr("NEBULA_DIR", "/etc/nebula"),
		NebulaCertBin:  envOr("NEBULA_CERT_BIN", "nebula-cert"),
		NebulaIface:    envOr("NEBULA_IFACE", "nebula1"),
		FieldSubnet:    os.Getenv("EDGE_AGENT_FIELD_SUBNET"),
		NginxStreamCfg: envOr("EDGE_AGENT_NGINX_CONF", "/etc/nginx/stream.d/cameras.conf"),
		StateFile:      envOr("EDGE_AGENT_STATE", "/var/lib/nvr-edge-agent/state.json"),
		PortPoolStart:  envInt("EDGE_AGENT_POOL_START", 8080),
	}
	if c.Token == "" {
		log.Println("WARN: EDGE_AGENT_TOKEN empty — remote (non-loopback) requests will be rejected")
	}
	if c.FieldSubnet != "" {
		if _, n, err := net.ParseCIDR(c.FieldSubnet); err == nil {
			c.fieldNet = n
		} else {
			log.Printf("WARN: EDGE_AGENT_FIELD_SUBNET %q invalid (%v) — /camera localIp whitelist DISABLED", c.FieldSubnet, err)
		}
	} else {
		log.Println("WARN: EDGE_AGENT_FIELD_SUBNET empty — /camera accepts any localIp (set it to whitelist the field LAN)")
	}
	return c
}

// localIPInField reports whether ip is inside the configured field subnet (G1: stop /camera being an
// arbitrary port-forward into the customer LAN). Fails OPEN when no valid field subnet is configured —
// loadConfig logs a warning in that case so the operator knows the whitelist is off.
func (c Config) localIPInField(ip string) bool {
	if c.fieldNet == nil {
		return true
	}
	parsed := net.ParseIP(ip)
	return parsed != nil && c.fieldNet.Contains(parsed)
}

// listenAddrs binds the wildcard 0.0.0.0 (covers loopback, the overlay IP for NVRs, AND the field LAN
// for the Android app). Same rationale as the ZeroTier agent.
func (c Config) listenAddrs() []string {
	return []string{joinHostPort("0.0.0.0", c.Port)}
}

// resolveOverlayIP returns the configured overlay IP or autodetects the Nebula tun interface.
func (c Config) resolveOverlayIP() string {
	if c.OverlayIP != "" {
		return c.OverlayIP
	}
	return detectNebulaIP(c.NebulaIface)
}

// detectNebulaIP returns the IPv4 of the Nebula tun device (default nebula1). It matches the exact
// iface name first, then falls back to any interface whose name starts with "nebula".
func detectNebulaIP(iface string) string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return ""
	}
	for _, ifc := range ifaces {
		if ifc.Name != iface && !strings.HasPrefix(ifc.Name, "nebula") {
			continue
		}
		addrs, _ := ifc.Addrs()
		for _, a := range addrs {
			if ipnet, ok := a.(*net.IPNet); ok {
				if v4 := ipnet.IP.To4(); v4 != nil {
					return v4.String()
				}
			}
		}
	}
	return ""
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func envInt(k string, def int) int {
	if v := os.Getenv(k); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}

func hostnameOr(def string) string {
	if h, err := os.Hostname(); err == nil && h != "" {
		return h
	}
	return def
}

func joinHostPort(host string, port int) string {
	return net.JoinHostPort(host, strconv.Itoa(port))
}

package main

import (
	"log"
	"net"
	"os"
	"strconv"
	"strings"
)

// Config is loaded entirely from environment (agent.env via systemd EnvironmentFile).
// Everything has a sane default so a freshly-imaged Pi runs with just a token + network id.
type Config struct {
	Token          string // EDGE_AGENT_TOKEN — required for non-loopback callers (shared fleet secret)
	GatewayName    string // EDGE_AGENT_NAME — human label shown in NVR
	Port           int    // EDGE_AGENT_PORT (default 9797)
	ZtIP           string // EDGE_AGENT_ZT_IP — override autodetected ZeroTier IP
	ZtNetworkID    string // ZT_NETWORK_ID — best-effort `zerotier-cli join` on boot
	FieldSubnet    string // EDGE_AGENT_FIELD_SUBNET — informational + WS-Discovery hint (e.g. 192.168.2.0/24)
	NginxStreamCfg string // EDGE_AGENT_NGINX_CONF (default /etc/nginx/stream.d/cameras.conf)
	StateFile      string // EDGE_AGENT_STATE (default /var/lib/nvr-edge-agent/state.json)
	PortPoolStart  int    // EDGE_AGENT_POOL_START (default 8080)
}

func loadConfig() Config {
	c := Config{
		Token:          os.Getenv("EDGE_AGENT_TOKEN"),
		GatewayName:    envOr("EDGE_AGENT_NAME", hostnameOr("gateway")),
		Port:           envInt("EDGE_AGENT_PORT", 9797),
		ZtIP:           os.Getenv("EDGE_AGENT_ZT_IP"),
		ZtNetworkID:    os.Getenv("ZT_NETWORK_ID"),
		FieldSubnet:    os.Getenv("EDGE_AGENT_FIELD_SUBNET"),
		NginxStreamCfg: envOr("EDGE_AGENT_NGINX_CONF", "/etc/nginx/stream.d/cameras.conf"),
		StateFile:      envOr("EDGE_AGENT_STATE", "/var/lib/nvr-edge-agent/state.json"),
		PortPoolStart:  envInt("EDGE_AGENT_POOL_START", 8080),
	}
	if c.Token == "" {
		log.Println("WARN: EDGE_AGENT_TOKEN empty — remote (non-loopback) requests will be rejected")
	}
	return c
}

// listenAddrs returns the host:port(s) the API binds. We bind the wildcard 0.0.0.0 unconditionally:
// it covers loopback (local field page), the ZeroTier IP (NVRs), AND the field LAN — the last is
// what the Android onboarding app needs, and on a zero-config baked image the field LAN IP is a
// per-site DHCP address we can't know at bake time. Security is unchanged: /identity + /label are
// read-only/cosmetic by design, and every camera/proxy mutation stays behind the fleet token
// regardless of interface. (Binding 0.0.0.0 + 127.0.0.1 on the same port would EADDRINUSE on Linux
// since the wildcard already includes loopback, so we return the single wildcard bind.)
func (c Config) listenAddrs() []string {
	return []string{joinHostPort("0.0.0.0", c.Port)}
}

// resolveZtIP returns the configured ZT IP or autodetects the ZeroTier interface (named zt*).
func (c Config) resolveZtIP() string {
	if c.ZtIP != "" {
		return c.ZtIP
	}
	return detectZeroTierIP()
}

func detectZeroTierIP() string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return ""
	}
	for _, ifc := range ifaces {
		if !strings.HasPrefix(ifc.Name, "zt") {
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

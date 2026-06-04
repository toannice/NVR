package main

import (
	"fmt"
	"net"
	"regexp"
	"strings"
	"time"

	"crypto/rand"
)

// DiscoveredDevice is one ONVIF device found on the field LAN via WS-Discovery.
type DiscoveredDevice struct {
	IP       string   `json:"ip"`
	Name     string   `json:"name"`
	Hardware string   `json:"hardware"`
	XAddrs   []string `json:"xaddrs"`
}

const wsDiscoveryAddr = "239.255.255.250:3702"

// probeTemplate is a WS-Discovery Probe for ONVIF NetworkVideoTransmitter devices.
const probeTemplate = `<?xml version="1.0" encoding="UTF-8"?>` +
	`<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" ` +
	`xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" ` +
	`xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" ` +
	`xmlns:dn="http://www.onvif.org/ver10/network/wsdl">` +
	`<e:Header><w:MessageID>uuid:%s</w:MessageID>` +
	`<w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>` +
	`<w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>` +
	`</e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>`

var (
	reXAddrs   = regexp.MustCompile(`(?s)<[^>]*XAddrs[^>]*>(.*?)</[^>]*XAddrs>`)
	reScopes   = regexp.MustCompile(`(?s)<[^>]*Scopes[^>]*>(.*?)</[^>]*Scopes>`)
	reHostPort = regexp.MustCompile(`https?://([^/]+)`)
)

// discover sends a multicast Probe and collects ProbeMatch responses for `timeout`.
// Lightweight by design: the agent only finds devices; the NVR does the heavy SOAP over the proxy.
func discover(timeout time.Duration) ([]DiscoveredDevice, error) {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		return nil, fmt.Errorf("listen udp: %w", err)
	}
	defer conn.Close()

	dst, err := net.ResolveUDPAddr("udp4", wsDiscoveryAddr)
	if err != nil {
		return nil, err
	}

	mid := newUUID()
	probe := fmt.Sprintf(probeTemplate, mid)
	if _, err := conn.WriteToUDP([]byte(probe), dst); err != nil {
		return nil, fmt.Errorf("send probe: %w", err)
	}

	_ = conn.SetReadDeadline(time.Now().Add(timeout))
	found := map[string]*DiscoveredDevice{}
	buf := make([]byte, 64*1024)
	for {
		n, _, err := conn.ReadFromUDP(buf)
		if err != nil {
			break // timeout reached
		}
		parseProbeMatch(string(buf[:n]), found)
	}

	out := make([]DiscoveredDevice, 0, len(found))
	for _, d := range found {
		out = append(out, *d)
	}
	return out, nil
}

func parseProbeMatch(body string, found map[string]*DiscoveredDevice) {
	xaddrsRaw := firstGroup(reXAddrs, body)
	if xaddrsRaw == "" {
		return
	}
	xaddrs := strings.Fields(xaddrsRaw)
	ip := hostFromXAddr(xaddrs)
	if ip == "" {
		return
	}
	dev, ok := found[ip]
	if !ok {
		dev = &DiscoveredDevice{IP: ip}
		found[ip] = dev
	}
	dev.XAddrs = xaddrs
	scopes := firstGroup(reScopes, body)
	if name := scopeValue(scopes, "onvif://www.onvif.org/name/"); name != "" {
		dev.Name = name
	}
	if hw := scopeValue(scopes, "onvif://www.onvif.org/hardware/"); hw != "" {
		dev.Hardware = hw
	}
}

func hostFromXAddr(xaddrs []string) string {
	for _, x := range xaddrs {
		if m := reHostPort.FindStringSubmatch(x); m != nil {
			host := m[1]
			if h, _, err := net.SplitHostPort(host); err == nil {
				return h
			}
			return host
		}
	}
	return ""
}

// scopeValue extracts and URL-decodes the suffix of a scope URI with the given prefix.
func scopeValue(scopes, prefix string) string {
	for _, s := range strings.Fields(scopes) {
		if strings.HasPrefix(s, prefix) {
			return unescapeScope(strings.TrimPrefix(s, prefix))
		}
	}
	return ""
}

func unescapeScope(s string) string {
	s = strings.ReplaceAll(s, "%20", " ")
	s = strings.ReplaceAll(s, "%2D", "-")
	s = strings.ReplaceAll(s, "_", " ")
	return s
}

func firstGroup(re *regexp.Regexp, s string) string {
	if m := re.FindStringSubmatch(s); m != nil {
		return strings.TrimSpace(m[1])
	}
	return ""
}

func newUUID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "00000000-0000-0000-0000-000000000000"
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

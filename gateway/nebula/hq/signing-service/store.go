package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

// Token is a one-time enrollment credential an admin mints for a region/khách and hands to the field
// worker (the "mã khu vực" — alongside the lighthouse address). It pins which CA signs the cert, which
// /24 the gateway's overlay IP comes from, and which groups the cert carries. It replaces the ZeroTier
// 16-hex network id, but is a SECRET and single-use (vá lỗ G3 hijack).
type Token struct {
	Token     string `json:"token"`     // opaque random hex the field worker types/scans
	Region    string `json:"region"`    // human label, e.g. "nha-A" / "khachX-hcm"
	CaName    string `json:"caName"`    // which CA under CADir signs (file <caName>.crt/.key); "" = default
	IPCidr    string `json:"ipCidr"`    // pool the overlay IP is carved from, e.g. 10.10.20.0/24
	Groups    string `json:"groups"`    // nebula cert groups, e.g. "gateways"
	ExpiresAt int64  `json:"expiresAt"` // unix sec; 0 = never
	Used      bool   `json:"used"`      // flipped true once a gateway enrolls with it (single-use)
}

func (t *Token) expired() bool { return t.ExpiresAt != 0 && time.Now().Unix() > t.ExpiresAt }

// Request is one gateway's enrollment, keyed by its token. It starts "pending" (waiting for the admin
// to click "Cấp phép" on the NVR) and becomes "signed" once approved — at which point CertPem/CaPem
// are filled and the agent (which has been polling) picks them up.
type Request struct {
	Token     string `json:"token"`
	Name      string `json:"name"`      // gateway name → nebula cert name
	PubPem    string `json:"pubPem"`    // the gateway's X25519 public key (private key stays on the Pi)
	OverlayIP string `json:"overlayIp"` // assigned overlay IP (without mask), e.g. 10.10.20.12
	Status    string `json:"status"`    // "pending" | "signed"
	CertPem   string `json:"certPem,omitempty"`
	CaPem     string `json:"caPem,omitempty"`
	CreatedAt int64  `json:"createdAt"`
	SignedAt  int64  `json:"signedAt,omitempty"`
}

type stateData struct {
	Tokens   map[string]*Token   `json:"tokens"`
	Requests map[string]*Request `json:"requests"` // keyed by token
}

type Store struct {
	mu   sync.Mutex
	path string
	d    stateData
}

func loadStore(path string) (*Store, error) {
	s := &Store{path: path, d: stateData{Tokens: map[string]*Token{}, Requests: map[string]*Request{}}}
	b, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return s, s.saveLocked()
		}
		return nil, err
	}
	if err := json.Unmarshal(b, &s.d); err != nil {
		return nil, err
	}
	if s.d.Tokens == nil {
		s.d.Tokens = map[string]*Token{}
	}
	if s.d.Requests == nil {
		s.d.Requests = map[string]*Request{}
	}
	return s, nil
}

func (s *Store) saveLocked() error {
	if err := os.MkdirAll(filepath.Dir(s.path), 0o755); err != nil {
		return err
	}
	b, err := json.MarshalIndent(s.d, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

// mintToken creates and persists a one-time enrollment token for a region.
func (s *Store) mintToken(region, caName, ipCidr, groups string, ttl time.Duration) (*Token, error) {
	if _, _, err := net.ParseCIDR(ipCidr); err != nil {
		return nil, fmt.Errorf("invalid ipCidr %q: %w", ipCidr, err)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	t := &Token{
		Token: randHex(16), Region: region, CaName: caName, IPCidr: ipCidr, Groups: groups,
	}
	if ttl > 0 {
		t.ExpiresAt = time.Now().Add(ttl).Unix()
	}
	s.d.Tokens[t.Token] = t
	return t, s.saveLocked()
}

func (s *Store) listTokens() []*Token {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]*Token, 0, len(s.d.Tokens))
	for _, t := range s.d.Tokens {
		out = append(out, t)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Region < out[j].Region })
	return out
}

// enroll records a gateway's enrollment request against a token and allocates its overlay IP. It is
// idempotent per token: a re-POST with the same token returns the existing request (so an agent that
// polls/retries doesn't churn). Returns the request + the token (for the lighthouse/CA context).
func (s *Store) enroll(tokenStr, name, pubPem string) (*Request, *Token, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	t := s.d.Tokens[tokenStr]
	if t == nil {
		return nil, nil, errBadToken
	}
	if t.expired() {
		return nil, nil, errExpiredToken
	}
	if r := s.d.Requests[tokenStr]; r != nil {
		// Already enrolled with this token — return the existing request (idempotent for polling).
		// Refresh the pubkey/name in case the agent regenerated before approval.
		if r.Status == "pending" {
			if pubPem != "" {
				r.PubPem = pubPem
			}
			if name != "" {
				r.Name = name
			}
			_ = s.saveLocked()
		}
		return r, t, nil
	}
	if t.Used {
		return nil, nil, errUsedToken
	}
	ip, err := s.allocIPLocked(t.IPCidr)
	if err != nil {
		return nil, nil, err
	}
	r := &Request{
		Token: tokenStr, Name: name, PubPem: pubPem, OverlayIP: ip,
		Status: "pending", CreatedAt: time.Now().Unix(),
	}
	t.Used = true
	s.d.Requests[tokenStr] = r
	return r, t, s.saveLocked()
}

func (s *Store) getRequest(tokenStr string) (*Request, *Token) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.d.Requests[tokenStr], s.d.Tokens[tokenStr]
}

// listPending returns requests awaiting admin approval (for the NVR "Chờ cấp phép" section).
func (s *Store) listPending() []*Request {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := []*Request{}
	for _, r := range s.d.Requests {
		if r.Status == "pending" {
			out = append(out, r)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt < out[j].CreatedAt })
	return out
}

// markSigned stores the signed cert + ca chain against the request and flips it to "signed".
func (s *Store) markSigned(tokenStr, certPem, caPem string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	r := s.d.Requests[tokenStr]
	if r == nil {
		return errNoRequest
	}
	r.CertPem, r.CaPem, r.Status, r.SignedAt = certPem, caPem, "signed", time.Now().Unix()
	return s.saveLocked()
}

func (s *Store) reject(tokenStr string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.d.Requests[tokenStr]; !ok {
		return false
	}
	delete(s.d.Requests, tokenStr)
	// Allow the token to be reused for a fresh attempt after a rejection.
	if t := s.d.Tokens[tokenStr]; t != nil {
		t.Used = false
	}
	_ = s.saveLocked()
	return true
}

// allocIPLocked returns the lowest free host IP in cidr, skipping the network address, the .1
// (reserved for the lighthouse) and any IP already handed out. Caller holds s.mu.
func (s *Store) allocIPLocked(cidr string) (string, error) {
	_, ipnet, err := net.ParseCIDR(cidr)
	if err != nil {
		return "", err
	}
	used := map[string]bool{}
	for _, r := range s.d.Requests {
		used[r.OverlayIP] = true
	}
	ip := cloneIP(ipnet.IP.To4())
	if ip == nil {
		return "", fmt.Errorf("only IPv4 pools supported: %s", cidr)
	}
	first := true
	for ipnet.Contains(ip) {
		if first { // skip network address itself
			first = false
			incIP(ip)
			continue
		}
		last := ip[len(ip)-1]
		if last == 1 || last == 0 || last == 255 { // skip lighthouse(.1), network(.0), broadcast(.255)
			incIP(ip)
			continue
		}
		cand := ip.String()
		if !used[cand] {
			return cand, nil
		}
		incIP(ip)
	}
	return "", fmt.Errorf("pool exhausted: %s", cidr)
}

func incIP(ip net.IP) {
	for i := len(ip) - 1; i >= 0; i-- {
		ip[i]++
		if ip[i] != 0 {
			break
		}
	}
}

func cloneIP(ip net.IP) net.IP {
	if ip == nil {
		return nil
	}
	c := make(net.IP, len(ip))
	copy(c, ip)
	return c
}

func randHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return fmt.Sprintf("%d", time.Now().UnixNano())
	}
	return hex.EncodeToString(b)
}

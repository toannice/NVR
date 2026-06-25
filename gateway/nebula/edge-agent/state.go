package main

import (
	"crypto/rand"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
)

// Camera is one proxied field camera. The NVR reads this list (GET /cameras) and builds its
// ONVIF endpoint = http://<overlayIP>:<OnvifPort>/... and RTSP = rtsp://<overlayIP>:<RtspPort>/...
// NOTE: no credentials are stored on the gateway by design — the NVR admin types them on adopt.
// (Unchanged from the ZeroTier agent — the L4 proxy is overlay-agnostic.)
type Camera struct {
	LocalIP       string `json:"localIp"`
	Name          string `json:"name"`
	OnvifPort     int    `json:"onvifPort"`
	RtspPort      int    `json:"rtspPort"`
	WebUIPort     int    `json:"webUIPort,omitempty"`     // overlay port → camera web UI
	WebUILocalPort int   `json:"webUILocalPort,omitempty"` // camera-side port (default 80)
}

// Enroll captures everything the app's /enroll handed us + what the HQ signing service assigned. It is
// the Nebula analog of the ZeroTier "NetworkID": instead of a 16-hex network id, a gateway is pointed
// at a region by a (signing service URL + one-time token); the signing service then tells us our
// overlay IP and the lighthouse to talk to. Persisted so a reboot resumes polling / re-applies config.
type Enroll struct {
	SigningURL          string `json:"signingUrl,omitempty"`          // HQ signing service base URL (public)
	Token               string `json:"token,omitempty"`               // one-time enrollment token ("mã khu vực")
	OverlayIP           string `json:"overlayIp,omitempty"`           // assigned by signing service, e.g. 10.10.20.12
	Lighthouse          string `json:"lighthouse,omitempty"`          // lighthouse PUBLIC addr host:4242
	LighthouseOverlayIP string `json:"lighthouseOverlayIp,omitempty"` // lighthouse overlay ip, e.g. 10.10.20.1
	Signed              bool   `json:"signed,omitempty"`              // cert received + config written + nebula started
}

// State is persisted to disk so proxies + enrollment survive reboots.
type State struct {
	GatewayID string `json:"gatewayId"`
	// AgentToken (G2) is a per-gateway API secret generated on first boot. The agent accepts it ALONGSIDE
	// the shared fleet token, so a stolen SD only leaks this one Pi's field-LAN secret — not a credential
	// usable against the rest of the fleet. The NVR's main path is the Nebula cert group (G2:C), not a
	// bearer token, so this is defense-in-depth for the field-LAN surface; see commercialization notes.
	AgentToken string   `json:"agentToken,omitempty"`
	Name       string   `json:"name,omitempty"`
	Installer  string   `json:"installer,omitempty"`
	Location   string   `json:"location,omitempty"`
	Enroll     Enroll   `json:"enroll"`
	Cameras    []Camera `json:"cameras"`

	mu   sync.Mutex `json:"-"`
	path string     `json:"-"`
}

func loadState(path string) (*State, error) {
	s := &State{path: path}
	b, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			s.GatewayID = newID()
			s.AgentToken = newToken()
			return s, s.save()
		}
		return nil, err
	}
	if err := json.Unmarshal(b, s); err != nil {
		return nil, err
	}
	s.path = path
	dirty := false
	if s.GatewayID == "" {
		s.GatewayID = newID()
		dirty = true
	}
	if s.AgentToken == "" { // upgrade an older state.json that predates per-gateway tokens
		s.AgentToken = newToken()
		dirty = true
	}
	// Migrate cameras that predate WebUIPort — assign a port from the same pool.
	for i := range s.Cameras {
		if s.Cameras[i].WebUIPort == 0 {
			s.Cameras[i].WebUIPort = s.allocLocked(8080, nil)
			s.Cameras[i].WebUILocalPort = 80
			dirty = true
		}
	}
	if dirty {
		_ = s.save()
	}
	return s, nil
}

func (s *State) save() error {
	if err := os.MkdirAll(filepath.Dir(s.path), 0o755); err != nil {
		return err
	}
	b, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

func (s *State) snapshot() []Camera {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Camera, len(s.Cameras))
	copy(out, s.Cameras)
	return out
}

func (s *State) find(localIP string) (Camera, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, c := range s.Cameras {
		if c.LocalIP == localIP {
			return c, true
		}
	}
	return Camera{}, false
}

func (s *State) upsert(localIP, name string, webUILocalPort, poolStart int) (Camera, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := range s.Cameras {
		if s.Cameras[i].LocalIP == localIP {
			if name != "" {
				s.Cameras[i].Name = name
			}
			return s.Cameras[i], false
		}
	}
	onvif := s.allocLocked(poolStart, nil)
	rtsp := s.allocLocked(poolStart, map[int]bool{onvif: true})
	webui := s.allocLocked(poolStart, map[int]bool{onvif: true, rtsp: true})
	if webUILocalPort <= 0 {
		webUILocalPort = 80
	}
	cam := Camera{LocalIP: localIP, Name: name, OnvifPort: onvif, RtspPort: rtsp, WebUIPort: webui, WebUILocalPort: webUILocalPort}
	s.Cameras = append(s.Cameras, cam)
	return cam, true
}

func (s *State) setLabel(name, installer, location string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if name != "" {
		s.Name = name
	}
	if installer != "" {
		s.Installer = installer
	}
	if location != "" {
		s.Location = location
	}
}

func (s *State) labels() (name, installer, location string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.Name, s.Installer, s.Location
}

// enrollSnapshot returns a copy of the current enrollment under lock.
func (s *State) enrollSnapshot() Enroll {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.Enroll
}

// setEnroll records the signing target + token the app pointed us at (resets Signed since it's a new
// region). Caller persists with s.save() and kicks the enroll loop.
func (s *State) setEnroll(signingURL, token string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.Enroll = Enroll{SigningURL: signingURL, Token: token}
}

// applyAssignment stores what the signing service returned on /enroll (overlay ip + lighthouse) while
// still pending. Caller persists.
func (s *State) applyAssignment(overlayIP, lighthouse, lighthouseOverlayIP string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if overlayIP != "" {
		s.Enroll.OverlayIP = overlayIP
	}
	if lighthouse != "" {
		s.Enroll.Lighthouse = lighthouse
	}
	if lighthouseOverlayIP != "" {
		s.Enroll.LighthouseOverlayIP = lighthouseOverlayIP
	}
}

func (s *State) markSigned(signed bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.Enroll.Signed = signed
}

func (s *State) remove(localIP string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := range s.Cameras {
		if s.Cameras[i].LocalIP == localIP {
			s.Cameras = append(s.Cameras[:i], s.Cameras[i+1:]...)
			return true
		}
	}
	return false
}

var reservedPorts = map[int]bool{1984: true, 8554: true, 8555: true}

func (s *State) allocLocked(poolStart int, extra map[int]bool) int {
	used := map[int]bool{}
	for _, c := range s.Cameras {
		used[c.OnvifPort] = true
		used[c.RtspPort] = true
		if c.WebUIPort != 0 {
			used[c.WebUIPort] = true
		}
	}
	for p := poolStart; p < 65535; p++ {
		if used[p] || reservedPorts[p] || extra[p] {
			continue
		}
		return p
	}
	return 0
}

func newID() string {
	b := make([]byte, 6)
	if _, err := rand.Read(b); err != nil {
		return fmt.Sprintf("gw-%d", os.Getpid())
	}
	return fmt.Sprintf("gw-%x", b)
}

// newToken returns a 32-hex (128-bit) per-gateway API token (G2). Falls back to a time seed only if the
// CSPRNG is unavailable (never expected) so the agent still boots.
func newToken() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return fmt.Sprintf("gwtok-%d", os.Getpid())
	}
	return fmt.Sprintf("%x", b)
}

func sortedCameras(in []Camera) []Camera {
	out := make([]Camera, len(in))
	copy(out, in)
	sort.Slice(out, func(i, j int) bool { return out[i].OnvifPort < out[j].OnvifPort })
	return out
}

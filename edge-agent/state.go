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
// ONVIF endpoint = http://<ztIP>:<OnvifPort>/... and RTSP = rtsp://<ztIP>:<RtspPort>/...
// NOTE: no credentials are stored on the gateway by design — the NVR admin types them on adopt.
type Camera struct {
	LocalIP   string `json:"localIp"`   // camera IP on the field LAN, e.g. 192.168.2.64
	Name      string `json:"name"`      // free label set by the field worker
	OnvifPort int    `json:"onvifPort"` // gateway port forwarding to <LocalIP>:80
	RtspPort  int    `json:"rtspPort"`  // gateway port forwarding to <LocalIP>:554
}

// State is persisted to disk so proxies survive reboots (rebuilt into nginx on start).
type State struct {
	GatewayID string   `json:"gatewayId"`           // stable id generated once, helps NVR keep identity across IP changes
	Name      string   `json:"name,omitempty"`      // label set via app /label (overrides EDGE_AGENT_NAME)
	Installer string   `json:"installer,omitempty"` // who installed it (field worker), set via app
	Location  string   `json:"location,omitempty"`  // site/location label, set via app
	Cameras   []Camera `json:"cameras"`

	mu   sync.Mutex `json:"-"`
	path string     `json:"-"`
}

func loadState(path string) (*State, error) {
	s := &State{path: path}
	b, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			s.GatewayID = newID()
			return s, s.save()
		}
		return nil, err
	}
	if err := json.Unmarshal(b, s); err != nil {
		return nil, err
	}
	if s.GatewayID == "" {
		s.GatewayID = newID()
	}
	s.path = path
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

// snapshot returns a copy of the camera list under lock (safe for callers to read).
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

// upsert adds or updates a camera. Idempotent by LocalIP: if it already exists, returns the
// existing port assignment (so re-adding the same camera is a no-op move). Allocates two free
// ports from the pool otherwise. Returns the resulting camera and whether it was newly created.
func (s *State) upsert(localIP, name string, poolStart int) (Camera, bool) {
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
	cam := Camera{LocalIP: localIP, Name: name, OnvifPort: onvif, RtspPort: rtsp}
	s.Cameras = append(s.Cameras, cam)
	return cam, true
}

// setLabel updates the human labels set by the Android onboarding app. Empty values are ignored
// so a partial update never wipes an existing field.
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

// labels returns the current label triple under lock.
func (s *State) labels() (name, installer, location string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.Name, s.Installer, s.Location
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

// reserved ports that must never be handed out (go2rtc API/RTSP/WebRTC + the agent's own port).
var reservedPorts = map[int]bool{1984: true, 8554: true, 8555: true}

// allocLocked finds the lowest free port >= poolStart not in state, not reserved, not in `extra`.
// Caller must hold s.mu.
func (s *State) allocLocked(poolStart int, extra map[int]bool) int {
	used := map[int]bool{}
	for _, c := range s.Cameras {
		used[c.OnvifPort] = true
		used[c.RtspPort] = true
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

// sortedCameras is a small helper for stable JSON output.
func sortedCameras(in []Camera) []Camera {
	out := make([]Camera, len(in))
	copy(out, in)
	sort.Slice(out, func(i, j int) bool { return out[i].OnvifPort < out[j].OnvifPort })
	return out
}

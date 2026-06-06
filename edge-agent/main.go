// nvr-edge-agent — passive L4 provisioning agent for a field gateway (Raspberry Pi).
//
// Design (see 4-6-to-do-jobs.md, Plan A):
//   - HTTP SERVER ONLY. The agent never connects to any NVR; NVRs discover it (auto-scan the
//     ZeroTier subnet) and drive it. This keeps it decoupled from any single NVR (multi-NVR ready).
//   - Per camera it only allocates two nginx `stream{}` TCP-forward ports (ONVIF + RTSP) — no SOAP,
//     no go2rtc restream, no credentials stored. All heavy ONVIF work stays on the NVR (256MB budget).
//   - State persists to disk; on boot it rebuilds nginx + reloads so proxies survive reboots.
package main

import (
	"embed"
	"encoding/json"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"
)

//go:embed web/index.html
var webFS embed.FS

var startTime = time.Now()

type server struct {
	cfg   Config
	state *State
}

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC)
	cfg := loadConfig()

	st, err := loadState(cfg.StateFile)
	if err != nil {
		log.Fatalf("load state: %v", err)
	}

	// Best-effort ZeroTier join, then wait briefly for an IP so the first nginx rebuild binds right.
	bootstrapZeroTier(cfg)
	ztIP := waitForZtIP(cfg, 15*time.Second)

	// Restore proxies from persisted state (survives reboot, no NVR needed).
	resyncOnBoot(cfg.NginxStreamCfg, ztIP, st.snapshot())

	s := &server{cfg: cfg, state: st}
	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.handleHealth)
	mux.HandleFunc("/identity", s.handleIdentity)
	mux.HandleFunc("/label", s.handleLabel)
	mux.HandleFunc("/discovery", s.handleDiscovery)
	mux.HandleFunc("/camera", s.handleCamera)
	mux.HandleFunc("/cameras", s.handleCameras)
	mux.HandleFunc("/", s.handleIndex)

	handler := s.auth(mux)

	addrs := cfg.listenAddrs()
	log.Printf("nvr-edge-agent %q starting on %v (gatewayId=%s)", cfg.GatewayName, addrs, st.GatewayID)
	var wg sync.WaitGroup
	for _, a := range addrs {
		a := a
		wg.Add(1)
		go func() {
			defer wg.Done()
			srv := &http.Server{Addr: a, Handler: handler, ReadHeaderTimeout: 10 * time.Second}
			if err := srv.ListenAndServe(); err != nil {
				log.Printf("listener %s stopped: %v", a, err)
			}
		}()
	}
	wg.Wait()
}

// publicPaths are reachable without the fleet token from any caller (LAN, not just loopback).
// They are read-only or cosmetic and are what the Android onboarding app uses over the field LAN
// (it has no fleet token): /identity to find+identify a gateway, /label to name it (Tier 0, D4).
// All camera/proxy mutation stays behind the fleet token (NVR-only).
var publicPaths = map[string]bool{"/identity": true, "/label": true}

// auth enforces the bearer token for non-loopback callers. Loopback (the local field page) and
// publicPaths (the field app) are exempt so they work without embedding the secret.
func (s *server) auth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !isLoopback(r.RemoteAddr) && !publicPaths[r.URL.Path] {
			tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
			if s.cfg.Token == "" || tok != s.cfg.Token {
				http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

func (s *server) handleHealth(w http.ResponseWriter, r *http.Request) {
	zt := ztStatus(s.cfg.ZtNetworkID)
	name, installer, location := s.state.labels()
	if name == "" {
		name = s.cfg.GatewayName
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":      "ok",
		"gatewayId":   s.state.GatewayID,
		"gatewayName": name,
		"installer":   installer,
		"location":    location,
		"zerotierIp":  s.cfg.resolveZtIP(),
		"ztAddress":   zt.address,
		"ztJoined":    zt.joined,
		"ztOnline":    zt.online,
		"fieldSubnet": s.cfg.FieldSubnet,
		"agentPort":   s.cfg.Port,
		"ramMb":       processRSSMb(),
		"uptimeSec":   int(time.Since(startTime).Seconds()),
		"nginxOk":     nginxOk(),
		"cameras":     sortedCameras(s.state.snapshot()),
	})
}

// handleIdentity (no-auth, LAN) lets the Android onboarding app discover + identify a gateway on
// the field LAN without the fleet token. Read-only; reports who/where + ZeroTier join status so
// the field worker can name it and tell the admin which member to approve on ztncui.
func (s *server) handleIdentity(w http.ResponseWriter, r *http.Request) {
	zt := ztStatus(s.cfg.ZtNetworkID)
	name, installer, location := s.state.labels()
	if name == "" {
		name = s.cfg.GatewayName
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"gatewayId":   s.state.GatewayID,
		"name":        name,
		"installer":   installer,
		"location":    location,
		"ztAddress":   zt.address,
		"ztJoined":    zt.joined,
		"ztOnline":    zt.online,
		"zerotierIp":  s.cfg.resolveZtIP(),
		"fieldSubnet": s.cfg.FieldSubnet,
		"agentPort":   s.cfg.Port,
		"alive":       true,
		"cameraCount": len(s.state.snapshot()),
	})
}

// handleLabel (no-auth, LAN) sets the human labels (name/installer/location) from the field app.
// Low-impact + cosmetic (Tier 0 design): no camera/proxy effect, persisted to state.json.
func (s *server) handleLabel(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, `{"error":"method"}`, http.StatusMethodNotAllowed)
		return
	}
	var req struct {
		Name      string `json:"name"`
		Installer string `json:"installer"`
		Location  string `json:"location"`
	}
	if err := readJSON(r, &req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.Name == "" && req.Installer == "" && req.Location == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "name, installer or location required"})
		return
	}
	s.state.setLabel(req.Name, req.Installer, req.Location)
	if err := s.state.save(); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	name, installer, location := s.state.labels()
	log.Printf("label set: name=%q installer=%q location=%q", name, installer, location)
	writeJSON(w, http.StatusOK, map[string]any{"name": name, "installer": installer, "location": location})
}

func (s *server) handleDiscovery(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, `{"error":"method"}`, http.StatusMethodNotAllowed)
		return
	}
	devices, err := discover(4 * time.Second)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"devices": devices})
}

// handleCamera: POST creates/ensures the proxy ports for a camera; DELETE removes them.
func (s *server) handleCamera(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodPost:
		var req struct {
			LocalIP string `json:"localIp"`
			Name    string `json:"name"`
		}
		if err := readJSON(r, &req); err != nil || req.LocalIP == "" {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "localIp required"})
			return
		}
		if net.ParseIP(req.LocalIP) == nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid localIp"})
			return
		}
		ztIP := s.cfg.resolveZtIP()
		if ztIP == "" {
			writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "ZeroTier IP not available yet"})
			return
		}
		cam, created := s.state.upsert(req.LocalIP, req.Name, s.cfg.PortPoolStart)
		if err := writeAndReload(s.cfg.NginxStreamCfg, ztIP, s.state.snapshot()); err != nil {
			if created {
				s.state.remove(req.LocalIP) // roll back the allocation if nginx rejected it
			}
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
			return
		}
		if err := s.state.save(); err != nil {
			log.Printf("state save: %v", err)
		}
		log.Printf("camera %s -> onvif:%d rtsp:%d (created=%v)", cam.LocalIP, cam.OnvifPort, cam.RtspPort, created)
		writeJSON(w, http.StatusOK, cam)

	case http.MethodDelete:
		localIP := r.URL.Query().Get("localIp")
		if localIP == "" {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "localIp required"})
			return
		}
		removed := s.state.remove(localIP)
		if removed {
			ztIP := s.cfg.resolveZtIP()
			if err := writeAndReload(s.cfg.NginxStreamCfg, ztIP, s.state.snapshot()); err != nil {
				writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
				return
			}
			_ = s.state.save()
		}
		writeJSON(w, http.StatusOK, map[string]any{"deleted": removed})

	default:
		http.Error(w, `{"error":"method"}`, http.StatusMethodNotAllowed)
	}
}

func (s *server) handleCameras(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"cameras": sortedCameras(s.state.snapshot())})
}

func (s *server) handleIndex(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	b, err := webFS.ReadFile("web/index.html")
	if err != nil {
		http.Error(w, "page unavailable", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = w.Write(b)
}

// --- ZeroTier bootstrap -----------------------------------------------------

func bootstrapZeroTier(cfg Config) {
	if cfg.ZtNetworkID == "" {
		return
	}
	if _, err := exec.LookPath("zerotier-cli"); err != nil {
		log.Printf("zerotier-cli not found, skipping join (%v)", err)
		return
	}
	out, err := exec.Command("zerotier-cli", "join", cfg.ZtNetworkID).CombinedOutput()
	if err != nil {
		log.Printf("zerotier join %s: %v: %s", cfg.ZtNetworkID, err, strings.TrimSpace(string(out)))
		return
	}
	log.Printf("zerotier join %s: %s (authorize the member in ZeroTier Central)", cfg.ZtNetworkID, strings.TrimSpace(string(out)))
}

func waitForZtIP(cfg Config, timeout time.Duration) string {
	deadline := time.Now().Add(timeout)
	for {
		if ip := cfg.resolveZtIP(); ip != "" {
			return ip
		}
		if time.Now().After(deadline) {
			return ""
		}
		time.Sleep(time.Second)
	}
}

// --- helpers ----------------------------------------------------------------

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func readJSON(r *http.Request, v any) error {
	defer r.Body.Close()
	b, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		return err
	}
	if len(b) == 0 {
		return nil
	}
	return json.Unmarshal(b, v)
}

func isLoopback(remoteAddr string) bool {
	host, _, err := net.SplitHostPort(remoteAddr)
	if err != nil {
		host = remoteAddr
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// processRSSMb reads resident memory from /proc (Linux) for the /health report.
func processRSSMb() int {
	b, err := os.ReadFile("/proc/self/statm")
	if err != nil {
		return 0
	}
	fields := strings.Fields(string(b))
	if len(fields) < 2 {
		return 0
	}
	var rssPages int
	for _, c := range fields[1] {
		rssPages = rssPages*10 + int(c-'0')
	}
	return rssPages * os.Getpagesize() / (1024 * 1024)
}

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

	s := &server{cfg: cfg, state: st}

	// Single background ZeroTier joiner. It converges the node onto whatever network is currently
	// selected — the persisted enroll (app) wins over the baked ZT_NETWORK_ID env. One loop covers
	// both the boot race (zerotier-one not ready yet) and a runtime enroll/region switch.
	go s.ztJoinLoop()

	// Wait briefly for a ZeroTier IP so the first nginx rebuild binds the right address.
	ztIP := waitForZtIP(cfg, 15*time.Second)

	// Restore proxies from persisted state (survives reboot, no NVR needed).
	resyncOnBoot(cfg.NginxStreamCfg, ztIP, st.snapshot())

	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.handleHealth)
	mux.HandleFunc("/identity", s.handleIdentity)
	mux.HandleFunc("/label", s.handleLabel)
	mux.HandleFunc("/enroll", s.handleEnroll)
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
// (it has no fleet token): /identity to find+identify a gateway, /label to name it, /enroll to point
// it at a region's ZeroTier network (Tier 0, D4). All camera/proxy mutation stays behind the fleet
// token (NVR-only).
//
// Why /enroll is safe without the fleet token, even though it joins a network: the ZeroTier network
// id is NOT a secret — knowing it only lets a node REQUEST membership; the node stays unauthorized
// until an admin approves it on the controller (NVR "Cấp phép"). So the worst a rogue field-LAN
// caller can do is make the Pi request a network whose admin will never approve it. handleEnroll
// additionally refuses to switch a gateway that already has cameras (see there).
var publicPaths = map[string]bool{"/identity": true, "/label": true, "/enroll": true}

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

// currentNetworkID returns the ZeroTier network the agent should be on: the app-enrolled id
// (persisted in state) wins over the baked ZT_NETWORK_ID env. Empty means "not enrolled yet".
func (s *server) currentNetworkID() string {
	if id := s.state.networkID(); id != "" {
		return id
	}
	return s.cfg.ZtNetworkID
}

func (s *server) handleHealth(w http.ResponseWriter, r *http.Request) {
	netID := s.currentNetworkID()
	zt := ztStatus(netID)
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
		"networkId":   netID,
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
// the field LAN without the fleet token. Read-only; reports who/where + ZeroTier join status so the
// field worker can enroll it into a region (POST /enroll) and name it. `networkId` is the network it
// is currently pointed at (empty = not enrolled yet); `ztJoined` stays false until the admin
// authorizes the member on the controller (NVR "Cấp phép").
func (s *server) handleIdentity(w http.ResponseWriter, r *http.Request) {
	netID := s.currentNetworkID()
	zt := ztStatus(netID)
	name, installer, location := s.state.labels()
	if name == "" {
		name = s.cfg.GatewayName
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"gatewayId":   s.state.GatewayID,
		"name":        name,
		"installer":   installer,
		"location":    location,
		"networkId":   netID,
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

// handleEnroll (no-auth, LAN) points this gateway at a region's ZeroTier network — the field worker
// types the 16-hex network id the admin gave them (cách A: one generic image, region chosen here
// instead of baked per-image). It persists the id (survives reboot), leaves the old network if this
// is a region switch, and kicks an immediate join (the background ztJoinLoop keeps retrying if
// zerotier-one isn't ready yet). The node stays UNAUTHORIZED until the admin approves it on the NVR
// ("Cấp phép"), so ztJoined will usually still be false in this response — that's normal.
func (s *server) handleEnroll(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, `{"error":"method"}`, http.StatusMethodNotAllowed)
		return
	}
	var req struct {
		NetworkID string `json:"networkId"`
	}
	if err := readJSON(r, &req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	// Normalise: ZeroTier ids are lowercase hex; tolerate spaces/case from manual entry.
	id := strings.ToLower(strings.TrimSpace(req.NetworkID))
	if !validNetworkID(id) {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "networkId must be 16 hex characters"})
		return
	}

	current := s.currentNetworkID()

	// Safety valve: refuse to re-point a gateway that already has cameras provisioned. Switching the
	// network changes the ZeroTier IP, which would orphan the nginx proxies bound to the old IP and
	// silently break live view. A real re-deploy should wipe state first (bake-prep / delete state).
	// This also blunts the "no-auth /enroll" risk: a rogue field-LAN caller can't hijack a live gateway.
	if id != current && len(s.state.snapshot()) > 0 {
		writeJSON(w, http.StatusConflict, map[string]any{
			"error": "gateway already has cameras; remove them (or re-image) before changing network",
		})
		return
	}

	if id == current {
		// Idempotent: already pointed here. Don't churn zerotier; just report the live status.
		log.Printf("enroll: already on network %s (no-op)", id)
	} else {
		// Leave the previous network (region switch) so we don't linger as a stale member.
		if current != "" {
			if out, err := ztLeave(current); err != nil {
				log.Printf("enroll: leave old network %s failed (continuing): %v: %s", current, err, out)
			} else {
				log.Printf("enroll: left old network %s: %s", current, out)
			}
		}
		// Persist BEFORE the join so a crash/reboot mid-join still re-joins the right region.
		s.state.setNetworkID(id)
		if err := s.state.save(); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
			return
		}
		// Best-effort immediate join for fast feedback; ztJoinLoop will keep retrying if it fails
		// (e.g. zerotier-one not ready). Not fatal — the loop converges within a few seconds.
		if out, err := ztJoin(id); err != nil {
			log.Printf("enroll: immediate join %s not ready yet (loop will retry): %v: %s", id, err, out)
		} else {
			log.Printf("enroll: joined network %s: %s (waiting for authorization)", id, out)
		}
	}

	// Report fresh status (bypass the ztStatus cache so we don't echo a stale joined flag right
	// after switching). ztAddress is the node's own identity — available even before authorization.
	addr, online := ztInfo()
	writeJSON(w, http.StatusOK, map[string]any{
		"networkId":  id,
		"ztAddress":  addr,
		"ztJoined":   ztJoined(id),
		"ztOnline":   online,
		"zerotierIp": s.cfg.resolveZtIP(),
		"requested":  true,
	})
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

// ztJoinLoop runs once for the agent's whole life and keeps the node joined to the CURRENTLY
// selected network (s.currentNetworkID() — the app-enrolled id wins over the baked env). One loop
// deliberately handles every case so there are no leaked/competing goroutines:
//   - Boot race: on a fresh image the agent (After=zerotier-one) can start before zerotier-one has
//     written /var/lib/zerotier-one/zerotier-one.port, so the first join fails with "missing port".
//     We just retry until it sticks.
//   - Not enrolled yet: a generic image starts with no network id; the loop idles (cheap polls)
//     until the app POSTs /enroll and currentNetworkID() becomes non-empty.
//   - Region switch: /enroll updates the persisted id; the loop converges onto the new network on
//     its next tick (the explicit leave/join in handleEnroll just makes it instant).
// It does NOT leave old networks — that's done explicitly in handleEnroll on a switch.
func (s *server) ztJoinLoop() {
	if _, err := exec.LookPath("zerotier-cli"); err != nil {
		log.Printf("zerotier-cli not found, ZeroTier auto-join disabled (%v)", err)
		return
	}
	loggedNotReady := false // throttle the boot-race log to once until it succeeds
	for {
		id := s.currentNetworkID()
		if id == "" || ztJoined(id) {
			// Nothing to do: either not enrolled yet, or already a member with an OK config.
			loggedNotReady = false
			time.Sleep(3 * time.Second)
			continue
		}
		out, err := ztJoin(id)
		if err == nil {
			log.Printf("zerotier join %s: %s (waiting for authorization)", id, out)
			loggedNotReady = false
		} else if !loggedNotReady {
			log.Printf("zerotier join %s not ready yet, will keep retrying: %v: %s", id, err, out)
			loggedNotReady = true
		}
		time.Sleep(3 * time.Second)
	}
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

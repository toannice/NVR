// nvr-edge-agent (Nebula) — passive L4 provisioning agent for a field gateway (Raspberry Pi).
//
// This is the Nebula fork of the ZeroTier agent (gateway/edge-agent/). The L4 proxy, discovery, state
// and HTTP surface are unchanged; only the overlay layer differs (nebula.go instead of zerotier.go):
//   - /enroll now points the gateway at a region via (signing service URL + one-time token) instead of
//     a 16-hex ZeroTier network id. The agent generates a keypair (private key stays here), submits the
//     public key to the HQ signing service, and polls until the admin approves ("Cấp phép") and a cert
//     comes back; it then writes /etc/nebula/config.yml and starts the nebula service.
//   - Health/identity report nebula status (cert present, service up, overlay ip, cert expiry).
package main

import (
	"bytes"
	"embed"
	"encoding/json"
	"io"
	"log"
	"net"
	"net/http"
	"os"
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

var httpClient = &http.Client{Timeout: 10 * time.Second}

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC)
	cfg := loadConfig()

	st, err := loadState(cfg.StateFile)
	if err != nil {
		log.Fatalf("load state: %v", err)
	}

	s := &server{cfg: cfg, state: st}

	// Single background enrollment loop. It drives the gateway from "pointed at a region" → "cert signed
	// + nebula running", and resumes after a reboot. Idle (cheap polls) until the app enrolls it.
	go s.nebulaEnrollLoop()

	// Best-effort wait for the overlay IP so the first nginx rebuild binds the right address.
	overlayIP := waitForOverlayIP(cfg, 15*time.Second)

	resyncOnBoot(cfg.NginxStreamCfg, overlayIP, st.snapshot())

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
	log.Printf("nvr-edge-agent (nebula) %q starting on %v (gatewayId=%s)", cfg.GatewayName, addrs, st.GatewayID)
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

// publicPaths are reachable without the fleet token from any caller (LAN). Same Tier-0 set as the
// ZeroTier agent. /enroll is safe without the fleet token because the enrollment token is single-use
// and the gateway stays unauthorized (no signed cert) until an admin approves it on the NVR.
var publicPaths = map[string]bool{"/identity": true, "/label": true, "/enroll": true}

func (s *server) auth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if isLoopback(r.RemoteAddr) || publicPaths[r.URL.Path] {
			next.ServeHTTP(w, r)
			return
		}
		// G2:C — when the Nebula firewall enforces the NVR group, any request that arrived over the
		// overlay interface is already authenticated as that group, so trust it without the fleet token.
		if s.cfg.NebulaNvrGroup != "" && s.isOverlayRequest(r) {
			next.ServeHTTP(w, r)
			return
		}
		// Legacy / field-LAN path: require the shared fleet token.
		tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if s.cfg.Token == "" || tok != s.cfg.Token {
			http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// isOverlayRequest reports whether the request was accepted on the agent's overlay IP (vs the field
// LAN). Used by G2:C to trust cert/group-authenticated overlay callers without a bearer token.
func (s *server) isOverlayRequest(r *http.Request) bool {
	la, ok := r.Context().Value(http.LocalAddrContextKey).(net.Addr)
	if !ok || la == nil {
		return false
	}
	host, _, err := net.SplitHostPort(la.String())
	if err != nil {
		host = la.String()
	}
	overlay := s.cfg.resolveOverlayIP()
	return overlay != "" && host == overlay
}

func (s *server) handleHealth(w http.ResponseWriter, r *http.Request) {
	st := s.cfg.status()
	e := s.state.enrollSnapshot()
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
		"overlay":     "nebula",
		"enrolled":    e.Token != "",
		"signed":      e.Signed && st.hasCert,
		"overlayIp":   st.overlayIP,
		"nebulaUp":    st.serviceUp,
		"certExpiry":  st.certExpiry,
		"lighthouse":  e.Lighthouse,
		"fieldSubnet": s.cfg.FieldSubnet,
		"agentPort":   s.cfg.Port,
		"ramMb":       processRSSMb(),
		"uptimeSec":   int(time.Since(startTime).Seconds()),
		"nginxOk":     nginxOk(),
		"cameras":     sortedCameras(s.state.snapshot()),
	})
}

func (s *server) handleIdentity(w http.ResponseWriter, r *http.Request) {
	st := s.cfg.status()
	e := s.state.enrollSnapshot()
	name, installer, location := s.state.labels()
	if name == "" {
		name = s.cfg.GatewayName
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"gatewayId":   s.state.GatewayID,
		"name":        name,
		"installer":   installer,
		"location":    location,
		"overlay":     "nebula",
		"enrolled":    e.Token != "",
		"signed":      e.Signed && st.hasCert,
		"enrollOpen":  s.cfg.enrollWindowOpen(time.Since(startTime)),
		"overlayIp":   st.overlayIP,
		"nebulaUp":    st.serviceUp,
		"lighthouse":  e.Lighthouse,
		"fieldSubnet": s.cfg.FieldSubnet,
		"agentPort":   s.cfg.Port,
		"alive":       true,
		"cameraCount": len(s.state.snapshot()),
	})
}

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

// handleEnroll (no-auth, LAN) points this gateway at a region. The field worker enters the "mã khu vực"
// = signing service URL + one-time token (the Nebula analog of the 16-hex network id). The agent
// generates a keypair, submits its PUBLIC key to the signing service, persists the enrollment, and the
// background loop polls until the admin approves on the NVR and a cert comes back. Stays "pending" in
// this response — that's normal (admin must Cấp phép).
func (s *server) handleEnroll(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, `{"error":"method"}`, http.StatusMethodNotAllowed)
		return
	}
	var req struct {
		SigningURL string `json:"signingUrl"`
		Token      string `json:"token"`
		Name       string `json:"name"`
	}
	if err := readJSON(r, &req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	signingURL := strings.TrimRight(strings.TrimSpace(req.SigningURL), "/")
	token := strings.TrimSpace(req.Token)
	if signingURL == "" || token == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "signingUrl and token required"})
		return
	}

	cur := s.state.enrollSnapshot()
	// G3: only accept a NEW region within the post-boot window — stops someone on the field LAN from
	// claiming a freshly-plugged, not-yet-adopted gateway. Re-submitting the SAME token (app retry) is
	// always allowed; pointing at a different region after the window requires a reboot to reopen it.
	sameToken := token == cur.Token && cur.Token != ""
	if !sameToken && !s.cfg.enrollWindowOpen(time.Since(startTime)) {
		writeJSON(w, http.StatusForbidden, map[string]any{
			"error": "enrollment window closed; reboot the gateway to enroll it",
		})
		return
	}
	// Safety valve (same as ZeroTier agent): refuse to re-point a gateway that already has cameras — the
	// overlay IP would change and orphan the nginx proxies. Re-image / remove cameras first.
	if token != cur.Token && len(s.state.snapshot()) > 0 {
		writeJSON(w, http.StatusConflict, map[string]any{
			"error": "gateway already has cameras; remove them (or re-image) before changing region",
		})
		return
	}

	if req.Name != "" {
		s.state.setLabel(req.Name, "", "")
	}

	// Generate (or reuse) the keypair; the private key never leaves the Pi.
	pub, err := s.cfg.ensureKeypair()
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "keygen: " + err.Error()})
		return
	}

	if token != cur.Token {
		s.state.setEnroll(signingURL, token)
	}

	// Immediate submit for fast feedback; the loop keeps retrying if HQ is briefly unreachable.
	name, _, _ := s.state.labels()
	body, code, err := s.signingEnroll(signingURL, token, name, pub)
	if err != nil || code >= 400 {
		// Persist anyway so the loop retries; report what we know.
		_ = s.state.save()
		msg := "submitted; will retry"
		if err != nil {
			msg = err.Error()
		} else if e, ok := body["error"].(string); ok {
			msg = e
		}
		writeJSON(w, http.StatusOK, map[string]any{"status": "pending", "note": msg, "requested": true})
		return
	}
	s.applyEnrollBody(body)
	_ = s.state.save()

	log.Printf("enroll: region submitted (signingUrl=%s token=%s..) status=%v", signingURL, safePrefix(token), body["status"])
	writeJSON(w, http.StatusOK, map[string]any{
		"status":    str(body["status"]),
		"overlayIp": str(body["overlayIp"]),
		"requested": true,
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
		// G1: only proxy to hosts on the field LAN — never let a fleet-token holder turn the gateway
		// into an arbitrary port-forward into the customer's network (router/NAS/etc).
		if !s.cfg.localIPInField(req.LocalIP) {
			writeJSON(w, http.StatusForbidden, map[string]any{"error": "localIp not in field subnet " + s.cfg.FieldSubnet})
			return
		}
		overlayIP := s.cfg.resolveOverlayIP()
		if overlayIP == "" {
			writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "overlay IP not available yet"})
			return
		}
		cam, created := s.state.upsert(req.LocalIP, req.Name, s.cfg.PortPoolStart)
		if err := writeAndReload(s.cfg.NginxStreamCfg, overlayIP, s.state.snapshot()); err != nil {
			if created {
				s.state.remove(req.LocalIP)
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
			overlayIP := s.cfg.resolveOverlayIP()
			if err := writeAndReload(s.cfg.NginxStreamCfg, overlayIP, s.state.snapshot()); err != nil {
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

// --- Nebula enrollment loop -------------------------------------------------

// nebulaEnrollLoop drives the gateway from "enrolled (pointed at a region)" to "cert signed + nebula
// running", and resumes after a reboot. One loop for the agent's whole life:
//   - Not enrolled: idle until /enroll sets a token.
//   - Pending: poll the signing service /enroll/status until the admin approves.
//   - Signed: write cert + config.yml, (re)start nebula, mark signed; then idle.
//
// All HQ calls are idempotent so retries/reboots converge safely.
func (s *server) nebulaEnrollLoop() {
	loggedWait := false
	for {
		e := s.state.enrollSnapshot()
		if e.Token == "" || e.SigningURL == "" {
			loggedWait = false
			time.Sleep(3 * time.Second)
			continue
		}
		if e.Signed && serviceActive("nebula") {
			loggedWait = false
			time.Sleep(5 * time.Second)
			continue
		}

		body, code, err := s.signingStatus(e.SigningURL, e.Token)
		if code == http.StatusNotFound {
			// HQ has no request for us (lost state / first boot after reboot before submit) — (re)submit.
			if pub, perr := s.cfg.ensureKeypair(); perr == nil {
				name, _, _ := s.state.labels()
				body, code, err = s.signingEnroll(e.SigningURL, e.Token, name, pub)
			}
		}
		if err != nil || code >= 400 {
			if !loggedWait {
				log.Printf("enroll loop: HQ not ready yet, will retry (code=%d err=%v)", code, err)
				loggedWait = true
			}
			time.Sleep(3 * time.Second)
			continue
		}
		loggedWait = false
		s.applyEnrollBody(body)

		if str(body["status"]) == "signed" {
			if err := s.applySignedCert(body); err != nil {
				log.Printf("enroll loop: apply signed cert failed: %v", err)
			}
		}
		_ = s.state.save()
		time.Sleep(3 * time.Second)
	}
}

// applyEnrollBody records the overlay IP + lighthouse the signing service assigned (still pending).
func (s *server) applyEnrollBody(body map[string]any) {
	s.state.applyAssignment(str(body["overlayIp"]), str(body["lighthouse"]), str(body["lighthouseOverlayIp"]))
}

// applySignedCert writes the cert/CA/config and starts nebula once the admin approved.
func (s *server) applySignedCert(body map[string]any) error {
	e := s.state.enrollSnapshot()
	cert, ca := str(body["certPem"]), str(body["caPem"])
	if err := s.cfg.writeCertAndConfig(cert, ca, e.OverlayIP, e.Lighthouse, e.LighthouseOverlayIP); err != nil {
		return err
	}
	if out, err := restartNebula(); err != nil {
		return fmtErr("systemctl restart nebula", err, out)
	}
	s.state.markSigned(true)
	log.Printf("enroll loop: APPROVED — cert written, nebula started (overlayIp=%s)", e.OverlayIP)
	return nil
}

// --- signing service client -------------------------------------------------

func (s *server) signingEnroll(base, token, name, pubPem string) (map[string]any, int, error) {
	payload, _ := json.Marshal(map[string]string{"token": token, "name": name, "pubPem": pubPem})
	return s.signingCall(http.MethodPost, base+"/enroll", payload)
}

func (s *server) signingStatus(base, token string) (map[string]any, int, error) {
	return s.signingCall(http.MethodGet, base+"/enroll/status?token="+token, nil)
}

func (s *server) signingCall(method, url string, payload []byte) (map[string]any, int, error) {
	var bodyReader io.Reader
	if payload != nil {
		bodyReader = bytes.NewReader(payload)
	}
	req, err := http.NewRequest(method, url, bodyReader)
	if err != nil {
		return nil, 0, err
	}
	if payload != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := httpClient.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	var m map[string]any
	_ = json.Unmarshal(b, &m)
	return m, resp.StatusCode, nil
}

func waitForOverlayIP(cfg Config, timeout time.Duration) string {
	deadline := time.Now().Add(timeout)
	for {
		if ip := cfg.resolveOverlayIP(); ip != "" {
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

func str(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	return ""
}

func safePrefix(tok string) string {
	if len(tok) > 6 {
		return tok[:6]
	}
	return tok
}

func fmtErr(ctx string, err error, out string) error {
	return &agentError{ctx: ctx, err: err, out: strings.TrimSpace(out)}
}

type agentError struct {
	ctx string
	err error
	out string
}

func (e *agentError) Error() string { return e.ctx + ": " + e.err.Error() + ": " + e.out }

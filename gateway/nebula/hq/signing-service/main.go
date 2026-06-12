// nvr-nebula-signing — HQ control plane for the Nebula overlay (replaces the ZeroTier ztncui
// controller). It is the only NEW moving part of the ZeroTier→Nebula migration; everything else
// (data path, scan, adopt) is reused unchanged. See gateway/nebula-migration-va-trien-khai.md §4.1.
//
// Two classes of caller:
//   - ADMIN (the NVR), bearer NEBULA_SIGN_TOKEN: mint enrollment tokens, list pending gateways,
//     approve ("Cấp phép") → sign a cert, or reject.
//   - GATEWAY (the field Pi's edge-agent), no admin secret, gated by the one-time enrollment token it
//     was given: submit its public key (/enroll), then poll (/enroll/status) until the admin approves
//     and it can pick up its signed cert + CA. The private key never leaves the Pi.
package main

import (
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"time"
)

type server struct {
	cfg   Config
	store *Store
}

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC)
	cfg := loadConfig()
	st, err := loadStore(cfg.StateFile, cfg.ReservedIPs)
	if err != nil {
		log.Fatalf("load state: %v", err)
	}
	s := &server{cfg: cfg, store: st}

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", s.handleHealthz)              // no auth — for watchdog/NVR poll
	mux.HandleFunc("/enroll", s.handleEnroll)                // gateway, token-gated
	mux.HandleFunc("/enroll/status", s.handleEnrollStatus)   // gateway, token-gated
	mux.HandleFunc("/renew", s.handleRenew)                  // gateway, token+pubkey-gated: auto re-sign (R1)
	mux.HandleFunc("/tokens", s.adminOnly(s.handleTokens))   // admin: GET list / POST mint
	mux.HandleFunc("/pending", s.adminOnly(s.handlePending)) // admin: list awaiting "Cấp phép"
	mux.HandleFunc("/approve", s.adminOnly(s.handleApprove)) // admin: sign cert
	mux.HandleFunc("/reject", s.adminOnly(s.handleReject))   // admin: drop a pending request
	mux.HandleFunc("/revoke", s.adminOnly(s.handleRevoke))   // admin: revoke a gateway (V3)

	srv := &http.Server{Addr: cfg.Listen, Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	log.Printf("nvr-nebula-signing on %s (caDir=%s lighthouse=%s)", cfg.Listen, cfg.CADir, cfg.Lighthouse)
	log.Fatal(srv.ListenAndServe())
}

// adminOnly wraps an NVR-facing handler with the bearer-token check.
func (s *server) adminOnly(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if s.cfg.AdminToken == "" || tok != s.cfg.AdminToken {
			writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
			return
		}
		next(w, r)
	}
}

func (s *server) handleHealthz(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":         true,
		"lighthouse": s.cfg.Lighthouse,
		"pending":    len(s.store.listPending()),
		"uptimeSec":  int(time.Since(startTime).Seconds()),
	})
}

// handleTokens: GET lists tokens (admin), POST mints one (admin). Body for POST:
// {region, caName?, ipCidr, groups?, ttlMinutes?}.
func (s *server) handleTokens(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, http.StatusOK, map[string]any{"tokens": s.store.listTokens()})
	case http.MethodPost:
		var req struct {
			Region     string `json:"region"`
			CaName     string `json:"caName"`
			IPCidr     string `json:"ipCidr"`
			Groups     string `json:"groups"`
			TTLMinutes int    `json:"ttlMinutes"`
		}
		if err := readJSON(r, &req); err != nil || req.IPCidr == "" {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "ipCidr required"})
			return
		}
		if req.Groups == "" {
			req.Groups = "gateways"
		}
		t, err := s.store.mintToken(req.Region, req.CaName, req.IPCidr, req.Groups, time.Duration(req.TTLMinutes)*time.Minute)
		if err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": err.Error()})
			return
		}
		log.Printf("token minted region=%q cidr=%s groups=%s", t.Region, t.IPCidr, t.Groups)
		// The "mã khu vực" the admin hands to the field worker = token + lighthouse address.
		writeJSON(w, http.StatusOK, map[string]any{
			"token":               t.Token,
			"region":              t.Region,
			"lighthouse":          s.cfg.Lighthouse,
			"lighthouseOverlayIp": s.cfg.LighthouseOverlayIP,
		})
	default:
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method"})
	}
}

// handleEnroll (gateway): POST {token, name, pubPem}. Records the request + allocates an overlay IP,
// returns "pending" — the agent then polls /enroll/status. Token-gated (the enrollment token itself
// is the credential; no admin secret needed, mirroring ZeroTier's no-auth /enroll but single-use).
func (s *server) handleEnroll(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method"})
		return
	}
	var req struct {
		Token  string `json:"token"`
		Name   string `json:"name"`
		PubPem string `json:"pubPem"`
	}
	if err := readJSON(r, &req); err != nil || req.Token == "" || req.PubPem == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "token and pubPem required"})
		return
	}
	rq, _, err := s.store.enroll(strings.TrimSpace(req.Token), req.Name, req.PubPem)
	if err != nil {
		writeJSON(w, statusForErr(err), map[string]any{"error": err.Error()})
		return
	}
	log.Printf("enroll request token=%s.. name=%q -> overlayIp=%s status=%s",
		safePrefix(rq.Token), rq.Name, rq.OverlayIP, rq.Status)
	writeJSON(w, http.StatusOK, s.statusBody(rq))
}

// handleEnrollStatus (gateway): GET ?token=... — the agent polls this until status=="signed", then
// writes the returned cert/ca + lighthouse into its config.yml and starts nebula.
func (s *server) handleEnrollStatus(w http.ResponseWriter, r *http.Request) {
	tok := strings.TrimSpace(r.URL.Query().Get("token"))
	rq, _ := s.store.getRequest(tok)
	if rq == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "no request for token"})
		return
	}
	writeJSON(w, http.StatusOK, s.statusBody(rq))
}

func (s *server) handlePending(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"pending": s.store.listPending()})
}

// handleApprove (admin "Cấp phép"): POST {token}. Signs the gateway's pubkey with the token's CA and
// the allocated overlay IP, then flips the request to "signed" for the polling agent to pick up.
func (s *server) handleApprove(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method"})
		return
	}
	var req struct {
		Token string `json:"token"`
	}
	if err := readJSON(r, &req); err != nil || req.Token == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "token required"})
		return
	}
	rq, t := s.store.getRequest(strings.TrimSpace(req.Token))
	if rq == nil || t == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "no request for token"})
		return
	}
	if rq.Revoked {
		writeJSON(w, http.StatusConflict, map[string]any{"error": "gateway revoked"})
		return
	}
	if rq.Status == "signed" {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "overlayIp": rq.OverlayIP, "note": "already signed"})
		return
	}
	certPem, caPem, fp, err := s.signCert(t.CaName, rq.Name, rq.OverlayIP, maskOf(t.IPCidr), t.Groups, rq.PubPem)
	if err != nil {
		log.Printf("approve sign failed token=%s..: %v", safePrefix(rq.Token), err)
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	if err := s.store.markSigned(rq.Token, certPem, caPem, fp); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	log.Printf("approved + signed token=%s.. name=%q ip=%s", safePrefix(rq.Token), rq.Name, rq.OverlayIP)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "overlayIp": rq.OverlayIP, "name": rq.Name})
}

// handleRenew (gateway, token-gated — no admin): POST {token, pubPem}. Auto re-signs an ALREADY-APPROVED
// gateway's cert so the fleet never lapses (R1). Safe without admin re-approval because we only sign the
// SAME pubkey that was originally approved — a cert minted for that key is useless to anyone without the
// matching private key (which never left the Pi). Refuses revoked gateways.
func (s *server) handleRenew(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method"})
		return
	}
	var req struct {
		Token  string `json:"token"`
		PubPem string `json:"pubPem"`
	}
	if err := readJSON(r, &req); err != nil || req.Token == "" || req.PubPem == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "token and pubPem required"})
		return
	}
	rq, t := s.store.getRequest(strings.TrimSpace(req.Token))
	if rq == nil || t == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "no request for token"})
		return
	}
	if rq.Revoked {
		writeJSON(w, http.StatusConflict, map[string]any{"error": "gateway revoked"})
		return
	}
	if rq.Status != "signed" {
		writeJSON(w, http.StatusConflict, map[string]any{"error": "not yet approved — cannot renew"})
		return
	}
	// Bind renewal to the originally-approved key: only re-sign the same pubkey.
	if normPem(req.PubPem) != normPem(rq.PubPem) {
		writeJSON(w, http.StatusForbidden, map[string]any{"error": "pubkey does not match the approved gateway"})
		return
	}
	certPem, caPem, fp, err := s.signCert(t.CaName, rq.Name, rq.OverlayIP, maskOf(t.IPCidr), t.Groups, rq.PubPem)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	if err := s.store.markSigned(rq.Token, certPem, caPem, fp); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	log.Printf("renewed token=%s.. name=%q ip=%s", safePrefix(rq.Token), rq.Name, rq.OverlayIP)
	writeJSON(w, http.StatusOK, s.statusBody(rq))
}

// handleRevoke (admin): POST {token}. Marks the gateway revoked (blocks renew + re-enroll → its
// short-lived cert lapses, V1) and appends its cert fingerprint to the blocklist file for the operator
// to wire into the lighthouse pki.blocklist + reload (V2 fast-cut).
func (s *server) handleRevoke(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method"})
		return
	}
	var req struct {
		Token string `json:"token"`
	}
	if err := readJSON(r, &req); err != nil || req.Token == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "token required"})
		return
	}
	fp, ok := s.store.markRevoked(strings.TrimSpace(req.Token))
	if !ok {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "no request for token"})
		return
	}
	blocklisted := false
	if fp != "" {
		blocklisted = s.appendBlocklist(fp)
	}
	log.Printf("revoked token=%s.. fingerprint=%q blocklisted=%v", safePrefix(strings.TrimSpace(req.Token)), fp, blocklisted)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "fingerprint": fp, "blocklisted": blocklisted})
}

func (s *server) handleReject(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method"})
		return
	}
	var req struct {
		Token string `json:"token"`
	}
	if err := readJSON(r, &req); err != nil || req.Token == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "token required"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"deleted": s.store.reject(strings.TrimSpace(req.Token))})
}

// statusBody is the shape both /enroll and /enroll/status return. When signed it carries everything
// the agent needs to write config.yml (cert, ca, lighthouse public addr + overlay ip, its own ip).
func (s *server) statusBody(rq *Request) map[string]any {
	body := map[string]any{
		"status":              rq.Status,
		"overlayIp":           rq.OverlayIP,
		"lighthouse":          s.cfg.Lighthouse,
		"lighthouseOverlayIp": s.cfg.LighthouseOverlayIP,
	}
	if rq.Status == "signed" {
		body["certPem"] = rq.CertPem
		body["caPem"] = rq.CaPem
	}
	return body
}

func statusForErr(err error) int {
	switch err {
	case errBadToken:
		return http.StatusUnauthorized
	case errExpiredToken, errUsedToken, errRevoked:
		return http.StatusConflict
	default:
		return http.StatusBadRequest
	}
}

// normPem normalises a PEM blob for comparison (trim + drop all whitespace) so cosmetic differences
// (trailing newline, CRLF) don't break the renewal pubkey match.
func normPem(s string) string {
	return strings.Join(strings.Fields(s), "")
}

// appendBlocklist appends a revoked cert fingerprint to the configured blocklist file (one per line,
// deduped best-effort). Returns true if written. The operator wires this file into the lighthouse
// pki.blocklist and reloads nebula for the fast-cut (V2).
func (s *server) appendBlocklist(fp string) bool {
	if s.cfg.BlocklistFile == "" {
		return false
	}
	if existing, err := os.ReadFile(s.cfg.BlocklistFile); err == nil && strings.Contains(string(existing), fp) {
		return true // already present
	}
	f, err := os.OpenFile(s.cfg.BlocklistFile, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		log.Printf("blocklist append failed (%s): %v", s.cfg.BlocklistFile, err)
		return false
	}
	defer f.Close()
	if _, err := f.WriteString(fp + "\n"); err != nil {
		log.Printf("blocklist write failed: %v", err)
		return false
	}
	return true
}

func safePrefix(tok string) string {
	if len(tok) > 6 {
		return tok[:6]
	}
	return tok
}

// --- helpers ----------------------------------------------------------------

var startTime = time.Now()

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

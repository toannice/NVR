// dc-bridge: headless WebRTC sidecar for the NVR (Linux, Pion).
//
// Two independent peers:
//   DATA peer     — cloud fetches device info (get_information, get_ethernet, ...)
//                   over a DataChannel. The JVM (dev.onvoid.webrtc) requires
//                   PulseAudio + X11 which are absent headless — Pion instead.
//   PLAYBACK peer — cloud playback video (H264 track fed by ffmpeg) + the control
//                   DataChannel (post_select_record / post_control_record) that
//                   rides ON this peer. go2rtc would swallow that channel, so the
//                   device must own the playback peer itself.
//
// HTTP API (port 8766):
//   POST /dc/offer         {"sdp":..., "ice_servers":[...]}      → DATA peer answer
//   POST /dc/ice           trickle ICE candidate for the DATA peer
//   POST /playback/offer   {"sdp":..., "src_url":..., "ice_servers":[...]} → answer
//   POST /playback/source  {"src_url":..., "rate":1|2|4|8}  swap ffmpeg src (seek/bar/speed)
//   POST /playback/pause   stop ffmpeg only, keep peer+track (frame freezes; resume = /playback/source)
//   POST /playback/stop    tear down playback peer + ffmpeg
//   GET  /health           → 200 ok
//
// DataChannel messages are forwarded to the JVM via HTTP POST to
//   http://127.0.0.1:3000/internal/dc/command
// and the response is sent back over the DataChannel.
//
// Build (from repo root): ./dc-bridge/build.sh   → data/dc-bridge (linux/amd64)
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/rtp"
	"github.com/pion/webrtc/v3"
)

const (
	listenAddr    = ":8766"
	jvmCommandURL = "http://127.0.0.1:3000/internal/dc/command"
)

type offerRequest struct {
	SDP        string            `json:"sdp"`
	IceServers []iceServerConfig `json:"ice_servers"`
}

type iceServerConfig struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username,omitempty"`
	Credential string   `json:"credential,omitempty"`
}

type offerResponse struct {
	SDP string `json:"sdp"`
}

type iceRequest struct {
	Candidate     string `json:"candidate"`
	SDPMid        string `json:"sdpMid"`
	SDPMLineIndex uint16 `json:"sdpMLineIndex"`
}

var (
	mu             sync.Mutex
	peerConnection *webrtc.PeerConnection
)

// ── Playback peer (separate from the DATA peer above) ────────────────────────
//
// Bar switch / seek / speed = swap the ffmpeg source on the SAME peer/track (no
// renegotiation). RTP sequence numbers are rewritten monotonically and
// timestamps rebased across ffmpeg restarts so the receiver doesn't treat the
// new run as packet loss / stale.
//
// Pause = kill ffmpeg but keep the peer + UDP + track: the browser holds the
// last decoded frame. Resume = /playback/source at the paused position (the JVM
// tracks positions; dc-bridge is stateless about media time).
var (
	playbackMu     sync.Mutex
	playbackPC     *webrtc.PeerConnection
	playbackFF     *exec.Cmd
	playbackUDP    *net.UDPConn
	playbackNewRun atomic.Bool // set on source-swap; read loop rebases timing
)

type playbackOfferRequest struct {
	SDP        string            `json:"sdp"`
	SrcURL     string            `json:"src_url"`
	IceServers []iceServerConfig `json:"ice_servers"`
}

type playbackSourceRequest struct {
	SrcURL string  `json:"src_url"`
	Rate   float64 `json:"rate"`
}

// buildICEServers builds the Pion ICE server list (Google STUN fallback + cloud).
func buildICEServers(in []iceServerConfig) []webrtc.ICEServer {
	iceServers := []webrtc.ICEServer{
		{URLs: []string{"stun:stun.l.google.com:19302"}},
		{URLs: []string{"stun:stun1.l.google.com:19302"}},
	}
	for _, s := range in {
		if len(s.URLs) == 0 {
			continue
		}
		entry := webrtc.ICEServer{URLs: s.URLs}
		if s.Username != "" {
			entry.Username = s.Username
			entry.Credential = s.Credential
			entry.CredentialType = webrtc.ICECredentialTypePassword
		}
		iceServers = append(iceServers, entry)
	}
	return iceServers
}

func handlePlaybackOffer(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	var req playbackOfferRequest
	if err := json.Unmarshal(body, &req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if req.SrcURL == "" {
		http.Error(w, "missing src_url", http.StatusBadRequest)
		return
	}

	// Tear down any previous playback session before starting a new one.
	stopPlayback()

	config := webrtc.Configuration{ICEServers: buildICEServers(req.IceServers)}
	pc, err := webrtc.NewPeerConnection(config)
	if err != nil {
		log.Printf("[dc-bridge] playback NewPeerConnection error: %v", err)
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	track, err := webrtc.NewTrackLocalStaticRTP(
		webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeH264},
		"video", "playback")
	if err != nil {
		log.Printf("[dc-bridge] playback NewTrack error: %v", err)
		pc.Close()
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if _, err := pc.AddTrack(track); err != nil {
		log.Printf("[dc-bridge] playback AddTrack error: %v", err)
		pc.Close()
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	// Control commands (post_select_record / post_control_record) arrive here.
	pc.OnDataChannel(func(dc *webrtc.DataChannel) {
		log.Printf("[dc-bridge] playback DataChannel opened: label=%s", dc.Label())
		dc.OnMessage(func(msg webrtc.DataChannelMessage) {
			log.Printf("[dc-bridge] playback DataChannel received %d bytes → JVM", len(msg.Data))
			sendToJVM(string(msg.Data), dc)
		})
	})

	pc.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		log.Printf("[dc-bridge] playback PeerConnection state: %s", state)
		if state == webrtc.PeerConnectionStateFailed || state == webrtc.PeerConnectionStateClosed {
			playbackMu.Lock()
			if playbackPC == pc {
				playbackMu.Unlock()
				stopPlayback()
				return
			}
			playbackMu.Unlock()
		}
	})

	if err := pc.SetRemoteDescription(webrtc.SessionDescription{
		Type: webrtc.SDPTypeOffer, SDP: req.SDP,
	}); err != nil {
		log.Printf("[dc-bridge] playback SetRemoteDescription error: %v", err)
		pc.Close()
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		log.Printf("[dc-bridge] playback CreateAnswer error: %v", err)
		pc.Close()
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	gatherComplete := webrtc.GatheringCompletePromise(pc)
	if err := pc.SetLocalDescription(answer); err != nil {
		log.Printf("[dc-bridge] playback SetLocalDescription error: %v", err)
		pc.Close()
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	select {
	case <-gatherComplete:
	case <-time.After(10 * time.Second):
		log.Printf("[dc-bridge] playback ICE gathering timeout — partial candidates")
	}

	// Bind UDP for ffmpeg's RTP output, then start the read loop + ffmpeg.
	udp, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0})
	if err != nil {
		log.Printf("[dc-bridge] playback UDP bind error: %v", err)
		pc.Close()
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	port := udp.LocalAddr().(*net.UDPAddr).Port

	playbackNewRun.Store(true)
	go rtpReadLoop(udp, track)
	ff := startPlaybackFfmpeg(req.SrcURL, port, 1)

	playbackMu.Lock()
	playbackPC = pc
	playbackFF = ff
	playbackUDP = udp
	playbackMu.Unlock()

	finalAnswer := pc.LocalDescription()
	respData, _ := json.Marshal(offerResponse{SDP: finalAnswer.SDP})
	w.Header().Set("Content-Type", "application/json")
	w.Write(respData)
	log.Printf("[dc-bridge] playback answer returned (src=%s, rtp port=%d)", req.SrcURL, port)
}

// handlePlaybackSource swaps the ffmpeg input on the SAME peer/track — used for
// bar switch, seek, speed change and pause-resume. Keeps the UDP socket and
// WebRTC track; only ffmpeg restarts.
func handlePlaybackSource(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	var req playbackSourceRequest
	if err := json.Unmarshal(body, &req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	playbackMu.Lock()
	defer playbackMu.Unlock()
	if playbackPC == nil || playbackUDP == nil {
		http.Error(w, "no active playback session", http.StatusConflict)
		return
	}
	port := playbackUDP.LocalAddr().(*net.UDPAddr).Port
	if playbackFF != nil && playbackFF.Process != nil {
		_ = playbackFF.Process.Kill()
		_ = playbackFF.Wait()
	}
	playbackNewRun.Store(true) // rebase RTP timing for the new run
	playbackFF = startPlaybackFfmpeg(req.SrcURL, port, req.Rate)
	w.WriteHeader(http.StatusOK)
	w.Write([]byte("ok"))
	log.Printf("[dc-bridge] playback source swapped → %s (rate=%g, rtp port=%d)", req.SrcURL, req.Rate, port)
}

// handlePlaybackPause stops ffmpeg but keeps the peer + UDP + track alive: the
// receiver simply stops getting RTP and the browser freezes on the last frame.
// Resume is a normal /playback/source at the paused position.
func handlePlaybackPause(w http.ResponseWriter, r *http.Request) {
	playbackMu.Lock()
	defer playbackMu.Unlock()
	if playbackPC == nil || playbackUDP == nil {
		http.Error(w, "no active playback session", http.StatusConflict)
		return
	}
	if playbackFF != nil && playbackFF.Process != nil {
		_ = playbackFF.Process.Kill()
		_ = playbackFF.Wait()
		playbackFF = nil
	}
	w.WriteHeader(http.StatusOK)
	w.Write([]byte("ok"))
	log.Printf("[dc-bridge] playback paused (ffmpeg stopped, peer kept)")
}

func handlePlaybackStop(w http.ResponseWriter, r *http.Request) {
	stopPlayback()
	w.WriteHeader(http.StatusOK)
	w.Write([]byte("ok"))
}

func stopPlayback() {
	playbackMu.Lock()
	defer playbackMu.Unlock()
	if playbackFF != nil && playbackFF.Process != nil {
		_ = playbackFF.Process.Kill()
		_ = playbackFF.Wait()
		playbackFF = nil
	}
	if playbackUDP != nil {
		playbackUDP.Close() // ends rtpReadLoop
		playbackUDP = nil
	}
	if playbackPC != nil {
		playbackPC.Close()
		playbackPC = nil
	}
}

// rtpReadLoop reads ffmpeg's RTP packets from udp and writes them to the WebRTC
// track. It rewrites sequence numbers monotonically and rebases timestamps so a
// source swap (new ffmpeg run) is seamless to the receiver. Returns when udp closes.
func rtpReadLoop(udp *net.UDPConn, track *webrtc.TrackLocalStaticRTP) {
	buf := make([]byte, 1600)
	var outSeq uint16
	var lastOutTs uint32
	var inBaseTs, outBaseTs uint32
	const tsGap uint32 = 3000 // ~1 frame @30fps in 90kHz; gap inserted on swap
	for {
		n, _, err := udp.ReadFromUDP(buf)
		if err != nil {
			return // socket closed → session ended
		}
		pkt := &rtp.Packet{}
		if err := pkt.Unmarshal(buf[:n]); err != nil {
			continue
		}
		if playbackNewRun.CompareAndSwap(true, false) {
			inBaseTs = pkt.Timestamp
			if outSeq == 0 && lastOutTs == 0 {
				outBaseTs = 0
			} else {
				outBaseTs = lastOutTs + tsGap
			}
		}
		outTs := outBaseTs + (pkt.Timestamp - inBaseTs)
		pkt.Timestamp = outTs
		pkt.SequenceNumber = outSeq
		outSeq++
		lastOutTs = outTs
		if err := track.WriteRTP(pkt); err != nil {
			log.Printf("[dc-bridge] playback WriteRTP error: %v", err)
			return
		}
	}
}

// vaapiOK is probed once at startup: true only when Intel VAAPI H264 encode actually
// works here (device present AND libva driver installed). Picks hw vs software encode.
var vaapiOK = probeVaapi()

func probeVaapi() bool {
	if _, err := os.Stat("/dev/dri/renderD128"); err != nil {
		return false
	}
	cmd := exec.Command("ffmpeg", "-hide_banner", "-loglevel", "error",
		"-vaapi_device", "/dev/dri/renderD128",
		"-f", "lavfi", "-i", "testsrc=size=320x240:rate=5:duration=1",
		"-vf", "format=nv12,hwupload", "-c:v", "h264_vaapi", "-f", "null", "-")
	ok := cmd.Run() == nil
	log.Printf("[dc-bridge] playback vaapi hw-encode available: %v", ok)
	return ok
}

func startPlaybackFfmpeg(srcURL string, port int, rate float64) *exec.Cmd {
	// Transcode recordings to 720p H264, WebRTC-safe: constrained baseline, NO
	// B-frames (browser stalls on reorder), short GOP (frequent keyframes → fast
	// recovery from loss and from source swaps). dump_extra repeats SPS/PPS before
	// keyframes so a mid-stream swap decodes. Prefer Intel VAAPI hw encode
	// (near-zero CPU); fall back to software libx264 when no working GPU.
	//
	// rate > 1 = fast-forward: -readrate reads the input rate× realtime and
	// setpts=PTS/rate compresses timestamps so playout stays realtime with rate×
	// content; fps=15 caps the encoded frame rate so bitrate stays sane.
	out := fmt.Sprintf("rtp://127.0.0.1:%d?pkt_size=1200", port)
	pace := []string{"-re"}
	vfSuffix := ""
	if rate > 1 {
		pace = []string{"-readrate", fmt.Sprintf("%g", rate)}
		vfSuffix = fmt.Sprintf(",setpts=PTS/%g,fps=15", rate)
	}
	var args []string
	if vaapiOK {
		args = append([]string{
			"-hide_banner", "-loglevel", "error",
			"-hwaccel", "vaapi", "-hwaccel_device", "/dev/dri/renderD128",
			"-hwaccel_output_format", "vaapi",
		}, pace...)
		args = append(args,
			"-i", srcURL,
			"-an",
			"-vf", "scale_vaapi=w=1280:h=-2"+vfSuffix,
			"-c:v", "h264_vaapi", "-profile:v", "constrained_baseline", "-bf", "0",
			"-b:v", "1500k", "-maxrate", "1800k", "-g", "15",
			"-bsf:v", "dump_extra",
			"-f", "rtp", "-payload_type", "96", out,
		)
	} else {
		args = append([]string{
			"-hide_banner", "-loglevel", "error",
		}, pace...)
		args = append(args,
			"-i", srcURL,
			"-an",
			"-vf", "scale=1280:-2"+vfSuffix,
			"-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency",
			"-profile:v", "baseline", "-bf", "0",
			"-b:v", "1500k", "-maxrate", "1800k", "-bufsize", "1800k",
			"-g", "15", "-keyint_min", "15", "-pix_fmt", "yuv420p",
			"-bsf:v", "dump_extra",
			"-f", "rtp", "-payload_type", "96", out,
		)
	}
	cmd := exec.Command("ffmpeg", args...)
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		log.Printf("[dc-bridge] playback ffmpeg start error: %v", err)
		return nil
	}
	return cmd
}

func handleOffer(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	var req offerRequest
	if err := json.Unmarshal(body, &req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	config := webrtc.Configuration{ICEServers: buildICEServers(req.IceServers)}

	// Close any existing peer connection before creating a new one
	mu.Lock()
	if peerConnection != nil {
		peerConnection.Close()
		peerConnection = nil
	}
	mu.Unlock()

	pc, err := webrtc.NewPeerConnection(config)
	if err != nil {
		log.Printf("[dc-bridge] NewPeerConnection error: %v", err)
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	mu.Lock()
	peerConnection = pc
	mu.Unlock()

	pc.OnDataChannel(func(dc *webrtc.DataChannel) {
		log.Printf("[dc-bridge] DataChannel opened: label=%s", dc.Label())
		dc.OnMessage(func(msg webrtc.DataChannelMessage) {
			rawJSON := string(msg.Data)
			log.Printf("[dc-bridge] DataChannel received %d bytes → forwarding to JVM", len(rawJSON))
			sendToJVM(rawJSON, dc)
		})
	})

	pc.OnICEConnectionStateChange(func(state webrtc.ICEConnectionState) {
		log.Printf("[dc-bridge] ICE state: %s", state)
	})

	pc.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		log.Printf("[dc-bridge] PeerConnection state: %s", state)
		if state == webrtc.PeerConnectionStateFailed || state == webrtc.PeerConnectionStateClosed {
			mu.Lock()
			if peerConnection == pc {
				peerConnection = nil
			}
			mu.Unlock()
		}
	})

	if err := pc.SetRemoteDescription(webrtc.SessionDescription{
		Type: webrtc.SDPTypeOffer,
		SDP:  req.SDP,
	}); err != nil {
		log.Printf("[dc-bridge] SetRemoteDescription error: %v", err)
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		log.Printf("[dc-bridge] CreateAnswer error: %v", err)
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	// Gather all ICE candidates before returning answer (non-trickle mode).
	// GatheringCompletePromise must be called BEFORE SetLocalDescription.
	gatherComplete := webrtc.GatheringCompletePromise(pc)

	if err := pc.SetLocalDescription(answer); err != nil {
		log.Printf("[dc-bridge] SetLocalDescription error: %v", err)
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	select {
	case <-gatherComplete:
		log.Printf("[dc-bridge] ICE gathering complete")
	case <-time.After(10 * time.Second):
		log.Printf("[dc-bridge] ICE gathering timeout — using partial candidates")
	}

	finalAnswer := pc.LocalDescription()
	respData, _ := json.Marshal(offerResponse{SDP: finalAnswer.SDP})
	w.Header().Set("Content-Type", "application/json")
	w.Write(respData)
	log.Printf("[dc-bridge] Answer returned (%d bytes SDP)", len(finalAnswer.SDP))
}

func handleIce(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	var req iceRequest
	if err := json.Unmarshal(body, &req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	mu.Lock()
	pc := peerConnection
	mu.Unlock()

	if pc == nil {
		// No active peer — ICE candidate can be silently ignored
		w.WriteHeader(http.StatusOK)
		return
	}

	sdpMid := req.SDPMid
	sdpMLineIndex := req.SDPMLineIndex
	if err := pc.AddICECandidate(webrtc.ICECandidateInit{
		Candidate:     req.Candidate,
		SDPMid:        &sdpMid,
		SDPMLineIndex: &sdpMLineIndex,
	}); err != nil {
		log.Printf("[dc-bridge] AddICECandidate error: %v", err)
	}
	w.WriteHeader(http.StatusOK)
}

func handleHealth(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK)
	w.Write([]byte("ok"))
}

// sendToJVM forwards a DataChannel message to the JVM command endpoint and
// sends the response back on the DataChannel.
func sendToJVM(rawJSON string, dc *webrtc.DataChannel) {
	const maxRetries = 3
	for i := 0; i < maxRetries; i++ {
		resp, err := http.Post(jvmCommandURL, "application/json",
			bytes.NewBufferString(rawJSON))
		if err != nil {
			log.Printf("[dc-bridge] JVM POST attempt %d/%d failed: %v", i+1, maxRetries, err)
			if i < maxRetries-1 {
				time.Sleep(300 * time.Millisecond)
			}
			continue
		}

		reply, readErr := io.ReadAll(resp.Body)
		resp.Body.Close()
		if readErr != nil {
			log.Printf("[dc-bridge] Read JVM response error: %v", readErr)
			return
		}
		if len(reply) == 0 {
			log.Printf("[dc-bridge] JVM returned empty response — skipping DataChannel send")
			return
		}

		if sendErr := dc.SendText(string(reply)); sendErr != nil {
			log.Printf("[dc-bridge] DataChannel send error: %v", sendErr)
		} else {
			log.Printf("[dc-bridge] DataChannel reply sent %d bytes", len(reply))
		}
		return
	}
	log.Printf("[dc-bridge] Failed to reach JVM after %d attempts", maxRetries)
}

func main() {
	http.HandleFunc("/dc/offer", handleOffer)
	http.HandleFunc("/dc/ice", handleIce)
	http.HandleFunc("/playback/offer", handlePlaybackOffer)
	http.HandleFunc("/playback/source", handlePlaybackSource)
	http.HandleFunc("/playback/pause", handlePlaybackPause)
	http.HandleFunc("/playback/stop", handlePlaybackStop)
	http.HandleFunc("/health", handleHealth)

	log.Printf("[dc-bridge] Starting on %s (JVM command URL: %s)", listenAddr, jvmCommandURL)
	log.Fatal(http.ListenAndServe(listenAddr, nil))
}

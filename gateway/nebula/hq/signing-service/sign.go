package main

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

var (
	errBadToken     = errors.New("unknown enrollment token")
	errExpiredToken = errors.New("enrollment token expired")
	errUsedToken    = errors.New("enrollment token already used")
	errNoRequest    = errors.New("no enrollment request for token")
)

// signCert runs `nebula-cert sign -in-pub` to sign the gateway's PUBLIC key (the private key never
// leaves the Pi). It signs with the CA selected by the token (<caName>.crt/.key under CADir) and
// returns the signed cert PEM plus that CA's cert PEM (so the agent can pin/trust it).
//
//	nebula-cert sign -ca-crt <ca>.crt -ca-key <ca>.key -in-pub <pub> \
//	    -name <name> -ip <overlayIp>/<mask> -groups <groups> -out-crt <crt>
func (s *server) signCert(caName, name, overlayIP, cidrMask, groups, pubPem string) (certPem, caPem string, err error) {
	caCrt, caKey, caPemStr, err := s.caFiles(caName)
	if err != nil {
		return "", "", err
	}

	tmp, err := os.MkdirTemp("", "nebula-sign-")
	if err != nil {
		return "", "", err
	}
	defer os.RemoveAll(tmp)

	pubPath := filepath.Join(tmp, "host.pub")
	crtPath := filepath.Join(tmp, "host.crt")
	if err := os.WriteFile(pubPath, []byte(ensureTrailingNL(pubPem)), 0o600); err != nil {
		return "", "", err
	}

	ipArg := overlayIP + "/" + cidrMask // nebula wants ip/mask, e.g. 10.10.20.12/24
	args := []string{
		"sign",
		"-ca-crt", caCrt,
		"-ca-key", caKey,
		"-in-pub", pubPath,
		"-name", name,
		"-ip", ipArg,
		"-out-crt", crtPath,
	}
	if groups != "" {
		args = append(args, "-groups", groups)
	}

	cmd := exec.Command(s.cfg.NebulaCert, args...)
	if out, err := cmd.CombinedOutput(); err != nil {
		return "", "", fmt.Errorf("nebula-cert sign failed: %v: %s", err, strings.TrimSpace(string(out)))
	}

	crtBytes, err := os.ReadFile(crtPath)
	if err != nil {
		return "", "", fmt.Errorf("read signed cert: %w", err)
	}
	return string(crtBytes), caPemStr, nil
}

// caFiles resolves the cert/key paths for a CA name (default "default" for the PoC single-CA case)
// and returns the CA cert PEM to hand back to the agent.
func (s *server) caFiles(caName string) (crtPath, keyPath, caPem string, err error) {
	if caName == "" {
		caName = "default"
	}
	crtPath = filepath.Join(s.cfg.CADir, caName+".crt")
	keyPath = filepath.Join(s.cfg.CADir, caName+".key")
	b, err := os.ReadFile(crtPath)
	if err != nil {
		return "", "", "", fmt.Errorf("CA %q cert not found at %s: %w", caName, crtPath, err)
	}
	if _, err := os.Stat(keyPath); err != nil {
		return "", "", "", fmt.Errorf("CA %q key not found at %s: %w", caName, keyPath, err)
	}
	return crtPath, keyPath, string(b), nil
}

// maskOf extracts the prefix length ("24") from a CIDR like "10.10.20.0/24".
func maskOf(cidr string) string {
	if i := strings.LastIndex(cidr, "/"); i >= 0 {
		return cidr[i+1:]
	}
	return "24"
}

func ensureTrailingNL(s string) string {
	if strings.HasSuffix(s, "\n") {
		return s
	}
	return s + "\n"
}

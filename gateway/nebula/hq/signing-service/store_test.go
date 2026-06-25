package main

import (
	"path/filepath"
	"testing"
)

// TestMarkRevokedByOverlayIP covers G8: the NVR revokes by overlay IP (it never sees the enrollment
// token), so revoking by IP must flag the right request and leave others untouched.
func TestMarkRevokedByOverlayIP(t *testing.T) {
	st, err := loadStore(filepath.Join(t.TempDir(), "state.json"), nil)
	if err != nil {
		t.Fatalf("loadStore: %v", err)
	}
	st.d.Tokens["tok-a"] = &Token{Token: "tok-a", IPCidr: "10.10.20.0/24"}
	st.d.Requests["tok-a"] = &Request{Token: "tok-a", OverlayIP: "10.10.20.12", Status: "signed", Fingerprint: "fp-a"}
	st.d.Requests["tok-b"] = &Request{Token: "tok-b", OverlayIP: "10.10.20.13", Status: "signed", Fingerprint: "fp-b"}

	fp, ok := st.markRevokedByOverlayIP("10.10.20.12")
	if !ok || fp != "fp-a" {
		t.Fatalf("revoke by ip = (%q,%v), want (fp-a,true)", fp, ok)
	}
	if !st.d.Requests["tok-a"].Revoked {
		t.Fatal("tok-a should be revoked")
	}
	if st.d.Requests["tok-b"].Revoked {
		t.Fatal("tok-b must be untouched")
	}
	if _, ok := st.markRevokedByOverlayIP("10.10.20.99"); ok {
		t.Fatal("unknown overlay IP must report ok=false")
	}
}

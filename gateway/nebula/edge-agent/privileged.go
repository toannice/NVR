package main

import (
	"os"
	"os/exec"
)

// G4 — least privilege. The agent runs as the unprivileged `nvragent` user (see nvr-edge-agent.service)
// and needs root for exactly two things: signalling the nginx master to reload after writing
// /etc/nginx/stream.d, and restarting the `nebula` unit after writing a new cert/config. Those two
// commands are granted NOPASSWD in /etc/sudoers.d/nvr-edge-agent and nothing else — so an RCE in the
// agent can't escalate to general root. Everything else (keygen, file writes, `nginx -t`,
// `systemctl is-active`) works unprivileged via the ReadWritePaths the unit grants.
//
// privilegedCmd runs `name args...` directly when we're already root (legacy/dev), else via `sudo -n`
// (non-interactive — fails fast if the sudoers rule is missing rather than hanging on a prompt).
func privilegedCmd(name string, args ...string) *exec.Cmd {
	if os.Geteuid() == 0 {
		return exec.Command(name, args...)
	}
	return exec.Command("sudo", append([]string{"-n", name}, args...)...)
}

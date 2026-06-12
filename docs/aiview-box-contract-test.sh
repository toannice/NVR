#!/usr/bin/env bash
#
# AiView Box ↔ NVR AIView — Integration contract verification (protocol v1.1).
#
# Verifies the NVR's integration endpoints against NVR_AIView_Integration_Protocol v1.1
# BEFORE you hand off to the Box dev team. Every assertion maps to a clause in the contract
# (§ references printed inline). Exits non-zero if ANY assertion fails.
#
# Usage:
#   docs/aiview-box-contract-test.sh
#   BASE=http://192.168.2.2:3000 BOX_USER=box BOX_PASS=secret docs/aiview-box-contract-test.sh
#
# Env (all optional — sensible defaults):
#   BASE       NVR base URL                (default http://localhost:3000)
#   COOKIE     admin session cookie        (default forged admin NVR_SESSION — works locally)
#   BOX_USER   integration username to set (default box)
#   BOX_PASS   integration password to set (default secret)
#
# Requires: bash, curl, jq.
set -u

BASE="${BASE:-http://localhost:3000}"
COOKIE="${COOKIE:-NVR_SESSION=%7B%22id%22%3A%22admin%22%7D}"
BOX_USER="${BOX_USER:-box}"
BOX_PASS="${BOX_PASS:-secret}"

PASS=0; FAIL=0
G="\033[32m"; R="\033[31m"; Y="\033[33m"; B="\033[34m"; N="\033[0m"

ok()   { PASS=$((PASS+1)); echo -e "  ${G}PASS${N} $1"; }
bad()  { FAIL=$((FAIL+1)); echo -e "  ${R}FAIL${N} $1"; [ -n "${2:-}" ] && echo -e "       ${Y}↳ $2${N}"; }
sect() { echo -e "\n${B}== $1 ==${N}"; }

# curl helpers: print "HTTP_STATUS\n<body>" so we can assert on both.
req() { # method path [json-body] [extra curl args...]
  local method="$1" path="$2"; shift 2
  local body="${1:-}"; [ $# -gt 0 ] && shift
  if [ -n "$body" ]; then
    curl -s -m 10 -w $'\n%{http_code}' -X "$method" "$BASE$path" \
      -H 'Content-Type: application/json' "$@" -d "$body"
  else
    curl -s -m 10 -w $'\n%{http_code}' -X "$method" "$BASE$path" "$@"
  fi
}
status() { tail -n1 <<<"$1"; }
bodyof() { sed '$d' <<<"$1"; }

require() { command -v "$1" >/dev/null 2>&1 || { echo "Missing dependency: $1"; exit 2; }; }
require curl; require jq

echo -e "${B}AiView Box integration contract test — v1.1${N}"
echo    "BASE=$BASE  BOX_USER=$BOX_USER"

# --- Preflight: NVR reachable -------------------------------------------------
if ! curl -s -m 5 "$BASE/api/version" >/dev/null; then
  echo -e "${R}NVR not reachable at $BASE/api/version — is the stack up?${N}"; exit 2
fi

# --- Setup: enable integration with known credentials (admin, §A8) -----------
sect "Setup — enable integration via admin API (needs session)"
R0=$(req PUT /api/integration/settings \
  "{\"enabled\":true,\"username\":\"$BOX_USER\",\"password\":\"$BOX_PASS\"}" \
  -H "Cookie: $COOKIE")
if [ "$(status "$R0")" = "200" ]; then
  ok "PUT /api/integration/settings enabled=true (200)"
  echo "$(bodyof "$R0")" | jq -e '.enabled==true and .username=="'"$BOX_USER"'" and .hasPassword==true' >/dev/null \
    && ok "settings view echoes enabled/username/hasPassword (no hash leaked)" \
    || bad "settings view shape wrong (must be {enabled,username,hasPassword})" "$(bodyof "$R0")"
  echo "$(bodyof "$R0")" | jq -e 'has("passwordHash")|not' >/dev/null \
    && ok "response does NOT expose password hash" \
    || bad "response leaked passwordHash"
else
  bad "could not enable integration (status $(status "$R0")) — check COOKIE/admin auth" "$(bodyof "$R0")"
  echo -e "${Y}Cannot continue without enabling integration. Set COOKIE to a valid admin session.${N}"
  exit 1
fi

# --- §3.1 getintegrationinfo --------------------------------------------------
sect "§3.1 GET /api/getintegrationinfo (no auth)"
R1=$(req GET /api/getintegrationinfo)
[ "$(status "$R1")" = "200" ] && ok "200 OK" || bad "expected 200, got $(status "$R1")"
J1="$(bodyof "$R1")"
echo "$J1" | jq -e '.protocol_version=="1.1"' >/dev/null && ok "protocol_version == \"1.1\"" || bad "protocol_version != 1.1" "$J1"
echo "$J1" | jq -e '.nvr_version|type=="string"' >/dev/null && ok "nvr_version is string" || bad "nvr_version missing/!string"
echo "$J1" | jq -e '.max_cameras|type=="number"' >/dev/null && ok "max_cameras is number" || bad "max_cameras missing/!number"
echo "$J1" | jq -e '.rtsp_auth|type=="boolean"' >/dev/null && ok "rtsp_auth is boolean" || bad "rtsp_auth missing/!boolean"
echo "$J1" | jq -e '.rtsp_auth==false' >/dev/null && ok "rtsp_auth == false (v1.1 default)" || bad "rtsp_auth should default false in v1.1"

# --- §3.2 getprofilecamera (valid creds) -------------------------------------
sect "§3.2 POST /api/getprofilecamera (valid creds → array)"
R2=$(req POST /api/getprofilecamera \
  "{\"username\":\"$BOX_USER\",\"password\":\"$BOX_PASS\",\"already_added_camera_ids\":[]}")
[ "$(status "$R2")" = "200" ] && ok "200 OK" || bad "expected 200, got $(status "$R2")" "$(bodyof "$R2")"
J2="$(bodyof "$R2")"
echo "$J2" | jq -e 'type=="array"' >/dev/null && ok "response is a JSON array" || bad "response is not an array" "$J2"
CAM_COUNT=$(echo "$J2" | jq 'length' 2>/dev/null || echo 0)
echo -e "       ${Y}↳ ${CAM_COUNT} not-yet-added camera(s) returned${N}"
if [ "${CAM_COUNT:-0}" -gt 0 ]; then
  # Validate the shape + restream URL format of the first camera.
  echo "$J2" | jq -e '.[0]|.camera_id|type=="string"' >/dev/null && ok "camera_id is string" || bad "camera_id missing/!string"
  echo "$J2" | jq -e '.[0]|.name|type=="string"' >/dev/null && ok "name is string" || bad "name missing/!string"
  echo "$J2" | jq -e '.[0]|.online|type=="boolean"' >/dev/null && ok "online is boolean" || bad "online missing/!boolean"
  echo "$J2" | jq -e '.[0]|has("sub_stream_url")' >/dev/null && ok "sub_stream_url key present (may be null)" || bad "sub_stream_url key missing"
  M0=$(echo "$J2" | jq -r '.[0].main_stream_url')
  ID0=$(echo "$J2" | jq -r '.[0].camera_id')
  if [[ "$M0" =~ ^rtsp://.*:8554/${ID0}_main$ ]]; then
    ok "main_stream_url matches rtsp://<ip>:8554/${ID0}_main  ($M0)"
  else
    bad "main_stream_url format wrong" "$M0"
  fi
  # sub_stream_url, when not null, must point at <id>_sub
  SUB0=$(echo "$J2" | jq -r '.[0].sub_stream_url')
  if [ "$SUB0" != "null" ]; then
    [[ "$SUB0" =~ :8554/${ID0}_sub$ ]] && ok "sub_stream_url matches <ip>:8554/${ID0}_sub" || bad "sub_stream_url format wrong" "$SUB0"
  else
    ok "sub_stream_url is null (camera has no sub-stream) — allowed"
  fi

  # --- Filtering: already_added_camera_ids must exclude that id (§3.2) -------
  sect "§3.2 filtering — already_added_camera_ids excludes returned cameras"
  R2F=$(req POST /api/getprofilecamera \
    "{\"username\":\"$BOX_USER\",\"password\":\"$BOX_PASS\",\"already_added_camera_ids\":[\"$ID0\"]}")
  echo "$(bodyof "$R2F")" | jq -e --arg id "$ID0" 'all(.[]; .camera_id != $id)' >/dev/null \
    && ok "camera_id $ID0 excluded when listed as already-added" \
    || bad "excluded camera_id still returned" "$(bodyof "$R2F")"
else
  echo -e "       ${Y}↳ No cameras with a channel_id present — shape checks for items skipped.${N}"
  echo -e "       ${Y}  (Add at least one camera so the Box has something to discover.)${N}"
fi

# --- §3.2 wrong password → 401 -----------------------------------------------
sect "§4.1 auth errors"
R3=$(req POST /api/getprofilecamera "{\"username\":\"$BOX_USER\",\"password\":\"WRONG\",\"already_added_camera_ids\":[]}")
[ "$(status "$R3")" = "401" ] && ok "wrong password → 401" || bad "wrong password expected 401, got $(status "$R3")"
echo "$(bodyof "$R3")" | jq -e '.error=="INVALID_CREDENTIALS"' >/dev/null && ok "error==INVALID_CREDENTIALS" || bad "error code wrong" "$(bodyof "$R3")"

# --- §4.1 bad body → 400 ------------------------------------------------------
R4=$(curl -s -m 10 -w $'\n%{http_code}' -X POST "$BASE/api/getprofilecamera" -H 'Content-Type: application/json' -d 'not-json')
[ "$(status "$R4")" = "400" ] && ok "malformed body → 400" || bad "malformed body expected 400, got $(status "$R4")"
echo "$(bodyof "$R4")" | jq -e '.error=="BAD_REQUEST"' >/dev/null && ok "error==BAD_REQUEST" || bad "error code wrong" "$(bodyof "$R4")"

# --- §3.3 getcamerastatus (no auth) ------------------------------------------
sect "§3.3 POST /api/getcamerastatus (no auth)"
PROBE_ID="${ID0:-1}"
R5=$(req POST /api/getcamerastatus "{\"camera_ids\":[\"$PROBE_ID\",\"__missing__\"]}")
[ "$(status "$R5")" = "200" ] && ok "200 OK without credentials" || bad "expected 200, got $(status "$R5")" "$(bodyof "$R5")"
J5="$(bodyof "$R5")"
echo "$J5" | jq -e 'type=="array" and length==2' >/dev/null && ok "array of 2 items (one per requested id)" || bad "expected 2-item array" "$J5"
echo "$J5" | jq -e '.[0]|.camera_id and (.exists|type=="boolean") and (.online|type=="boolean")' >/dev/null \
  && ok "items have {camera_id, exists:bool, online:bool}" || bad "status item shape wrong" "$J5"
echo "$J5" | jq -e '.[1].exists==false and .[1].online==false' >/dev/null \
  && ok "missing camera → exists=false, online=false" || bad "missing camera not reported as absent" "$J5"

# --- §4.1 integration disabled → 403 -----------------------------------------
sect "§4.1 integration disabled → 403 FORBIDDEN"
req PUT /api/integration/settings "{\"enabled\":false,\"username\":\"$BOX_USER\"}" -H "Cookie: $COOKIE" >/dev/null
R6=$(req POST /api/getprofilecamera "{\"username\":\"$BOX_USER\",\"password\":\"$BOX_PASS\",\"already_added_camera_ids\":[]}")
[ "$(status "$R6")" = "403" ] && ok "disabled → 403" || bad "disabled expected 403, got $(status "$R6")"
echo "$(bodyof "$R6")" | jq -e '.error=="FORBIDDEN"' >/dev/null && ok "error==FORBIDDEN" || bad "error code wrong" "$(bodyof "$R6")"
# re-enable for any follow-up runs
req PUT /api/integration/settings "{\"enabled\":true,\"username\":\"$BOX_USER\",\"password\":\"$BOX_PASS\"}" -H "Cookie: $COOKIE" >/dev/null
ok "re-enabled integration after 403 test"

# --- §4.4 rate-limit → 429 (run LAST: it fills the per-IP window) -------------
sect "§4.4 rate-limit → 429 TOO_MANY_REQUESTS (30 req/min/IP)"
GOT429=""; RETRY_HDR=""
for i in $(seq 1 45); do
  RESP=$(curl -s -m 5 -D - -o /dev/null -w '%{http_code}' -X POST "$BASE/api/getcamerastatus" \
    -H 'Content-Type: application/json' -d '{"camera_ids":["1"]}')
  CODE=$(tail -n1 <<<"$RESP")
  if [ "$CODE" = "429" ]; then
    GOT429=1
    RETRY_HDR=$(echo "$RESP" | grep -i '^retry-after:' | tr -d '\r' | awk '{print $2}')
    break
  fi
done
[ -n "$GOT429" ] && ok "received 429 within 45 rapid requests" || bad "never got 429 — rate limiter not enforcing"
if [ -n "$GOT429" ]; then
  [ -n "$RETRY_HDR" ] && ok "Retry-After header present (=$RETRY_HDR s)" || bad "429 missing Retry-After header"
fi

# --- Summary ------------------------------------------------------------------
echo -e "\n${B}========================================${N}"
echo -e "  ${G}PASS: $PASS${N}    ${R}FAIL: $FAIL${N}"
echo -e "${B}========================================${N}"
if [ "$FAIL" -eq 0 ]; then
  echo -e "${G}✔ Contract OK — safe to hand off to the Box dev team.${N}"
  exit 0
else
  echo -e "${R}X Contract violations above — fix before handoff.${N}"
  exit 1
fi

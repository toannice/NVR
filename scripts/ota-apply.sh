#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# ota-apply.sh — đổi container sang bản mới, kiểm khoẻ, rollback nếu lỗi.
#
# CHẠY TRÊN HOST bằng systemd (nvr-ota-apply.service), KHÔNG chạy trong container.
#
# Vì sao phải là host script: bước này recreate cả nvr-wrapper LẪN nvr-watchdog. Một container
# không thể chạy `docker compose up -d` cho chính nó — lệnh đó giết tiến trình đang chạy nó,
# giữa lệnh, và thế là không còn ai health-check hay rollback. systemd oneshot trên host là
# thứ duy nhất sống sót qua bước đó.
#
# Watchdog đã làm xong phần nặng trước khi gọi tới đây:
#   · verify chữ ký Ed25519 + sha256 từng payload + whitelist đường dẫn
#   · chạy 4 gate chính sách
#   · `docker load` image mới (tag đã trỏ sang bản mới)
#   · ghim image cũ vào nvr-ota-rollback/* để có đường lùi
#   · ghi data/ota/apply-plan.json + rollback/plan.json
#
# Script này KHÔNG verify lại gì — nó tin apply-plan.json, vì file đó do watchdog ghi vào một
# thư mục chỉ root ghi được, sau khi đã verify. Nhiệm vụ ở đây thuần là phẫu thuật.
#
# Kích hoạt: watchdog ghi data/ota/trigger → nvr-ota-apply.path thấy → chạy service này.
# ──────────────────────────────────────────────────────────────────────────────
set -uo pipefail

PROJECT_DIR="${PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$PROJECT_DIR" || exit 1

OTA_DIR="$PROJECT_DIR/data/ota"
PLAN="$OTA_DIR/apply-plan.json"
ROLLBACK_PLAN="$OTA_DIR/rollback/plan.json"
STATUS="$OTA_DIR/status.json"
TRIGGER="$OTA_DIR/trigger"
LOCK="$OTA_DIR/apply.lock"
STAGED="$OTA_DIR/staged"

DC="docker compose"

# ── Log ──────────────────────────────────────────────────────────────────────
# Ra cả journald (systemd bắt stdout) và status.json. status.json là thứ quan trọng hơn: lúc
# này container watchdog đang bị recreate nên `docker logs nvr-watchdog` MẤT hết dòng trước
# đó, và máy field không SSH được ngay.
log()  { printf '[ota-apply] %s\n' "$*"; }
fail() { printf '[ota-apply] LỖI: %s\n' "$*" >&2; }

need() { command -v "$1" >/dev/null || { fail "thiếu $1"; exit 1; }; }
need docker
need jq

# ── Ghi status.json (temp + rename, atomic) ──────────────────────────────────
status_set() { # status_set <state> <progress> <message> [errorCode]
    local state="$1" prog="$2" msg="$3" errc="${4:-}"
    local tmp="$STATUS.tmp.$$"
    # Giữ nguyên các field watchdog đã ghi (version/requestId/trigger/startedAt/log), chỉ
    # cập nhật phần tiến trình và NỐI THÊM một dòng log.
    if [[ -f "$STATUS" ]]; then
        jq --arg s "$state" --argjson p "$prog" --arg m "$msg" --arg e "$errc" \
           --arg now "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg line "$(date -u +%Y-%m-%dT%H:%M:%SZ) [$state] $msg" \
           '.state=$s | .progress=$p | .message=$m | .updatedAt=$now
            | (if $e != "" then .errorCode=$e else . end)
            | .log = ((.log // []) + [$line] | .[-200:])' \
           "$STATUS" > "$tmp" 2>/dev/null && mv -f "$tmp" "$STATUS"
    else
        jq -n --arg s "$state" --argjson p "$prog" --arg m "$msg" --arg e "$errc" \
              --arg now "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
              '{state:$s, progress:$p, message:$m, errorCode:$e, updatedAt:$now, log:[]}' \
           > "$tmp" && mv -f "$tmp" "$STATUS"
    fi
    rm -f "$tmp"
    log "[$state] $msg"
}

# ── Telegram ─────────────────────────────────────────────────────────────────
telegram() {
    local text="$1"
    [[ -f "$PROJECT_DIR/.env" ]] || return 0
    local tok cid
    tok="$(grep -E '^TELEGRAM_BOT_TOKEN=' "$PROJECT_DIR/.env" | cut -d= -f2- | tr -d '"'"'"' ')"
    cid="$(grep -E '^TELEGRAM_CHAT_ID=' "$PROJECT_DIR/.env" | cut -d= -f2- | tr -d '"'"'"' ')"
    [[ -n "$tok" && -n "$cid" ]] || return 0
    curl -sS -m 20 -X POST "https://api.telegram.org/bot${tok}/sendMessage" \
        -d chat_id="$cid" -d parse_mode=HTML \
        --data-urlencode "text=${text}" >/dev/null 2>&1 || log "gửi Telegram thất bại (bỏ qua)"
}

HOST="$(hostname)"

# ── Vào việc ─────────────────────────────────────────────────────────────────

# Xoá trigger NGAY: path unit dùng PathExists nên còn file là còn kích hoạt lại → vòng lặp vô tận.
rm -f "$TRIGGER"

[[ -f "$PLAN" ]] || { fail "không thấy $PLAN — không có gì để áp"; exit 0; }

# flock: một lần apply duy nhất. `200>` mở fd rồi flock -n, thất bại thì thoát ngay chứ
# không chờ — hai lần apply chồng nhau tệ hơn nhiều so với bỏ qua một lần.
exec 200>"$LOCK"
flock -n 200 || { fail "đã có một lần apply đang chạy (flock $LOCK) — bỏ qua"; exit 0; }

FROM_VER="$(jq -r '.fromVersion // ""' "$PLAN")"
TO_VER="$(jq -r '.toVersion   // ""' "$PLAN")"
RISK="$(jq -r '.risk // "normal"' "$PLAN")"
REQ_ID="$(jq -r '.requestId // ""' "$PLAN")"
mapfile -t CONTAINERS < <(jq -r '.recreate[]?' "$PLAN")
mapfile -t OVERLAY    < <(jq -r '.overlayFiles[]?' "$PLAN")
PRE_HOOK="$(jq -r '.preApplyHook  // ""' "$PLAN")"
POST_HOOK="$(jq -r '.postApplyHook // ""' "$PLAN")"

[[ ${#CONTAINERS[@]} -gt 0 ]] || { fail "apply-plan không khai container nào để recreate"; exit 1; }

log "════════════════════════════════════════════════════"
log " Áp bản: ${FROM_VER:-?} → ${TO_VER}   risk=$RISK"
log " Container: ${CONTAINERS[*]}"
log " Overlay  : ${#OVERLAY[@]} file"
log "════════════════════════════════════════════════════"

# ── Hook trước khi đổi ───────────────────────────────────────────────────────
if [[ -n "$PRE_HOOK" && -f "$STAGED/$PRE_HOOK" ]]; then
    status_set SWAPPING 78 "Chạy hook trước khi đổi ($PRE_HOOK)"
    if ! bash "$STAGED/$PRE_HOOK"; then
        status_set REJECTED 100 "Hook $PRE_HOOK thất bại — huỷ cập nhật, chưa đổi gì" "PRE_HOOK_FAIL"
        telegram "🖥️ <b>[$HOST]</b>%0A❌ Hook chuẩn bị thất bại, huỷ cập nhật <b>$TO_VER</b>%0Aℹ️ Máy vẫn chạy bản cũ"
        exit 1
    fi
fi

# ── Ghi file overlay ─────────────────────────────────────────────────────────
# Watchdog đã kiểm whitelist + sha256 rồi. Ở đây chỉ copy, nhưng vẫn chặn đường dẫn tuyệt đối
# và ".." lần nữa: rẻ, và nếu apply-plan.json bị sửa bằng cách nào đó thì đây là chốt cuối.
NGINX_CONF_CHANGED=0
if [[ ${#OVERLAY[@]} -gt 0 ]]; then
    status_set SWAPPING 80 "Ghi ${#OVERLAY[@]} file cấu hình"
    for rel in "${OVERLAY[@]}"; do
        [[ -n "$rel" ]] || continue
        if [[ "$rel" == /* || "$rel" == *".."* ]]; then
            fail "đường dẫn overlay không an toàn, bỏ qua: $rel"; continue
        fi
        src="$STAGED/files/$rel"
        [[ -f "$src" ]] || { fail "thiếu $src, bỏ qua"; continue; }
        mkdir -p "$PROJECT_DIR/$(dirname "$rel")"

        # `cp -f` (không phải mv/install) LÀ CÓ CHỦ Ý: compose bind-mount từng FILE đơn lẻ
        # (./nginx/nginx.conf:/etc/nginx/nginx.conf), và bind-mount file neo theo INODE. Ghi
        # bằng mv hoặc install sẽ tạo inode mới, làm đứt liên kết mount — container tiếp tục
        # thấy nội dung CŨ, im lặng, cho tới lần recreate sau. `cp -f` mở file đích với
        # O_TRUNC nên giữ nguyên inode.
        cp -f "$src" "$PROJECT_DIR/$rel"
        log "  ghi $rel"
        [[ "$rel" == nginx/* ]] && NGINX_CONF_CHANGED=1
    done
fi

# ── FRIGATE_HOST_IP phải khớp IP thật ────────────────────────────────────────
# Gói có thể mang docker-compose.yml mới, và compose publish cổng restream theo
# ${FRIGATE_HOST_IP}. IP trong .env mà lệch IP thật thì docker báo "cannot assign requested
# address", frigate không lên → mất CẢ live view lẫn ghi hình, trong khi container khác vẫn
# "healthy" nên nhìn bảng trạng thái không thấy sai ở đâu. Đã xảy ra thật 15-08-2026.
if [[ -x "$PROJECT_DIR/scripts/nvr-ip-guard.sh" ]]; then
    status_set SWAPPING 82 "Đồng bộ FRIGATE_HOST_IP theo IP thật"
    "$PROJECT_DIR/scripts/nvr-ip-guard.sh" --env-only >/dev/null 2>&1 \
        || log "nvr-ip-guard trả lỗi (bỏ qua, không chặn cập nhật)"
fi

# ── Đổi container ────────────────────────────────────────────────────────────
# nginx LUÔN nằm trong danh sách (watchdog đã thêm). CLAUDE.md ghi rõ: nginx KHÔNG tự start
# khi recreate nvr-wrapper — quên là site unreachable. Áp OTA lúc 2h sáng rồi để site chết tới
# sáng là kịch bản tệ nhất của cả cơ chế này.
status_set SWAPPING 85 "Đổi container: ${CONTAINERS[*]}"
if ! $DC up -d --no-deps "${CONTAINERS[@]}" 2>&1 | while IFS= read -r l; do log "  $l"; done; then
    log "docker compose up trả lỗi — sẽ để health-check quyết định"
fi

# Bảo hiểm: nginx phải chạy, kể cả khi nó không có trong danh sách vì lý do nào đó.
$DC up -d nginx >/dev/null 2>&1 || fail "không start được nginx!"

# nginx.conf mới thì PHẢI bắt nginx đọc lại.
#
# `docker compose up -d nginx` ở trên là KHÔNG ĐỦ: compose so image + cấu hình service để quyết
# định có recreate, và nội dung một file được bind-mount không nằm trong phép so đó. Container
# đang chạy sẽ được giữ nguyên, và nginx tiếp tục dùng config đã nạp vào bộ nhớ từ lúc start —
# tức là một gói mang nginx.conf mới sẽ KHÔNG CÓ HIỆU LỰC, im lặng, cho tới lần restart máy sau.
#
# Thử `nginx -s reload` trước (không rớt kết nối nào). Reload thất bại thường là do config mới
# sai cú pháp — lúc đó force-recreate để lỗi nổ ra ngay và health-check bắt được, thay vì để
# nginx chạy tiếp với config cũ và ta tưởng đã áp thành công.
if [[ $NGINX_CONF_CHANGED -eq 1 ]]; then
    status_set SWAPPING 86 "Nạp lại cấu hình nginx"
    if docker exec nvr-nginx nginx -t >/dev/null 2>&1 && docker exec nvr-nginx nginx -s reload >/dev/null 2>&1; then
        log "  nginx đã nạp lại config mới"
    else
        fail "nginx -t/-s reload thất bại với config mới — force-recreate để lỗi hiện ra"
        $DC up -d --force-recreate --no-deps nginx 2>&1 | while IFS= read -r l; do log "  $l"; done
    fi
fi

# ── Health-check ─────────────────────────────────────────────────────────────
# /api/health MỘT MÌNH LÀ KHÔNG ĐỦ: nó trả 200 kể cả khi container CŨ vẫn đang chạy (compose
# up thất bại lặng lẽ, hoặc container mới crash rồi restart về bản cũ). Health pass ⇒ máy ghi
# "cập nhật thành công" trong khi chưa đổi gì. Nên phải có expectJson đối chiếu /api/version
# báo ĐÚNG version mới.
check_one() { # check_one <url> <expectStatus> <expectJsonCompact> <timeoutSec>
    local url="$1" want="$2" wantjson="$3" timeout="$4"
    local deadline=$(( $(date +%s) + timeout ))
    local last="chưa nhận được phản hồi"
    while [[ $(date +%s) -lt $deadline ]]; do
        local body code
        body="$(curl -sS -m 5 -w '\n%{http_code}' "$url" 2>/dev/null)" || { sleep 5; last="không kết nối được"; continue; }
        code="$(tail -n1 <<<"$body")"
        body="$(sed '$d' <<<"$body")"
        if [[ "$code" != "$want" ]]; then
            last="HTTP $code (mong $want)"; sleep 5; continue
        fi
        if [[ "$wantjson" == "{}" || -z "$wantjson" ]]; then
            echo "ok"; return 0
        fi
        # Mọi cặp key/value trong expectJson phải khớp
        local mismatch=0 k v got
        while IFS=$'\t' read -r k v; do
            [[ -n "$k" ]] || continue
            got="$(jq -r --arg k "$k" '.[$k] // ""' <<<"$body" 2>/dev/null)"
            [[ "$got" == "$v" ]] || { mismatch=1; last="$k='$got' nhưng mong '$v'"; }
        done < <(jq -r 'to_entries[] | "\(.key)\t\(.value)"' <<<"$wantjson")
        if [[ $mismatch -eq 0 ]]; then echo "ok"; return 0; fi
        sleep 5
    done
    echo "$last"; return 1
}

run_health_checks() {
    local n=0 total
    total="$(jq '.healthChecks | length' "$PLAN")"
    if [[ "$total" -eq 0 ]]; then
        log "apply-plan không khai health check nào — chờ 20s rồi coi là OK"
        sleep 20; return 0
    fi
    while IFS=$'\t' read -r url want wantjson timeout; do
        [[ -n "$url" ]] || continue
        n=$((n + 1))
        status_set HEALTH_CHECK 90 "Kiểm khoẻ $n/$total: $url"
        local r
        r="$(check_one "$url" "$want" "$wantjson" "$timeout")"
        if [[ "$r" != "ok" ]]; then
            HEALTH_FAIL_REASON="$url → $r"
            return 1
        fi
        log "  ✓ $url"
    done < <(jq -r '.healthChecks[] | "\(.url)\t\(.expectStatus // 200)\t\(.expectJson // {} | tostring)\t\(.timeoutSec // 180)"' "$PLAN")
    return 0
}

HEALTH_FAIL_REASON=""
if run_health_checks; then
    # ── Thành công ───────────────────────────────────────────────────────────
    if [[ -n "$POST_HOOK" && -f "$STAGED/$POST_HOOK" ]]; then
        status_set HEALTH_CHECK 95 "Chạy hook sau khi đổi ($POST_HOOK)"
        bash "$STAGED/$POST_HOOK" || log "hook $POST_HOOK trả lỗi (không rollback vì máy đã khoẻ)"
    fi

    # Ghi lại ID THẬT của từng image vừa đặt.
    #
    # Lần apply sau sẽ ghim đường lùi theo các ID này chứ không theo tag hiện tại — vì tag
    # (vd nvr-wrapper:latest) có thể bị đổi ngoài đường OTA: `docker compose build` gán thẳng
    # vào nó. Ghim theo tag lúc đó sẽ trỏ vào chính image mới, và rollback thành lệnh không
    # làm gì trong khi vẫn báo thành công. Đã gặp thật khi test 21-08-2026.
    IMG_JSON="{}"
    while read -r tag; do
        [[ -n "$tag" ]] || continue
        id="$(docker image inspect -f '{{.Id}}' "$tag" 2>/dev/null || true)"
        [[ -n "$id" ]] || { fail "không đọc được image id của $tag"; continue; }
        IMG_JSON="$(jq --arg t "$tag" --arg i "$id" '. + {($t): $i}' <<<"$IMG_JSON")"
        log "  ghi nhận $tag → ${id:0:19}"
    done < <(jq -r '.imageTags[]?' "$PLAN")

    # Nâng installedVersion VÀ sàn chống hạ cấp. Sàn = max(sàn cũ, version mới) — không bao
    # giờ hạ, vì hạ sàn là mở lại cửa cho downgrade attack.
    STATE="$OTA_DIR/state.json"
    tmp="$STATE.tmp.$$"
    jq --arg v "$TO_VER" --arg from "$FROM_VER" --arg now "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
       --argjson imgs "$IMG_JSON" '
        def vnum: split(".") | map(tonumber? // 0);
        .installedVersion = $v
        | .floorVersion = (if (.floorVersion // "") == "" then $v
                           elif (($v | vnum) > ((.floorVersion) | vnum)) then $v
                           else .floorVersion end)
        | .committedImages = ((.committedImages // {}) + $imgs)
        | .lastResult = "COMMITTED" | .lastResultAt = $now
        | .history = ((.history // []) + [{at:$now, fromVersion:$from, toVersion:$v,
                                           outcome:"COMMITTED", detail:"ota-apply.sh"}] | .[-20:])
    ' "$STATE" > "$tmp" 2>/dev/null && mv -f "$tmp" "$STATE" || {
        # State trắng/hỏng: dựng mới thay vì bỏ qua (bỏ qua = mất sàn chống hạ cấp)
        jq -n --arg v "$TO_VER" --arg now "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --argjson imgs "$IMG_JSON" \
            '{installedVersion:$v, floorVersion:$v, committedImages:$imgs,
              lastResult:"COMMITTED", lastResultAt:$now, history:[]}' \
            > "$STATE"
    }
    rm -f "$tmp"

    status_set COMMITTED 100 "Cập nhật thành công lên $TO_VER"
    telegram "🖥️ <b>[$HOST]</b>%0A✅ Cập nhật <b>${FROM_VER:-?} → $TO_VER</b> thành công%0A🛡️ Sàn chống hạ cấp: <code>$TO_VER</code>"

    # Dọn: gói + thư mục giải nén. Image cũ đã ghim (nvr-ota-rollback/*) thì GIỮ tới lần cập
    # nhật sau — ổ nhỏ nhưng mất đường lùi thì tệ hơn nhiều.
    rm -rf "$STAGED"
    rm -f "$PLAN"
    log "đã dọn staged/ và apply-plan.json"
    exit 0
fi

# ── Thất bại → rollback ──────────────────────────────────────────────────────
fail "health-check thất bại: $HEALTH_FAIL_REASON"
status_set ROLLING_BACK 95 "Kiểm khoẻ thất bại ($HEALTH_FAIL_REASON) — đang lùi về ${FROM_VER:-bản cũ}" "HEALTH_FAIL"
telegram "🖥️ <b>[$HOST]</b>%0A⚠️ Bản <b>$TO_VER</b> không lên được%0A🔍 <code>$HEALTH_FAIL_REASON</code>%0A↩️ Đang lùi về ${FROM_VER:-bản cũ}…"

MAX_ATTEMPTS=2
attempt=0
while [[ $attempt -lt $MAX_ATTEMPTS ]]; do
    attempt=$((attempt + 1))
    log "rollback lần $attempt/$MAX_ATTEMPTS"

    # Trả tag chuẩn về image cũ đã ghim
    if [[ -f "$ROLLBACK_PLAN" ]]; then
        while IFS=$'\t' read -r tag parked; do
            [[ -n "$tag" ]] || continue
            if docker image inspect "$parked" >/dev/null 2>&1; then
                docker tag "$parked" "$tag" && log "  trả $tag về $parked"
            else
                fail "  image lùi $parked KHÔNG CÒN — bị docker prune xoá?"
            fi
        done < <(jq -r '.images[]? | "\(.tag)\t\(.parkedTag)"' "$ROLLBACK_PLAN")

        # Trả file cấu hình cũ
        while read -r rel; do
            [[ -n "$rel" ]] || continue
            src="$OTA_DIR/rollback/files/$rel"
            [[ -f "$src" ]] && cp -f "$src" "$PROJECT_DIR/$rel" && log "  trả $rel"
        done < <(jq -r '.files[]?' "$ROLLBACK_PLAN")
    else
        fail "không thấy $ROLLBACK_PLAN — rollback chỉ có thể restart lại container"
    fi

    $DC up -d --force-recreate --no-deps "${CONTAINERS[@]}" >/dev/null 2>&1
    $DC up -d nginx >/dev/null 2>&1

    # Health-check bản cũ: chỉ kiểm /api/health, KHÔNG kiểm expectJson version — vì version
    # mong đợi trong plan là của bản MỚI, còn ta đang cố về bản cũ.
    if [[ "$(check_one "http://127.0.0.1:3000/api/health" 200 "{}" 120)" == "ok" ]]; then
        status_set ROLLED_BACK 100 "Đã lùi về ${FROM_VER:-bản cũ}, máy hoạt động lại bình thường" "HEALTH_FAIL"

        # KHÔNG nâng floorVersion. Nếu nâng, bản lỗi vừa rollback sẽ chặn luôn chính bản cũ
        # đang chạy — máy tự khoá mình ra khỏi mọi đường cập nhật, và cách duy nhất để cứu là
        # ra tận nơi.
        STATE="$OTA_DIR/state.json"
        tmp="$STATE.tmp.$$"
        jq --arg v "$TO_VER" --arg now "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg r "$HEALTH_FAIL_REASON" '
            .lastResult = "ROLLED_BACK" | .lastResultAt = $now
            | .history = ((.history // []) + [{at:$now, fromVersion:(.installedVersion // ""),
                          toVersion:$v, outcome:"ROLLED_BACK", detail:$r}] | .[-20:])
        ' "$STATE" > "$tmp" 2>/dev/null && mv -f "$tmp" "$STATE"
        rm -f "$tmp"

        telegram "🖥️ <b>[$HOST]</b>%0A↩️ Đã lùi về <b>${FROM_VER:-bản cũ}</b> thành công%0A✅ Máy đang hoạt động bình thường%0A🔍 Bản $TO_VER lỗi: <code>$HEALTH_FAIL_REASON</code>"
        rm -rf "$STAGED"; rm -f "$PLAN"
        exit 0
    fi
    fail "rollback lần $attempt chưa khoẻ"
done

# ── Rollback cũng thất bại ───────────────────────────────────────────────────
status_set ROLLBACK_FAILED 100 \
    "Không lùi được sau $MAX_ATTEMPTS lần — CẦN CAN THIỆP THỦ CÔNG" "ROLLBACK_FAILED"
telegram "🖥️ <b>[$HOST]</b>%0A🚨 <b>NGHIÊM TRỌNG</b>: cập nhật $TO_VER thất bại VÀ không lùi được sau $MAX_ATTEMPTS lần.%0A⚠️ Máy có thể đang không ghi hình.%0A🔧 Cần vào máy xử lý: <code>docs/ota/runbook-su-co.md</code>"
exit 1

#!/bin/bash
# ============================================================
# NVR Wrapper — Health Check & Auto-Rollback Script
# ============================================================
# Monitors the nvr-wrapper container.
# If it crashes 3+ times in 5 minutes, automatically rolls back
# to the previous known-good image version.
#
# Usage:
#   sudo crontab -e
#   */2 * * * * /path/to/my-nvr/scripts/health-check-rollback.sh >> /var/log/nvr-health.log 2>&1
# ============================================================

set -e

PROJECT_DIR="${HOME}/my-nvr"
ENV_FILE="${PROJECT_DIR}/.env"
CONTAINER="nvr-wrapper"
BACKUP_VERSION_FILE="${PROJECT_DIR}/backend/data/.last_good_version"
CRASH_COUNT_FILE="/tmp/nvr_crash_count"
CRASH_WINDOW=300  # 5 minutes in seconds
MAX_CRASHES=3
LOG_PREFIX="[$(date '+%Y-%m-%d %H:%M:%S')] [Health]"

log() {
    echo "${LOG_PREFIX} $*"
}

# Get current status of container
STATUS=$(docker inspect --format='{{.State.Status}}' "${CONTAINER}" 2>/dev/null || echo "not_found")
RESTART_COUNT=$(docker inspect --format='{{.RestartCount}}' "${CONTAINER}" 2>/dev/null || echo "0")

log "Container: ${CONTAINER} | Status: ${STATUS} | Restarts: ${RESTART_COUNT}"

# ─── Happy Path ────────────────────────────────────────────
if [[ "${STATUS}" == "running" ]]; then
    # Do an actual HTTP health check
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "http://localhost:3000/api/health" || echo "000")
    if [[ "${HTTP_CODE}" == "200" ]]; then
        log "✓ Health check passed (HTTP ${HTTP_CODE})"
        # Save current version as last known good
        source "${ENV_FILE}" 2>/dev/null || true
        echo "${NVR_WRAPPER_VERSION:-unknown}" > "${BACKUP_VERSION_FILE}"
        rm -f "${CRASH_COUNT_FILE}"  # Reset crash counter
        exit 0
    fi
    log "✗ HTTP health check failed (${HTTP_CODE})"
fi

# ─── Container is unhealthy — track crashes ────────────────
NOW=$(date +%s)
if [[ -f "${CRASH_COUNT_FILE}" ]]; then
    LAST_CRASH_TIME=$(head -1 "${CRASH_COUNT_FILE}")
    CRASH_COUNT=$(tail -1 "${CRASH_COUNT_FILE}")
    TIME_DIFF=$(( NOW - LAST_CRASH_TIME ))
    if [[ ${TIME_DIFF} -gt ${CRASH_WINDOW} ]]; then
        # Old crash window expired, reset
        echo "${NOW}" > "${CRASH_COUNT_FILE}"
        echo "1" >> "${CRASH_COUNT_FILE}"
        CRASH_COUNT=1
    else
        # Increment crash count
        CRASH_COUNT=$(( CRASH_COUNT + 1 ))
        echo "${LAST_CRASH_TIME}" > "${CRASH_COUNT_FILE}"
        echo "${CRASH_COUNT}" >> "${CRASH_COUNT_FILE}"
    fi
else
    echo "${NOW}" > "${CRASH_COUNT_FILE}"
    echo "1" >> "${CRASH_COUNT_FILE}"
    CRASH_COUNT=1
fi

log "⚠ Unhealthy crash count: ${CRASH_COUNT}/${MAX_CRASHES}"

# ─── Rollback if crash threshold exceeded ─────────────────
if [[ ${CRASH_COUNT} -ge ${MAX_CRASHES} ]]; then
    log "✗ CRASH THRESHOLD EXCEEDED. Initiating rollback..."
    rm -f "${CRASH_COUNT_FILE}"

    # Read last known good version
    if [[ -f "${BACKUP_VERSION_FILE}" ]]; then
        LAST_GOOD_VERSION=$(cat "${BACKUP_VERSION_FILE}")
        log "Rolling back to version: ${LAST_GOOD_VERSION}"

        # Update .env to previous version
        sed -i "s/^NVR_WRAPPER_VERSION=.*/NVR_WRAPPER_VERSION=${LAST_GOOD_VERSION}/" "${ENV_FILE}"

        # Restart with old version
        cd "${PROJECT_DIR}"
        docker compose pull nvr-wrapper 2>&1 | tail -3
        docker compose up -d nvr-wrapper
        log "✓ Rolled back nvr-wrapper to version ${LAST_GOOD_VERSION}"
    else
        log "No backup version found. Restarting container as-is..."
        docker restart "${CONTAINER}" || true
    fi
fi

exit 0

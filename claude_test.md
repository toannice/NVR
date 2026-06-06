# NVR E2E Test Suite

All tests live in `/home/toan/e2e-generator/`. Built with Playwright (headless Chromium). No Claude API key needed.

---

## Prerequisites

```bash
# Session auth must be fresh before running tests
cd /home/toan/e2e-generator
node setup.js 0-1-2          # draws pattern, saves auth.json
```

If `auth.json` already exists and is recent (< 24h), skip this step.

---

## Run all tests

```bash
cd /home/toan/e2e-generator
node_modules/.bin/playwright test --reporter=list
```

## Run one file

```bash
node_modules/.bin/playwright test nvr.spec.ts --reporter=list
node_modules/.bin/playwright test forgot-password.spec.ts --reporter=list
```

## Run headed (watch browser)

```bash
node_modules/.bin/playwright test --headed --reporter=list
```

---

## Test files

### `nvr.spec.ts` — Core UI (10 tests)

| Group | Test | What it checks |
|---|---|---|
| Tab navigation | all tabs load without lock screen | Navigating Xem lại / Tải xuống / Sự kiện AI / Cấu hình doesn't trigger pattern lock |
| Tab navigation | Live View always works | Can always return to Trực tiếp |
| Playback | camera list appears | At least one camera in Xem lại |
| Playback | selecting camera shows timeline | Timeline appears after camera click |
| Playback | timeline click doesn't crash | Scrubber click handled without error |
| Camera config | config page loads | Camera list visible in Cấu hình |
| Camera config | Add camera modal opens | Thêm mới → Thêm thủ công → form fields visible |
| Camera config | Add camera form accepts input | Can fill ONVIF form without submitting |
| Authentication | logout shows lock screen | Đăng xuất → pattern canvas appears |
| Authentication | session persists after reload | Page refresh keeps user logged in |

### `forgot-password.spec.ts` — Reset Pattern (5 tests)

| # | Test | What it checks |
|---|---|---|
| 1 | Full reset flow | Quên mật khẩu → email → OTP from DB → draw 0-3-6 → auto login |
| 2 | Old pattern rejected, new accepted | After reset: 0-1-2 fails (canvas stays), 0-3-6 unlocks |
| 3 | Restore original pattern | Resets back to 0-1-2, restores real gmail_hash |
| 4 | Wrong OTP shows error | '000000' rejected, draw screen doesn't appear |
| 5 | Unregistered email shows error | Wrong email shows error, OTP step never appears |

---

## How the forgot-password tests work (no real email)

`E2E_TEST_MODE=true` in `.env` makes the backend:
1. **Skip SMTP** — OTP is written to SQLite but no email is sent
2. **`/api/dev/inject-otp`** — dev-only endpoint that updates `gmail_hash` in `lock_config` to accept the test email `e2e@test.local`

The test reads the OTP directly from the SQLite database at `/home/toan/my-nvr/data/nvr.db`.

After the tests, `gmail_hash` is restored to the real email (`nvr.bkav@gmail.com`).

---

## Key selectors used

| UI element | Selector |
|---|---|
| Pattern lock canvas | `canvas.touch-none` |
| Nav tab "Cấu hình" | `getByRole('button', { name: 'Cấu hình', exact: true }).first()` |
| Playback timeline | `[title="Lăn chuột để Zoom, Kéo để di chuyển"]` |
| Add camera (step 1) | `getByRole('button', { name: /Thêm mới/i })` |
| Add camera (step 2) | `getByRole('button', { name: /Thêm thủ công/i })` |
| IP field | `getByPlaceholder('192.168.1.xxx')` |
| Reset email field | `getByPlaceholder('Nhập Gmail đã đăng ký')` |
| OTP field | `getByPlaceholder('Nhập mã 6 số từ Gmail')` |

---

## Known behaviours

- **Logout confirm dialog** — handled with `page.once('dialog', d => d.accept())`
- **Timeline click** — needs `{ force: true }` to bypass pointer-events overlay on inner segments
- **Error feedback on verify** — in verify mode, wrong pattern shows a shake animation only (no visible error text). Check canvas is still visible instead.
- **Pattern lock canvas vs live-view canvas** — live-view canvases don't have `touch-none` class. Always use `canvas.touch-none` for the lock.

---

## Correct way to wait after a wrong pattern (instead of `waitForTimeout`)

`PatternLockScreen.tsx` wraps the canvas in a div that gets `animate-shake` class when the pattern is wrong. The disabled period is 1800ms (hardcoded in `showError(durationMs = 1800)`). After 1800ms the class is removed and the canvas re-enables.

**Wrong approach (fragile):**
```typescript
await page.waitForTimeout(2000); // magic number — breaks if durationMs changes
```

**Correct approach:**
```typescript
// 1. Confirm the wrong pattern was actually recognized (shake starts)
await expect(page.locator('div.animate-shake')).toBeVisible({ timeout: 2000 });
// 2. Wait for the disabled period to end (shake stops, canvas re-enabled)
await expect(page.locator('div.animate-shake')).not.toBeVisible({ timeout: 5000 });
```

This adapts automatically if the disabled duration ever changes in the frontend.

---

## Stateful tests — recovery guide

Tests 1–3 in `forgot-password.spec.ts` are sequential and share server state. If a test crashes mid-way, the pattern on the server may be left in an unknown state, which breaks subsequent runs.

### How to tell what state the server is in

```bash
# Try setup.js with each known pattern — whichever succeeds tells you the current state
node setup.js 0-1-2   # original pattern
# or
node setup.js 0-3-6   # post-reset pattern (after test 1 ran but test 3 did not)
```

### Recovery steps

**Scenario A — test 1 crashed after changing pattern to `0-3-6`:**
```bash
# setup.js with the NEW pattern to get a valid auth session
node setup.js 0-3-6

# Then run test 3 alone (it resets back to 0-1-2 and restores gmail_hash)
node_modules/.bin/playwright test forgot-password.spec.ts --grep "restore"

# Verify by re-running setup with original pattern
node setup.js 0-1-2
```

**Scenario B — test 3 crashed, pattern is back to `0-1-2` but `gmail_hash` is still the test email:**
```bash
# gmail_hash is pointing at e2e@test.local — real reset requests won't work
# Fix by calling inject-otp with the real email directly:
curl -k -X POST https://192.168.2.107/api/dev/inject-otp \
  -H 'Content-Type: application/json' \
  -d '{"email":"nvr.bkav@gmail.com","code":"000000"}'

# Then verify normal pattern still works
node setup.js 0-1-2
```

**Scenario C — fully lost, don't know what pattern is set:**
```bash
# Check if lockout is in effect first (do not burn attempts)
curl -k https://192.168.2.107/api/health

# Try patterns via setup.js (it does one attempt per run, no lockout risk from setup)
node setup.js 0-1-2
# if that fails:
node setup.js 0-3-6
# if both fail — use the browser manually to reset via "Quên mật khẩu?" with nvr.bkav@gmail.com
```

**Always run the full suite after recovery to confirm green:**
```bash
node_modules/.bin/playwright test --reporter=list
```

---

## Frontend fix applied (003 branch)

`App.tsx` now checks `/api/camera-status` on mount. If the session cookie is valid → `isSessionUnlocked = true` immediately. This means:
- Page refresh no longer forces re-authentication
- `auth.json` from `setup.js` works across full page loads in tests

---

## Backend changes for testing

| File | Change |
|---|---|
| `EmailService.kt` | Skip SMTP when `E2E_TEST_MODE=true`; OTP still written to DB |
| `routes/DevRouting.kt` | `POST /api/dev/inject-otp` — writes OTP + updates gmail_hash (disabled when `E2E_TEST_MODE != true`) |
| `plugins/Routing.kt` | Registers `devRouting()` |
| `docker-compose.yml` | Passes `E2E_TEST_MODE` env var to nvr-wrapper container |
| `.env` | `E2E_TEST_MODE=true` |

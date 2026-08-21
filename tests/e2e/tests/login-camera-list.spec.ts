import { test, expect, Page } from '@playwright/test';

/**
 * Nghiệm thu bug "đăng nhập lần đầu không thấy camera nào, phải F5".
 *
 * Bug: App.tsx nạp danh sách bằng useEffect(..., []) nên chỉ chạy lúc mount, khi phiên
 * chưa mở khoá → /api/cameras trả 401 → danh sách rỗng. handleUnlock không nạp lại nên
 * màn Trực tiếp trống cho tới khi người dùng tự F5.
 *
 * Test này KHÔNG đăng nhập bằng API như functional.spec.ts — làm vậy là bỏ qua đúng cái
 * đang cần kiểm. Nó vẽ pattern trên canvas như người dùng thật, rồi khẳng định danh sách
 * hiện ra mà KHÔNG reload trang.
 *
 * Chạy:  npx playwright test tests/login-camera-list.spec.ts --project=chromium
 *        NVR_PATTERN=0124 BASE=https://192.168.1.29 npx playwright test ...
 */

const rawPattern = process.env.NVR_PATTERN || '0124';
const DOTS = (rawPattern.includes('-') ? rawPattern.split('-') : rawPattern.split('')).map(Number);

/** Vẽ pattern trên canvas: mousedown ở dot đầu, rê qua từng dot, mouseup. */
async function drawPattern(page: Page, dots: number[]) {
    const canvas = page.locator('canvas').first();
    await expect(canvas).toBeVisible({ timeout: 10_000 });
    const box = await canvas.boundingBox();
    if (!box) throw new Error('Không lấy được toạ độ canvas pattern');

    // Khớp getDotCenters() trong PatternLock.tsx: pad = size*0.13, step = (size - pad*2)/2
    const size = Math.min(box.width, box.height);
    const pad = size * 0.13;
    const step = (size - pad * 2) / 2;
    const centerOf = (i: number) => ({
        x: box.x + pad + (i % 3) * step,
        y: box.y + pad + Math.floor(i / 3) * step,
    });

    const first = centerOf(dots[0]);
    await page.mouse.move(first.x, first.y);
    await page.mouse.down();
    for (const d of dots.slice(1)) {
        const p = centerOf(d);
        await page.mouse.move(p.x, p.y, { steps: 8 }); // rê từ từ để canvas bắt được từng dot
    }
    await page.mouse.up();
}

test('Đăng nhập lần đầu: danh sách camera hiện ngay, không cần F5', async ({ browser }) => {
    // Context sạch = không cookie phiên, đúng trạng thái "mở máy đăng nhập lần đầu"
    const context = await browser.newContext({ ignoreHTTPSErrors: true });
    const page = await context.newPage();

    const consoleLines: string[] = [];
    page.on('console', (m) => consoleLines.push(`${m.type()}: ${m.text()}`));

    // Phiên sạch: app hiện thẳng màn vẽ pattern, chưa có shell nào.
    await page.goto('/');

    // Đợi qua cửa sổ của bộ chặn 401 trong App.tsx: nó thấy /api/camera-status trả 401
    // lúc mount, hẹn 1.5 giây sau kiểm lại, vẫn 401 thì gọi handleLogout -> POST
    // /api/lock/logout. Máy tự động vẽ pattern nhanh hơn mốc đó nên lệnh logout muộn
    // này rơi vào SAU khi verify thành công và giết luôn phiên vừa tạo — người thật vẽ
    // tay không kịp nhanh vậy nên không dính. Đợi cho nó bắn xong rồi mới vẽ.
    await page.waitForTimeout(3_000);

    await drawPattern(page, DOTS);

    // ── Khẳng định chính ────────────────────────────────────────────────────────
    // KHÔNG reload ở bất kỳ đâu sau đây — đó chính là điều kiện của bug: trước khi sửa,
    // chỗ này đứng im ở "Không tìm thấy camera nào" cho tới khi người dùng tự F5.
    // Bám bộ đếm ở chân màn Trực tiếp, KHÔNG bám tên tab: sau khi mở khoá app có thể
    // đang ở bố cục toàn màn hình, lúc đó thanh tab không nằm trong DOM.
    const counter = page.getByText(/Hiển thị: [1-9]\d* camera/);
    await expect(counter).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('Không tìm thấy camera nào')).toBeHidden();

    // ── Đối chiếu với API ───────────────────────────────────────────────────────
    // Hỏi API SAU khi UI đã hiện: cookie phiên từ /api/lock/verify mất một nhịp mới vào
    // tới cookie jar dùng chung, hỏi ngay lúc verify vừa trả 200 thì còn nhận 401 (đo
    // được: 401 ở +6.3s, 200 ở +7.4s). Đó là độ trễ của test, không phải lỗi sản phẩm.
    const apiRes = await page.context().request.get('/api/cameras');
    expect(apiRes.ok(), 'phiên chưa mở khoá — pattern sai?').toBeTruthy();
    const apiCameras = await apiRes.json();
    expect(apiCameras.length, 'máy này phải có sẵn camera thì test mới có nghĩa').toBeGreaterThan(0);
    await expect(page.getByText(`Hiển thị: ${apiCameras.length} camera`)).toBeVisible();

    console.log(`API: ${apiCameras.length} camera · console: ${consoleLines.filter(l => l.includes('FETCH_LIST')).join(' | ') || '(không có dòng FETCH_LIST)'}`);
    await context.close();
});

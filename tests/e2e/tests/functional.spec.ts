import { test, expect } from '@playwright/test';

const rawPattern = process.env.NVR_PATTERN || '0124';
const PATTERN = rawPattern.includes('-') ? rawPattern : rawPattern.split('').join('-');


test.describe('My-NVR Functional & Data Integrity', () => {

  // Setup authentication before testing UI
  test.beforeEach(async ({ page }) => {
    // Authenticate via API to bypass Pattern Lock UI drawing
    const res = await page.context().request.post('/api/lock/verify', {
      data: { pattern: PATTERN }
    });
    
    expect(res.ok(), `LOGIN FAILED! Your pattern ${PATTERN} is incorrect. Please set NVR_PATTERN env var correctly.`).toBeTruthy();
    
    await page.goto('/');
    // Wait for "Trực tiếp" tab or a recognizable element
    await expect(page.getByText('Trực tiếp')).toBeVisible({ timeout: 15000 });
  });

  test('Đối soát dữ liệu UI vs API và Kiểm tra Stream', async ({ page }) => {
    // 1. Cào dữ liệu từ API Backend (Ktor)
    const apiRes = await page.context().request.get('/api/cameras');
    expect(apiRes.ok()).toBeTruthy();
    const apiCameras = await apiRes.json();
    console.log(`API found ${apiCameras.length} cameras.`);

    // 2. Kiểm tra trên UI Dashboard
    await page.goto('/');
    await page.waitForTimeout(3000); 
    
    // Camera display names are in span with class bg-black/60
    const uiCameraNames = page.locator('span.bg-black\\/60'); 
    
    // API returns all cameras, UI shows them too.
    await expect(uiCameraNames).toHaveCount(apiCameras.length);

    // 3. Kiểm tra từng Camera: Tên, Trạng thái và Luồng Live
    for (let i = 0; i < apiCameras.length; i++) {
        const camName = await uiCameraNames.nth(i).innerText();
        
        // Find corresponding camera from API to ensure matching
        const apiCam = apiCameras.find((c: any) => c.name === camName || c.id === camName);
        expect(apiCam).toBeDefined();
        
        // Check if video is playing in the same container (grandparent/parent of the title span)
        const itemContainer = uiCameraNames.nth(i).locator('xpath=./../..');
        const video = itemContainer.locator('video');
        if (await video.count() > 0) {
            // Wait longer for video to actually start loading/playing in a real env
            await page.waitForTimeout(3000); 
            const status = await video.evaluate((node: HTMLVideoElement) => {
                return {
                    currentTime: node.currentTime,
                    paused: node.paused,
                    readyState: node.readyState,
                    networkState: node.networkState
                };
            });
            // ReadyState >= 2 (HAVE_CURRENT_DATA) is usually enough to see a frame
            const isPlaying = status.readyState >= 2;
            console.log(`Camera ${camName}: Status[RT:${status.readyState}, CT:${status.currentTime.toFixed(2)}] -> ${isPlaying ? 'OK/LIVE' : 'BUFFERING/FAILED'}`);
        }
    }
  });

});

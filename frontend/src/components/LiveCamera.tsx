import { useRef, useEffect, useState, useCallback } from 'react';
import { t } from '../i18n';

// go2rtc serves fMP4 over plain HTTP (chunked transfer encoding).
// The codec is declared in the Content-Type header as "video/mp4; codecs=avc1.640028".
// This is the same stream Frigate's native UI uses via MSE (Media Source Extensions).
// We try to read that codec from the response; fall back to a known-good default.
const FALLBACK_MIME = 'video/mp4; codecs="avc1.640028"';

// Remove buffered segments older than this to avoid unbounded memory use.
const BUFFER_CLEANUP_BEHIND_S = 30;

/** Camera HEVC mà trình duyệt (Chrome/Firefox) không giải mã được qua MSE — lùi sang transcode. */
class HevcUnsupportedError extends Error {}

const LiveCamera = ({
    name,
    className,
    onClick,
}: {
    name: string;
    className?: string;
    onClick?: () => void;
    isGrid?: boolean;
    isFullscreen?: boolean;
    initDelay?: number;
}) => {
    const videoRef = useRef<HTMLVideoElement>(null);
    const imgRef = useRef<HTMLImageElement>(null);
    const [mode, setMode] = useState<'mse' | 'snapshot' | 'error'>('mse');
    const [mseKey, setMseKey] = useState(0);
    // go2rtc stream tên phát thật — mặc định trùng `name`, đổi sang `${name}_h264` một lần nếu
    // camera là HEVC và trình duyệt không giải mã được qua MSE (xem HevcUnsupportedError).
    const [streamName, setStreamName] = useState(name);
    const hevcFallbackTriedRef = useRef(false);

    // Dò codec luồng chính TRƯỚC khi mở stream.mp4 — không được mở rồi bỏ dở khi là HEVC
    // (đã gây go2rtc panic thật ngoài đời với camera P200-0802N: mở fetch, đọc Content-Type,
    // ném lỗi ngay mà KHÔNG đọc/hủy body — go2rtc đã đẩy chunk MP4 vào response (push, không chờ
    // client đọc); effect cleanup abort() kết nối giữa chừng trúng đúng race "ghi tiếp vào response
    // đã đóng" trong go2rtc 1.9.9 (vendor trong Frigate 0.16.3, không vá được) →
    // `panic: runtime error: invalid memory address or nil pointer dereference`, chết cả tiến trình
    // go2rtc — mất live view + ghi hình của MỌI camera, không riêng camera vừa đổi). Endpoint dò
    // (`/api/frigate/stream-codec/`) chỉ đọc SDP có sẵn từ go2rtc, không tạo consumer nào — an toàn.
    useEffect(() => {
        let cancelled = false;
        hevcFallbackTriedRef.current = false;
        setStreamName(name);

        (async () => {
            try {
                const r = await fetch(`/api/frigate/stream-codec/${name}`);
                if (!r.ok || cancelled) return;
                const data = await r.json();
                if (cancelled || data?.codec !== 'h265') return;
                if (hevcFallbackTriedRef.current) return;
                // Không tin MediaSource.isTypeSupported('...hvc1/hev1...') để quyết định phát HEVC
                // trực tiếp: hàm này chỉ xác nhận trình duyệt HIỂU codec string, không đảm bảo giải mã
                // đúng bitstream thật của camera. Test thật với P200-0802N: isTypeSupported() trả true
                // nhưng hình bị vỡ nửa khung + đơ giữa 2 keyframe (giải mã lỗi phần cứng). Luôn lùi sang
                // transcode H.264 khi nguồn là HEVC — không còn nhánh "thử phát trực tiếp".
                hevcFallbackTriedRef.current = true;
                console.warn(`[LiveCamera] ${name} is HEVC — always using transcode fallback (native HEVC-MSE decode is unreliable on this platform).`);
                const fr = await fetch(`/api/frigate/live-fallback/${name}`);
                if (!fr.ok || cancelled) return;
                const fd = await fr.json();
                if (!cancelled && fd?.stream) setStreamName(fd.stream);
            } catch (e) {
                // Dò thất bại (mạng, go2rtc chưa sẵn sàng...) → thử phát trực tiếp như bình thường;
                // nhánh Content-Type bên dưới vẫn còn làm lưới an toàn cuối, giờ đã hủy body đúng cách.
                console.warn(`[LiveCamera] ${name}: codec probe failed, will try direct play`, e);
            }
        })();

        return () => { cancelled = true; };
    }, [name]);

    // ── MSE streaming ──────────────────────────────────────────────────────────
    useEffect(() => {
        if (mode !== 'mse') return;
        const video = videoRef.current;
        if (!video || !name || !streamName) return;

        let cancelled = false;
        let mediaSource: MediaSource | null = null;
        let sourceBuffer: SourceBuffer | null = null;
        let abortCtl: AbortController | null = null;
        const chunkQueue: Uint8Array[] = [];
        let draining = false;

        const drainQueue = () => {
            if (!sourceBuffer || draining || chunkQueue.length === 0) return;
            if (sourceBuffer.updating) return;
            draining = true;
            const chunk = chunkQueue.shift()!;
            try {
                // Copy to a fresh Uint8Array to satisfy appendBuffer's strict typing
                sourceBuffer.appendBuffer(new Uint8Array(chunk));
            } catch (e) {
                // e.g. QuotaExceededError – cleanup old segments
                cleanupBuffer();
                draining = false;
            }
        };

        const cleanupBuffer = () => {
            if (!sourceBuffer || !video) return;
            const behind = video.currentTime - BUFFER_CLEANUP_BEHIND_S;
            if (behind > 0 && sourceBuffer.buffered.length > 0) {
                const start = sourceBuffer.buffered.start(0);
                if (behind > start) {
                    try { sourceBuffer.remove(start, behind); } catch (_) { /* ignore */ }
                }
            }
        };

        mediaSource = new MediaSource();
        const objectUrl = URL.createObjectURL(mediaSource);
        video.src = objectUrl;

        mediaSource.addEventListener('sourceopen', async () => {
            if (cancelled || !mediaSource) return;

            const streamUrl = `/api/frigate/go2rtc/stream.mp4?src=${streamName}`;

            try {
                // Start the fetch — go2rtc opens the RTSP pipeline on first request.
                abortCtl = new AbortController();
                const response = await fetch(streamUrl, { signal: abortCtl.signal });

                if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`);

                // Determine MIME type from server (go2rtc declares the codec in Content-Type).
                const serverMime = response.headers.get('content-type') ?? '';
                const isHevc = /hvc1|hev1|h\.?265|hevc/i.test(serverMime);

                if (!MediaSource.isTypeSupported(serverMime) && (isHevc || !MediaSource.isTypeSupported(FALLBACK_MIME))) {
                    // Lưới an toàn cuối — bình thường codec probe ở effect trên đã chặn HEVC từ
                    // trước khi tới đây. Nếu vẫn rơi vào đây (probe lỗi, camera đổi codec giữa lúc
                    // probe và lúc mở stream...), PHẢI hủy body tường minh trước khi bỏ cuộc — không
                    // được bỏ dở kết nối đang có go2rtc ghi dở dang vào (xem lý do ở effect dò codec
                    // phía trên, đã gây go2rtc panic thật với camera P200-0802N).
                    try { await response.body.cancel(); } catch { /* đã đóng thì thôi */ }
                    if (isHevc) throw new HevcUnsupportedError();
                    throw new Error(`Unsupported MIME: ${serverMime || FALLBACK_MIME}`);
                }
                const mimeType = MediaSource.isTypeSupported(serverMime) ? serverMime : FALLBACK_MIME;

                sourceBuffer = mediaSource.addSourceBuffer(mimeType);
                sourceBuffer.mode = 'segments';
                sourceBuffer.addEventListener('updateend', () => {
                    draining = false;
                    cleanupBuffer();
                    drainQueue();
                });

                // Stream body chunks into the queue.
                const reader = response.body.getReader();
                while (!cancelled) {
                    const { done, value } = await reader.read();
                    if (done || cancelled) break;
                    chunkQueue.push(value);
                    drainQueue();
                }
                
                if (!cancelled) {
                    console.warn(`[LiveCamera] Stream ended naturally for ${name}, trying to reconnect...`);
                    setTimeout(() => {
                        if (!cancelled) setMseKey(k => k + 1);
                    }, 3000);
                }
            } catch (e: any) {
                if (cancelled || e?.name === 'AbortError') return;

                if (e instanceof HevcUnsupportedError && !hevcFallbackTriedRef.current) {
                    hevcFallbackTriedRef.current = true;
                    console.warn(`[LiveCamera] ${name} is HEVC, trình duyệt không phát trực tiếp được — lùi sang luồng transcode...`);
                    try {
                        const r = await fetch(`/api/frigate/live-fallback/${name}`);
                        if (r.ok) {
                            const data = await r.json();
                            if (!cancelled && data?.stream) {
                                setStreamName(data.stream);
                                return; // effect re-runs with the new stream name via the streamName dep
                            }
                        }
                        console.warn(`[LiveCamera] ${name}: không đăng ký được luồng transcode H.264`);
                    } catch (fallbackErr) {
                        console.warn(`[LiveCamera] ${name}: lỗi gọi luồng transcode`, fallbackErr);
                    }
                    // Không lấy được luồng transcode → rơi xuống nhánh reconnect chung bên dưới.
                }

                console.warn(`[LiveCamera] MSE failed for ${name}, reconnecting in 3s...`, e);
                setTimeout(() => {
                    if (!cancelled) setMseKey(k => k + 1);
                }, 3000);
            }
        });

        mediaSource.addEventListener('sourceclose', () => {
            URL.revokeObjectURL(objectUrl);
        });

        video.play().catch(() => { /* autoplay policy; browser will play on user interaction */ });

        // When the page enters back-forward cache or gets frozen, JS is suspended WITHOUT
        // unmounting: the fetch stays open but unread, leaving a zombie connection that
        // counts against Chrome's ~6-connections-per-host limit and starves every future
        // stream request (all tiles stay black with no error). Close the stream up front.
        const onPageHide = () => abortCtl?.abort();
        window.addEventListener('pagehide', onPageHide);
        document.addEventListener('freeze', onPageHide);

        return () => {
            cancelled = true;
            window.removeEventListener('pagehide', onPageHide);
            document.removeEventListener('freeze', onPageHide);
            abortCtl?.abort();
            URL.revokeObjectURL(objectUrl);
            if (video) {
                video.removeAttribute('src');
                video.load();
            }
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [name, streamName, mode, mseKey]);

    useEffect(() => {
        let lastVisibleTime = Date.now();

        const handleVisibilityChange = () => {
            if (document.visibilityState === 'visible' && mode === 'mse') {
                const now = Date.now();
                // If hidden for more than 10 seconds, force a fresh connection
                if (now - lastVisibleTime > 10000) {
                    console.log(`[LiveCamera] Tab visible again after ${Math.round((now - lastVisibleTime)/1000)}s gap, reloading stream for ${name}`);
                    setMseKey(k => k + 1);
                }
            } else if (document.visibilityState === 'hidden') {
                lastVisibleTime = Date.now();
            }
        };

        let lastTime = -1;
        let lastProgressAt = Date.now();
        const checkLag = () => {
            const video = videoRef.current;
            if (!video || mode !== 'mse') return;

            // Watchdog: a silently dead pipeline (decoder stall, starved/queued fetch,
            // zombie connection) shows up as currentTime frozen while the tab is visible.
            // None of the other reconnect paths catch that, so force a fresh connection.
            if (video.currentTime !== lastTime) {
                lastTime = video.currentTime;
                lastProgressAt = Date.now();
            } else if (document.visibilityState === 'visible' && Date.now() - lastProgressAt > 10_000) {
                lastProgressAt = Date.now();
                console.warn(`[LiveCamera] No playback progress >10s for ${name}, forcing reconnect`);
                setMseKey(k => k + 1);
                return;
            }
            if (video.paused) return;

            if (video.buffered.length > 0) {
                const latestBuffer = video.buffered.end(video.buffered.length - 1);
                const lag = latestBuffer - video.currentTime;
                // If we are more than 1.5s behind the live edge, jump forward
                if (lag > 1.5) {
                    video.currentTime = latestBuffer - 0.1;
                }
            }
        };

        const interval = setInterval(checkLag, 1000);
        
        // Auto-reload the camera every 5 minutes to guarantee true real-time
        const reloadInterval = setInterval(() => {
            if (document.visibilityState === 'visible' && mode === 'mse') {
                console.log(`[LiveCamera] Auto-reloading stream for ${name} to maintain real-time sync`);
                setMseKey(k => k + 1);
            }
        }, 5 * 60 * 1000); // 5 minutes

        // Coming back from back-forward cache / frozen state: the stream was aborted on
        // pagehide (or died while suspended) — always start a fresh connection.
        const handlePageShow = (e: PageTransitionEvent) => {
            if (e.persisted && mode === 'mse') setMseKey(k => k + 1);
        };
        const handleResume = () => {
            if (mode === 'mse') setMseKey(k => k + 1);
        };

        document.addEventListener('visibilitychange', handleVisibilityChange);
        window.addEventListener('pageshow', handlePageShow);
        document.addEventListener('resume', handleResume);

        return () => {
            clearInterval(interval);
            clearInterval(reloadInterval);
            document.removeEventListener('visibilitychange', handleVisibilityChange);
            window.removeEventListener('pageshow', handlePageShow);
            document.removeEventListener('resume', handleResume);
        };
    }, [mode, name]);

    // ── Snapshot fallback (500 ms refresh) ────────────────────────────────────
    const updateSnapshot = useCallback(() => {
        if (!imgRef.current || !name) return;
        imgRef.current.src = `/api/frigate/api/${name}/latest.jpg?t=${Date.now()}`;
    }, [name]);

    useEffect(() => {
        if (mode !== 'snapshot') return;
        updateSnapshot();
        const timer = setInterval(updateSnapshot, 500);
        return () => clearInterval(timer);
    }, [mode, updateSnapshot]);

    // ── Render ─────────────────────────────────────────────────────────────────
    return (
        <div className="relative w-full h-full bg-gray-950" onClick={onClick}>
            {mode === 'mse' && (
                <video
                    key={mseKey}
                    ref={videoRef}
                    className={className || "w-full h-full object-contain"}
                    autoPlay playsInline muted
                />
            )}
            {mode === 'snapshot' && (
                <div className="relative w-full h-full">
                    <img
                        ref={imgRef}
                        className={className || "w-full h-full object-contain"}
                        alt={name}
                        onError={() => setMode('error')}
                    />
                    <div className="absolute bottom-1 right-1 bg-black/60 text-yellow-400 text-xs px-1 rounded font-mono">
                        📷 Live
                    </div>
                </div>
            )}
            {mode === 'error' && (
                <div className="w-full h-full flex flex-col items-center justify-center bg-gray-950 text-gray-400 text-xs gap-2">
                    <span className="text-2xl">📡</span>
                    <span>{t("Không thể tải luồng video")}</span>
                    <button
                        onClick={(e) => {
                            e.stopPropagation();
                            setMode('mse');
                            setMseKey(k => k + 1);
                        }}
                        className="mt-1 px-3 py-1 text-xs bg-blue-700 text-white rounded hover:bg-blue-600"
                    >{t("Thử lại")}</button>
                </div>
            )}
        </div>
    );
};

export default LiveCamera;

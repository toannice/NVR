import { useRef, useEffect, useState, useCallback } from 'react';

// go2rtc serves fMP4 over plain HTTP (chunked transfer encoding).
// The codec is declared in the Content-Type header as "video/mp4; codecs=avc1.640028".
// This is the same stream Frigate's native UI uses via MSE (Media Source Extensions).
// We try to read that codec from the response; fall back to a known-good default.
const FALLBACK_MIME = 'video/mp4; codecs="avc1.640028"';

// Remove buffered segments older than this to avoid unbounded memory use.
const BUFFER_CLEANUP_BEHIND_S = 30;

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

    // ── MSE streaming ──────────────────────────────────────────────────────────
    useEffect(() => {
        if (mode !== 'mse') return;
        const video = videoRef.current;
        if (!video || !name) return;

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

            const streamUrl = `/api/frigate/go2rtc/stream.mp4?src=${name}`;

            try {
                // Start the fetch — go2rtc opens the RTSP pipeline on first request.
                abortCtl = new AbortController();
                const response = await fetch(streamUrl, { signal: abortCtl.signal });

                if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`);

                // Determine MIME type from server (go2rtc declares the codec in Content-Type).
                const serverMime = response.headers.get('content-type') ?? '';
                const mimeType = MediaSource.isTypeSupported(serverMime)
                    ? serverMime
                    : FALLBACK_MIME;

                if (!MediaSource.isTypeSupported(mimeType)) {
                    throw new Error(`Unsupported MIME: ${mimeType}`);
                }

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

        return () => {
            cancelled = true;
            abortCtl?.abort();
            URL.revokeObjectURL(objectUrl);
            if (video) {
                video.removeAttribute('src');
                video.load();
            }
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [name, mode, mseKey]);

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

        const checkLag = () => {
            const video = videoRef.current;
            if (!video || mode !== 'mse' || video.paused) return;
            
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

        document.addEventListener('visibilitychange', handleVisibilityChange);
        
        return () => {
            clearInterval(interval);
            clearInterval(reloadInterval);
            document.removeEventListener('visibilitychange', handleVisibilityChange);
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
        const t = setInterval(updateSnapshot, 500);
        return () => clearInterval(t);
    }, [mode, updateSnapshot]);

    // ── Render ─────────────────────────────────────────────────────────────────
    return (
        <div className="relative w-full h-full bg-gray-950" onClick={onClick}>
            {mode === 'mse' && (
                <video
                    key={mseKey}
                    ref={videoRef}
                    className={`w-full h-full object-contain ${className ?? ''}`}
                    autoPlay playsInline muted
                />
            )}
            {mode === 'snapshot' && (
                <div className="relative w-full h-full">
                    <img
                        ref={imgRef}
                        className={`w-full h-full object-contain ${className ?? ''}`}
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
                    <span>Không thể tải luồng video</span>
                    <button
                        onClick={(e) => {
                            e.stopPropagation();
                            setMode('mse');
                            setMseKey(k => k + 1);
                        }}
                        className="mt-1 px-3 py-1 text-xs bg-blue-700 text-white rounded hover:bg-blue-600"
                    >
                        Thử lại
                    </button>
                </div>
            )}
        </div>
    );
};

export default LiveCamera;

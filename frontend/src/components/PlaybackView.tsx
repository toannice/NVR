import { useState, useEffect, useRef } from 'react'
import Hls from 'hls.js'
import { Icon } from './Icon'
import { PlaybackSidebar, PlaybackRightPanel } from './PlaybackPanels'

// --- Moved HLS and Video State into PlaybackView to fix tab-switching black screen --- //
export const PlaybackView = ({
  cameras, playbackCam, setPlaybackCam, timelineDate, setTimelineDate,
  recordings, isLoadingRecordings, playbackStart, showExport, setShowExport, handleExport: _handleExport, addToast
}: any) => {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [videoUrl, setVideoUrl] = useState('');
  const [hlsError, setHlsError] = useState('');
  console.log("playbackStart", playbackStart);
  // Zoom & Pan State
  const [zoomLevel, setZoomLevel] = useState(24); // Hours visible (24 = full day, 1 = max zoom)
  const timelineRef = useRef<HTMLDivElement>(null);
  const isDragging = useRef(false);
  const dragStartX = useRef(0);
  const dragStartTime = useRef(0); // Store time when drag starts
  const hasMoved = useRef(false); // Track if drag actually moved significantly
  const [playbackRate, setPlaybackRate] = useState(1);
  const [isPlaying, setIsPlaying] = useState(false);
  const [vodWindow, setVodWindow] = useState<{ start: number, end: number } | null>(null);

  // Feature 5: Red playhead indicator - track current playback time
  const [currentPlaybackTime, setCurrentPlaybackTime] = useState<number>(0); // Wall-clock time in seconds

  // Update playback time indicator
  useEffect(() => {
    const updatePlaybackTime = () => {
      // Don't update time from video while dragging to avoid jitter/fighting
      if (isDragging.current) return;

      if (videoRef.current && vodWindow && !isNaN(videoRef.current.currentTime)) {
        // Convert video player time to wall-clock time using gap-aware logic
        const wallTime = getWallClockFromPlayerTime(videoRef.current.currentTime);
        setCurrentPlaybackTime(wallTime);
      }
    };

    const interval = setInterval(updatePlaybackTime, 100); // Update 10 times per second
    return () => clearInterval(interval);
  }, [vodWindow, recordings]);

  // Download functionality moved to ExportModal (opened via handleExport prop)

  // Handle camera selection with Toggle logic
  const handleCameraSelect = (camName: string) => {
    if (playbackCam === camName) {
      setPlaybackCam(''); // Deselect
      setVideoUrl(''); // Clear video
    } else {
      setPlaybackCam(camName);
    }
  };

  // Ensure vodWindow is set when camera or date changes
  useEffect(() => {
    setVodWindow(null); // Reset on cam/date change
  }, [playbackCam, timelineDate]);

  // Auto-play / Construct VOD URL
  useEffect(() => {
    if (playbackCam && timelineDate) {
      if (!vodWindow) {
        // Compute default window (last 2 hours of day or up to now) and trigger re-run.
        // Do NOT set videoUrl here — setting both in one run causes a double HLS load:
        // setVodWindow triggers this effect again, producing a second setVideoUrl with a
        // different ?t= value, which destroys+recreates HLS before the first load completes.
        const dayEnd = Math.floor(new Date(timelineDate + 'T23:59:59').getTime() / 1000);
        const now = Math.floor(Date.now() / 1000);
        const end = Math.min(dayEnd, now);
        const start = end - 7200;
        setVodWindow({ start, end });
        return;
      }

      // Use vodWindow.start/end as cache key — same window = same URL = HLS not restarted.
      const url = `/api/frigate/vod/${playbackCam}/start/${vodWindow.start}/end/${vodWindow.end}/index.m3u8`;
      setVideoUrl(`${url}?t=${vodWindow.start}_${vodWindow.end}`);
    } else {
      setVideoUrl('');
    }
  }, [playbackCam, timelineDate, vodWindow]);

  // HLS Logic (Internal to Component)
  useEffect(() => {
    let hls: Hls | null = null;
    if (videoUrl && videoRef.current) {
      setHlsError('')

      // Detect MP4 static file
      const isMp4 = videoUrl.includes('.mp4');

      if (isMp4) {
        // Native Playback for MP4
        videoRef.current.src = videoUrl;
        videoRef.current.play().catch(e => console.log("Autoplay blocked/mp4", e));
      } else if (Hls.isSupported()) {
        hls = new Hls()
        hls.loadSource(videoUrl);
        hls.attachMedia(videoRef.current);
        hls.on(Hls.Events.ERROR, (_event, data) => {
          if (data.fatal) {
            // Optional: Don't show confusing network errors immediately on seek
            if (data.details !== 'manifestLoadError') {
              setHlsError(`Stream Error: ${data.details}`)
            }
            if (data.type === Hls.ErrorTypes.NETWORK_ERROR) hls?.startLoad();
            else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) hls?.recoverMediaError();
            else hls?.destroy();
          }
        });
        videoRef.current.play().catch(e => console.log("Autoplay blocked", e));
      } else if (videoRef.current.canPlayType('application/vnd.apple.mpegurl')) {
        // Safari native HLS support
        videoRef.current.src = videoUrl;
      }

      // Check for pending seek
      if (typeof window !== 'undefined' && window['pendingSeekTime' as any]) {
        const seekTarget = window['pendingSeekTime' as any];
        delete window['pendingSeekTime' as any];

        const onLoaded = () => {
          if (!videoRef.current) return;

          let seekPos = 0;
          if (!isMp4) {
            seekPos = Math.max(0, getPlayerTimeFromWallClock(seekTarget as unknown as number));
          }

          videoRef.current.currentTime = seekPos;
          videoRef.current.play().catch(() => { });
          videoRef.current.removeEventListener('loadedmetadata', onLoaded);
        };
        videoRef.current.addEventListener('loadedmetadata', onLoaded);
      }
    }
    return () => { if (hls) hls.destroy(); }
  }, [videoUrl, playbackCam]);

  // Apply Playback Rate whenever it changes (preserve position)
  useEffect(() => {
    if (videoRef.current) {
      const savedTime = videoRef.current.currentTime;
      const wasPlaying = !videoRef.current.paused;
      videoRef.current.playbackRate = playbackRate;
      // Restore position (some browsers reset on rate change)
      if (savedTime > 0) {
        videoRef.current.currentTime = savedTime;
      }
      // Resume playing if it was playing
      if (wasPlaying) {
        videoRef.current.play().catch(() => { });
      }
    }
  }, [playbackRate]);

  // --- Zoom & Pan Handlers ---
  const handleWheel = (e: React.WheelEvent) => {
    e.stopPropagation();
    // Determine zoom direction
    // DeltaY > 0 -> Zoom Out (increase visible hours)
    // DeltaY < 0 -> Zoom In (decrease visible hours)
    const delta = e.deltaY > 0 ? 1 : -1;
    setZoomLevel(prev => {
      const next = Math.max(1, Math.min(24, prev + delta * 2)); // Step logic
      return next;
    });
  };

  // Derived viewOffset to keep Playhead centered
  // Center of screen = currentPlaybackTime
  // viewOffset (Start of screen) = currentPlaybackTime_Hours - (zoomLevel / 2)
  const dayStart = new Date(timelineDate + 'T00:00:00').getTime() / 1000;
  let viewOffset = 0;
  if (currentPlaybackTime > 0) {
    const currentHour = (currentPlaybackTime - dayStart) / 3600;
    viewOffset = currentHour - (zoomLevel / 2);
  }

  const handleMouseDown = (e: React.MouseEvent) => {
    isDragging.current = true;
    dragStartX.current = e.clientX;
    dragStartTime.current = currentPlaybackTime;
    hasMoved.current = false; // Reset move flag
  };

  const handleMouseMove = (e: React.MouseEvent) => {
    // Timeline pan (Dragging timeline to seek)
    if (!isDragging.current || !timelineRef.current) return;

    // Calculate delta
    const deltaX = e.clientX - dragStartX.current;

    // Check threshold to treat as drag
    if (Math.abs(deltaX) > 5) {
      hasMoved.current = true;
    }

    const width = timelineRef.current.clientWidth;
    // width pixels = zoomLevel hours
    const hoursMoved = (deltaX / width) * zoomLevel;

    // Dragging RIGHT (Positive delta) -> We want to move time BACKWARDS (See earlier)
    // So newTime = startTime - movedTime
    const timeMovedSec = hoursMoved * 3600;
    const newTime = dragStartTime.current - timeMovedSec;

    // Seek Logic
    // Constrain to Day?
    const dayEnd = dayStart + 86400;
    const clampedTime = Math.max(dayStart, Math.min(dayEnd, newTime));

    // Update Player
    if (videoRef.current && vodWindow) {
      // Check if inside vodWindow
      if (clampedTime >= vodWindow.start && clampedTime <= vodWindow.end) {
        const playerTime = getPlayerTimeFromWallClock(clampedTime);
        videoRef.current.currentTime = playerTime;
        // Don't auto play if we are just dragging? Maybe just updating time is enough.
        // But valid playerTime update updates the frame.
      } else {
        // Outside current VOD Window...
        // We arguably need to Fetch new VOD window?
        // That's complex for drag.
        // For now just update "currentPlaybackTime" locally for UI?
        // But video won't sync.
        // Let's just update currentPlaybackTime to force UI verify,
        // and trigger seek on MouseUp?
        // Or simple Seek if inside window.
      }
      // Force UI update
      setCurrentPlaybackTime(clampedTime);
    } else {
      // No video loaded? Just update UI time?
      setCurrentPlaybackTime(clampedTime);
    }
  };

  const handleMouseUp = (_: React.MouseEvent) => {
    if (isDragging.current) {
      // Final seek or fetch window if needed?
      // If we dragged outside VOD window, we might need to reload.
      // Check if currentPlaybackTime is outside vodWindow
      if (vodWindow && (currentPlaybackTime < vodWindow.start || currentPlaybackTime > vodWindow.end)) {
        // Trigger reload?
        // handleTimelineClick logic handles this by setting new VOD window.
        // We can reuse that logic or simplified version.
        const clickTime = currentPlaybackTime;
        // ... (Refetch logic similar to click) ...
        // Simply trigger the reload by clearing vodWindow or special handler?
        // Let's call a debounced seek or just rely on user clicking if they drag too far.
        // But requirement: "tôi sẽ kéo thanh thời gian tổng để chọn thời gian phát lại"

        // Let's trigger the Window update logic:
        const dayEnd = Math.floor(new Date(timelineDate + 'T23:59:59').getTime() / 1000);
        const now = Math.floor(Date.now() / 1000);
        let newStart = Math.floor(clickTime - 3600);
        let newEnd = Math.floor(clickTime + 3600);
        const effectiveLimit = Math.min(dayEnd, now);
        if (newEnd > effectiveLimit) newEnd = effectiveLimit;
        if (newStart < dayStart) newStart = dayStart;

        setVodWindow({ start: newStart, end: newEnd });
        (window as any).pendingSeekTime = clickTime;
      }
    }
    isDragging.current = false;
  };

  // --- Seek Logic: Wall-Clock to Player Time ---
  // Frigate VOD playlist only contains segments within vodWindow.
  // We must filter recordings to match what's in the playlist.
  const getPlayerTimeFromWallClock = (targetTime: number) => {
    if (!recordings || recordings.length === 0) return 0;

    // Filter to segments in current VOD window
    let relevant = recordings;
    if (vodWindow) {
      relevant = recordings.filter((r: any) => r.end_time > vodWindow.start && r.start_time < vodWindow.end);
    }

    const sorted = [...relevant].sort((a: any, b: any) => a.start_time - b.start_time);

    let cumulativeTime = 0;

    for (const seg of sorted) {
      if (targetTime >= seg.end_time) {
        // Target is after this segment -> Add full duration
        cumulativeTime += (seg.end_time - seg.start_time);
      } else if (targetTime >= seg.start_time && targetTime < seg.end_time) {
        // Target is IN this segment -> Add partial duration
        cumulativeTime += (targetTime - seg.start_time);
        return cumulativeTime;
      } else {
        // Target is before this segment.
        return cumulativeTime;
      }
    }
    return cumulativeTime;
  };

  // --- Display Logic: Player Time to Wall-Clock ---
  const getWallClockFromPlayerTime = (playerTime: number) => {
    if (!recordings || recordings.length === 0) return 0;

    // Filter to segments in current VOD window
    let relevant = recordings;
    if (vodWindow) {
      relevant = recordings.filter((r: any) => r.end_time > vodWindow.start && r.start_time < vodWindow.end);
    }

    // Sort logic
    const sorted = [...relevant].sort((a: any, b: any) => a.start_time - b.start_time);

    let cumulative = 0;
    for (const seg of sorted) {
      const segDuration = seg.end_time - seg.start_time;
      if (playerTime < cumulative + segDuration) {
        // Inside this segment
        const offset = playerTime - cumulative;
        return seg.start_time + offset;
      }
      cumulative += segDuration;
    }
    // If past end/not found in range, assume end of last segment?
    // Or if playerTime > total, return last end.
    if (sorted.length > 0) return sorted[sorted.length - 1].end_time;
    return 0;
  };

  // Timeline Click / Seek Click
  const handleTimelineClick = (e: React.MouseEvent<HTMLDivElement>) => {
    if (!videoRef.current || isDragging.current) return;

    // If we dragged (moved significantly), treat as Drag End, NOT Click.
    // Click event fires after MouseUp.
    if (hasMoved.current) {
      hasMoved.current = false; // Reset
      return;
    }
    // Prevent click if slightly dragged?
    // Ideally check if drag distance was small. For now assume simple click.

    const rect = e.currentTarget.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const ratio = Math.max(0, Math.min(1, x / rect.width)); // 0 to 1 relative to View

    // Convert View ratio to Day ratio
    // View covers [viewOffset, viewOffset + zoomLevel]
    const clickedHour = viewOffset + (ratio * zoomLevel);
    const dayStart = new Date(timelineDate + 'T00:00:00').getTime() / 1000;
    const clickTime = dayStart + (clickedHour * 3600);

    // Update Playback immediately
    setCurrentPlaybackTime(clickTime);

    // Check if inside a segment
    const hitSegment = recordings.find((r: any) => clickTime >= r.start_time && clickTime <= r.end_time);

    if (hitSegment) {
      // Check if clickTime is inside valid VOD window
      if (vodWindow && clickTime >= vodWindow.start && clickTime <= vodWindow.end) {
        // Inside window: Just seek
        const playerTime = getPlayerTimeFromWallClock(clickTime);
        videoRef.current.currentTime = playerTime;
        videoRef.current.play().catch(() => { });
      } else {
        // Create a 2-hour window centered on the click (T-1h to T+1h)
        const dayStart = new Date(timelineDate + 'T00:00:00').getTime() / 1000;
        const dayEnd = Math.floor(new Date(timelineDate + 'T23:59:59').getTime() / 1000);
        const now = Math.floor(Date.now() / 1000);

        let newStart = Math.floor(clickTime - 3600); // -1 hour
        let newEnd = Math.floor(clickTime + 3600);   // +1 hour

        // Constrain End
        // If "now" is earlier than dayEnd (today), constrain to now
        const effectiveLimit = Math.min(dayEnd, now);

        // Fix for "Live" / Latest Footage:
        // If the user clicks within the last minute or so, or if newEnd is very close to now,
        // we should ensure we capture the absolute latest segments which might be slightly ahead of "now" due to clock diffs or just ensure we get it all.
        // Actually, Frigate recordings might be in progress.
        // If we are viewing "Today", we should ideally set end to "now" dynamically?
        // But vodWindow is static.
        // Let's just ensure effectiveLimit is fresh (it is, calculated above).

        // Logic: If the requested range [newStart, newEnd] overlaps with "Now",
        // we should probably just set end = now to be safe, or effectiveLimit.
        // effectiveLimit is min(dayEnd, now). So it is safe.

        if (newEnd > effectiveLimit) {
          newEnd = effectiveLimit;
          // Optional: Shift start back if we hit the end?
          // User requested "centered" but if we hit end, we might show [End-2h, End].
          // But strict centered request: "T-1h to T+1h".
          // If T+1h > Now, just cut it off. Window might be < 2h.
          // If user wants exactly 2h always: newStart = newEnd - 7200;
          // Let's keep it simple: Cut off end.
        }

        // Constrain Start
        if (newStart < dayStart) newStart = dayStart;

        // Force integer types
        newStart = Math.floor(newStart);
        newEnd = Math.floor(newEnd);

        setVodWindow({ start: newStart, end: newEnd });

        // Auto-seek to the click time after reload
        (window as any).pendingSeekTime = clickTime;

      }
    } else {
      // Grey area click -> Jump to start of day or nearest future segment?
      // User: "chạy về đầu lúc có video của ngày hôm đó" (Jump to start of day's video)
      if (recordings.length > 0) {
        // Seek to 0 (beginning of playlist) which corresponds to first segment start
        videoRef.current.currentTime = 0;
        videoRef.current.play().catch(() => { });
      }
    }
  };

  const captureScreenshot = async () => {
    if (!videoRef.current || !playbackCam) return;
    try {
      const canvas = document.createElement('canvas');
      canvas.width = videoRef.current.videoWidth;
      canvas.height = videoRef.current.videoHeight;
      const ctx = canvas.getContext('2d');
      if (!ctx) throw new Error("Could not get canvas context");
      ctx.drawImage(videoRef.current, 0, 0, canvas.width, canvas.height);
      const dataUrl = canvas.toDataURL('image/png');

      // Use current playback time for filename
      const playbackDate = new Date(currentPlaybackTime * 1000);
      const hh = String(playbackDate.getHours()).padStart(2, '0');
      const mm = String(playbackDate.getMinutes()).padStart(2, '0');
      const ss = String(playbackDate.getSeconds()).padStart(2, '0');
      const dd = String(playbackDate.getDate()).padStart(2, '0');
      const mo = String(playbackDate.getMonth() + 1).padStart(2, '0');
      const yyyy = playbackDate.getFullYear();

      const filename = `${playbackCam}_${hh}-${mm}-${ss}_${dd}-${mo}-${yyyy}.png`;

      const response = await fetch('/api/save-screenshot', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ dataUrl, filename })
      });

      if (response.ok) {
        // Only show success if fetch actually succeeded
        addToast({
          type: 'success',
          title: 'Chụp ảnh thành công',
          message: `Ảnh đã được lưu vào thư viện với tên "${filename}".`
        });
      } else {
        const errorData = await response.json().catch(() => ({}));
        throw new Error(errorData.error || `Server responded with ${response.status}`);
      }
    } catch (err: any) {
      console.error('Screenshot failed:', err);
      addToast({
        type: 'error',
        title: 'Lỗi chụp ảnh',
        message: err.message || 'Không thể lưu hình ảnh vào hệ thống.'
      });
    }
  };

  // Improved Digital Zoom & Pan State
  const [transform, setTransform] = useState({ scale: 1, viewX: 0.5, viewY: 0.5 });
  const isPanning = useRef(false);
  const startPan = useRef({ x: 0, y: 0 }); // Stores clientX/Y
  const startView = useRef({ x: 0.5, y: 0.5 }); // Stores viewX/Y
  const containerRef = useRef<HTMLDivElement>(null); // Ref for boundary calculation

  const handleVideoZoom = (e: React.WheelEvent) => {
    e.preventDefault();
    e.stopPropagation();

    const direction = e.deltaY > 0 ? -1 : 1;
    const newScale = Math.max(1, Math.min(8, transform.scale + direction * 0.5));

    if (newScale === 1) {
      setTransform({ scale: 1, viewX: 0.5, viewY: 0.5 });
    } else {
      setTransform(prev => ({ ...prev, scale: newScale }));
    }
  };

  const handlePanStart = (e: React.MouseEvent) => {
    if (transform.scale > 1) {
      isPanning.current = true;
      startPan.current = { x: e.clientX, y: e.clientY };
      startView.current = { x: transform.viewX, y: transform.viewY };
      e.stopPropagation();
    }
  };

  const handlePanMove = (e: React.MouseEvent) => {
    if (isPanning.current && containerRef.current) {
      e.preventDefault();
      const rect = containerRef.current.getBoundingClientRect();

      const deltaX = e.clientX - startPan.current.x;
      const deltaY = e.clientY - startPan.current.y;

      // Convert pixel delta to normalized view delta
      // A movement of 'rect.width' pixels = 1.0 normalized unit IF scale was 1.
      // But we are moving the VIEWPORT over the image.
      // If we drag mouse RIGHT (+deltaX), we want to see LEFT (decrease viewX).
      // Logic: viewX is 0-1.
      // Change in viewX = - (deltaX / rect.width) / transform.scale
      // Wait, is it / scale?
      // Visualizing: If zoomed 2x. Image is 2000px wide (in 1000px container).
      // Dragging 500px right. We moved 1/4 of total image width? No.
      // We moved 500px of SCREEN space.
      // The image moved 500px right.
      // So the viewport center moved 500px LEFT relative to image.
      // 500px is 0.5 of screen width.
      // In normalized coords (0-1 relative to screen size? No relative to IMAGE size).
      // Screen width = 1000px. Image width = 2000px.
      // 500px screen movement = 500px image movement.
      // 500px / 2000px = 0.25 normalized image units.
      // So deltaView = deltaScreen / (rect.width * scale)

      const deltaViewX = deltaX / (rect.width * transform.scale);
      const deltaViewY = deltaY / (rect.height * transform.scale);

      let newViewX = startView.current.x - deltaViewX;
      let newViewY = startView.current.y - deltaViewY;

      // Clamp viewX/Y
      // Viewport width in normalized units = 1 / scale
      // Center must be within [0.5/scale, 1 - 0.5/scale]
      const halfViewW = 0.5 / transform.scale;
      const halfViewH = 0.5 / transform.scale;

      newViewX = Math.max(halfViewW, Math.min(1 - halfViewW, newViewX));
      newViewY = Math.max(halfViewH, Math.min(1 - halfViewH, newViewY));

      setTransform(prev => ({ ...prev, viewX: newViewX, viewY: newViewY }));
    }
  };

  const handlePanEnd = () => {
    isPanning.current = false;
  };

  const resetZoom = () => setTransform({ scale: 1, viewX: 0.5, viewY: 0.5 });

  return (
    <div className="flex flex-1 overflow-hidden h-full">
      {/* Left Sidebar */}
      <PlaybackSidebar cameras={cameras} onSelect={handleCameraSelect} selectedCam={playbackCam} />

      {/* Center Main */}
      <section className="flex-1 flex flex-col min-w-0 bg-black relative">
        <div className="flex-1 relative flex items-center justify-center bg-black overflow-hidden">
          {videoUrl ? (
            <div
              ref={containerRef}
              className="w-full h-full flex items-center justify-center overflow-hidden relative group"
              onWheel={handleVideoZoom}
              style={{ cursor: transform.scale > 1 ? 'grab' : 'zoom-in' }}
            >
              <div className="absolute top-2 right-2 z-20 opacity-0 group-hover:opacity-100 transition-opacity">
                <button onClick={() => setVideoUrl('')} className="bg-black/60 text-white p-2 rounded-full hover:bg-red-500 transition-colors" title="Đóng">
                  <Icon name="close" className="text-sm" />
                </button>
              </div>
              {videoUrl.includes('.jpg') ? (
                <img
                  src={videoUrl}
                  className="max-w-full max-h-full object-contain"
                  alt="AI Bounding Box Snapshot"
                  style={{
                    transform: transform.scale > 1
                      ? `scale(${transform.scale}) translate(${(0.5 - transform.viewX) * 100}%, ${(0.5 - transform.viewY) * 100}%)`
                      : 'none',
                    transformOrigin: 'center center',
                    transition: isPanning.current ? 'none' : 'transform 0.1s ease-out'
                  }}
                  onMouseDown={handlePanStart}
                  onMouseMove={handlePanMove}
                  onMouseUp={handlePanEnd}
                  onMouseLeave={handlePanEnd}
                />
              ) : (
                <video
                  ref={videoRef}
                  className="w-full h-full object-contain"
                  crossOrigin="anonymous"
                  onPlay={() => setIsPlaying(true)}
                  onPause={() => setIsPlaying(false)}
                  style={{
                    transform: transform.scale > 1
                      ? `scale(${transform.scale}) translate(${(0.5 - transform.viewX) * 100}%, ${(0.5 - transform.viewY) * 100}%)`
                      : 'none',
                    transformOrigin: 'center center',
                    transition: isPanning.current ? 'none' : 'transform 0.1s ease-out',
                    cursor: transform.scale > 1 ? 'grab' : 'default'
                  }}
                  onMouseDown={handlePanStart}
                  onMouseMove={handlePanMove}
                  onMouseUp={handlePanEnd}
                  onMouseLeave={handlePanEnd}
                />
              )}
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center text-gray-500">
              <Icon name="videocam_off" className="text-4xl mb-2" />
              <span className="text-sm">Chọn camera để xem lại</span>
            </div>
          )}
          {playbackCam && (
            <div className="absolute top-4 left-4 bg-black/60 text-white px-3 py-1 rounded text-sm font-bold">
              {playbackCam}
            </div>
          )}
          {hlsError && <div className="absolute top-14 left-4 bg-red-500/80 text-white px-3 py-1 rounded text-xs">{hlsError}</div>}
        </div>

        {/* Playback Controls Toolbar */}
        <div className="h-48 bg-white dark:bg-[#1F2937] border-t border-gray-200 dark:border-gray-700 flex flex-col shrink-0">
          {/* Toolbar Buttons */}
          <div className="flex items-center justify-between px-4 py-2 border-b border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-gray-800 overflow-x-auto no-scrollbar gap-4">
            <div className="flex items-center gap-2 shrink-0">
              <button
                onClick={() => {
                  if (videoRef.current) {
                    if (isPlaying) videoRef.current.pause();
                    else videoRef.current.play();
                  }
                }}
                className="p-2 hover:bg-gray-200 dark:hover:bg-gray-700 rounded text-gray-600 dark:text-gray-300"
                title={isPlaying ? "Tạm dừng" : "Phát"}
              >
                <Icon name={isPlaying ? "pause" : "play_arrow"} />
              </button>

              <div className="h-6 w-px bg-gray-300 dark:bg-gray-600 mx-2"></div>

              {/* Speed Control */}
              <div className="flex items-center gap-1 bg-[#EF4444] rounded-md px-3 py-1.5 shadow-sm hover:bg-red-600 transition-colors">
                <Icon name="speed" className="text-white text-sm" />
                <select
                  className="bg-transparent text-xs font-bold text-white border-none focus:ring-0 cursor-pointer outline-none"
                  value={playbackRate}
                  onChange={(e) => setPlaybackRate(parseFloat(e.target.value))}
                >
                  <option value="0.5" className="text-gray-900 bg-white">0.5x</option>
                  <option value="1" className="text-gray-900 bg-white">1x</option>
                  <option value="2" className="text-gray-900 bg-white">2x</option>
                  <option value="4" className="text-gray-900 bg-white">4x</option>
                  <option value="8" className="text-gray-900 bg-white">8x</option>
                </select>
              </div>

              <div className="h-6 w-px bg-gray-300 dark:bg-gray-600 mx-2"></div>


              <button
                onClick={captureScreenshot}
                className="p-2 hover:bg-gray-200 dark:hover:bg-gray-700 rounded text-gray-600 dark:text-gray-300"
                title="Chụp ảnh"
                disabled={!videoUrl}
              >
                <Icon name="camera_alt" />
              </button>
              <button
                onClick={() => {
                  const newScale = Math.min(8, transform.scale + 0.5);
                  setTransform(prev => ({ ...prev, scale: newScale }));
                }}
                className="p-2 hover:bg-gray-200 dark:hover:bg-gray-700 rounded text-gray-600 dark:text-gray-300"
                title="Zoom video (lăn chuột để zoom)"
                disabled={!videoUrl}
              >
                <Icon name="zoom_in" />
              </button>
              <button
                onClick={resetZoom}
                className="p-2 hover:bg-gray-200 dark:hover:bg-gray-700 rounded text-gray-600 dark:text-gray-300"
                title="Reset Zoom (100%)"
                disabled={!videoUrl || transform.scale === 1}
              >
                <Icon name="restart_alt" />
              </button>
            </div>

            {/* Zoom Controls Overlay */}
            <div className="flex items-center gap-2 shrink-0">
              <span className="text-xs font-bold text-[#EF4444] mr-2">
                {transform.scale > 1 ? `${Math.round(transform.scale * 100)}%` : ''}
              </span>
              <span className="text-xs text-gray-500 dark:text-gray-400 mr-2">Zoom: {zoomLevel}h</span>

              {/* Center Time Display */}
              <div className="mx-4 text-xl font-mono font-bold text-[#EF4444] bg-red-50 dark:bg-red-900/10 px-3 py-1 rounded border border-red-100 dark:border-red-900/30 min-w-[120px] text-center">
                {(() => {
                  if (currentPlaybackTime <= 0) return "--:--:--";
                  const date = new Date(currentPlaybackTime * 1000);
                  return date.toLocaleTimeString('vi-VN', { hour12: false });
                })()}
              </div>

              <button onClick={() => setZoomLevel(Math.min(24, zoomLevel + 1))}><Icon name="remove" className="text-base text-gray-500" /></button>
              <input
                className="w-24 h-1 bg-gray-300 rounded-lg appearance-none cursor-pointer dark:bg-gray-600 accent-[#EF4444]"
                type="range" min="1" max="24" step="1"
                value={25 - zoomLevel} // Invert so Right is Zoom In? Usually Right is "More". Here "More Zoom" = "Less Hours"
                onChange={(e) => setZoomLevel(25 - parseInt(e.target.value))}
              />
              <button onClick={() => setZoomLevel(Math.max(1, zoomLevel - 1))}><Icon name="add" className="text-base text-gray-500" /></button>
            </div>
          </div>

          {/* Timeline Area */}
          <div
            className="flex-1 relative overflow-hidden bg-gray-900 select-none cursor-pointer group"
            ref={timelineRef}
            onWheel={handleWheel}
            onMouseDown={handleMouseDown}
            onMouseMove={handleMouseMove}
            onMouseUp={handleMouseUp}
            onMouseLeave={handleMouseUp}
            onClick={handleTimelineClick}
            title="Lăn chuột để Zoom, Kéo để di chuyển"
          >
            {/* Center Line (Playhead) - Fixed at 50% */}
            <div className="absolute top-0 bottom-0 left-1/2 w-0.5 bg-red-500 z-50 transform -translate-x-1/2 pointer-events-none">
              <div className="w-3 h-3 bg-red-500 rounded-full absolute -top-1 left-1/2 transform -translate-x-1/2" />
            </div>
            {/* Current Time Indicator - Needs to be mapped to viewOffset?
                Actually playbackStart is not consistently available/updated in this mock.
                Ideally we show a line if current video time maps to visible window.
            */}

            {/* Time Ruler */}
            <div className="absolute top-0 left-0 h-6 border-b border-gray-700 flex text-xs text-gray-400 font-mono pointer-events-none z-10 w-full overflow-hidden">
              {/* Generate markers based on viewOffset and zoomLevel */}
              {Array.from({ length: zoomLevel + 1 }).map((_, i) => {
                const hourMark = viewOffset + i; // fractional hours?
                // Just show integer hours if they fall in range
                // Logic: Iterate 0..24, show if >= viewOffset && <= viewOffset+zoom
                console.log("hourMark", hourMark);
                return null;
              })}
              {[0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24].map(h => {
                if (h < viewOffset || h > viewOffset + zoomLevel) return null;

                // Responsive spacing logic to prevent overlap of hour text
                const step = zoomLevel > 16 ? 4 : zoomLevel > 8 ? 2 : 1;
                if (h % step !== 0) return null;

                const leftPct = ((h - viewOffset) / zoomLevel) * 100;
                return (
                  <div key={h} className="absolute border-l border-gray-700 pl-1 pt-1 h-full text-[10px] md:text-xs" style={{ left: `${leftPct}%` }}>
                    {h}:00
                  </div>
                );
              })}
            </div>

            {/* Loading State */}
            {isLoadingRecordings && <div className="absolute inset-0 flex items-center justify-center text-xs text-white/50 z-20">Đang tải bản ghi...</div>}

            {/* Timeline Data */}
            <div className="absolute top-8 left-0 right-0 h-16 flex items-center w-full">
              {/* Container is window. Items are absolute positioned relative to window based on zoom */}
              <div className="w-full h-8 bg-gray-800 rounded relative overflow-hidden">
                {recordings.map((seg: any, idx: number) => {
                  const dayStart = new Date(timelineDate + 'T00:00:00').getTime() / 1000;

                  // Calculate strictly relative to Day (0-86400)
                  const segStartDay = seg.start_time - dayStart;
                  const segEndDay = seg.end_time - dayStart;

                  // Convert to Window coords
                  // Window Start (seconds) = viewOffset * 3600
                  // Window Duration (seconds) = zoomLevel * 3600
                  const winStart = viewOffset * 3600;
                  const winDur = zoomLevel * 3600;

                  // normalize to window (0-100%)
                  // left = (segStart - winStart) / winDur * 100

                  const startPct = ((segStartDay - winStart) / winDur) * 100;
                  const widthPct = ((segEndDay - segStartDay) / winDur) * 100;

                  // Optimization: Don't render if completely out of view
                  if (startPct + widthPct < 0 || startPct > 100) return null;

                  return <div key={idx} className="absolute top-0 bottom-0 bg-[#34D399] border-l border-r border-[#34D399]/50" style={{ left: `${startPct}%`, width: `${widthPct}%` }} />;
                })}

                {/* Feature 5: Red Playhead Indicator with Time Display - NOW IN TOOLBAR or Fixed Center? */}
                {/* The user asked for "Make playhead fixed in center" AND "Text display in toolbar" */}
                {/* We rendered the fixed playhead above (z-50) */}
                {/* We remove the moving playhead code below */}


              </div>
            </div>
          </div>

          {/* Legend */}
          <div className="h-8 bg-white dark:bg-[#1F2937] border-t border-gray-200 dark:border-gray-700 flex items-center px-4 text-xs gap-4">
            <div className="flex items-center gap-1"><span className="font-bold text-gray-500">HD:</span> <span className="text-gray-400">Lăn chuột để Zoom • Kéo để di chuyển • Click để xem</span></div>
          </div>
        </div>
      </section>

      {/* Right Sidebar */}
      {/* Right Sidebar */}
      <PlaybackRightPanel
        showExport={showExport}
        setShowExport={setShowExport}
        onExport={_handleExport}
        timelineDate={timelineDate}
        setTimelineDate={setTimelineDate}
        playbackCam={playbackCam}
        onPlayEvent={(url: string) => {
          setVideoUrl(url);
          if (videoRef.current) {
            videoRef.current.load();
            videoRef.current.play();
          }
          setIsPlaying(true);
        }}
        cameras={cameras}
      />
    </div>
  );
}
// --- End Refactor --- //

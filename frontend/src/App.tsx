import { useState, useEffect, useRef, useCallback } from 'react'
import AITab from './components/AITab'
import PatternLockScreen from './components/PatternLockScreen'
import { useMqttEvents } from './hooks/useMqttEvents'
import { t } from './i18n'
import './App.css'
import { Header } from './components/Header'
import { LiveView } from './components/LiveView'
import { MiniPlayer } from './components/MiniPlayer'
import { PlaybackView } from './components/PlaybackView'
import { ExportModal } from './components/PlaybackPanels'
import { SetupView } from './components/SetupView'
import { ConfirmDeleteModal } from './components/ConfirmDeleteModal'
import { ToastContainer } from './components/ToastContainer'
import { DownloadsView } from './components/DownloadsView'

interface RecordingSegment {
  start_time: number;
  end_time: number;
}

function App() {
  const [activeTab, setActiveTab] = useState('Live View')
  const [setupTab, setSetupTab] = useState('Camera List')
  const [currentTime, setCurrentTime] = useState(new Date())
  useEffect(() => { const timer = setInterval(() => setCurrentTime(new Date()), 1000); return () => clearInterval(timer) }, [])

  // ── MQTT Real-time Bounding Boxes ──────────────────────────────────────────
  const { getCameraDetections } = useMqttEvents()

  // ── App Version (from /api/version) ───────────────────────────────────────
  // Application version label has been removed from UI per user request


  // ── Pattern Lock (session-based) ─────────────────────────────────────────
  const [isPatternSetup, setIsPatternSetup] = useState(false)
  // showLockScreen: show the lock/setup overlay
  const [showLockScreen, setShowLockScreen] = useState(false)
  // isSessionUnlocked: true for 10 min after a successful unlock
  const [isSessionUnlocked, setIsSessionUnlocked] = useState(false)
  const autoLockTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  // pendingTabRef: the tab the user wanted to visit before the lock appeared
  const pendingTabRef = useRef<string | null>(null)

  const handleLogout = useCallback(async () => {
    // Lock the UI first: the network may be dead or starved (zombie-connection
    // incident 2026-07-15) — a hung logout request must never block the lock screen.
    setIsSessionUnlocked(false);
    setActiveTab('Live View');
    if (autoLockTimerRef.current) clearTimeout(autoLockTimerRef.current);
    setShowLockScreen(true);
    try {
      await (window as any).fetch('/api/lock/logout', { method: 'POST' });
    } catch (e) {
      console.error('Logout failed', e);
    }
  }, [setIsSessionUnlocked, setActiveTab, setShowLockScreen]);

  useEffect(() => {
    (window as any).fetch('/api/lock/status')
      .then((r: any) => r.json())
      .then((d: { isSetup: boolean }) => {
        setIsPatternSetup(d.isSetup)
        if (!d.isSetup) {
          setShowLockScreen(true)
        }
      })
      .catch(() => { });

    // Provide a way for children to call logout
    (window as any).logout = handleLogout;
  }, [handleLogout]);

  // On mount: restore unlocked state if a valid session cookie already exists
  // Fixes page-refresh and codegen/test scenarios where isSessionUnlocked resets to false
  useEffect(() => {
    fetch('/api/camera-status')
      .then(r => { if (r.ok) setIsSessionUnlocked(true); })
      .catch(() => {});
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Simple global check for 401
  useEffect(() => {
    const originalFetch = (window as any).fetch;
    let probing = false;
    const interceptedFetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      return originalFetch.call(window, input, init).then((response: Response) => {
        if (response.status === 401 && !input.toString().includes('/api/lock/verify')) {
          // Transient 401s happen (seen after long tab freezes, 2026-07-15) and heal in
          // seconds — locking a kiosk screen on the first one is wrong. Confirm the
          // session is really gone with one cheap authed probe before logging out.
          if (!probing) {
            probing = true;
            setTimeout(() => {
              originalFetch.call(window, '/api/camera-status')
                .then((probe: Response) => { if (probe.status === 401) handleLogout(); })
                .catch(() => { /* network down ≠ session gone; keep the UI unlocked */ })
                .finally(() => { probing = false; });
            }, 1500);
          }
        }
        return response;
      });
    };
    (window as any).fetch = interceptedFetch;
    return () => { (window as any).fetch = originalFetch; };
  }, [handleLogout]);

  const startAutoLockTimer = () => {
    if (autoLockTimerRef.current) clearTimeout(autoLockTimerRef.current)
    autoLockTimerRef.current = setTimeout(() => {
      // After 10 min: expire session, redirect to Live View
      setIsSessionUnlocked(false)
      setActiveTab('Live View')
    }, 10 * 60 * 1000)
  }

  // Called after successful pattern draw in verify mode
  const handleUnlock = () => {
    setIsSessionUnlocked(true)
    setShowLockScreen(false)
    // Navigate to the tab the user originally clicked
    if (pendingTabRef.current) {
      setActiveTab(pendingTabRef.current)
      pendingTabRef.current = null
    }
    startAutoLockTimer()
  }

  // Called after first-time pattern setup is confirmed
  const handleSetupComplete = () => {
    setIsPatternSetup(true)
    setShowLockScreen(false)
    setIsSessionUnlocked(true)
    startAutoLockTimer()
  }

  // Intercept tab navigation
  const handleTabChange = (tab: string) => {
    // Live View is always freely accessible
    if (tab === 'Live View') {
      setActiveTab(tab)
      return
    }
    // Not setup yet: show setup screen
    if (!isPatternSetup) {
      setShowLockScreen(true)
      return
    }
    // Session is active: navigate freely (no extra unlock needed)
    if (isSessionUnlocked) {
      setActiveTab(tab)
      return
    }
    // Session expired: require unlock, remember intended tab
    pendingTabRef.current = tab
    setShowLockScreen(true)
  }
  // ─────────────────────────────────────────────────────────────
  const [cameras, setCameras] = useState<any[]>([])
  const [loading, setLoading] = useState(true)
  const [setupStatus, setSetupStatus] = useState('')
  // Deletion State
  const [deleteModal, setDeleteModal] = useState<{ open: boolean, id: string, name: string }>({ open: false, id: '', name: '' });

  // Toast State
  const [toasts, setToasts] = useState<any[]>([]);

  const addToast = (toast: { type: 'success' | 'error' | 'info' | 'warning', title: string, message: string }) => {
    const id = Date.now();
    setToasts(prev => [...prev, { ...toast, key: id }]);
    setTimeout(() => {
      setToasts(prev => prev.filter(t => t.key !== id));
    }, 5000);
  };

  const removeToast = (id: number) => {
    setToasts(prev => prev.filter(t => t.key !== id));
  };

  // Live View State
  // Persist which cameras occupy which cells (order = cell position) so a reload restores the
  // exact arrangement. Lazy-init from storage BEFORE the persist effect runs, so mount can't
  // clobber the saved value with an empty array; fetchCameras then drops any deleted cameras.
  const [visibleCamIds, setVisibleCamIds] = useState<string[]>(() => {
    try {
      const saved = JSON.parse(localStorage.getItem('nvr.visibleCamIds') || 'null');
      if (Array.isArray(saved)) return saved;
    } catch { /* corrupt/unavailable storage → empty (fetchCameras fills with all) */ }
    return [];
  });
  useEffect(() => {
    try { localStorage.setItem('nvr.visibleCamIds', JSON.stringify(visibleCamIds)); } catch { /* ignore */ }
  }, [visibleCamIds]);
  // Persist the grid layout so a reload keeps the user's choice (e.g. '1+5') instead of
  // snapping back to the 2x2 default. Stored as JSON so number (2) vs string ('1+5') survives.
  const [layout, setLayout] = useState<number | string>(() => {
    try {
      const saved = localStorage.getItem('nvr.liveLayout');
      if (saved != null) return JSON.parse(saved);
    } catch { /* corrupt/unavailable storage → fall through to default */ }
    return 2;
  });
  useEffect(() => {
    try { localStorage.setItem('nvr.liveLayout', JSON.stringify(layout)); } catch { /* ignore */ }
  }, [layout]);
  const [fullscreenCamId, setFullscreenCamId] = useState<string | null>(null);
  const [miniPlayerCamId, setMiniPlayerCamId] = useState<string | null>(null);

  const toggleCamera = (id: string) => {
    setVisibleCamIds(prev => {
      if (fullscreenCamId === id) setFullscreenCamId(null);
      if (prev.includes(id)) {
        const newArr = [...prev];
        const index = newArr.indexOf(id);
        newArr[index] = '';
        while (newArr.length > 0 && newArr[newArr.length - 1] === '') {
          newArr.pop();
        }
        return newArr;
      } else {
        const emptyIdx = prev.indexOf('');
        if (emptyIdx !== -1) {
          const newArr = [...prev];
          newArr[emptyIdx] = id;
          return newArr;
        }
        return [...prev, id];
      }
    });
  }

  // ── App-level fullscreen (hides header + sidebar, auto on load) ────────────
  const [isAppFullscreen, setIsAppFullscreen] = useState(true)
  const requestFullscreen = () => {
    const next = !isAppFullscreen
    setIsAppFullscreen(next)
    if (next) {
      document.documentElement.requestFullscreen().catch(() => {})
    } else {
      if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
    }
  }
  // On first user interaction, silently enter browser fullscreen (needs gesture)
  useEffect(() => {
    if (!document.fullscreenEnabled) return
    const enterBrowserFs = () => {
      if (!document.fullscreenElement) {
        document.documentElement.requestFullscreen().catch(() => {})
      }
      document.removeEventListener('click', enterBrowserFs)
      document.removeEventListener('keydown', enterBrowserFs)
    }
    document.addEventListener('click', enterBrowserFs)
    document.addEventListener('keydown', enterBrowserFs)
    return () => {
      document.removeEventListener('click', enterBrowserFs)
      document.removeEventListener('keydown', enterBrowserFs)
    }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps
  // Keep isAppFullscreen in sync when user presses Esc to exit browser fullscreen
  useEffect(() => {
    const onFsChange = () => { if (!document.fullscreenElement && isAppFullscreen) setIsAppFullscreen(false) }
    document.addEventListener('fullscreenchange', onFsChange)
    return () => document.removeEventListener('fullscreenchange', onFsChange)
  }, [isAppFullscreen])

  // Playback
  // Playback
  const [playbackCam, setPlaybackCam] = useState('')
  // PlaybackView uses playbackStart prop passed from App?
  // Wait, I refactored PlaybackView to use its own state for videoUrl but what about playbackStart?
  // In my previous replace, PlaybackView *receives* `playbackStart` prop but doesn't seem to calculate it internally.
  // Actually, I removed `playbackStart` update in App...
  // Let's keep common state (cam, date, etc) here but remove video specific vars.

  const [showExport, setShowExport] = useState(false)
  const [timelineDate, setTimelineDate] = useState(new Date().toISOString().split('T')[0])
  const [recordings, setRecordings] = useState<RecordingSegment[]>([])
  const [isLoadingRecordings, setIsLoadingRecordings] = useState(false)

  // When a camera is selected for Playback, auto-jump to most recent date with recordings
  useEffect(() => {
    if (!playbackCam) return;
    const autoSelectDate = async () => {
      try {
        const res = await fetch('/api/frigate/api/recordings/summary');
        if (res.ok) {
          const summary: Record<string, boolean> = await res.json();
          const dates = Object.keys(summary).sort().reverse(); // newest first
          if (dates.length > 0) {
            setTimelineDate(dates[0]);
          }
        }
      } catch (e) {
        console.error('Failed to auto-select date', e);
      }
    };
    autoSelectDate();
  }, [playbackCam]);

  // Removed videoUrl, hlsError, videoRef - now in PlaybackView

  // Nạp danh sách camera lúc mở app VÀ mỗi khi phiên chuyển sang đã mở khoá.
  // Vì sao phải có vế thứ hai: /api/cameras nằm sau authenticate("auth-session"), nên lần
  // gọi lúc chưa mở khoá trả 401 → fetchCameras không setCameras → danh sách rỗng. Trước
  // đây effect này chỉ chạy đúng một lần lúc mount, mà handleUnlock/handleSetupComplete
  // không nạp lại, nên đăng nhập lần đầu xong màn Trực tiếp trống trơn ("Không tìm thấy
  // camera nào", Hiển thị: 0 camera) cho tới khi người dùng tự F5 — F5 mới remount với
  // cookie phiên đã có. Bám theo isSessionUnlocked là phủ được cả hai đường vào
  // (mở khoá bằng pattern và thiết lập pattern lần đầu).
  useEffect(() => { fetchCameras() }, [isSessionUnlocked])
  useEffect(() => { if (playbackCam) fetchRecordings(playbackCam, timelineDate) }, [playbackCam, timelineDate])

  // Periodic camera status update (every 10 seconds)
  useEffect(() => {
    const updateCameraStatus = async () => {
      try {
        const statusRes = await fetch('/api/camera-status');
        if (statusRes.ok) {
          const statusMap = await statusRes.json();
          setCameras(prev => prev.map(cam => ({
            ...cam,
            isOnline: statusMap[cam.id]?.isOnline ?? cam.isOnline,
            fps: statusMap[cam.id]?.fps ?? cam.fps
          })));
        }
      } catch (e) {
        console.error('Failed to update camera status:', e);
      }
    };

    const interval = setInterval(updateCameraStatus, 3000); // 3 seconds
    return () => clearInterval(interval);
  }, [])

  const fetchCameras = async () => {
    setLoading(true)
    try {
      const res = await fetch('/api/cameras');
      if (!res.ok) {
        // 401 = chưa mở khoá phiên, không phải lỗi: effect bám isSessionUnlocked sẽ gọi lại
        // ngay sau khi mở khoá. Log ra để lần sau thấy danh sách trống thì biết đang ở nhánh nào.
        console.warn(`[CAMERA] [FETCH_LIST] [SKIPPED] | http=${res.status} unlocked=${isSessionUnlocked} — giữ nguyên danh sách hiện có`);
      }
      if (res.ok) {
        const data = await res.json();

        // Fetch camera status
        const statusRes = await fetch('/api/camera-status');
        const statusMap = statusRes.ok ? await statusRes.json() : {};

        // Backend returns [{ id: slug, name: DisplayName, ip, manufacturer, model, ... }]
        const list = data.map((c: any) => ({
          id: c.id, // slug
          name: c.name, // Display Name
          url: `/api/frigate/api/${c.id}?h=720`,
          record: true,
          isOnline: statusMap[c.id]?.isOnline ?? false,
          fps: statusMap[c.id]?.fps ?? 0,
          ip: c.ip, // ONVIF metadata
          onvifPort: c.onvifPort ?? 80, // ONVIF port (set to socat proxy port for proxied cameras)
          manufacturer: c.manufacturer, // ONVIF metadata
          model: c.model, // ONVIF metadata
          ptzEnabled: c.ptzEnabled, // Manually toggled PTZ flag
          ptzMode: c.ptzMode ?? 'auto' // PTZ capability override: auto | full | zoom
        }));
        setCameras(list);
        // Keep the restored/current arrangement, just drop cameras that no longer exist.
        // Nothing valid saved (first run / all stale) → fall back to showing every camera.
        const ids = list.map((c: any) => c.id);
        setVisibleCamIds(prev => {
          const kept = prev.filter(id => ids.includes(id));
          return kept.length ? kept : ids;
        });
      }
    } catch (e) { console.error(e) } finally { setLoading(false) }
  }

  // Auto-refresh recordings every 30s if viewing Today to keep timeline fresh
  useEffect(() => {
    if (!playbackCam || !timelineDate) return;

    // Check if timelineDate is Today
    const today = new Date().toISOString().split('T')[0];
    if (timelineDate === today) {
      const interval = setInterval(() => {
        fetchRecordings(playbackCam, timelineDate, true); // true = silent refresh
      }, 30000);
      return () => clearInterval(interval);
    }
  }, [playbackCam, timelineDate]);

  const fetchRecordings = async (cam: string, date: string, silent = false) => {
    if (!silent) {
      setIsLoadingRecordings(true);
      setRecordings([]); // Clear stale data before fetching new camera recordings
    }
    try {
      // Frigate API: timestamps in seconds
      const start = new Date(date + 'T00:00:00').getTime() / 1000;
      const end = new Date(date + 'T23:59:59').getTime() / 1000;

      // Changed from 'events' to 'recordings' to get green bar data
      const res = await fetch(`/api/frigate/api/${cam}/recordings?after=${start}&before=${end}`);
      if (res.ok) {
        const data = await res.json();
        // Recordings endpoint returns array of { start_time, end_time, ... }
        setRecordings(data);
      } else {
        console.error("Failed to fetch recordings");
        setRecordings([]);
      }
    } catch (e) {
      console.error(e);
      if (!silent) setRecordings([]);
    } finally {
      if (!silent) setIsLoadingRecordings(false);
    }
  }

  // Removed Auto-play and HLS effects from App as they are now in PlaybackView


  const handleAddCamera = async (params: any) => {
    setSetupStatus(`Adding ${params.name}...`);
    try {
      const res = await fetch('/api/add-camera', {
        method: 'POST',
        body: JSON.stringify(params),
        headers: { 'Content-Type': 'application/json' }
      });
      const data = await res.json();

      if (res.ok) {
        setSetupStatus('Added successfully');
        await fetchCameras();
        addToast({ type: 'success', title: t('Thành công'), message: t('Đã thêm camera "{0}" thành công!').replace('{0}', params.name) });
        if (data.frigateRestarted === false) {
          addToast({ type: 'warning', title: t('Cảnh báo'), message: t('Không thể restart AI View NVR. Camera sẽ hoạt động sau khi AI View NVR khởi động lại.') });
        }
        return data;
      } else {
        const errorMsg = data.error || 'Failed to add camera';
        setSetupStatus(`Error: ${errorMsg}`);
        throw new Error(errorMsg);
      }
    } catch (e: any) {
      setSetupStatus(`Error: ${e.message}`);
      throw e;
    }
  }

  const handleDeleteCamera = async (id: string, name?: string) => {
    // Open custom modal
    // Find name if not provided (id is slug, cameras has name)
    const camName = name || cameras.find(c => c.id === id)?.name || id;
    setDeleteModal({ open: true, id, name: camName });
  }

  const confirmDeleteCamera = async () => {
    const { id, name } = deleteModal;
    setDeleteModal({ ...deleteModal, open: false });

    // Optimistic Update: Remove immediately from list
    setCameras(prev => prev.filter(c => c.id !== id));

    // Success toast immediately
    addToast({ type: 'success', title: t('Đã xóa'), message: t('Đã xóa camera "{0}" thành công').replace('{0}', name) });

    try {
      await fetch('/api/delete-camera', { method: 'POST', body: JSON.stringify({ name: id }), headers: { 'Content-Type': 'application/json' } });
      // No need to fetch cameras again immediately if we are optimistic,
      // but fetching ensures sync after restart.
      // Since restart takes 10s, fetching immediately might return old data unless backend handles it.
      // Backend API deletes config file BEFORE restart. So fetchCameras should return correct list.
      await fetchCameras();
    } catch (e) {
      console.error(e);
      // Revert or show error if needed, but for deletion usually harmless to just log
    }
  }

  // Export Logic
  const initialExportState = {
    cam: cameras.length > 0 ? cameras[0].name : '',
    date: new Date().toISOString().split('T')[0],
    startTime: '08:00:00',
    endTime: '09:00:00'
  };
  const [exportParams, setExportParams] = useState(initialExportState);
  const [pendingExport, setPendingExport] = useState(false);

  const handleExport = () => {
    setShowExport(true);
    // Pre-fill with current selection if available
    if (playbackCam) setExportParams(prev => ({ ...prev, cam: playbackCam, date: timelineDate }));
  }

  const performDownload = async () => {
    setPendingExport(true);
    try {
      const startTs = Math.floor(new Date(`${exportParams.date}T${exportParams.startTime}`).getTime() / 1000);
      const endTs = Math.floor(new Date(`${exportParams.date}T${exportParams.endTime}`).getTime() / 1000);

      const res = await fetch('/api/export-video', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          camera: exportParams.cam,
          startTime: String(startTs),
          endTime: String(endTs)
        })
      });

      let exportId = null;
      if (res.ok) {
        const data = await res.json().catch(() => ({}));
        exportId = data.export_id || null;
      } else {
        const err = await res.json().catch(() => ({ error: 'Unknown error' }));
        let errorMsg = err.error || 'Export failed';
        if (errorMsg.includes('No recordings found')) {
          const camName = cameras.find(c => c.id === exportParams.cam)?.name || exportParams.cam;
          errorMsg = t("Khung giờ từ '{0}' đến '{1}' cho camera \"{2}\" không tồn tại").replace('{0}', exportParams.startTime).replace('{1}', exportParams.endTime).replace('{2}', camName);
        }
        throw new Error(errorMsg);
      }

      console.log('Export requested from Frigate. Waiting for file to be ready...', exportId);

      // Polling for Completion
      let attempts = 0;
      const checkInterval = setInterval(async () => {
        attempts++;
        try {
          const statusRes = await fetch('/api/list-exports');
          if (statusRes.ok) {
            const exports = await statusRes.json();
            // Look for the specific export file by export_id if available,
            // otherwise fallback to checking the most recent export matching camera name.
            let latest = null;
            if (exportId) {
              latest = exports.find((e: any) => e.name === exportId);
            } else {
              latest = exports.find((e: any) => e.name.includes(exportParams.cam));
            }

            // Check if file is found
            if (latest) {
              // Wait a tiny bit extra to ensure file is fully flushed (sometimes Frigate creates the file before it's done writing)
              clearInterval(checkInterval);

              setTimeout(() => {
                setPendingExport(false);
                setShowExport(false);

                const link = document.createElement('a');
                link.href = `/media/exports/${latest.name}`;
                link.download = latest.name;
                document.body.appendChild(link);
                link.click();
                document.body.removeChild(link);

                addToast({ type: 'success', title: t('Đã xuất video'), message: t('Video "{0}" đã sẵn sàng trong tab Tải xuống.').replace('{0}', latest.name) });
              }, 2000);
              return;
            }
          }
        } catch (e) {
          console.error("Polling error", e);
        }

        if (attempts > 40) { // Timeout after 40 seconds
          clearInterval(checkInterval);
          setPendingExport(false);
          setShowExport(false);
          addToast({ type: 'info', title: t('Đã gửi yêu cầu'), message: t('Yêu cầu tải xuống đã được gửi. Video sẽ xuất hiện trong tab "Tải xuống" sau ít phút.') });
        }
      }, 1000);

    } catch (e: any) {
      console.error('Export failed', e);
      addToast({ type: 'error', title: t('Lỗi tải xuống'), message: e.message || t('Không thể xuất video.') });
      setPendingExport(false);
    }
  }

  return (
    <div className="bg-[#F3F4F6] dark:bg-[#111827] font-body h-screen flex flex-col text-[#1F2937] dark:text-[#F9FAFB] overflow-hidden">
      {!isAppFullscreen && (
        <Header
          activeTab={activeTab}
          setActiveTab={handleTabChange}
          currentTime={currentTime}
        />
      )}
      <div className={`flex-1 overflow-hidden relative flex flex-col ${isAppFullscreen ? '' : 'pt-2'}`}>
        {/* Pattern Lock Overlay — shows during setup OR when session needs unlock */}
        {showLockScreen && (
          <PatternLockScreen
            isSetupMode={!isPatternSetup}
            onUnlock={handleUnlock}
            onSetupComplete={handleSetupComplete}
          />
        )}
        {activeTab === 'Live View' && <LiveView cameras={cameras} loading={loading} visibleCamIds={visibleCamIds} setVisibleCamIds={setVisibleCamIds} toggleCamera={toggleCamera} layout={layout} setLayout={setLayout} fullscreenCamId={fullscreenCamId} setFullscreenCamId={setFullscreenCamId} requestFullscreen={requestFullscreen} appFullscreen={isAppFullscreen} getCameraDetections={getCameraDetections} />}
        {activeTab === 'Playback' && (
          <>
            <PlaybackView
              cameras={cameras} playbackCam={playbackCam} setPlaybackCam={setPlaybackCam}
              timelineDate={timelineDate} setTimelineDate={setTimelineDate} fetchCameras={fetchCameras}
              recordings={recordings} isLoadingRecordings={isLoadingRecordings}
              showExport={showExport} setShowExport={setShowExport} handleExport={handleExport}
              addToast={addToast}
            />
            {showExport && (
              <ExportModal
                cameras={cameras}
                params={exportParams}
                setParams={setExportParams}
                onClose={() => setShowExport(false)}
                onDownload={performDownload}
                pendingExport={pendingExport}
              />
            )}
          </>
        )}
        {activeTab === 'Downloads' && <DownloadsView />}
        {activeTab === 'Cấu hình AI' && (
          <AITab cameras={cameras} addToast={addToast} />
        )}
        {activeTab === 'Setup' && (
          <SetupView
            setupTab={setupTab}
            setSetupTab={setSetupTab}
            cameras={cameras}
            handleDeleteCamera={handleDeleteCamera}
            handleAddCamera={handleAddCamera}
            setupStatus={setupStatus}
            fetchCameras={fetchCameras}
            addToast={addToast}
            setActiveTab={setActiveTab}
            setCameras={setCameras}
            visibleCamIds={visibleCamIds}
            toggleCamera={toggleCamera}
            setMiniPlayerCamId={setMiniPlayerCamId}
          />
        )}


      </div>
      {miniPlayerCamId && (
        <MiniPlayer
          camId={miniPlayerCamId}
          onClose={() => setMiniPlayerCamId(null)}
          onExpand={() => {
            setMiniPlayerCamId(null);
            if (!visibleCamIds.includes(miniPlayerCamId)) toggleCamera(miniPlayerCamId);
            setActiveTab('Live View');
            // Also optionally set fullscreen if desired, but adding to grid is safer default
          }}
        />
      )}
      <ToastContainer toasts={toasts} removeToast={removeToast} />
      <ConfirmDeleteModal
        isOpen={deleteModal.open}
        name={deleteModal.name}
        onClose={() => setDeleteModal({ ...deleteModal, open: false })}
        onConfirm={confirmDeleteCamera}
      />
    </div>
  );
}

export default App

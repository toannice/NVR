import { useState, useEffect, useRef, useCallback } from 'react'
import Hls from 'hls.js'
import AIConfigView from './components/AIConfigView'
import PatternLockScreen from './components/PatternLockScreen'
import BoundingBoxCanvas from './components/BoundingBoxCanvas'
import LiveCamera from './components/LiveCamera'
import { useMqttEvents } from './hooks/useMqttEvents'
import './App.css'
import customLogo from './assets/aicam-logo-white1.png'

// Mock Data for UI Fidelity
// Removed MOCK_GROUPS in favor of flat list as requested

// Mock Data removed

interface RecordingSegment {
  start_time: number;
  end_time: number;
}

// --- Icons --- //
const Icon = ({ name, className = "" }: { name: string, className?: string }) => (
  <span className={`material-icons ${className} `}>{name}</span>
)

// --- Sub-components --- //

const Header = ({ activeTab, setActiveTab, currentTime }: {
  activeTab: string,
  setActiveTab: (t: string) => void,
  currentTime: Date
}) => {
  const [deployVersion, setDeployVersion] = useState<string>('');
  useEffect(() => {
    fetch('/api/version')
      .then(r => r.json())
      .then(d => setDeployVersion(d.deployVersion || ''))
      .catch(() => {});
  }, []);

  return (
  <header className="h-14 flex items-center justify-between px-4 shadow-md z-20 shrink-0 text-white overflow-hidden relative" style={{ backgroundColor: '#EF4444' }}>
    <div className="flex items-center space-x-2 shrink-0">
      <img src={customLogo} alt="AI View Logo" className="h-10 w-auto object-contain rounded-sm" />
      {deployVersion && (
        <span
          title={`Build: ${deployVersion}`}
          className="hidden sm:inline-block bg-white/20 text-white text-[10px] font-mono px-1.5 py-0.5 rounded border border-white/30 leading-tight cursor-default select-none"
        >
          {deployVersion.length > 10 ? deployVersion.slice(0, 7) : deployVersion}
        </span>
      )}
    </div>

    <nav className="flex items-center space-x-1 flex-1 overflow-x-auto justify-center px-4 no-scrollbar">
      {['Live View', 'Playback', 'Downloads', 'Sự kiện AI', 'Setup'].map(id => (
        <button
          key={id}
          onClick={() => setActiveTab(id)}
          className={`px-4 py-2 rounded-full font-medium transition-colors text-sm whitespace-nowrap ${activeTab === id
            ? 'bg-white text-[#EF4444] shadow-sm'
            : 'text-white hover:bg-white/20'
            } `}
        >
          {id === 'Live View' ? 'Trực tiếp' : id === 'Playback' ? 'Xem lại' : id === 'Downloads' ? 'Tải xuống' : id === 'Sự kiện AI' ? 'Sự kiện AI' : 'Cấu hình'}
        </button>
      ))}
    </nav>

    <div className="flex items-center space-x-3 text-sm shrink-0">
      <span className="hidden xl:inline font-medium opacity-90 whitespace-nowrap">NVR AI View - {currentTime.toLocaleTimeString('vi-VN')}</span>
      <button
        onClick={() => {
          if (confirm('Bạn có muốn đăng xuất không?')) {
            (window as any).logout();
          }
        }}
        className="flex items-center space-x-1 hover:text-gray-200 transition-colors shrink-0"
      >
        <Icon name="logout" className="text-base" />
        <span className="hidden sm:inline">Đăng xuất</span>
      </button>
    </div>
  </header>
  )
}

// ============================================
// PTZ Control Component (Feature 2)
// ============================================

const PtzControls = ({ cameraId, ptzEnabled }: { cameraId: string; ptzEnabled: boolean }) => {
  const [isPtzOpen, setIsPtzOpen] = useState(false);
  const [isMoving, setIsMoving] = useState(false);
  const moveIntervalRef = useRef<number | null>(null);

  // Don't render anything if camera doesn't have PTZ enabled
  if (!ptzEnabled) return null;
  const sendPtzCommand = (pan: number, tilt: number, zoom: number = 0) => {
    fetch(`/api/cameras/${cameraId}/ptz/move`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pan, tilt, zoom })
    }).catch(() => {});
  };

  const stopPtz = () => {
    fetch(`/api/cameras/${cameraId}/ptz/stop`, { method: 'POST' }).catch(() => {});
    if (moveIntervalRef.current) { clearInterval(moveIntervalRef.current); moveIntervalRef.current = null; }
    setIsMoving(false);
  };

  const startContinuousMove = (e: React.PointerEvent, pan: number, tilt: number, zoom: number = 0) => {
    e.preventDefault();
    e.currentTarget.setPointerCapture(e.pointerId);
    
    setIsMoving(true);
    sendPtzCommand(pan, tilt, zoom);
  };

  const handlePointerUp = (e: React.PointerEvent) => {
    e.preventDefault();
    if (e.currentTarget.hasPointerCapture(e.pointerId)) {
      e.currentTarget.releasePointerCapture(e.pointerId);
    }
    stopPtz(); // ALWAYS stop on release, fixes sticky camera bug
  }

  const btnBase = "w-8 h-8 bg-gray-700 hover:bg-gray-600 active:bg-gray-500 rounded text-white flex items-center justify-center transition-colors select-none touch-none";
  const diagBase = `${btnBase} text-xs`;

  return (
    <div onClick={(e) => e.stopPropagation()} className="relative touch-none">
      {/* ── Toggle button: small icon, always visible ──────────────────── */}
      <button
        onClick={() => { setIsPtzOpen(o => !o); stopPtz(); }}
        className={`flex items-center justify-center w-8 h-8 rounded-full shadow-lg border transition-colors select-none
          ${isPtzOpen
            ? 'bg-blue-600 border-blue-400 text-white'
            : 'bg-black/70 border-gray-500 text-gray-200 hover:bg-black/90 hover:border-gray-300'
          }`}
        title="Điều khiển PTZ"
      >
        <Icon name="open_with" className="text-base" />
      </button>

      {/* ── D-pad panel: shown above the toggle button ─────────────────── */}
      {isPtzOpen && (
        <div className="absolute bottom-10 left-0 bg-black/80 backdrop-blur-sm rounded-lg p-2 shadow-2xl border border-gray-600 z-40 select-none">
          {/* Direction grid 3×3 */}
          <div className="grid grid-cols-3 gap-1 mb-1.5 touch-none">
            <button onPointerDown={(e) => startContinuousMove(e, -0.5,  0.5)} onPointerUp={handlePointerUp} onPointerCancel={handlePointerUp} onContextMenu={e => e.preventDefault()} className={diagBase} title="↖">↖</button>
            <button onPointerDown={(e) => startContinuousMove(e,  0,    0.5)} onPointerUp={handlePointerUp} onPointerCancel={handlePointerUp} onContextMenu={e => e.preventDefault()} className={btnBase}  title="Lên"><Icon name="arrow_upward"   className="text-sm" /></button>
            <button onPointerDown={(e) => startContinuousMove(e,  0.5,  0.5)} onPointerUp={handlePointerUp} onPointerCancel={handlePointerUp} onContextMenu={e => e.preventDefault()} className={diagBase} title="↗">↗</button>

            <button onPointerDown={(e) => startContinuousMove(e, -0.5,  0  )} onPointerUp={handlePointerUp} onPointerCancel={handlePointerUp} onContextMenu={e => e.preventDefault()} className={btnBase}  title="Trái"><Icon name="arrow_back"     className="text-sm" /></button>
            <button onClick={() => stopPtz()} className="w-8 h-8 bg-red-600 hover:bg-red-500 rounded text-white flex items-center justify-center transition-colors touch-none" title="Dừng"><Icon name="stop" className="text-sm" /></button>
            <button onPointerDown={(e) => startContinuousMove(e,  0.5,  0  )} onPointerUp={handlePointerUp} onPointerCancel={handlePointerUp} onContextMenu={e => e.preventDefault()} className={btnBase}  title="Phải"><Icon name="arrow_forward"  className="text-sm" /></button>

            <button onPointerDown={(e) => startContinuousMove(e, -0.5, -0.5)} onPointerUp={handlePointerUp} onPointerCancel={handlePointerUp} onContextMenu={e => e.preventDefault()} className={diagBase} title="↙">↙</button>
            <button onPointerDown={(e) => startContinuousMove(e,  0,   -0.5)} onPointerUp={handlePointerUp} onPointerCancel={handlePointerUp} onContextMenu={e => e.preventDefault()} className={btnBase}  title="Xuống"><Icon name="arrow_downward" className="text-sm" /></button>
            <button onPointerDown={(e) => startContinuousMove(e,  0.5, -0.5)} onPointerUp={handlePointerUp} onPointerCancel={handlePointerUp} onContextMenu={e => e.preventDefault()} className={diagBase} title="↘">↘</button>
          </div>

          {/* Zoom row */}
          <div className="flex gap-1 touch-none">
            <button onPointerDown={(e) => startContinuousMove(e, 0, 0,  0.3)} onPointerUp={handlePointerUp} onPointerCancel={handlePointerUp} onContextMenu={e => e.preventDefault()}
              className="flex-1 h-6 bg-gray-700 hover:bg-gray-600 rounded text-white text-xs flex items-center justify-center gap-0.5 transition-colors touch-none">
              <Icon name="add" className="text-xs" />Zoom
            </button>
            <button onPointerDown={(e) => startContinuousMove(e, 0, 0, -0.3)} onPointerUp={handlePointerUp} onPointerCancel={handlePointerUp} onContextMenu={e => e.preventDefault()}
              className="flex-1 h-6 bg-gray-700 hover:bg-gray-600 rounded text-white text-xs flex items-center justify-center gap-0.5 transition-colors touch-none">
              <Icon name="remove" className="text-xs" />Zoom
            </button>
          </div>

          {isMoving && <div className="mt-1 text-center text-[10px] text-green-400">Đang di chuyển…</div>}
        </div>
      )}
    </div>
  );
};

// ============================================
// LIVE CAMERA WITH HLS STREAMING
// ============================================

const CameraGridItem = ({
  cam,
  fullscreenCamId,
  setFullscreenCamId,
  toggleCamera,
  detections,
  cameraIndex: _cameraIndex = 0,
}: {
  cam: any,
  fullscreenCamId: string | null,
  setFullscreenCamId: (id: string | null) => void,
  toggleCamera: (id: string) => void,
  detections?: import('./hooks/useMqttEvents').Detection[],
  cameraIndex?: number,
}) => {
  if (!cam) {
    return (
      <div className="w-full h-full bg-[#111827] pointer-events-none flex items-center justify-center">
        <img src={customLogo} alt="AI View Logo" className="h-16 w-auto object-contain opacity-30 grayscale" />
      </div>
    );
  }

  return (
    <>
      <LiveCamera
        name={cam.id}
        className={`w-full h-full object-cover object-top ${!cam.isOnline ? 'hidden' : ''}`}
        isFullscreen={!!fullscreenCamId}
        isGrid={!fullscreenCamId}
      />

      {/* AI Bounding Box Canvas Overlay */}
      {cam.isOnline && (
        <BoundingBoxCanvas
          cameraId={cam.id}
          detections={detections ?? []}
          enabled={true}
        />
      )}

      {/* Connection Lost Overlay */}
      {!cam.isOnline && (
        <div className="absolute inset-0 bg-black flex flex-col items-center justify-center z-10">
          <Icon name="videocam_off" className="text-gray-500 text-4xl mb-2" />
          <span className="text-gray-400 text-sm font-medium">Mất kết nối</span>
        </div>
      )}

      <div className="absolute top-4 left-2 flex items-center space-x-2 z-20">
        {cam.record && <span className="bg-red-600 text-white text-[10px] px-1.5 py-0.5 rounded animate-pulse">REC</span>}
        <span className="bg-black/60 text-white text-xs px-2 py-0.5 rounded backdrop-blur-sm">{cam.name}</span>
        {/* Show active detection count badge */}
        {(detections?.length ?? 0) > 0 && (
          <span className="bg-red-600/80 text-white text-[10px] px-1.5 py-0.5 rounded font-bold animate-pulse">
            {detections!.length} AI
          </span>
        )}
      </div>

      {/* Camera Controls Overlay */}
      <div className="absolute top-2 right-2 flex items-center space-x-1 opacity-0 group-hover:opacity-100 transition-opacity z-40">
        <button
          onClick={(e) => { e.stopPropagation(); setFullscreenCamId(fullscreenCamId ? null : cam.id); }}
          className="bg-black/60 text-white p-1 rounded hover:bg-black/80"
          title={fullscreenCamId ? "Thu nhỏ" : "Phóng to"}
        >
          <Icon name={fullscreenCamId ? "fullscreen_exit" : "fullscreen"} className="text-sm" />
        </button>
        <button
          onClick={(e) => { e.stopPropagation(); toggleCamera(cam.id); }}
          className={`bg-black/60 text-white p-1 rounded hover:bg-black/80 hover:text-red-400 ${fullscreenCamId ? 'hidden' : ''}`}
          title="Đóng camera"
        >
          <Icon name="close" className="text-sm" />
        </button>
      </div>

      {/* PTZ Controls - bottom left, self-hides if camera has no PTZ */}
      {cam.isOnline && (
        <div className="absolute bottom-2 left-2 z-30" onClick={(e) => e.stopPropagation()}>
          <PtzControls cameraId={cam.id} ptzEnabled={!!cam.ptzEnabled} />
        </div>
      )}

    </>
  );
};

// MSEPlayer and WebRTCPlayer have been replaced by HLSPlayer (lines 62-108)
// Using HLS.js for stable, reliable streaming without WebSocket/proxy issues

const MiniPlayer = ({ camId, onClose, onExpand }: { camId: string, onClose: () => void, onExpand: () => void }) => {
  const [position, setPosition] = useState({ x: window.innerWidth - 340, y: window.innerHeight - 240 });
  const [isDragging, setIsDragging] = useState(false);
  const dragStart = useRef({ x: 0, y: 0 });

  const handleMouseDown = (e: React.MouseEvent) => {
    setIsDragging(true);
    dragStart.current = { x: e.clientX - position.x, y: e.clientY - position.y };
  };

  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      if (isDragging) {
        setPosition({ x: e.clientX - dragStart.current.x, y: e.clientY - dragStart.current.y });
      }
    };
    const handleMouseUp = () => setIsDragging(false);

    if (isDragging) {
      document.addEventListener('mousemove', handleMouseMove);
      document.addEventListener('mouseup', handleMouseUp);
    }
    return () => {
      document.removeEventListener('mousemove', handleMouseMove);
      document.removeEventListener('mouseup', handleMouseUp);
    };
  }, [isDragging]);

  return (
    <div
      className="fixed z-[100] w-80 h-52 bg-black rounded-lg shadow-2xl overflow-hidden border border-gray-600 flex flex-col"
      style={{ left: position.x, top: position.y }}
    >
      <div
        className="h-8 bg-gray-800 flex items-center justify-between px-2 cursor-move"
        onMouseDown={handleMouseDown}
      >
        <span className="text-white text-xs truncate max-w-[150px] font-mono">{camId}</span>
        <div className="flex space-x-1">
          <button onClick={onExpand} className="text-gray-300 hover:text-white p-1" title="Mở rộng"><Icon name="open_in_full" className="text-sm" /></button>
          <button onClick={onClose} className="text-gray-300 hover:text-red-400 p-1" title="Đóng"><Icon name="close" className="text-sm" /></button>
        </div>
      </div>
      <div className="flex-1 bg-black overflow-hidden relative" onDoubleClick={onClose}>
        <LiveCamera name={camId} className="w-full h-full object-cover object-top" isFullscreen={true} isGrid={false} />
      </div>
    </div>
  );
};

// --- LIVE VIEW COMPONENTS --- //
const LiveViewSidebar = ({ cameras, visibleCamIds, toggleCamera }: { cameras: any[], visibleCamIds: string[], toggleCamera: (id: string) => void }) => {
  const [searchTerm, setSearchTerm] = useState('');
  const filteredCameras = cameras.filter(c => c.name.toLowerCase().includes(searchTerm.toLowerCase()));

  return (
    <aside className="w-64 bg-white dark:bg-[#1F2937] border-r border-gray-200 dark:border-gray-700 flex flex-col shrink-0">
      <div className="p-3 border-b border-gray-200 dark:border-gray-700">
        <div className="relative">
          <span className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
            <Icon name="search" className="text-gray-400 text-sm" />
          </span>
          <input
            className="w-full bg-gray-100 dark:bg-gray-700 text-gray-800 dark:text-gray-200 border-none rounded-md py-1.5 pl-9 pr-3 text-sm focus:ring-1 focus:ring-[#EF4444]"
            placeholder="Tìm kiếm..."
            type="text"
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
          />
        </div>
      </div>
      <div className="flex-1 overflow-y-auto p-2">
        <ul className="space-y-1">
          {filteredCameras.length === 0 && (
            <li className="text-center text-gray-500 text-sm py-4">
              Không tìm thấy camera nào.
            </li>
          )}
          {filteredCameras.map(cam => {
            const isSelected = visibleCamIds.includes(cam.id);
            return (
              <li key={cam.id}>
                <button
                  draggable
                  onDragStart={(e) => {
                    e.dataTransfer.setData('cameraId', cam.id);
                  }}
                  onClick={() => toggleCamera(cam.id)}
                  className={`w-full flex items-center px-2 py-2 rounded-md hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors group text-left cursor-grab active:cursor-grabbing ${isSelected ? 'bg-red-50 dark:bg-red-900/20' : ''}`}
                  title="Kéo thả vào ô trống để thêm, hoặc Click để thêm tự động"
                >
                  <span className={`h-2.5 w-2.5 rounded-full mr-3 shrink-0 ${cam.isOnline ? 'bg-green-500' : 'bg-red-500'}`} title={cam.isOnline ? "Trực tuyến" : "Ngoại tuyến"}></span>
                  <div className="flex flex-col min-w-0 flex-1 pointer-events-none">
                    <span className={`text-sm font-medium truncate ${isSelected ? 'text-[#EF4444]' : 'text-gray-700 dark:text-gray-200'}`}>{cam.name}</span>
                    {cam.ip && <span className={`text-[10px] truncate ${isSelected ? 'text-red-400' : 'text-gray-500'}`}>{cam.ip}</span>}
                  </div>
                  {isSelected && <Icon name="check" className="text-[#EF4444] text-sm shrink-0 ml-2 pointer-events-none" />}
                </button>
              </li>
            );
          })}
        </ul>
      </div>
    </aside>
  )
}

const LiveView = ({
  cameras, loading, visibleCamIds, setVisibleCamIds, toggleCamera, layout, setLayout, fullscreenCamId, setFullscreenCamId, requestFullscreen, getCameraDetections
}: {
  cameras: any[], loading: boolean, visibleCamIds: string[], setVisibleCamIds: (ids: React.SetStateAction<string[]>) => void, toggleCamera: (id: string) => void, layout: number | string, setLayout: (l: number | string) => void, fullscreenCamId: string | null, setFullscreenCamId: (id: string | null) => void, requestFullscreen: () => void,
  getCameraDetections: (cameraId: string) => import('./hooks/useMqttEvents').Detection[]
}) => {
  const visibleCameras = visibleCamIds.map(id => id ? cameras.find(c => c.id === id) || null : null);
  const activeCamera = fullscreenCamId ? cameras.find(c => c.id === fullscreenCamId) : null;

  // Auto-hide toolbar logic
  const [showToolbar, setShowToolbar] = useState(true);
  const [isLayoutMenuOpen, setLayoutMenuOpen] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  let hideTimeout: ReturnType<typeof setTimeout> | null = null;
  const handleMouseMove = () => {
    setShowToolbar(true);
    if (hideTimeout) clearTimeout(hideTimeout);
    if (document.fullscreenElement) {
      hideTimeout = setTimeout(() => setShowToolbar(false), 2000);
    }
  };
  useEffect(() => {
    const onFsChange = () => {
      const fs = !!document.fullscreenElement;
      setIsFullscreen(fs);
      if (!fs) setShowToolbar(true);
    }
    // Set initial
    setIsFullscreen(!!document.fullscreenElement);
    document.addEventListener('fullscreenchange', onFsChange);
    return () => document.removeEventListener('fullscreenchange', onFsChange);
  }, []);

  // Dynamic grid classes
  const gridClasses: Record<string | number, string> = {
    1: 'grid-cols-1 grid-rows-1',
    2: 'grid-cols-2 grid-rows-2',
    3: 'grid-cols-3 grid-rows-3',
    4: 'grid-cols-4 grid-rows-4',
    5: 'grid-cols-5 grid-rows-5',
    '1+3': 'grid-layout-1-3',
    '1+4': 'grid-layout-1-4',
    '1+5': 'grid-layout-1-5',
    '1+6': 'grid-layout-1-6',
    '2+3': 'grid-layout-2-3',
    '2+4': 'grid-layout-2-4',
    '2+5': 'grid-layout-2-5',
  };
  const currentGridBaseClass = gridClasses[layout] || 'grid-cols-2 grid-rows-2';

  // Grid placehoder logic
  let totalCells = typeof layout === 'number' ? layout * layout : 4;
  if (typeof layout === 'string') {
    const parts = layout.split('+').map(Number);
    if (parts.length === 2) {
      totalCells = parts[0] + parts[1];
    }
  }

  const cells = [];
  const camerasToShow = fullscreenCamId && activeCamera ? [activeCamera] : visibleCameras;

  for (let i = 0; i < totalCells; i++) {
    cells.push(camerasToShow[i] || null);
  }
  // In fullscreen single mode, just show 1 cell
  const renderList = (fullscreenCamId && activeCamera) ? [activeCamera] : cells;
  const currentGridClass = fullscreenCamId ? 'grid-cols-1 grid-rows-1' : currentGridBaseClass;

  const handleDrop = (e: React.DragEvent, targetIndex: number) => {
    e.preventDefault();
    const draggedCamId = e.dataTransfer.getData('cameraId');
    if (!draggedCamId) return;

    // Feature check: Don't allow dropping if currently in fullscreen
    if (fullscreenCamId) {
      setFullscreenCamId(null);
    }

    setVisibleCamIds(prev => {
      let newArr = [...prev];

      const oldIndex = newArr.indexOf(draggedCamId);
      if (oldIndex !== -1) {
        newArr[oldIndex] = '';
      }

      while (newArr.length <= targetIndex) {
        newArr.push('');
      }

      const existingCam = newArr[targetIndex];
      newArr[targetIndex] = draggedCamId;

      if (existingCam && oldIndex !== -1) {
        newArr[oldIndex] = existingCam;
      } else if (existingCam) {
        const emptyIdx = newArr.indexOf('');
        if (emptyIdx !== -1) {
          newArr[emptyIdx] = existingCam;
        } else {
          newArr.push(existingCam);
        }
      }

      while (newArr.length > 0 && newArr[newArr.length - 1] === '') {
        newArr.pop();
      }

      return newArr;
    });
  };

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault(); // allow drop
  };

  return (
    <div className="flex flex-1 overflow-hidden h-full">
      <LiveViewSidebar cameras={cameras} visibleCamIds={visibleCamIds} toggleCamera={toggleCamera} />
      <main className="flex-1 flex flex-col bg-gray-100 dark:bg-gray-900 relative" id="live-view-container" onMouseMove={handleMouseMove}>
        {/* Grid Area */}
        <div className={`flex-1 p-2 grid gap-2 h-full overflow-hidden ${currentGridClass} `}>
          {loading && <div className="text-gray-500 flex items-center justify-center col-span-full">Đang tải camera...</div>}

          {renderList.map((cam, idx) => (
            <div
              key={cam ? cam.id : `empty-${idx}`}
              className={`relative bg-black rounded overflow-hidden group border border-gray-800 shadow-lg transition-colors ${cam ? '' : 'opacity-70 border-dashed border-gray-700 hover:border-gray-500'} ${fullscreenCamId ? '' : 'hover:ring-2 hover:ring-[#EF4444]'}`}
              onDoubleClick={() => cam && setFullscreenCamId(fullscreenCamId ? null : cam.id)}
              onDrop={(e) => handleDrop(e, idx)}
              onDragOver={handleDragOver}
            >
              <CameraGridItem
                cam={cam}
                cameraIndex={idx}
                fullscreenCamId={fullscreenCamId}
                setFullscreenCamId={setFullscreenCamId}
                toggleCamera={toggleCamera}
                detections={cam ? getCameraDetections(cam.id) : []}
              />
            </div>
          ))}
        </div>

        {/* Toolbar */}
        <div className={`
            flex items-center justify-between px-4 shrink-0 shadow-sm z-50 transition-all duration-300
            ${isFullscreen
            ? `absolute bottom-0 left-0 right-0 bg-white/90 dark:bg-[#1F2937]/90 backdrop-blur-sm border-t-0 p-3 h-16 ${showToolbar ? 'translate-y-0 opacity-100' : 'translate-y-full opacity-0'}`
            : 'relative h-14 bg-white dark:bg-[#1F2937] border-t border-gray-200 dark:border-gray-700 opacity-100'
          }
`}>
          <div className="flex items-center space-x-4">
            <div className="relative">
              <button
                onClick={() => setLayoutMenuOpen(!isLayoutMenuOpen)}
                className="flex items-center space-x-2 text-gray-700 dark:text-gray-200 hover:text-[#EF4444] transition-colors py-2"
              >
                <Icon name="grid_view" className="text-2xl" />
                <span className="font-medium">Bố cục: {typeof layout === 'number' ? `${layout}x${layout}` : layout}</span>
                <Icon name="expand_more" className="text-sm" />
              </button>
              {/* Grid selector dropdown */}
              {isLayoutMenuOpen && (
                <>
                  <div className="fixed inset-0 z-40" onClick={() => setLayoutMenuOpen(false)}></div>
                  <div className="absolute bottom-full left-0 mb-2 w-48 bg-white dark:bg-gray-800 rounded shadow-lg border border-gray-200 dark:border-gray-700 z-50 overflow-hidden flex flex-row">
                    <div className="flex-1 border-r border-gray-200 dark:border-gray-700">
                      {[1, 2, 3, 4, 5].map(n => (
                        <button
                          key={n}
                          onClick={() => { setLayout(n); setLayoutMenuOpen(false); }}
                          className={`w-full text-left px-4 py-2 text-sm hover:bg-gray-100 dark:hover:bg-gray-700 ${layout === n ? 'text-[#EF4444] font-bold' : 'text-gray-700 dark:text-gray-300'}`}
                        >
                          {n} x {n}
                        </button>
                      ))}
                    </div>
                    <div className="flex-1">
                      {['1+3', '1+4', '1+5', '1+6', '2+3', '2+4', '2+5'].map(l => (
                        <button
                          key={l}
                          onClick={() => { setLayout(l); setLayoutMenuOpen(false); }}
                          className={`w-full text-left px-4 py-2 text-sm hover:bg-gray-100 dark:hover:bg-gray-700 ${layout === l ? 'text-[#EF4444] font-bold' : 'text-gray-700 dark:text-gray-300'}`}
                        >
                          {l}
                        </button>
                      ))}
                    </div>
                  </div>
                </>
              )}
            </div>
            <div className="h-6 w-px bg-gray-300 dark:bg-gray-600"></div>
            <div className="flex items-center space-x-2 text-gray-500 dark:text-gray-400 text-sm">
              <span>Hiển thị: <span className="font-semibold text-gray-800 dark:text-white">{fullscreenCamId && activeCamera ? 1 : visibleCameras.filter(Boolean).length}</span> camera</span>
            </div>
          </div>
          <div className="flex items-center space-x-3">
            <button
              onClick={requestFullscreen}
              className="flex items-center space-x-2 px-3 py-1.5 text-white rounded-md shadow-sm transition-colors" style={{ backgroundColor: '#EF4444' }}
            >
              <Icon name={document.fullscreenElement ? "fullscreen_exit" : "fullscreen"} className="text-xl" />
              <span className="text-sm font-medium">{document.fullscreenElement ? "Thoát" : "Toàn màn hình"}</span>
            </button>
          </div>
        </div>
      </main>
    </div>
  )
}

// --- PLAYBACK COMPONENTS --- //
const PlaybackSidebar = ({ cameras, onSelect, selectedCam }: { cameras: any[], onSelect: (c: string) => void, selectedCam: string }) => {
  const [searchTerm, setSearchTerm] = useState('');
  const filteredCameras = cameras.filter(c => c.name.toLowerCase().includes(searchTerm.toLowerCase()));

  return (
    <aside className="w-64 bg-white dark:bg-[#1F2937] border-r border-gray-200 dark:border-gray-700 flex flex-col shrink-0">
      <div className="p-4 border-b border-gray-200 dark:border-gray-700">
        <h2 className="font-semibold text-lg mb-4 flex items-center gap-2 text-gray-800 dark:text-white">
          <Icon name="videocam" className="text-[#EF4444]" /> Danh sách Camera
        </h2>
        <div className="relative">
          <input
            className="w-full pl-9 pr-3 py-2 bg-gray-100 dark:bg-gray-700 border-none rounded text-sm focus:ring-2 focus:ring-[#EF4444] text-gray-800 dark:text-white"
            placeholder="Tìm kiếm camera..."
            type="text"
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
          />
          <Icon name="search" className="absolute left-2 top-2 text-gray-400 text-lg" />
        </div>
      </div>
      <div className="flex-1 overflow-y-auto p-2 space-y-1">
        {filteredCameras.length === 0 && <div className="text-gray-500 text-sm text-center py-4">Không tìm thấy.</div>}
        {filteredCameras.map(cam => (
          <button
            key={cam.id}
            onClick={() => onSelect(cam.id)}
            className={`w-full text-left px-3 py-2 rounded flex items-center gap-3 transition-colors ${selectedCam === cam.id
              ? 'bg-red-50 dark:bg-red-900/20 text-[#EF4444] font-medium'
              : 'text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700'
              }`}
          >
            <Icon name="circle" className={`text-sm ${selectedCam === cam.id ? 'text-[#EF4444]' : 'text-gray-400'}`} />
            <span className="truncate">{cam.name}</span>
          </button>
        ))}
      </div>
    </aside>
  )
}

/* eslint-disable-next-line @typescript-eslint/no-explicit-any */
const PlaybackRightPanel = ({ showExport, setShowExport: _setShowExport, onExport, timelineDate, setTimelineDate, playbackCam, onPlayEvent, cameras }: any) => {
  const [currentMonth, setCurrentMonth] = useState(new Date());
  const [daysWithData, setDaysWithData] = useState<number[]>([]);
  const [events, setEvents] = useState<any[]>([]);

  const getDaysInMonth = (year: number, month: number) => new Date(year, month + 1, 0).getDate();
  const getFirstDayOfMonth = (year: number, month: number) => new Date(year, month, 1).getDay();

  const year = currentMonth.getFullYear();
  const month = currentMonth.getMonth();
  const daysInMonth = getDaysInMonth(year, month);
  const firstDay = getFirstDayOfMonth(year, month);

  const days = [];
  for (let i = 0; i < firstDay; i++) { days.push(null); }
  for (let i = 1; i <= daysInMonth; i++) { days.push(i); }

  const handlePrevMonth = () => setCurrentMonth(new Date(year, month - 1, 1));
  const handleNextMonth = () => setCurrentMonth(new Date(year, month + 1, 1));

  // Fetch days with data using /api/recordings/summary (lightweight endpoint)
  useEffect(() => {
    const fetchMonthData = async () => {
      try {
        let url = `/api/frigate/api/recordings/summary`;
        if (playbackCam) {
          url += `?camera=${playbackCam}`;
        }
        const res = await fetch(url);
        if (res.ok) {
          const summary: Record<string, boolean> = await res.json();
          // Summary keys are dates like "2026-02-25", filter by current month
          const daysSet = new Set<number>();
          Object.keys(summary).forEach(dateStr => {
            const d = new Date(dateStr + 'T00:00:00');
            if (d.getFullYear() === year && d.getMonth() === month) {
              daysSet.add(d.getDate());
            }
          });
          setDaysWithData(Array.from(daysSet));
        } else {
          setDaysWithData([]);
        }
      } catch (e) {
        console.error('Failed calendar fetch', e);
        setDaysWithData([]);
      }
    };
    fetchMonthData();
  }, [year, month, playbackCam]);

  // Fetch Events for selected date
  useEffect(() => {
    const fetchEvents = async () => {
      if (!timelineDate) return;
      const start = new Date(timelineDate + 'T00:00:00').getTime() / 1000;
      const end = new Date(timelineDate + 'T23:59:59').getTime() / 1000;

      let url = `/api/frigate/api/events?after=${start}&before=${end}&limit=100`;
      if (playbackCam) {
        url += `&camera=${playbackCam}`;
      }

      try {
        const res = await fetch(url);
        if (res.ok) {
          const data = await res.json();
          setEvents(data);
        }
      } catch (e) { console.error(e); }
    };
    fetchEvents();
  }, [timelineDate, playbackCam]);

  return (
    <aside className="w-80 bg-white dark:bg-[#1F2937] border-l border-gray-200 dark:border-gray-700 flex flex-col shrink-0">
      <div className="p-4 border-b border-gray-200 dark:border-gray-700">
        <div className="flex items-center justify-between mb-4">
          <h3 className="font-bold text-lg text-gray-800 dark:text-white">
            Tháng {month + 1}/{year}
          </h3>
          <div className="flex gap-1">
            <button onClick={handlePrevMonth} className="p-1 hover:bg-gray-100 dark:hover:bg-gray-700 rounded"><Icon name="chevron_left" className="text-sm" /></button>
            <button onClick={handleNextMonth} className="p-1 hover:bg-gray-100 dark:hover:bg-gray-700 rounded"><Icon name="chevron_right" className="text-sm" /></button>
          </div>
        </div>

        <div className="grid grid-cols-7 gap-1 text-center text-sm mb-2 text-gray-400">
          {['CN', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7'].map(d => <span key={d} className="text-xs font-semibold py-1">{d}</span>)}
        </div>
        <div className="grid grid-cols-7 gap-1 text-center text-sm">
          {days.map((d, i) => {
            if (!d) return <div key={`empty-${i}`} />;

            const dateStr = `${year}-${String(month + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
            const isSelected = timelineDate === dateStr;
            const hasData = daysWithData.includes(d);

            return (
              <button
                key={d}
                onClick={() => setTimelineDate(dateStr)}
                className={`
                  relative py-1 rounded-full hover:bg-gray-100 dark:hover:bg-gray-700 flex flex-col items-center justify-center
                  ${isSelected ? 'bg-[#EF4444] text-white font-bold hover:bg-[#EF4444]' : 'text-gray-600 dark:text-gray-300'}
                `}
              >
                <span>{d}</span>
                {hasData && !isSelected && <span className="w-1 h-1 bg-red-500 rounded-full absolute bottom-0.5"></span>}
              </button>
            );
          })}
        </div>
      </div>

      <div className="flex-1 flex flex-col p-4 overflow-hidden">
        <div className="flex items-center justify-between mb-3">
          <h3 className="font-bold text-sm text-gray-700 dark:text-gray-300 uppercase tracking-wider">Sự kiện trong ngày</h3>
          <span className="text-xs text-gray-500">{events.length} sự kiện</span>
        </div>
        <div className="flex-1 overflow-y-auto space-y-3 mb-4 pr-1 custom-scrollbar">
          {events.length === 0 ? (
            <div className="text-center text-gray-500 text-sm py-4">Không có sự kiện nào</div>
          ) : (
            events.map((evt) => (
              <div key={evt.id} className="bg-white dark:bg-gray-800 p-3 rounded shadow-sm border-l-4 border-[#EF4444] hover:bg-gray-50 dark:hover:bg-gray-750 transition-colors group">
                <div className="flex justify-between items-start mb-1">
                  <span className="font-bold text-sm text-[#EF4444] capitalize">{evt.label.replace('_', ' ')}</span>
                  <span className="text-xs text-gray-500 font-mono">{new Date(evt.start_time * 1000).toLocaleTimeString()}</span>
                </div>
                <div className="flex gap-2 mt-2">
                  <img src={`/api/frigate/api/events/${evt.id}/thumbnail.jpg`} className="w-16 h-12 object-cover rounded bg-gray-200" alt="Thumb" loading="lazy" />
                  <div className="flex-1 flex flex-col justify-between min-w-0 pb-1">
                    <div className="text-xs text-gray-500 truncate" title={evt.id}>
                      {(() => {
                        const labelMap: Record<string, string> = {
                          'person': 'người',
                          'car': 'xe hơi',
                          'motorcycle': 'xe máy',
                          'bicycle': 'xe đạp',
                          'truck': 'xe tải',
                          'bus': 'xe khách',
                          'dog': 'chó',
                          'cat': 'mèo'
                        };
                        const label = labelMap[evt.label] || evt.label.replace('_', ' ');
                        const cameraMatch = cameras?.find((c: any) => c.slug === evt.camera || c.id === evt.camera || c.original_slug === evt.camera);
                        const camDisplayName = cameraMatch?.name;
                        const zoneName = evt.zones && evt.zones.length > 0 ? evt.zones[0] : '';

                        // Use camera display name if found, otherwise use zone name, otherwise use raw camera ID
                        const location = camDisplayName || zoneName || evt.camera || '';

                        return `Phát hiện ${label}${location ? ` ở ${location}` : ''}`;
                      })()}
                    </div>
                    <div className="flex flex-wrap gap-1 mt-1">
                      <button
                        onClick={() => onPlayEvent && onPlayEvent(`/api/frigate/api/events/${evt.id}/clip.mp4`)}
                        className="text-[10px] sm:text-xs bg-[#EF4444] text-white px-2 py-1 rounded hover:bg-red-600 transition-colors flex items-center gap-1"
                      >
                        <Icon name="play_arrow" className="text-[10px]" /> Video
                      </button>
                      {evt.has_snapshot && (
                        <button
                          onClick={() => onPlayEvent && onPlayEvent(`/api/frigate/api/events/${evt.id}/snapshot.jpg?bbox=1`)}
                          className="text-[10px] sm:text-xs bg-[#10B981] text-white px-2 py-1 rounded hover:bg-green-600 transition-colors flex items-center gap-1"
                          title="Xem ảnh AI có khoanh vùng"
                        >
                          <Icon name="crop_free" className="text-[10px]" /> Ảnh AI
                        </button>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            ))
          )}
        </div>
        <button onClick={onExport} className="w-full py-3 text-white font-bold rounded shadow-lg transition-transform transform active:scale-95 flex items-center justify-center gap-2 mt-auto" style={{ backgroundColor: '#EF4444' }}>
          <Icon name="download" />
          Tải Video
        </button>
        {showExport && <div className="mt-2 p-2 bg-gray-100 dark:bg-gray-800 rounded text-xs text-center">Đang xử lý tải xuống...</div>}
      </div>
    </aside>
  )
}

const ExportModal = ({ cameras, params, setParams, onClose, onDownload, pendingExport }: any) => (
  <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center p-4">
    <div className="bg-white dark:bg-[#1F2937] rounded-lg shadow-2xl w-full max-w-md overflow-hidden flex flex-col ring-1 ring-gray-200 dark:ring-gray-700">
      <div className="px-6 py-4 border-b border-gray-200 dark:border-gray-700 flex items-center justify-between bg-white dark:bg-gray-800">
        <h2 className="text-lg font-bold text-[#EF4444] flex items-center gap-2">
          <Icon name="download" className="text-xl" /> Tải video ghi hình
        </h2>
        <button onClick={onClose} className="text-gray-400 hover:text-gray-600 dark:hover:text-gray-200 p-1 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-full transition-colors">
          <Icon name="close" className="text-xl" />
        </button>
      </div>

      <div className="p-6 space-y-5">
        {pendingExport ? (
          <div className="flex flex-col items-center justify-center py-4 space-y-3">
            <div className="w-8 h-8 border-4 border-[#EF4444] border-t-transparent rounded-full animate-spin"></div>
            <p className="text-sm font-medium text-gray-700 dark:text-gray-300">Đang xử lý tải xuống...</p>
            <p className="text-xs text-gray-500">Vui lòng đợi trong giây lát.</p>
          </div>
        ) : (
          <>
            <div className="space-y-1.5">
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300">Chọn Camera</label>
              <div className="relative">
                <select
                  value={params.cam}
                  onChange={e => setParams({ ...params, cam: e.target.value })}
                  className="block w-full pl-3 pr-10 py-2.5 text-base border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-white focus:outline-none focus:ring-[#EF4444] focus:border-[#EF4444] sm:text-sm rounded-md shadow-sm appearance-none"
                >
                  {cameras.map((c: any) => <option key={c.name} value={c.name}>{c.name}</option>)}
                </select>
                <div className="pointer-events-none absolute inset-y-0 right-0 flex items-center px-2 text-gray-500">
                  <Icon name="expand_more" className="text-lg" />
                </div>
              </div>
            </div>

            <div className="space-y-1.5">
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300">Ngày ghi hình</label>
              <input
                type="date"
                value={params.date}
                onChange={e => setParams({ ...params, date: e.target.value })}
                className="block w-full pl-3 pr-3 py-2.5 border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-white focus:outline-none focus:ring-[#EF4444] focus:border-[#EF4444] sm:text-sm rounded-md shadow-sm"
              />
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-1.5">
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300">Bắt đầu</label>
                <input
                  type="time" step="1"
                  value={params.startTime}
                  onChange={e => setParams({ ...params, startTime: e.target.value })}
                  className="block w-full px-3 py-2.5 border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-white focus:ring-[#EF4444] focus:border-[#EF4444] sm:text-sm rounded-md shadow-sm"
                />
              </div>
              <div className="space-y-1.5">
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300">Kết thúc</label>
                <input
                  type="time" step="1"
                  value={params.endTime}
                  onChange={e => setParams({ ...params, endTime: e.target.value })}
                  className="block w-full px-3 py-2.5 border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-white focus:ring-[#EF4444] focus:border-[#EF4444] sm:text-sm rounded-md shadow-sm"
                />
              </div>
            </div>

            <div className="bg-blue-50 dark:bg-blue-900/30 border border-blue-200 dark:border-blue-800 rounded-md p-3 flex items-start gap-3">
              <Icon name="info" className="text-blue-500 dark:text-blue-400 text-sm mt-0.5" />
              <p className="text-xs text-blue-700 dark:text-blue-300">
                Thời gian tải xuống phụ thuộc vào độ dài video và tốc độ mạng. Vui lòng không đóng trình duyệt trong quá trình tải.
              </p>
            </div>
          </>
        )}
      </div>

      <div className="px-6 py-4 bg-gray-50 dark:bg-gray-800/50 border-t border-gray-200 dark:border-gray-700 flex justify-end gap-3">
        <button onClick={onClose} className="px-4 py-2 bg-white dark:bg-gray-700 text-gray-700 dark:text-gray-200 text-sm font-medium rounded-md border border-gray-300 dark:border-gray-600 hover:bg-gray-50 dark:hover:bg-gray-600 transition-colors shadow-sm">
          Hủy bỏ
        </button>
        {!pendingExport && (
          <button onClick={onDownload} className="px-4 py-2 bg-[#EF4444] hover:bg-red-700 text-white text-sm font-medium rounded-md shadow-sm transition-colors flex items-center gap-2">
            <Icon name="download" className="text-sm" />
            Bắt đầu tải
          </button>
        )}
      </div>
    </div>
  </div>
)

// --- Moved HLS and Video State into PlaybackView to fix tab-switching black screen --- //
const PlaybackView = ({
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
      // Determine Start/End for VOD Request
      // If vodWindow is set, use it.
      // If NOT set, default to last 2 hours of the day (or now)

      let start: number, end: number;

      if (vodWindow) {
        start = vodWindow.start;
        end = vodWindow.end;
      } else {
        // Default: Load last 2 hours max to be safe
        const dayEnd = Math.floor(new Date(timelineDate + 'T23:59:59').getTime() / 1000);
        const now = Math.floor(Date.now() / 1000);
        end = Math.min(dayEnd, now);
        start = end - 7200; // 2 hours back
        setVodWindow({ start, end }); // Sync state
      }

      // Add a random parameter to prevent caching of the playlist
      const url = `/api/frigate/vod/${playbackCam}/start/${start}/end/${end}/index.m3u8`;
      setVideoUrl(`${url}?t=${Date.now()}`);
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
          // Seek Logic for VOD Window
          // Note: vodWindow is captured from closure. Since this effect runs when videoUrl changes (which changes when vodWindow changes),
          // it should be fresh.
          if (vodWindow && !isMp4) { // Only offset for VOD playlist
            seekPos = Math.max(0, (seekTarget as unknown as number) - vodWindow.start);
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

// ============================================
// SETUP VIEW - Camera Management with ONVIF
// ============================================

// SETUP VIEW - Camera Management with ONVIF
// ============================================

// ============================================
// RECORDING SCHEDULE SECTION
// ============================================

const RecordingScheduleSection = ({ cameras, addToast }: any) => {
  const [selectedCamera, setSelectedCamera] = useState('');
  const [schedules, setSchedules] = useState<any>({});
  const [selectedDays, setSelectedDays] = useState<string[]>([]);
  const [startTime, setStartTime] = useState('08:00');
  const [endTime, setEndTime] = useState('18:00');
  const [isLoading, setIsLoading] = useState(false);

  const daysOfWeek = [
    { id: 'monday', label: 'Thứ 2' },
    { id: 'tuesday', label: 'Thứ 3' },
    { id: 'wednesday', label: 'Thứ 4' },
    { id: 'thursday', label: 'Thứ 5' },
    { id: 'friday', label: 'Thứ 6' },
    { id: 'saturday', label: 'Thứ 7' },
    { id: 'sunday', label: 'Chủ nhật' }
  ];

  useEffect(() => {
    fetchSchedules();
  }, []);

  const fetchSchedules = async () => {
    try {
      const res = await fetch('/api/recording-schedules');
      if (res.ok) {
        const data = await res.json();
        const grouped: any = {};
        data.forEach((sch: any) => {
          const cam = sch.camera || sch.cameraSlug || 'Unknown';
          if (!grouped[cam]) grouped[cam] = [];
          grouped[cam].push(sch);
        });
        setSchedules(grouped);
      }
    } catch (e) {
      console.error('Failed to load schedules:', e);
    }
  };

  const handleDayToggle = (dayId: string) => {
    setSelectedDays(prev =>
      prev.includes(dayId)
        ? prev.filter(d => d !== dayId)
        : [...prev, dayId]
    );
  };

  // Quick Select Helpers
  const selectWeekdays = () => setSelectedDays(['monday', 'tuesday', 'wednesday', 'thursday', 'friday']);
  const selectAllWeek = () => setSelectedDays(['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday']);

  const handleAddSchedule = async () => {
    if (!selectedCamera) {
      addToast?.({ type: 'error', message: 'Vui lòng chọn camera' });
      return;
    }
    if (selectedDays.length === 0) {
      addToast?.({ type: 'error', message: 'Vui lòng chọn ít nhất một ngày' });
      return;
    }

    setIsLoading(true);
    try {
      const res = await fetch('/api/recording-schedules', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          camera: selectedCamera,
          days: selectedDays,
          startTime,
          endTime
        })
      });

      if (res.ok) {
        addToast?.({ type: 'success', message: 'Đã thêm lịch trình thành công!' });
        fetchSchedules();
        // Don't clear camera, just days/time for next entry
        setSelectedDays([]);
        // setStartTime('08:00');
        // setEndTime('18:00');
      } else {
        const error = await res.json();
        addToast?.({ type: 'error', message: error.message || 'Lỗi khi lưu lịch trình' });
      }
    } catch (e: any) {
      addToast?.({ type: 'error', message: 'Lỗi kết nối: ' + e.message });
    } finally {
      setIsLoading(false);
    }
  };

  const handleDeleteSchedule = async (cameraName: string, scheduleId?: string) => {
    try {
      const url = scheduleId
        ? `/api/recording-schedules/${cameraName}/${scheduleId}`
        : `/api/recording-schedules/${cameraName}`;

      const res = await fetch(url, { method: 'DELETE' });
      if (res.ok) {
        addToast?.({ type: 'success', message: 'Đã xóa lịch trình thành công!' });
        fetchSchedules();
      }
    } catch (e: any) {
      addToast?.({ type: 'error', message: 'Lỗi khi xóa: ' + e.message });
    }
  };

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold text-gray-800 dark:text-white flex items-center">
        <Icon name="schedule" className="text-[#EF4444] mr-3 text-3xl" />
        Lịch trình ghi hình
      </h1>

      <div className="bg-white dark:bg-gray-800 rounded-lg shadow-md p-6 space-y-4">
        <h2 className="text-lg font-semibold text-gray-700 dark:text-gray-200">Thêm lịch trình ghi hình</h2>

        <div className="space-y-2">
          <label className="block text-sm font-medium text-gray-700 dark:text-gray-300">Chọn Camera</label>
          <select
            value={selectedCamera}
            onChange={(e) => setSelectedCamera(e.target.value)}
            className="w-full px-4 py-2 border border-gray-300 dark:border-gray-600 rounded-md bg-white dark:bg-gray-700 text-gray-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-[#EF4444]"
          >
            <option value="">-- Chọn camera áp dụng --</option>
            {cameras.map((cam: any) => (
              <option key={cam.name} value={cam.name}>{cam.name}</option>
            ))}
          </select>
        </div>

        <div className="space-y-2">
          <div className="flex justify-between items-center">
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300">Lặp lại vào các ngày</label>
            <div className="flex gap-2">
              <button onClick={selectWeekdays} className="text-xs bg-gray-200 dark:bg-gray-700 px-2 py-1 rounded hover:bg-gray-300 dark:hover:bg-gray-600">Trong tuần</button>
              <button onClick={selectAllWeek} className="text-xs bg-gray-200 dark:bg-gray-700 px-2 py-1 rounded hover:bg-gray-300 dark:hover:bg-gray-600">Cả tuần</button>
            </div>
          </div>
          <div className="grid grid-cols-7 gap-2">
            {daysOfWeek.map(day => (
              <button
                key={day.id}
                onClick={() => handleDayToggle(day.id)}
                className={`px-3 py-2 rounded-md text-sm font-medium transition-colors ${selectedDays.includes(day.id)
                  ? 'bg-[#EF4444] text-white'
                  : 'bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-300 hover:bg-gray-300 dark:hover:bg-gray-600'
                  }`}
              >
                {day.label}
              </button>
            ))}
          </div>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300">Giờ bắt đầu</label>
            <input
              type="time"
              value={startTime}
              onChange={(e) => setStartTime(e.target.value)}
              className="w-full px-4 py-2 border border-gray-300 dark:border-gray-600 rounded-md bg-white dark:bg-gray-700 text-gray-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-[#EF4444]"
            />
          </div>
          <div className="space-y-2">
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300">Giờ kết thúc</label>
            <input
              type="time"
              value={endTime}
              onChange={(e) => setEndTime(e.target.value)}
              className="w-full px-4 py-2 border border-gray-300 dark:border-gray-600 rounded-md bg-white dark:bg-gray-700 text-gray-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-[#EF4444]"
            />
          </div>
        </div>

        <button
          onClick={handleAddSchedule}
          disabled={isLoading}
          className="w-full px-4 py-2 bg-[#EF4444] hover:bg-red-600 text-white rounded-md font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
        >
          {isLoading ? (
            <>
              <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin"></div>
              Đang lưu...
            </>
          ) : (
            <>
              <Icon name="add" />
              Thêm lịch trình
            </>
          )}
        </button>
      </div>

      <div className="bg-white dark:bg-gray-800 rounded-lg shadow-md p-6">
        <h2 className="text-lg font-semibold text-gray-700 dark:text-gray-200 mb-4">Danh sách lịch trình đã thiết lập</h2>

        {Object.keys(schedules).length === 0 ? (
          <div className="text-center py-8 text-gray-500 dark:text-gray-400">
            <Icon name="schedule" className="text-4xl mb-2 opacity-50" />
            <p>Chưa có lịch trình nào được cấu hình</p>
          </div>
        ) : (
          <div className="space-y-6">
            {Object.keys(schedules).map(camSlug => {
              const camSchedules = Array.isArray(schedules[camSlug]) ? schedules[camSlug] : [schedules[camSlug]];
              const camera = cameras.find((c: any) => c.slug === camSlug);
              const displayName = camera ? camera.display_name : camSlug;
              return (
                <div key={camSlug} className="border border-gray-200 dark:border-gray-700 rounded-lg overflow-hidden">
                  <div className="bg-gray-50 dark:bg-gray-900 px-4 py-2 font-medium flex justify-between items-center">
                    <span>{displayName}</span>
                    <button onClick={() => handleDeleteSchedule(camSlug)} className="text-xs text-red-500 hover:underline">Xóa tất cả</button>
                  </div>
                  <div className="divide-y divide-gray-200 dark:divide-gray-700">
                    {camSchedules.map((sch: any, idx: number) => (
                      <div key={sch.id || idx} className="px-4 py-3 flex justify-between items-center">
                        <div>
                          <div className="text-sm font-medium">{sch.startTime} - {sch.endTime}</div>
                          <div className="text-xs text-gray-500">
                            {sch.days.map((d: string) => daysOfWeek.find(dow => dow.id === d)?.label).join(', ')}
                          </div>
                        </div>
                        <button
                          onClick={() => handleDeleteSchedule(camSlug, sch.id)}
                          className="text-gray-400 hover:text-red-500"
                          title="Xóa lịch trình này"
                        >
                          <Icon name="delete" />
                        </button>
                      </div>
                    ))}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
};

const SetupView = ({ setupTab, setSetupTab, cameras, setCameras, handleDeleteCamera, handleAddCamera, setupStatus, fetchCameras, addToast, setMiniPlayerCamId }: any) => {
  const [showOnvifModal, setShowOnvifModal] = useState(false);
  const [showManualModal, setShowManualModal] = useState(false);
  const [showCredentialsModal, setShowCredentialsModal] = useState(false);
  const [showEditModal, setShowEditModal] = useState(false);
  const [selectedDevice, setSelectedDevice] = useState<any>(null);
  const [editingCamera, setEditingCamera] = useState<any>(null);
  const [onvifDevices, setOnvifDevices] = useState<any[]>([]);
  const [isScanning, setIsScanning] = useState(false);
  const [isAddingDevice, setIsAddingDevice] = useState(false);

  const scanOnvifDevices = async () => {
    setIsScanning(true);
    try {
      const res = await fetch('/api/onvif/scan');
      const data = await res.json();
      setOnvifDevices(data.devices || []);
    } catch (e) {
      console.error(e);
      addToast({ type: 'error', title: 'Lỗi ONVIF', message: 'Không thể quét thiết bị: ' + (e as Error).message });
    } finally {
      setIsScanning(false);
    }
  };

  const togglePtzSetting = async (id: string, newEnabled: boolean) => {
    try {
      const res = await fetch(`/api/cameras/${id}/ptz-enabled`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ptzEnabled: newEnabled })
      });
      if (res.ok) {
        setCameras((prev: any[]) => prev.map(c => c.id === id ? { ...c, ptzEnabled: newEnabled } : c));
        addToast({ type: 'success', title: 'Thành công', message: 'Đã cập nhật cấu hình PTZ' });
      } else {
        const errorData = await res.json().catch(() => ({}));
        throw new Error(errorData.error || 'Lỗi không xác định');
      }
    } catch (e: any) {
      addToast({ type: 'error', title: 'Lỗi', message: `Không thể cập nhật PTZ: ${e.message}` });
    }
  };

  const handleAddOnvifDevice = async (device: any, data: { username: string, password: string, name: string }) => {
    setIsAddingDevice(true);
    // Update device name with the one from the modal
    const updatedDevice = { ...device, name: data.name };
    const credentials = { username: data.username, password: data.password };

    try {
      const res = await fetch('/api/onvif/add-device', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ device: updatedDevice, credentials })
      });
      const resData = await res.json();
      if (res.ok) {
        addToast({ type: 'success', title: 'Thêm thành công', message: `Camera ${data.name} đã được thêm!` });
        setOnvifDevices(prev => prev.map(d => d.id === device.id ? { ...d, isAdded: true, name: data.name } : d));
        setShowCredentialsModal(false);
        // Refresh cameras list automatically
        if (fetchCameras) await fetchCameras();
      } else {
        addToast({ type: 'error', title: 'Lỗi thêm camera', message: resData.error || 'Không thể thêm camera' });
      }
    } catch (e) {
      console.error(e);
      addToast({ type: 'error', title: 'Lỗi kết nối', message: (e as Error).message });
    } finally {
      setIsAddingDevice(false);
    }
  };

  return (
    <div className="flex flex-1 overflow-hidden h-full">
      <aside className="w-64 bg-white dark:bg-[#1F2937] border-r border-gray-200 dark:border-gray-700 flex flex-col shrink-0">
        <div className="p-4 border-b border-gray-200 dark:border-gray-700">
          <h2 className="text-sm font-bold text-gray-500 uppercase tracking-wider">Thiết lập Camera</h2>
        </div>
        <ul className="flex-1 overflow-y-auto py-2">
          {[
            { id: 'Camera List', icon: 'list', label: 'Danh sách Camera' },
            { id: 'Network', icon: 'settings_ethernet', label: 'Cấu hình mạng' },
            { id: 'Recording Schedule', icon: 'schedule', label: 'Lịch trình ghi hình' },
            { id: 'Storage', icon: 'storage', label: 'Lưu trữ' }
          ].map((item) => (
            <li key={item.id}>
              <button
                onClick={() => setSetupTab(item.id)}
                className={`w-full flex items-center px-6 py-3 transition-colors text-left ${setupTab === item.id
                  ? 'bg-red-50 dark:bg-red-900/20 text-[#EF4444] font-medium border-r-4 border-[#EF4444]'
                  : 'text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700'
                  }`}
              >
                <Icon name={item.icon} className="mr-3 text-xl" /> {item.label}
              </button>
            </li>
          ))}
        </ul>
      </aside>

      <div className="flex-1 bg-gray-100 dark:bg-gray-900 p-8 overflow-y-auto">
        {setupTab === 'Camera List' && (
          <>
            <div className="flex justify-between items-center mb-6">
              <h1 className="text-2xl font-bold text-gray-800 dark:text-white flex items-center">
                <Icon name="videocam" className="text-[#EF4444] mr-3 text-3xl" />
                Danh sách Camera
              </h1>
              <button
                onClick={() => {
                  setShowOnvifModal(true);
                  scanOnvifDevices();
                }}
                className="flex items-center px-4 py-2 bg-[#EF4444] text-white rounded shadow-md hover:bg-red-600 transition-colors"
              >
                <Icon name="add" className="text-xl mr-2" />
                Thêm mới
              </button>
            </div>

            <div className="bg-white dark:bg-[#1F2937] rounded-lg shadow border border-gray-200 dark:border-gray-700 overflow-hidden">
              <table className="w-full text-left border-collapse">
                <thead className="bg-gray-50 dark:bg-[#252525]">
                  <tr>
                    <th className="p-4 text-xs font-semibold tracking-wide text-gray-500 dark:text-gray-400 uppercase border-b border-gray-200 dark:border-gray-700 w-16 text-center">STT</th>
                    <th className="p-4 text-xs font-semibold tracking-wide text-gray-500 dark:text-gray-400 uppercase border-b border-gray-200 dark:border-gray-700">Tên Camera</th>
                    <th className="p-4 text-xs font-semibold tracking-wide text-gray-500 dark:text-gray-400 uppercase border-b border-gray-200 dark:border-gray-700">Địa chỉ IP</th>
                    <th className="p-4 text-xs font-semibold tracking-wide text-gray-500 dark:text-gray-400 uppercase border-b border-gray-200 dark:border-gray-700 text-center">Trạng thái</th>
                    <th className="p-4 text-xs font-semibold tracking-wide text-gray-500 dark:text-gray-400 uppercase border-b border-gray-200 dark:border-gray-700 text-center">PTZ</th>
                    <th className="p-4 text-xs font-semibold tracking-wide text-gray-500 dark:text-gray-400 uppercase border-b border-gray-200 dark:border-gray-700 text-center w-40">Hành động</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-200 dark:divide-gray-700 bg-white dark:bg-[#1F2937]">
                  {cameras.map((c: any, idx: number) => (
                    <tr key={c.name} className="hover:bg-gray-50 dark:hover:bg-[#2A2A2A] transition-colors group">
                      <td className="p-4 text-sm text-center text-gray-500 dark:text-gray-400">{idx + 1}</td>
                      <td className="p-4 text-sm font-medium text-gray-900 dark:text-white">
                        <div className="flex items-center">
                          <span className="w-8 h-8 rounded bg-gray-200 dark:bg-gray-700 flex items-center justify-center mr-3 text-gray-500">
                            <Icon name="videocam" className="text-lg" />
                          </span>
                          {c.name}
                        </div>
                      </td>
                      <td className="p-4 text-sm text-gray-600 dark:text-gray-300 font-mono">{c.ip || 'N/A'}</td>
                      <td className="p-4 text-center">
                        <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${c.isOnline
                          ? 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400'
                          : 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400'
                          }`}>
                          <span className={`w-1.5 h-1.5 rounded-full ${c.isOnline ? 'bg-green-500' : 'bg-red-500'} mr-1.5`}></span>
                          {c.isOnline ? 'Online' : 'Offline'}
                        </span>
                      </td>
                      <td className="p-4 text-center">
                        <button
                          onClick={() => togglePtzSetting(c.id, !c.ptzEnabled)}
                          className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors focus:outline-none ${
                            c.ptzEnabled ? 'bg-[#F04E30]' : 'bg-gray-300 dark:bg-gray-600'
                          }`}
                        >
                          <span className={`inline-block h-3.5 w-3.5 transform rounded-full bg-white shadow-md transition-transform ${
                            c.ptzEnabled ? 'translate-x-[18px]' : 'translate-x-1'
                          }`} />
                        </button>
                      </td>
                      <td className="p-4">
                        <div className="flex justify-center space-x-2">
                          <button
                            onClick={() => {
                              // Open Mini Player instead of navigating
                              setMiniPlayerCamId(c.id);
                            }}
                            className="p-1.5 text-gray-500 hover:text-blue-600 hover:bg-blue-50 dark:hover:bg-blue-900/20 rounded transition-colors"
                            title="Xem nhanh (Mini Player)"
                          >
                            <Icon name="visibility" className="text-xl" />
                          </button>
                          <button
                            onClick={() => {
                              setEditingCamera(c);
                              setShowEditModal(true);
                            }}
                            className="p-1.5 text-gray-500 hover:text-gray-800 hover:bg-gray-100 dark:hover:bg-gray-700 rounded transition-colors"
                            title="Cấu hình"
                          >
                            <Icon name="settings" className="text-xl" />
                          </button>
                          <button
                            onClick={() => handleDeleteCamera(c.id)}
                            className="p-1.5 text-gray-500 hover:text-red-600 hover:bg-red-50 rounded transition-colors"
                            title="Xóa"
                          >
                            <Icon name="delete" className="text-xl" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {cameras.length === 0 && (
                <div className="p-10 text-center text-gray-500 dark:text-gray-400">Chưa có camera nào</div>
              )}
            </div>
          </>
        )}

        {setupTab === 'Add Camera' && (
          <div className="p-10 text-center text-gray-500 dark:text-gray-400">
            Chức năng thêm camera đã được chuyển vào tab "Danh sách Camera" → Nút "Thêm mới"
          </div>
        )}

        {setupTab === 'Network' && <NetworkConfigSection />}
        {setupTab === 'Recording Schedule' && <RecordingScheduleSection cameras={cameras} addToast={addToast} />}
        {setupTab === 'Storage' && <StorageConfigSection />}
      </div>

      {showOnvifModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-8">
          <div className="bg-white dark:bg-[#1F2937] w-full max-w-5xl rounded-lg shadow-2xl flex flex-col max-h-[90vh] border border-gray-200 dark:border-gray-700 overflow-hidden">
            <div className="px-6 py-4 border-b border-gray-200 dark:border-gray-700 flex justify-between items-center">
              <h2 className="text-xl font-bold text-[#EF4444] flex items-center">
                <Icon name="travel_explore" className="mr-2" />
                Thêm Camera qua LAN/ONVIF
              </h2>
              <button onClick={() => setShowOnvifModal(false)} className="text-gray-400 hover:text-gray-600">
                <Icon name="close" />
              </button>
            </div>

            <div className="p-6 flex-1 overflow-hidden flex flex-col min-h-0">
              <div className="flex justify-between items-center mb-6">
                <div className="text-sm text-gray-500">
                  Tìm thấy <span className="font-bold text-[#EF4444]">{onvifDevices.length}</span> thiết bị ONVIF
                </div>
                <button
                  onClick={scanOnvifDevices}
                  disabled={isScanning}
                  className="px-4 py-2 bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-300 rounded hover:bg-gray-300 flex items-center text-sm font-medium disabled:opacity-50"
                >
                  <Icon name="refresh" className="text-lg mr-1" /> {isScanning ? 'Đang quét...' : 'Quét lại'}
                </button>
                <button
                  onClick={() => {
                    setShowOnvifModal(false);
                    setShowManualModal(true);
                  }}
                  className="px-4 py-2 bg-[#EF4444] text-white rounded hover:bg-red-600 transition-colors flex items-center text-sm font-medium shadow-sm"
                >
                  <Icon name="add" className="text-lg mr-1" /> Thêm thủ công
                </button>
              </div>

              <div className="flex-1 overflow-auto border rounded min-h-0">
                <table className="w-full text-left">
                  <thead className="bg-gray-50 dark:bg-[#252525] sticky top-0">
                    <tr>
                      <th className="p-3 text-xs font-semibold text-gray-500 uppercase">STT</th>
                      <th className="p-3 text-xs font-semibold text-gray-500 uppercase">Tên Camera</th>
                      <th className="p-3 text-xs font-semibold text-gray-500 uppercase">IP</th>
                      <th className="p-3 text-xs font-semibold text-gray-500 uppercase">Trạng thái</th>
                      <th className="p-3 text-xs font-semibold text-gray-500 uppercase text-right">Hành động</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y">
                    {onvifDevices.map((device, idx) => (
                      <tr key={device.id} className="hover:bg-gray-50 dark:hover:bg-[#2A2A2A]">
                        <td className="p-3 text-sm text-center">{idx + 1}</td>
                        <td className="p-3 text-sm font-medium">
                          <Icon name="videocam" className="inline mr-2" />{device.name}
                        </td>
                        <td className="p-3 text-sm font-mono">{device.ip}</td>
                        <td className="p-3">
                          {device.status === 'online' && (
                            <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs bg-green-100 text-green-800">
                              <span className="w-1.5 h-1.5 rounded-full bg-green-500 mr-1"></span>Online
                            </span>
                          )}
                        </td>
                        <td className="p-3 text-right">
                          {device.isAdded ? (
                            <button className="px-3 py-1 bg-gray-200 text-gray-400 rounded text-sm" disabled>
                              Đã thêm
                            </button>
                          ) : (
                            <button
                              onClick={() => {
                                setSelectedDevice(device);
                                setShowCredentialsModal(true);
                              }}
                              className="px-3 py-1 bg-[#EF4444]/10 hover:bg-[#EF4444] text-[#EF4444] hover:text-white border border-[#EF4444]/20 rounded text-sm transition"
                            >
                              Thêm
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            <div className="px-6 py-4 bg-gray-50 dark:bg-[#252525] border-t flex justify-end gap-3">
              <button onClick={() => setShowOnvifModal(false)} className="px-4 py-2 border rounded">Xong</button>
            </div>
          </div>
        </div>
      )}

      {/* Credentials Modal for ONVIF Device */}
      {showCredentialsModal && selectedDevice && (
        <OnvifCredentialsModal
          device={selectedDevice}
          onClose={() => {
            setShowCredentialsModal(false);
            setSelectedDevice(null);
          }}
          onSubmit={(credentials: any) => handleAddOnvifDevice(selectedDevice, credentials)}
          isLoading={isAddingDevice}
        />
      )}

      {/* Camera Edit Modal */}
      {showEditModal && editingCamera && (
        <EditCameraModal
          device={editingCamera}
          onClose={() => {
            setShowEditModal(false);
            setEditingCamera(null);
          }}
          onSubmit={async (newName: string) => {
            try {
              const res = await fetch('/api/edit-camera', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ id: editingCamera.original_slug || editingCamera.id, name: newName })
              });
              const data = await res.json();
              if (!res.ok) throw new Error(data.error || 'Failed to rename');
              addToast({ type: 'success', title: 'Thành công', message: data.message });
              setShowEditModal(false);
              setEditingCamera(null);
              fetchCameras(); // Refresh the list
            } catch (e: any) {
              addToast({ type: 'error', title: 'Lỗi', message: e.message });
            }
          }}
          isLoading={false}
        />
      )}


      {/* Manual Add Camera Modal */}
      {showManualModal && (
        <ManualCameraForm
          onClose={() => setShowManualModal(false)}
          onAdd={handleAddCamera}
          status={setupStatus}
          addToast={addToast}
        />
      )}
    </div>
  );
};

// --- Network Config Section --- //
function NetworkConfigSection() {
  const [data, setData] = useState<any>(null);
  useEffect(() => {
    fetch('/api/network-config').then(r => r.json()).then(setData).catch(console.error);
  }, []);

  if (!data) return <div className="p-10 text-center text-gray-500">Đang tải thông tin mạng...</div>;

  const Field = ({ label, value }: any) => (
    <div className="mb-6">
      <label className="block text-sm font-semibold text-gray-500 dark:text-gray-400 mb-2">{label}</label>
      <div className="bg-white dark:bg-[#2D2D2D] text-gray-900 dark:text-white px-4 py-3 rounded-lg border border-gray-200 dark:border-gray-700 font-mono text-lg shadow-sm">
        {value || '...'}
      </div>
    </div>
  );

  return (
    <div className="p-8 max-w-4xl mx-auto">
      <h2 className="text-2xl font-bold text-gray-800 dark:text-white mb-8 border-b border-gray-200 dark:border-gray-700 pb-4 flex items-center">
        <Icon name="settings_ethernet" className="mr-3 text-[#F04E30]" />
        Cấu hình Mạng
      </h2>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
        <div className="space-y-1">
          <Field label="Địa chỉ IPv4" value={data.ip} />
          <Field label="Subnet Mask" value={data.subnet} />
          <Field label="Default Gateway" value={data.gateway} />
        </div>
        <div className="space-y-1">
          <Field label="DNS Ưu tiên" value={data.dns1} />
          <Field label="DNS Phụ" value={data.dns2} />
        </div>
      </div>
    </div>
  );
};

// --- Storage Config Section --- //
function StorageConfigSection() {
  const [data, setData] = useState<any>(null);
  useEffect(() => {
    fetch('/api/storage-config').then(r => r.json()).then(setData).catch(console.error);
  }, []);

  if (!data) return <div className="p-10 text-center text-gray-500">Đang tải thông tin lưu trữ...</div>;

  const formatBytes = (bytes: number) => {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + ['B', 'KB', 'MB', 'GB', 'TB'][i];
  };

  const freePercent = Math.min((data.free / data.total) * 100, 100);

  return (
    <div className="p-8 max-w-4xl mx-auto">
      <h2 className="text-2xl font-bold text-gray-800 dark:text-white mb-8 border-b border-gray-200 dark:border-gray-700 pb-4 flex items-center">
        <Icon name="storage" className="mr-3 text-[#F04E30]" />
        Trạng thái Lưu trữ
      </h2>

      <div className="bg-white dark:bg-[#1F2937] p-8 rounded-xl shadow-sm border border-gray-200 dark:border-gray-700">
        <div className="flex justify-between items-end mb-4">
          <div className="text-lg font-bold text-gray-700 dark:text-gray-300">Dung lượng còn trống</div>
          <div className="text-sm text-gray-500">{formatBytes(data.folderUsed)} Sử dụng / {formatBytes(data.total)} Máy chủ</div>
        </div>

        <div className="relative h-6 bg-gray-200 dark:bg-gray-700 rounded-full overflow-hidden mb-2">
          {/* Progress Bar (Free Space) */}
          <div
            className="absolute h-full bg-[#F04E30] transition-all duration-1000 ease-out"
            style={{ width: `${freePercent}%` }}
          ></div>
          {/* Text Overlay (so it doesn't clip if the bar is very small) */}
          <div className="absolute inset-0 flex items-center px-3 pointer-events-none">
            <span className="text-white text-xs font-bold drop-shadow-md">
              {formatBytes(data.free)}
            </span>
          </div>
        </div>

        <div className="flex justify-between text-sm mt-4">
          <div className="flex items-center">
            <span className="w-3 h-3 bg-[#F04E30] rounded-full mr-2"></span>
            <span className="text-gray-600 dark:text-gray-300">Dữ liệu NVR: <strong>{formatBytes(data.folderUsed)}</strong></span>
          </div>
        </div>
      </div>
    </div>
  );
};

// --- Confirmation Modal --- //
function ConfirmDeleteModal({ name, isOpen, onClose, onConfirm }: any) {
  if (!isOpen) return null;
  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/60 backdrop-blur-sm p-4 animate-in fade-in duration-200">
      <div className="bg-white dark:bg-[#1F2937] w-full max-w-md rounded-[20px] shadow-2xl overflow-hidden transform scale-100 transition-all border border-gray-200 dark:border-gray-700">
        <div className="p-8 text-center">
          <div className="w-16 h-16 bg-red-100 dark:bg-red-900/20 rounded-full flex items-center justify-center mx-auto mb-6 ring-4 ring-red-50 dark:ring-red-900/10">
            <Icon name="delete_forever" className="text-3xl text-red-600 dark:text-red-500" />
          </div>
          <h3 className="text-xl font-bold text-gray-900 dark:text-white mb-2">Xóa Camera?</h3>
          <p className="text-gray-500 dark:text-gray-400 mb-8 leading-relaxed">
            Bạn có chắc chắn muốn xóa camera <br />
            <span className="font-bold text-gray-800 dark:text-gray-200 text-lg">"{name}"</span> không?
          </p>
          <div className="flex gap-3">
            <button onClick={onClose} className="flex-1 py-3 px-4 bg-gray-100 hover:bg-gray-200 dark:bg-gray-700 dark:hover:bg-gray-600 text-gray-700 dark:text-gray-200 font-semibold rounded-xl transition-colors">
              Hủy bỏ
            </button>
            <button onClick={onConfirm} className="flex-1 py-3 px-4 bg-[#F04E30] hover:bg-[#D63A1D] text-white font-semibold rounded-xl shadow-lg shadow-red-500/30 transition-all transform active:scale-[0.98]">
              Xóa ngay
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// Edit Camera Name Modal Component (No ONVIF credentials required)
function EditCameraModal({ device, onClose, onSubmit, isLoading }: any) {
  const [cameraName, setCameraName] = useState(device.name || '');

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!cameraName) {
      alert('Vui lòng nhập tên camera');
      return;
    }
    onSubmit(cameraName);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
      <div className="bg-white dark:bg-[#2D2D2D] w-full max-w-md rounded-lg shadow-2xl border border-gray-200 dark:border-gray-700 overflow-hidden">
        <div className="px-6 py-4 border-b border-gray-200 dark:border-gray-700 flex justify-between items-center bg-white dark:bg-[#333]">
          <h2 className="text-lg font-bold text-indigo-600 dark:text-indigo-400 flex items-center">
            <Icon name="edit" className="mr-2" />
            Đổi tên Camera
          </h2>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 dark:hover:text-gray-200 transition-colors" disabled={isLoading}>
            <Icon name="close" />
          </button>
        </div>

        <div className="p-6">
          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                Tên Camera <span className="text-red-500">*</span>
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                  <Icon name="videocam" className="text-gray-400 text-lg" />
                </div>
                <input
                  required
                  className="w-full pl-10 rounded border-gray-300 dark:border-gray-600 dark:bg-gray-800 dark:text-white focus:ring-indigo-500 focus:border-indigo-500 shadow-sm"
                  placeholder="Ví dụ: Camera Cổng"
                  type="text"
                  value={cameraName}
                  onChange={e => setCameraName(e.target.value)}
                  disabled={isLoading}
                />
              </div>
            </div>

            <div className="flex justify-end gap-3 mt-6">
              <button
                type="button"
                onClick={onClose}
                className="px-4 py-2 border border-gray-300 dark:border-gray-600 rounded text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-800 transition-colors"
                disabled={isLoading}
              >
                Hủy
              </button>
              <button
                type="submit"
                className="px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded shadow transition-colors flex items-center disabled:opacity-50"
                disabled={isLoading}
              >
                {isLoading ? <Icon name="refresh" className="animate-spin mr-2" /> : <Icon name="save" className="mr-2 text-sm" />}
                Lưu tên mới
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
}

// ONVIF Credentials Modal Component
function OnvifCredentialsModal({ device, onClose, onSubmit, isLoading, addToast }: any) {
  const [cameraName, setCameraName] = useState(device.name || '');
  const [credentials, setCredentials] = useState({
    username: 'admin',
    password: ''
  });
  const [ptzEnabled, setPtzEnabled] = useState(false);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!cameraName) {
      addToast({ type: 'error', title: 'Lỗi', message: 'Vui lòng nhập tên camera' });
      return;
    }
    if (!credentials.password) {
      addToast({ type: 'error', title: 'Lỗi', message: 'Vui lòng nhập mật khẩu ONVIF' });
      return;
    }
    // Pass the edited name along with credentials
    onSubmit({ ...credentials, name: cameraName, ptzEnabled });
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
      <div className="bg-white dark:bg-[#2D2D2D] w-full max-w-md rounded-lg shadow-2xl border border-gray-200 dark:border-gray-700 overflow-hidden">
        <div className="px-6 py-4 border-b border-gray-200 dark:border-gray-700 flex justify-between items-center bg-white dark:bg-[#333]">
          <h2 className="text-lg font-bold text-[#F04E30] flex items-center">
            <Icon name="key" className="mr-2" />
            Cấu hình Camera ONVIF
          </h2>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 dark:hover:text-gray-200 transition-colors" disabled={isLoading}>
            <Icon name="close" />
          </button>
        </div>

        <div className="p-6">
          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                Tên Camera <span className="text-red-500">*</span>
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                  <Icon name="videocam" className="text-gray-400 text-lg" />
                </div>
                <input
                  required
                  className="w-full pl-10 rounded border-gray-300 dark:border-gray-600 dark:bg-gray-800 dark:text-white focus:ring-[#F04E30] focus:border-[#F04E30] shadow-sm"
                  placeholder="Ví dụ: Camera Cổng"
                  type="text"
                  value={cameraName}
                  onChange={e => setCameraName(e.target.value)}
                  disabled={isLoading}
                />
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                Tên đăng nhập ONVIF <span className="text-red-500">*</span>
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                  <Icon name="person" className="text-gray-400 text-lg" />
                </div>
                <input
                  required
                  className="w-full pl-10 rounded border-gray-300 dark:border-gray-600 dark:bg-gray-800 dark:text-white focus:ring-[#F04E30] focus:border-[#F04E30] shadow-sm"
                  placeholder="admin"
                  type="text"
                  value={credentials.username}
                  onChange={e => setCredentials({ ...credentials, username: e.target.value })}
                  disabled={isLoading}
                />
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                Mật khẩu ONVIF <span className="text-red-500">*</span>
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                  <Icon name="lock" className="text-gray-400 text-lg" />
                </div>
                <input
                  required
                  className="w-full pl-10 rounded border-gray-300 dark:border-gray-600 dark:bg-gray-800 dark:text-white focus:ring-[#F04E30] focus:border-[#F04E30] shadow-sm"
                  placeholder="••••••••"
                  type="password"
                  value={credentials.password}
                  onChange={e => setCredentials({ ...credentials, password: e.target.value })}
                  disabled={isLoading}
                />
              </div>
            </div>

            {/* PTZ Toggle */}
            <div className="flex items-center justify-between py-2 px-3 bg-gray-50 dark:bg-gray-800/50 rounded border border-gray-200 dark:border-gray-700">
              <div>
                <p className="text-sm font-medium text-gray-700 dark:text-gray-300">Camera hỗ trợ PTZ</p>
                <p className="text-xs text-gray-500 dark:text-gray-400">Bật nếu camera có thể xoay / tilt / zoom</p>
              </div>
              <button
                type="button"
                onClick={() => setPtzEnabled(v => !v)}
                className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors focus:outline-none ${
                  ptzEnabled ? 'bg-[#F04E30]' : 'bg-gray-300 dark:bg-gray-600'
                }`}
              >
                <span className={`inline-block h-4 w-4 transform rounded-full bg-white shadow-md transition-transform ${
                  ptzEnabled ? 'translate-x-6' : 'translate-x-1'
                }`} />
              </button>
            </div>

            <div className="text-xs text-gray-500 dark:text-gray-400 mt-2 bg-gray-50 dark:bg-gray-800/50 p-2 rounded border border-gray-200 dark:border-gray-700">
              <Icon name="info" className="text-xs mr-1" />
              IP Thiết bị: {device.ip}
            </div>
          </form>
        </div>

        <div className="px-6 py-4 bg-gray-50 dark:bg-[#252525] border-t border-gray-200 dark:border-gray-700 flex justify-end gap-3">
          <button
            onClick={onClose}
            disabled={isLoading}
            className="px-5 py-2.5 border border-gray-300 dark:border-gray-600 text-gray-700 dark:text-gray-300 rounded hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors font-medium text-sm disabled:opacity-50"
          >
            Hủy
          </button>
          <button
            onClick={handleSubmit}
            disabled={isLoading}
            className="px-6 py-2.5 bg-[#F04E30] text-white rounded hover:bg-[#D63A1D] transition-colors font-medium shadow-md flex items-center text-sm disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {isLoading ? (
              <>
                <Icon name="hourglass_empty" className="text-lg mr-2 animate-spin" />
                Đang thêm...
              </>
            ) : (
              <>
                <Icon name="add" className="text-lg mr-2" />
                Thêm Camera
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
};

// Toast Notification Component
const ToastContainer = ({ toasts, removeToast }: any) => {
  return (
    <div className="fixed bottom-4 right-4 z-[9999] flex flex-col gap-2">
      {toasts.map((toast: any) => (
        <div
          key={toast.key}
          className={`flex items-center p-4 rounded-lg shadow-lg text-white min-w-[300px] animate-fade-in-up transition-all duration-300 ${toast.type === 'success' ? 'bg-green-600' :
            toast.type === 'error' ? 'bg-red-600' : 'bg-blue-600'
            }`}
        >
          <Icon name={toast.type === 'success' ? 'check_circle' : toast.type === 'error' ? 'error' : 'info'} className="mr-3 text-2xl" />
          <div className="flex-1">
            <h4 className="font-bold text-sm">{toast.title}</h4>
            <p className="text-sm opacity-90">{toast.message}</p>
          </div>
          <button onClick={() => removeToast(toast.key)} className="ml-2 hover:bg-white/20 rounded-full p-1 transition-colors">
            <Icon name="close" className="text-sm" />
          </button>
        </div>
      ))}
    </div>
  );
};

// Manual Camera Add Form Component matching design
function ManualCameraForm({ onClose, onAdd, status, addToast }: any) {
  const [formData, setFormData] = useState({
    name: '',
    ip: '',
    port: '80', // ONVIF Port
    rtspPort: '554', // RTSP Port
    username: 'admin',
    password: '',
    path: '/stream1',
    addToLiveView: true
  });
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isProbing, setIsProbing] = useState(false);
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [error, setError] = useState('');

  const handleProbe = async () => {
    if (!formData.ip) {
      setError('Vui lòng nhập IP camera trước khi quét');
      return;
    }
    console.log(handleProbe);
    setIsProbing(true);
    setError('');
    try {
      const res = await fetch('/api/onvif/probe', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          ip: formData.ip,
          port: formData.port,
          username: formData.username,
          password: formData.password
        })
      });
      const data = await res.json();
      if (res.ok) {
        setFormData(prev => ({
          ...prev,
          path: data.path,
          name: prev.name || data.deviceInfo?.Model || prev.name
        }));

        // Try to extract RTSP port from streamUri if available
        if (data.streamUri) {
          const match = data.streamUri.match(/rtsp:\/\/[^/]+:(\d+)\//);
          if (match && match[1]) {
            setFormData(prev => ({ ...prev, rtspPort: match[1] }));
          }
        }
        addToast({ type: 'success', title: 'Tìm thấy camera', message: `Đã tìm thấy thông tin camera qua ONVIF!\nModel: ${data.deviceInfo?.Model}\nPath: ${data.path}` });
      } else {
        throw new Error(data.error || 'Không thể kết nối ONVIF');
      }
    } catch (e: any) {
      const msg = `Lỗi quét ONVIF: ${e.message}. Bạn hãy kiểm tra lại thông tin đăng nhập hoặc nhập Path thủ công trong phần Nâng cao.`;
      setError(msg);
      addToast({ type: 'error', title: 'Lỗi quét ONVIF', message: e.message });
      setShowAdvanced(true);
    } finally {
      setIsProbing(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsSubmitting(true);
    setError('');

    try {
      let finalPath = formData.path;
      let finalName = formData.name;
      let finalRtspPort = formData.rtspPort;

      // If user hasn't explicitly opened Advanced settings or path is default/empty, try to probe first
      // Or we can just always probe if we don't have a custom path
      // Logic: Probing is safer to ensure we get the right path.

      // Update status to show we are probing
      // Note: We don't have a status state exposed here other than passing it via prop or using a local one?
      // We'll reuse 'error' state for status temporarily or just let the spinner show.

      // Perform ONVIF Probe to get path if not provided manually (or if user wants auto logic)
      if (!showAdvanced || finalPath === '/stream1') {
        try {
          const resProbe = await fetch('/api/onvif/probe', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              ip: formData.ip,
              port: formData.port,
              username: formData.username,
              password: formData.password
            })
          });
          const probeData = await resProbe.json();
          if (resProbe.ok) {
            finalPath = probeData.path || finalPath;
            finalName = finalName || probeData.deviceInfo?.Model || finalName;

            // Extract RTSP Port from auto-probe result
            if (probeData.streamUri) {
              const match = probeData.streamUri.match(/rtsp:\/\/[^/]+:(\d+)\//);
              if (match && match[1]) {
                // Update the state for consistency (optional but good)
                setFormData(prev => ({ ...prev, rtspPort: match[1] }));
                finalRtspPort = match[1];
                // IMPORTANT: Update local var if we were using one, but we use formData.rtspPort below. 
                // Since setState is async, we must use a local variable for the immediate submit logic.
                // Let's create `finalRtspPort`.
              }
            }
          } else {
            console.warn("Probe failed, using fallback/manual path", probeData.error);
          }
        } catch (err) {
          console.warn("Probe request failed", err);
        }
      }

      // Determine correct RTSP Port
      // Use state value by default, but if we auto-probed, we might want to use that. 
      // Actually, since setState is async, we can't rely on formData.rtspPort updating in time if we just called setFormData above.
      // We need to re-parse or store it in a local variable.

      // Let's do a quick re-fetch of the derived port from `finalPath`? No, path doesn't have port.
      // We should ideally have `finalRtspPort`.


      // Ensure path starts with /
      const normalizedPath = finalPath.startsWith('/') ? finalPath : `/${finalPath}`;
      // Use RTSP Port for the stream URL
      const rtspUrl = `rtsp://${formData.username}:${formData.password}@${formData.ip}:${finalRtspPort}${normalizedPath}`;

      // We wrap this in a promise because onAdd (handleAddCamera) might be async but typed loosely here
      await Promise.resolve(onAdd({ name: finalName, rtspUrl: rtspUrl, record: true, onvifPort: parseInt(formData.port) || 80 }));

      // If successful (no error thrown), close modal
      onClose();
    } catch (e: any) {
      console.error(e);
      setError(e.message || 'Có lỗi xảy ra khi thêm camera');
      addToast({ type: 'error', title: 'Lỗi thêm camera', message: e.message || 'Có lỗi xảy ra khi thêm camera' });
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
      <div className="bg-white dark:bg-[#2D2D2D] w-full max-w-2xl rounded-lg shadow-2xl flex flex-col max-h-[90vh] z-20 border border-gray-200 dark:border-gray-700 overflow-hidden animate-fade-in-up">
        <div className="px-6 py-4 border-b border-gray-200 dark:border-gray-700 flex justify-between items-center bg-white dark:bg-[#333]">
          <h2 className="text-xl font-bold text-[#F04E30] flex items-center">
            <Icon name="add_circle" className="mr-2" />
            Thêm Camera Thủ Công
          </h2>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 dark:hover:text-gray-200 transition-colors" disabled={isSubmitting}>
            <Icon name="close" />
          </button>
        </div>

        <div className="p-8 flex-1 overflow-y-auto">
          <form onSubmit={handleSubmit} className="space-y-6">
            <div className="p-4 bg-orange-50 dark:bg-orange-900/10 border border-orange-200 dark:border-orange-800 rounded-lg flex items-start gap-3 mb-2">
              <Icon name="info" className="text-orange-500 mt-0.5" />
              <div className="text-sm text-orange-800 dark:text-orange-200">
                Nhập IP và thông tin đăng nhập, hệ thống sẽ tự động quét và tìm đường dẫn RTSP phù hợp.
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                Tên camera <span className="text-red-500">*</span>
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                  <Icon name="videocam" className="text-gray-400 text-lg" />
                </div>
                <input
                  required
                  className="w-full pl-10 rounded border-gray-300 dark:border-gray-600 dark:bg-gray-800 dark:text-white focus:ring-[#F04E30] focus:border-[#F04E30] shadow-sm"
                  placeholder="Ví dụ: Camera Cổng Chính"
                  type="text"
                  value={formData.name}
                  onChange={e => setFormData({ ...formData, name: e.target.value })}
                  disabled={isSubmitting}
                />
              </div>
              <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">Đặt tên dễ gợi nhớ vị trí lắp đặt.</p>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
              <div className="md:col-span-2">
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                  Địa chỉ IP <span className="text-red-500">*</span>
                </label>
                <div className="relative">
                  <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                    <Icon name="dns" className="text-gray-400 text-lg" />
                  </div>
                  <input
                    required
                    className="w-full pl-10 rounded border-gray-300 dark:border-gray-600 dark:bg-gray-800 dark:text-white focus:ring-[#F04E30] focus:border-[#F04E30] shadow-sm font-mono"
                    placeholder="192.168.1.xxx"
                    type="text"
                    value={formData.ip}
                    onChange={e => setFormData({ ...formData, ip: e.target.value })}
                    disabled={isSubmitting || isProbing}
                  />
                </div>
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2 truncate" title="Cổng Admin/ONVIF">Cổng (Port)</label>
                <div className="relative">
                  <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                    <Icon name="settings_input_component" className="text-gray-400 text-lg" />
                  </div>
                  <input
                    className="w-full pl-10 rounded border-gray-300 dark:border-gray-600 dark:bg-gray-800 dark:text-white focus:ring-[#F04E30] focus:border-[#F04E30] shadow-sm"
                    placeholder="80"
                    type="number"
                    value={formData.port}
                    onChange={e => setFormData({ ...formData, port: e.target.value })}
                    disabled={isSubmitting || isProbing}
                  />
                </div>
              </div>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              <div>
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                  Tên đăng nhập <span className="text-red-500">*</span>
                </label>
                <div className="relative">
                  <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                    <Icon name="person" className="text-gray-400 text-lg" />
                  </div>
                  <input
                    required
                    className="w-full pl-10 rounded border-gray-300 dark:border-gray-600 dark:bg-gray-800 dark:text-white focus:ring-[#F04E30] focus:border-[#F04E30] shadow-sm"
                    placeholder="admin"
                    type="text"
                    value={formData.username}
                    onChange={e => setFormData({ ...formData, username: e.target.value })}
                    disabled={isSubmitting || isProbing}
                  />
                </div>
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                  Mật khẩu <span className="text-red-500">*</span>
                </label>
                <div className="relative">
                  <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                    <Icon name="lock" className="text-gray-400 text-lg" />
                  </div>
                  <input
                    required
                    className="w-full pl-10 rounded border-gray-300 dark:border-gray-600 dark:bg-gray-800 dark:text-white focus:ring-[#F04E30] focus:border-[#F04E30] shadow-sm"
                    placeholder="••••••••"
                    type="password"
                    value={formData.password}
                    onChange={e => setFormData({ ...formData, password: e.target.value })}
                    disabled={isSubmitting || isProbing}
                  />
                </div>
              </div>
            </div>



            <div className="pt-2">
              <div className="flex items-center justify-between mb-2">
                <button
                  type="button"
                  onClick={() => setShowAdvanced(!showAdvanced)}
                  className="text-sm font-medium text-gray-500 hover:text-gray-700 flex items-center"
                >
                  <Icon name={showAdvanced ? "expand_less" : "expand_more"} />
                  Thiết lập nâng cao (Đường dẫn thủ công)
                </button>
              </div>

              {showAdvanced && (
                <div className="p-4 bg-gray-50 dark:bg-gray-800/50 rounded border border-gray-200 dark:border-gray-700 animate-fade-in mb-4">
                  <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                    Đường dẫn RTSP (RTSP Path)
                  </label>
                  <div className="relative">
                    <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                      <Icon name="link" className="text-gray-400 text-lg" />
                    </div>
                    <input
                      className="w-full pl-10 rounded border-gray-300 dark:border-gray-600 dark:bg-gray-800 dark:text-white focus:ring-[#F04E30] focus:border-[#F04E30] shadow-sm font-mono"
                      placeholder="/stream1 hoặc /profile1/live"
                      type="text"
                      value={formData.path}
                      onChange={e => setFormData({ ...formData, path: e.target.value })}
                      disabled={isSubmitting || isProbing}
                    />
                  </div>
                </div>
              )}
            </div>

            <div className="pt-2">
              <label className="inline-flex items-center cursor-pointer">
                <input
                  type="checkbox"
                  className="rounded border-gray-300 text-[#F04E30] shadow-sm focus:border-[#F04E30] focus:ring focus:ring-offset-0 focus:ring-[#F04E30]/50"
                  checked={formData.addToLiveView}
                  onChange={e => setFormData({ ...formData, addToLiveView: e.target.checked })}
                  disabled={isSubmitting || isProbing}
                />
                <span className="ml-2 text-sm text-gray-600 dark:text-gray-400">Tự động thêm vào luồng xem trực tiếp (Live View)</span>
              </label>
            </div>

            {(status || error) && (
              <div className={`text-sm text-center p-3 rounded-lg border ${error ? 'bg-red-50 text-red-700 border-red-200' : 'bg-blue-50 text-blue-700 border-blue-200'}`}>
                {error || status}
              </div>
            )}
          </form>
        </div>

        <div className="px-6 py-4 bg-gray-50 dark:bg-[#252525] border-t border-gray-200 dark:border-gray-700 flex justify-end gap-3">
          <button
            onClick={onClose}
            className="px-5 py-2.5 border border-gray-300 dark:border-gray-600 text-gray-700 dark:text-gray-300 rounded hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors font-medium text-sm uppercase tracking-wide"
            disabled={isSubmitting}
          >
            Hủy bỏ
          </button>
          <button
            onClick={handleSubmit}
            disabled={isSubmitting}
            className="px-6 py-2.5 bg-[#F04E30] text-white rounded hover:bg-[#D63A1D] transition-colors font-medium shadow-md flex items-center text-sm uppercase tracking-wide disabled:opacity-70 disabled:cursor-not-allowed"
          >
            {isSubmitting ? (
              <>
                <Icon name="hourglass_empty" className="text-lg mr-2 animate-spin" />
                Đang xử lý...
              </>
            ) : (
              <>
                <Icon name="add" className="text-lg mr-2" />
                Thêm
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
};

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
    try {
      await (window as any).fetch('/api/lock/logout', { method: 'POST' });
    } catch (e) {
      console.error('Logout failed', e);
    }
    setIsSessionUnlocked(false);
    setActiveTab('Live View');
    if (autoLockTimerRef.current) clearTimeout(autoLockTimerRef.current);
    setShowLockScreen(true);
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

  // Simple global check for 401
  useEffect(() => {
    const originalFetch = (window as any).fetch;
    const interceptedFetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      return originalFetch.call(window, input, init).then((response: Response) => {
        if (response.status === 401 && !input.toString().includes('/api/lock/verify')) {
          handleLogout();
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

  const addToast = (toast: { type: 'success' | 'error' | 'info', title: string, message: string }) => {
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
  const [visibleCamIds, setVisibleCamIds] = useState<string[]>([]);
  const [layout, setLayout] = useState<number | string>(2);
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

  const requestFullscreen = () => {
    const elem = document.getElementById('live-view-container');
    if (elem) {
      if (!document.fullscreenElement) {
        elem.requestFullscreen().catch(err => console.log(err));
      } else {
        document.exitFullscreen();
      }
    }
  }

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

  useEffect(() => { fetchCameras() }, [])
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
          manufacturer: c.manufacturer, // ONVIF metadata
          model: c.model, // ONVIF metadata
          ptzEnabled: c.ptzEnabled // Manually toggled PTZ flag
        }));
        setCameras(list);
        setVisibleCamIds(list.map((c: any) => c.id));
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
        addToast({ type: 'success', title: 'Thành công', message: `Đã thêm camera "${params.name}" thành công!` });
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
    addToast({ type: 'success', title: 'Đã xóa', message: `Đã xóa camera "${name}" thành công` });

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
          errorMsg = `Khung giờ từ '${exportParams.startTime}' đến '${exportParams.endTime}' cho camera "${camName}" không tồn tại`;
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

                addToast({ type: 'success', title: 'Đã xuất video', message: `Video "${latest.name}" đã sẵn sàng trong tab Tải xuống.` });
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
          addToast({ type: 'info', title: 'Đã gửi yêu cầu', message: 'Yêu cầu tải xuống đã được gửi. Video sẽ xuất hiện trong tab "Tải xuống" sau ít phút.' });
        }
      }, 1000);

    } catch (e: any) {
      console.error('Export failed', e);
      addToast({ type: 'error', title: 'Lỗi tải xuống', message: e.message || 'Không thể xuất video.' });
      setPendingExport(false);
    }
  }

  return (
    <div className="bg-[#F3F4F6] dark:bg-[#111827] font-body h-screen flex flex-col text-[#1F2937] dark:text-[#F9FAFB] overflow-hidden">
      <Header
        activeTab={activeTab}
        setActiveTab={handleTabChange}
        currentTime={currentTime}
      />
      <div className="flex-1 overflow-hidden relative flex flex-col pt-2">
        {/* Pattern Lock Overlay — shows during setup OR when session needs unlock */}
        {showLockScreen && (
          <PatternLockScreen
            isSetupMode={!isPatternSetup}
            onUnlock={handleUnlock}
            onSetupComplete={handleSetupComplete}
          />
        )}
        {activeTab === 'Live View' && <LiveView cameras={cameras} loading={loading} visibleCamIds={visibleCamIds} setVisibleCamIds={setVisibleCamIds} toggleCamera={toggleCamera} layout={layout} setLayout={setLayout} fullscreenCamId={fullscreenCamId} setFullscreenCamId={setFullscreenCamId} requestFullscreen={requestFullscreen} getCameraDetections={getCameraDetections} />}
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
        {activeTab === 'Sự kiện AI' && (
          <AIConfigView cameras={cameras} addToast={addToast} />
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

const DownloadsView = () => {
  const [activeTab, setActiveTab] = useState<'video' | 'image'>('video');
  const [files, setFiles] = useState<any[]>([]);
  const [screenshots, setScreenshots] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [previewUrl, setPreviewUrl] = useState('');
  const [imagePreviewUrl, setImagePreviewUrl] = useState('');

  // Fetch Data
  const fetchData = useCallback(() => {
    setLoading(true);
    if (activeTab === 'video') {
      fetch('/api/list-exports')
        .then(res => res.json())
        .then(data => setFiles(data))
        .catch(e => console.error(e))
        .finally(() => setLoading(false));
    } else {
      fetch('/api/list-screenshots')
        .then(res => res.json())
        .then(data => setScreenshots(data))
        .catch(e => console.error(e))
        .finally(() => setLoading(false));
    }
  }, [activeTab]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // Actions
  const handleDeleteVideo = async (filename: string) => {
    if (!confirm(`Bạn có chắc chắn muốn xóa video "${filename}" không?`)) return;
    try {
      const res = await fetch(`/api/exports/${filename}`, { method: 'DELETE' });
      if (res.ok) {
        setFiles(prev => prev.filter(f => f.name !== filename));
      } else {
        alert('Xóa thất bại');
      }
    } catch (e) {
      console.error(e);
      alert('Lỗi kết nối');
    }
  };

  const handleDeleteScreenshot = async (filename: string) => {
    if (!confirm(`Bạn có chắc chắn muốn xóa ảnh "${filename}" không?`)) return;
    try {
      const res = await fetch(`/api/screenshots/${filename}`, { method: 'DELETE' });
      if (res.ok) {
        setScreenshots(prev => prev.filter(s => s.name !== filename));
      } else {
        alert('Xóa thất bại');
      }
    } catch (e) {
      console.error(e);
      alert('Lỗi kết nối');
    }
  };

  return (
    <div className="flex-1 bg-gray-100 dark:bg-gray-900 p-8 overflow-y-auto">
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-2xl font-bold text-gray-800 dark:text-white flex items-center gap-2">
          <Icon name={activeTab === 'video' ? 'movie' : 'image'} className="text-[#EF4444]" />
          Thư viện lưu trữ
        </h2>

        {/* Tabs */}
        <div className="flex bg-white dark:bg-gray-800 rounded-lg p-1 shadow-sm border border-gray-200 dark:border-gray-700">
          <button
            onClick={() => setActiveTab('video')}
            className={`px-4 py-2 rounded-md text-sm font-medium transition-colors ${activeTab === 'video'
              ? 'bg-[#EF4444] text-white shadow'
              : 'text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700'
              }`}
          >
            Video
          </button>
          <button
            onClick={() => setActiveTab('image')}
            className={`px-4 py-2 rounded-md text-sm font-medium transition-colors ${activeTab === 'image'
              ? 'bg-[#EF4444] text-white shadow'
              : 'text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700'
              }`}
          >
            Ảnh chụp
          </button>
        </div>
      </div>

      {loading ? (
        <div className="flex items-center justify-center p-12">
          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-[#EF4444]"></div>
          <span className="ml-3 text-gray-500">Đang tải dữ liệu...</span>
        </div>
      ) : (
        <div className="bg-white dark:bg-[#1F2937] rounded-lg shadow overflow-hidden">
          <table className="w-full text-left">
            <thead className="bg-gray-50 dark:bg-gray-800 border-b border-gray-200 dark:border-gray-700">
              <tr>
                <th className="p-4 font-semibold text-gray-600 dark:text-gray-300">Tên file</th>
                <th className="p-4 font-semibold text-gray-600 dark:text-gray-300">Kích thước</th>
                <th className="p-4 font-semibold text-gray-600 dark:text-gray-300">Thời gian tạo</th>
                <th className="p-4 font-semibold text-right text-gray-600 dark:text-gray-300">Hành động</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
              {/* Empty State */}
              {activeTab === 'video' && files.length === 0 && (
                <tr><td colSpan={4} className="p-8 text-center text-gray-500">Chưa có video nào.</td></tr>
              )}
              {activeTab === 'image' && screenshots.length === 0 && (
                <tr><td colSpan={4} className="p-8 text-center text-gray-500">Chưa có ảnh chụp nào.</td></tr>
              )}

              {/* Video List */}
              {activeTab === 'video' && files.map(f => (
                <tr key={f.name} className="hover:bg-gray-50 dark:hover:bg-gray-800 transition-colors">
                  <td className="p-4 font-medium text-gray-800 dark:text-white flex items-center gap-2">
                    <Icon name="movie" className="text-blue-500" /> {f.name}
                  </td>
                  <td className="p-4 text-gray-600 dark:text-gray-400">{f.size}</td>
                  <td className="p-4 text-gray-600 dark:text-gray-400">{new Date(f.time).toLocaleString('vi-VN')}</td>
                  <td className="p-4 text-right space-x-2">
                    <button onClick={() => setPreviewUrl(f.path)} className="p-2 bg-blue-50 text-blue-600 rounded hover:bg-blue-100" title="Xem">
                      <Icon name="visibility" />
                    </button>
                    <a href={f.path} download className="p-2 bg-gray-100 text-gray-700 rounded hover:bg-gray-200 inline-block" title="Tải về">
                      <Icon name="download" />
                    </a>
                    <button onClick={() => handleDeleteVideo(f.name)} className="p-2 bg-red-50 text-red-600 rounded hover:bg-red-100" title="Xóa">
                      <Icon name="delete" />
                    </button>
                  </td>
                </tr>
              ))}

              {/* Screenshot List */}
              {activeTab === 'image' && screenshots.map(s => (
                <tr key={s.name} className="hover:bg-gray-50 dark:hover:bg-gray-800 transition-colors">
                  <td className="p-4 font-medium text-gray-800 dark:text-white flex items-center gap-2">
                    <Icon name="image" className="text-green-500" /> {/* Image Icon */}
                    {s.name}
                  </td>
                  <td className="p-4 text-gray-600 dark:text-gray-400">{s.size}</td>
                  <td className="p-4 text-gray-600 dark:text-gray-400">{new Date(s.time).toLocaleString('vi-VN')}</td>
                  <td className="p-4 text-right space-x-2">
                    <button onClick={() => setImagePreviewUrl(s.path)} className="p-2 bg-blue-50 text-blue-600 rounded hover:bg-blue-100" title="Xem">
                      <Icon name="visibility" />
                    </button>
                    <a href={s.path} download className="p-2 bg-gray-100 text-gray-700 rounded hover:bg-gray-200 inline-block" title="Tải về">
                      <Icon name="download" />
                    </a>
                    <button onClick={() => handleDeleteScreenshot(s.name)} className="p-2 bg-red-50 text-red-600 rounded hover:bg-red-100" title="Xóa">
                      <Icon name="delete" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Video Modal */}
      {previewUrl && (
        <div className="fixed inset-0 z-50 bg-black/80 flex items-center justify-center p-4" onClick={() => setPreviewUrl('')}>
          <div className="relative w-full max-w-4xl bg-black rounded overflow-hidden shadow-2xl" onClick={e => e.stopPropagation()}>
            <button onClick={() => setPreviewUrl('')} className="absolute top-4 right-4 text-white bg-black/50 rounded-full p-2 hover:bg-black/80 z-10">
              <Icon name="close" />
            </button>
            <video src={previewUrl} controls autoPlay className="w-full h-auto max-h-[80vh]" />
          </div>
        </div>
      )}

      {/* Image Modal */}
      {imagePreviewUrl && (
        <div className="fixed inset-0 z-50 bg-black/90 flex items-center justify-center p-4" onClick={() => setImagePreviewUrl('')}>
          <div className="relative max-w-[90vw] max-h-[90vh]" onClick={e => e.stopPropagation()}>
            <button onClick={() => setImagePreviewUrl('')} className="absolute -top-12 right-0 text-white p-2 hover:text-gray-300">
              <Icon name="close" className="text-3xl" />
            </button>
            <img src={imagePreviewUrl} alt="Preview" className="max-w-full max-h-[85vh] rounded-lg shadow-2xl border border-gray-700" />
          </div>
        </div>
      )}
    </div>
  );
}

export default App

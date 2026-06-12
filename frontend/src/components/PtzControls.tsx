import { useState, useEffect, useRef } from 'react'
import { Icon } from './Icon'

// ============================================
// PTZ Control Component (Feature 2)
// ============================================

export const PtzControls = ({ cameraId, ptzEnabled }: { cameraId: string; ptzEnabled: boolean }) => {
  const [isPtzOpen, setIsPtzOpen] = useState(false);
  const [isMoving, setIsMoving] = useState(false);
  const moveIntervalRef = useRef<number | null>(null);
  // Real PTZ capabilities from the camera's ONVIF GetNodes. Default to full controls
  // so a slow/failed probe never hides a d-pad that actually works; we only ever
  // narrow the controls once the camera tells us it lacks PanTilt or Zoom
  // (e.g. the Bkav P200 field camera is zoom-only).
  const [caps, setCaps] = useState<{ hasPanTilt: boolean; hasZoom: boolean }>({ hasPanTilt: true, hasZoom: true });

  // Probe capabilities lazily — only when the user opens the panel — to avoid
  // firing an ONVIF GetNodes at every camera (and through the socat proxy) on page load.
  useEffect(() => {
    if (!ptzEnabled || !isPtzOpen) return;
    let cancelled = false;
    fetch(`/api/cameras/${cameraId}/ptz/capabilities`)
      .then(r => (r.ok ? r.json() : null))
      .then(d => { if (!cancelled && d) setCaps({ hasPanTilt: !!d.hasPanTilt, hasZoom: !!d.hasZoom }); })
      .catch(() => { /* keep defaults */ });
    return () => { cancelled = true; };
  }, [cameraId, ptzEnabled, isPtzOpen]);

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
          {/* Direction grid 3×3 — only for cameras that actually advertise PanTilt */}
          {caps.hasPanTilt && (
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
          )}

          {/* Zoom row — only if the camera supports Zoom. Includes its own Stop when there's no d-pad. */}
          {caps.hasZoom && (
          <div className="flex gap-1 touch-none">
            <button onPointerDown={(e) => startContinuousMove(e, 0, 0,  0.3)} onPointerUp={handlePointerUp} onPointerCancel={handlePointerUp} onContextMenu={e => e.preventDefault()}
              className="flex-1 h-6 bg-gray-700 hover:bg-gray-600 rounded text-white text-xs flex items-center justify-center gap-0.5 transition-colors touch-none">
              <Icon name="add" className="text-xs" />Zoom
            </button>
            <button onPointerDown={(e) => startContinuousMove(e, 0, 0, -0.3)} onPointerUp={handlePointerUp} onPointerCancel={handlePointerUp} onContextMenu={e => e.preventDefault()}
              className="flex-1 h-6 bg-gray-700 hover:bg-gray-600 rounded text-white text-xs flex items-center justify-center gap-0.5 transition-colors touch-none">
              <Icon name="remove" className="text-xs" />Zoom
            </button>
            {!caps.hasPanTilt && (
              <button onClick={() => stopPtz()} className="w-8 h-6 bg-red-600 hover:bg-red-500 rounded text-white flex items-center justify-center transition-colors touch-none" title="Dừng"><Icon name="stop" className="text-xs" /></button>
            )}
          </div>
          )}

          {!caps.hasPanTilt && !caps.hasZoom && (
            <div className="text-[10px] text-gray-300 px-1 py-1 whitespace-nowrap">Camera không hỗ trợ điều khiển PTZ</div>
          )}

          {isMoving && <div className="mt-1 text-center text-[10px] text-green-400">Đang di chuyển…</div>}
        </div>
      )}
    </div>
  );
};

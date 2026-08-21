import { Icon } from './Icon'
import { PtzControls } from './PtzControls'
import BoundingBoxCanvas from './BoundingBoxCanvas'
import LiveCamera from './LiveCamera'
import customLogo from '../assets/aicam-logo-white1.png'
import { t } from '../i18n';

// ============================================
// LIVE CAMERA WITH HLS STREAMING
// ============================================

export const CameraGridItem = ({
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
  detections?: import('../hooks/useMqttEvents').Detection[],
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
          <span className="text-gray-400 text-sm font-medium">{t("Mất kết nối")}</span>
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
          title={fullscreenCamId ? t("Thu nhỏ") : t("Phóng to")}
        >
          <Icon name={fullscreenCamId ? "fullscreen_exit" : "fullscreen"} className="text-sm" />
        </button>
        <button
          onClick={(e) => { e.stopPropagation(); toggleCamera(cam.id); }}
          className={`bg-black/60 text-white p-1 rounded hover:bg-black/80 hover:text-red-400 ${fullscreenCamId ? 'hidden' : ''}`}
          title={t("Đóng camera")}
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

import { useState, useEffect } from 'react'
import { Icon } from './Icon'
import { useModalKeys } from '../hooks/useModalKeys'
import { CameraGridItem } from './CameraGridItem'
import { t } from '../i18n';

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
            placeholder={t("Tìm kiếm...")}
            type="text"
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
          />
        </div>
      </div>
      <div className="flex-1 overflow-y-auto p-2">
        <ul className="space-y-1">
          {filteredCameras.length === 0 && (
            <li className="text-center text-gray-500 text-sm py-4">{t("Không tìm thấy camera nào.")}</li>
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
                  title={t("Kéo thả vào ô trống để thêm, hoặc Click để thêm tự động")}
                >
                  <span className={`h-2.5 w-2.5 rounded-full mr-3 shrink-0 ${cam.isOnline ? 'bg-green-500' : 'bg-red-500'}`} title={cam.isOnline ? t("Trực tuyến") : t("Ngoại tuyến")}></span>
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

export const LiveView = ({
  cameras, loading, visibleCamIds, setVisibleCamIds, toggleCamera, layout, setLayout, fullscreenCamId, setFullscreenCamId, requestFullscreen, appFullscreen, getCameraDetections
}: {
  cameras: any[], loading: boolean, visibleCamIds: string[], setVisibleCamIds: (ids: React.SetStateAction<string[]>) => void, toggleCamera: (id: string) => void, layout: number | string, setLayout: (l: number | string) => void, fullscreenCamId: string | null, setFullscreenCamId: (id: string | null) => void, requestFullscreen: () => void, appFullscreen: boolean,
  getCameraDetections: (cameraId: string) => import('../hooks/useMqttEvents').Detection[]
}) => {
  const visibleCameras = visibleCamIds.map(id => id ? cameras.find(c => c.id === id) || null : null);
  const activeCamera = fullscreenCamId ? cameras.find(c => c.id === fullscreenCamId) : null;

  // Auto-hide toolbar logic
  const [showToolbar, setShowToolbar] = useState(true);
  const [isLayoutMenuOpen, setLayoutMenuOpen] = useState(false);

  // Esc đóng menu chọn bố cục (trước đây chỉ đóng được khi click ra ngoài).
  useModalKeys({ onClose: () => setLayoutMenuOpen(false), enabled: isLayoutMenuOpen });
  const [isFullscreen, setIsFullscreen] = useState(false);
  let hideTimeout: ReturnType<typeof setTimeout> | null = null;
  const handleMouseMove = () => {
    setShowToolbar(true);
    if (hideTimeout) clearTimeout(hideTimeout);
    if (appFullscreen || document.fullscreenElement) {
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
      {!appFullscreen && <LiveViewSidebar cameras={cameras} visibleCamIds={visibleCamIds} toggleCamera={toggleCamera} />}
      <main className="flex-1 flex flex-col bg-gray-100 dark:bg-gray-900 relative" id="live-view-container" onMouseMove={handleMouseMove}>
        {/* Grid Area */}
        <div className={`flex-1 p-2 grid gap-2 h-full overflow-hidden ${currentGridClass} `}>
          {loading && <div className="text-gray-500 flex items-center justify-center col-span-full">{t("Đang tải camera...")}</div>}

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
            ${(appFullscreen || isFullscreen)
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
                <span className="font-medium">{t('Bố cục:')} {typeof layout === 'number' ? `${layout}x${layout}` : layout}</span>
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
              <span>{t("Hiển thị:")}<span className="font-semibold text-gray-800 dark:text-white">{fullscreenCamId && activeCamera ? 1 : visibleCameras.filter(Boolean).length}</span> camera</span>
            </div>
          </div>
          <div className="flex items-center space-x-3">
            <button
              onClick={requestFullscreen}
              className="flex items-center space-x-2 px-3 py-1.5 text-white rounded-md shadow-sm transition-colors" style={{ backgroundColor: '#EF4444' }}
            >
              <Icon name={(appFullscreen || document.fullscreenElement) ? "fullscreen_exit" : "fullscreen"} className="text-xl" />
              <span className="text-sm font-medium">{(appFullscreen || document.fullscreenElement) ? t("Thoát") : t("Toàn màn hình")}</span>
            </button>
          </div>
        </div>
      </main>
    </div>
  )
}

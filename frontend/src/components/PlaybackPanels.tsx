import { useState, useEffect } from 'react'
import { Icon } from './Icon'

// --- PLAYBACK COMPONENTS --- //
export const PlaybackSidebar = ({ cameras, onSelect, selectedCam }: { cameras: any[], onSelect: (c: string) => void, selectedCam: string }) => {
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
export const PlaybackRightPanel = ({ showExport, setShowExport: _setShowExport, onExport, timelineDate, setTimelineDate, playbackCam, onPlayEvent, cameras }: any) => {
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

export const ExportModal = ({ cameras, params, setParams, onClose, onDownload, pendingExport }: any) => (
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

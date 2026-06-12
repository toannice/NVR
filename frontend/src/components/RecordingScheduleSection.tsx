import { useState, useEffect } from 'react'
import { Icon } from './Icon'

// ============================================
// RECORDING SCHEDULE SECTION
// ============================================

export const RecordingScheduleSection = ({ cameras, addToast }: any) => {
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

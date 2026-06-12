import { useState, useEffect } from 'react'
import { Icon } from './Icon'

// --- Network Config Section --- //
export function NetworkConfigSection() {
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
export function StorageConfigSection() {
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

import { useState, useEffect } from 'react'
import { Icon } from './Icon'
import { t } from '../i18n';

// --- Thông tin hệ thống ---------------------------------------------------
// Gộp "Cấu hình mạng" + "Lưu trữ" thành MỘT trang thông tin phẳng.
//
// Trước đây trang mạng render 5 giá trị bằng <div>{t("được style y hệt ô")}<input>
// (nền trắng, viền, bo góc, font mono) nhưng KHÔNG có nút Lưu — người dùng tưởng
// sửa được rồi loay hoay tìm chỗ lưu. Cả hai API (/api/network-config,
// /api/storage-config) đều chỉ đọc, nên trang này cũng chỉ đọc và trình bày
// đúng như vậy: nhãn bên trái, giá trị bên phải, không giả dạng form.

const formatBytes = (bytes: number) => {
  if (!bytes || bytes <= 0) return '0 B';
  const k = 1024;
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + ['B', 'KB', 'MB', 'GB', 'TB'][i];
};

/** Một dòng thông tin phẳng: nhãn — giá trị. */
const Row = ({ label, value, mono = false }: { label: string; value: any; mono?: boolean }) => (
  <div className="flex items-baseline justify-between gap-6 py-2.5 border-b border-gray-100 dark:border-gray-800 last:border-0">
    <span className="text-sm text-gray-500 dark:text-gray-400 shrink-0">{label}</span>
    <span className={`text-sm text-gray-900 dark:text-gray-100 text-right break-all ${mono ? 'font-mono' : ''}`}>
      {value === undefined || value === null || value === '' ? '—' : value}
    </span>
  </div>
);

const Group = ({ icon, title, children }: any) => (
  <section className="mb-8">
    <h3 className="flex items-center text-xs font-bold uppercase tracking-wider text-gray-400 dark:text-gray-500 mb-1">
      <Icon name={icon} className="mr-2 text-base text-[#F04E30]" />
      {title}
    </h3>
    <div className="divide-y-0">{children}</div>
  </section>
);

export function SystemInfoSection() {
  const [net, setNet] = useState<any>(null);
  const [storage, setStorage] = useState<any>(null);
  const [health, setHealth] = useState<any>(null);
  const [failed, setFailed] = useState<string[]>([]);

  useEffect(() => {
    const load = (url: string, set: (v: any) => void, label: string) =>
      fetch(url)
        .then(r => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
        .then(set)
        .catch(() => setFailed(f => (f.includes(label) ? f : [...f, label])));

    load('/api/network-config', setNet, 'mạng');
    load('/api/storage-config', setStorage, 'lưu trữ');
    load('/api/health', setHealth, 'phiên bản');
  }, []);

  // Từng khối hiện ngay khi API của nó trả về — không chờ cả ba.
  const loading = !net && !storage && !health && failed.length === 0;

  // Lưu ý tên trường: API gọi là `cameraUsage` nhưng backend duyệt các thư mục con của
  // /app/media/recordings — mà Frigate xếp theo NGÀY (recordings/YYYY-MM-DD/HH/camera),
  // nên đây là dung lượng theo ngày, không phải theo camera. Ghi nhãn đúng như vậy.
  const dayUsage: [string, number][] = storage?.cameraUsage
    ? Object.entries(storage.cameraUsage as Record<string, number>).sort((a, b) => b[0] < a[0] ? -1 : 1)
    : [];
  const usedPercent = storage ? Math.min(((storage.total - storage.free) / storage.total) * 100, 100) : 0;

  return (
    <div className="p-8 max-w-3xl mx-auto">
      <h2 className="text-2xl font-bold text-gray-800 dark:text-white mb-2 flex items-center">
        <Icon name="info" className="mr-3 text-[#F04E30]" />{t("Thông tin hệ thống")}</h2>
      <p className="text-sm text-gray-500 dark:text-gray-400 mb-8 pb-4 border-b border-gray-200 dark:border-gray-700">{t("Thông tin chỉ để xem. Đổi IP/DNS làm ở hệ điều hành máy chủ, không đổi tại đây.")}</p>

      {loading && <div className="py-10 text-center text-gray-500">{t("Đang tải thông tin hệ thống…")}</div>}

      {failed.length > 0 && (
        <div className="mb-6 rounded-lg bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 px-4 py-3 text-sm text-amber-800 dark:text-amber-300 flex items-start gap-2">
          <Icon name="warning" className="text-base mt-0.5" />
          <span>{t('Không lấy được thông tin: {0}. Thử tải lại trang.').replace('{0}', failed.join(', '))}</span>
        </div>
      )}

      {net && (
        <Group icon="settings_ethernet" title={t("Mạng")}>
          <Row label={t("Địa chỉ IPv4")} value={net.ip} mono />
          <Row label="Subnet mask" value={net.subnet} mono />
          <Row label="Default gateway" value={net.gateway} mono />
          <Row label={t("DNS ưu tiên")} value={net.dns1} mono />
          <Row label={t("DNS phụ")} value={net.dns2} mono />
          <Row label={t("Địa chỉ MAC")} value={net.mac} mono />
        </Group>
      )}

      {storage && (
        <Group icon="storage" title={t("Lưu trữ")}>
          <div className="mb-4 mt-1">
            <div className="relative h-2 bg-gray-200 dark:bg-gray-700 rounded-full overflow-hidden">
              <div className="absolute h-full bg-[#F04E30] transition-all duration-700" style={{ width: `${usedPercent}%` }} />
            </div>
            <div className="flex justify-between text-xs text-gray-500 mt-1.5">
              <span>{t('Đã dùng {0}').replace('{0}', formatBytes(storage.total - storage.free))}</span>
              <span>{t('Còn trống {0}').replace('{0}', formatBytes(storage.free))}</span>
            </div>
          </div>
          <Row label={t("Tổng dung lượng")} value={formatBytes(storage.total)} />
          <Row label={t("Còn trống")} value={formatBytes(storage.free)} />
          <Row label={t("Đã dùng (toàn ổ)")} value={formatBytes(storage.total - storage.free)} />
          <Row label={t("Riêng dữ liệu ghi hình NVR")} value={formatBytes(storage.folderUsed)} />
        </Group>
      )}

      {dayUsage.length > 0 && (
        <Group icon="calendar_month" title={t("Dung lượng ghi hình theo ngày")}>
          {dayUsage.map(([day, bytes]) => (
            <Row key={day} label={day} value={formatBytes(bytes)} mono />
          ))}
        </Group>
      )}

      {health && (
        <Group icon="memory" title={t("Phần mềm")}>
          <Row label={t("Phiên bản ứng dụng")} value={health.version} mono />
          <Row label={t("Phiên bản triển khai")} value={health.deployVersion} mono />
          <Row label={t("Cơ sở dữ liệu")} value={health.db === 'healthy' ? t('Bình thường') : health.db} />
        </Group>
      )}
    </div>
  );
}

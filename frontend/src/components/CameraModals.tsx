import { useState } from 'react'
import { Icon } from './Icon'
import { useModalKeys } from '../hooks/useModalKeys'
import { t } from '../i18n';

// Edit Camera Modal — name + ONVIF port (no ONVIF credentials required).
// The ONVIF port matters for PTZ over a socat proxy: set it to the proxy's port
// (e.g. 8080) so the backend's ONVIF/PTZ requests reach the camera through the proxy.
export function EditCameraModal({ device, onClose, onSubmit, isLoading }: any) {
  const [cameraName, setCameraName] = useState(device.name || '');
  const [onvifPort, setOnvifPort] = useState<string>(String(device.onvifPort ?? 80));
  const [onvifUser, setOnvifUser] = useState('');
  const [onvifPass, setOnvifPass] = useState('');
  const [ptzMode, setPtzMode] = useState<string>(device.ptzMode ?? 'auto');

  // Esc = đóng, Enter = lưu. Khoá lại trong lúc đang gửi để không submit hai lần.
  useModalKeys({ onClose, onSubmit: () => handleSubmit(), enabled: !isLoading });

  const handleSubmit = (e?: React.FormEvent) => {
    e?.preventDefault();
    if (!cameraName) {
      alert(t('Vui lòng nhập tên camera'));
      return;
    }
    const port = parseInt(onvifPort, 10);
    if (!port || port < 1 || port > 65535) {
      alert(t('Cổng ONVIF không hợp lệ (1–65535)'));
      return;
    }
    onSubmit({
      name: cameraName,
      onvifPort: port,
      // Only sent when filled — leaving these blank keeps the saved credentials untouched.
      onvifUsername: onvifUser.trim() || undefined,
      onvifPassword: onvifPass || undefined,
      ptzMode,
    });
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
      <div className="bg-white dark:bg-[#2D2D2D] w-full max-w-md rounded-lg shadow-2xl border border-gray-200 dark:border-gray-700 overflow-hidden">
        <div className="px-6 py-4 border-b border-gray-200 dark:border-gray-700 flex justify-between items-center bg-white dark:bg-[#333]">
          <h2 className="text-lg font-bold text-indigo-600 dark:text-indigo-400 flex items-center">
            <Icon name="edit" className="mr-2" />{t("Sửa Camera")}</h2>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 dark:hover:text-gray-200 transition-colors" disabled={isLoading}>
            <Icon name="close" />
          </button>
        </div>

        <div className="p-6">
          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">{t("Tên Camera")}<span className="text-red-500">*</span>
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                  <Icon name="videocam" className="text-gray-400 text-lg" />
                </div>
                <input
                  required
                  className="w-full pl-10 rounded border-gray-300 dark:border-gray-600 dark:bg-gray-800 dark:text-white focus:ring-indigo-500 focus:border-indigo-500 shadow-sm"
                  placeholder={t("Ví dụ: Camera Cổng")}
                  type="text"
                  value={cameraName}
                  onChange={e => setCameraName(e.target.value)}
                  disabled={isLoading}
                />
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">{t("Cổng ONVIF")}</label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                  <Icon name="settings_ethernet" className="text-gray-400 text-lg" />
                </div>
                <input
                  className="w-full pl-10 rounded border-gray-300 dark:border-gray-600 dark:bg-gray-800 dark:text-white focus:ring-indigo-500 focus:border-indigo-500 shadow-sm"
                  placeholder="80"
                  type="number"
                  min={1}
                  max={65535}
                  value={onvifPort}
                  onChange={e => setOnvifPort(e.target.value)}
                  disabled={isLoading}
                />
              </div>
              <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("Mặc định 80. Camera qua proxy socat: đặt bằng cổng proxy (vd 8080) để điều khiển PTZ.")}</p>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                ONVIF Username / Password
              </label>
              <div className="grid grid-cols-2 gap-2">
                <div className="relative">
                  <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                    <Icon name="person" className="text-gray-400 text-lg" />
                  </div>
                  <input
                    className="w-full pl-10 rounded border-gray-300 dark:border-gray-600 dark:bg-gray-800 dark:text-white focus:ring-indigo-500 focus:border-indigo-500 shadow-sm"
                    placeholder="admin"
                    type="text"
                    autoComplete="off"
                    value={onvifUser}
                    onChange={e => setOnvifUser(e.target.value)}
                    disabled={isLoading}
                  />
                </div>
                <div className="relative">
                  <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                    <Icon name="lock" className="text-gray-400 text-lg" />
                  </div>
                  <input
                    className="w-full pl-10 rounded border-gray-300 dark:border-gray-600 dark:bg-gray-800 dark:text-white focus:ring-indigo-500 focus:border-indigo-500 shadow-sm"
                    placeholder="••••••••"
                    type="password"
                    autoComplete="new-password"
                    value={onvifPass}
                    onChange={e => setOnvifPass(e.target.value)}
                    disabled={isLoading}
                  />
                </div>
              </div>
              <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("Chỉ cần cho camera qua proxy (URL RTSP không kèm mật khẩu). Để trống = giữ nguyên creds đã lưu.")}</p>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">{t("Kiểu điều khiển PTZ")}</label>
              <select
                className="w-full rounded border-gray-300 dark:border-gray-600 dark:bg-gray-800 dark:text-white focus:ring-indigo-500 focus:border-indigo-500 shadow-sm"
                value={ptzMode}
                onChange={e => setPtzMode(e.target.value)}
                disabled={isLoading}
              >
                <option value="auto">{t("Tự động phát hiện (ONVIF GetNodes)")}</option>
                <option value="full">{t("Toàn phần — Pan / Tilt / Zoom")}</option>
                <option value="zoom">{t("Chỉ Zoom")}</option>
              </select>
              <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("Chọn")}<b>{t("Toàn phần")}</b>{t("nếu camera có pan/tilt thật nhưng ONVIF báo thiếu (ép hiện d-pad).")}</p>
            </div>

            <div className="flex justify-end gap-3 mt-6">
              <button
                type="button"
                onClick={onClose}
                className="px-4 py-2 border border-gray-300 dark:border-gray-600 rounded text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-800 transition-colors"
                disabled={isLoading}
              >{t("Hủy")}</button>
              <button
                type="submit"
                className="px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded shadow transition-colors flex items-center disabled:opacity-50"
                disabled={isLoading}
              >
                {isLoading ? <Icon name="refresh" className="animate-spin mr-2" /> : <Icon name="save" className="mr-2 text-sm" />}
                Lưu
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
}

// ONVIF Credentials Modal Component
export function OnvifCredentialsModal({ device, onClose, onSubmit, isLoading, addToast }: any) {
  const [cameraName, setCameraName] = useState(device.name || '');
  const [credentials, setCredentials] = useState({
    username: 'admin',
    password: ''
  });
  const [ptzEnabled, setPtzEnabled] = useState(false);

  // Esc = đóng, Enter = thêm camera.
  useModalKeys({ onClose, onSubmit: () => handleSubmit(), enabled: !isLoading });

  const handleSubmit = (e?: React.FormEvent) => {
    e?.preventDefault();
    if (!cameraName) {
      addToast({ type: 'error', title: t('Lỗi'), message: t('Vui lòng nhập tên camera') });
      return;
    }
    if (!credentials.password) {
      addToast({ type: 'error', title: t('Lỗi'), message: t('Vui lòng nhập mật khẩu ONVIF') });
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
            <Icon name="key" className="mr-2" />{t("Cấu hình Camera ONVIF")}</h2>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 dark:hover:text-gray-200 transition-colors" disabled={isLoading}>
            <Icon name="close" />
          </button>
        </div>

        <div className="p-6">
          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">{t("Tên Camera")}<span className="text-red-500">*</span>
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                  <Icon name="videocam" className="text-gray-400 text-lg" />
                </div>
                <input
                  required
                  className="w-full pl-10 rounded border-gray-300 dark:border-gray-600 dark:bg-gray-800 dark:text-white focus:ring-[#F04E30] focus:border-[#F04E30] shadow-sm"
                  placeholder={t("Ví dụ: Camera Cổng")}
                  type="text"
                  value={cameraName}
                  onChange={e => setCameraName(e.target.value)}
                  disabled={isLoading}
                />
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">{t("Tên đăng nhập ONVIF")}<span className="text-red-500">*</span>
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
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">{t("Mật khẩu ONVIF")}<span className="text-red-500">*</span>
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
                <p className="text-sm font-medium text-gray-700 dark:text-gray-300">{t("Camera hỗ trợ PTZ")}</p>
                <p className="text-xs text-gray-500 dark:text-gray-400">{t("Bật nếu camera có thể xoay / tilt / zoom")}</p>
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
          >{t("Hủy")}</button>
          <button
            onClick={handleSubmit}
            disabled={isLoading}
            className="px-6 py-2.5 bg-[#F04E30] text-white rounded hover:bg-[#D63A1D] transition-colors font-medium shadow-md flex items-center text-sm disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {isLoading ? (
              <>
                <Icon name="hourglass_empty" className="text-lg mr-2 animate-spin" />{t("Đang thêm...")}</>
            ) : (
              <>
                <Icon name="add" className="text-lg mr-2" />{t("Thêm Camera")}</>
            )}
          </button>
        </div>
      </div>
    </div>
  );
};

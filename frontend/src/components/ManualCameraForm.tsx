import { useState } from 'react'
import { Icon } from './Icon'

// Manual Camera Add Form Component matching design
export function ManualCameraForm({ onClose, onAdd, status, addToast }: any) {
  const [activeMode, setActiveMode] = useState<'onvif' | 'rtsp'>('onvif');
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
  const [rtspData, setRtspData] = useState({ name: '', rtsp: '', subRtsp: '', ip: '' });
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
      const res = await fetch('/api/probe-onvif', {
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
      let finalSubStreamUri: string | undefined;

      // If user hasn't explicitly opened Advanced settings or path is default/empty, try to probe first
      // Or we can just always probe if we don't have a custom path
      // Logic: Probing is safer to ensure we get the right path.

      // Update status to show we are probing
      // Note: We don't have a status state exposed here other than passing it via prop or using a local one?
      // We'll reuse 'error' state for status temporarily or just let the spinner show.

      // Perform ONVIF Probe to get path if not provided manually (or if user wants auto logic)
      let finalStreamUri: string | undefined;
      if (!showAdvanced || finalPath === '/stream1') {
        try {
          const resProbe = await fetch('/api/probe-onvif', {
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
            // Prefer the full stream URI the camera advertised via ONVIF GetStreamUri —
            // it already carries the correct path (e.g. /profile1/live), port and credentials.
            // Rebuilding from a parsed path is what silently defaulted cameras to /stream1.
            if (probeData.streamUri) finalStreamUri = probeData.streamUri;
            finalPath = probeData.path || finalPath;
            finalName = finalName || probeData.deviceInfo?.Model || finalName;

            // Extract RTSP Port from auto-probe result (used only for the manual fallback)
            if (probeData.streamUri) {
              const match = probeData.streamUri.match(/rtsp:\/\/[^/]+:(\d+)\//);
              if (match && match[1]) {
                setFormData(prev => ({ ...prev, rtspPort: match[1] }));
                finalRtspPort = match[1];
              }
            }
            if (probeData.subStreamUri) {
              finalSubStreamUri = probeData.subStreamUri;
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


      // In auto mode (user didn't open Advanced) we rely entirely on the ONVIF probe. If it
      // didn't return a stream URI, do NOT silently fall back to the hardcoded "/stream1" default
      // (that produces an offline camera). Fail loudly so the user fixes creds or sets a path.
      if (!showAdvanced && !finalStreamUri) {
        throw new Error('Không lấy được luồng RTSP qua ONVIF. Kiểm tra IP / cổng ONVIF / tài khoản / mật khẩu, hoặc mở "Nâng cao" để nhập Path thủ công.');
      }

      // Ensure path starts with /
      const normalizedPath = finalPath.startsWith('/') ? finalPath : `/${finalPath}`;
      // Prefer the full URI from ONVIF (correct path/port/creds); otherwise build it manually.
      const rtspUrl = finalStreamUri
        || `rtsp://${formData.username}:${formData.password}@${formData.ip}:${finalRtspPort}${normalizedPath}`;

      const onvifPayload: any = { name: finalName, rtspUrl: rtspUrl, record: true, onvifPort: parseInt(formData.port) || 80 };
      if (finalSubStreamUri) onvifPayload.subRtspUrl = finalSubStreamUri;
      await Promise.resolve(onAdd(onvifPayload));

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

  const handleRtspSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!rtspData.name.trim()) { setError('Vui lòng nhập tên camera'); return; }
    if (!rtspData.rtsp.trim()) { setError('Vui lòng nhập đường dẫn RTSP'); return; }
    if (!rtspData.rtsp.startsWith('rtsp://')) { setError('Đường dẫn RTSP phải bắt đầu bằng rtsp://'); return; }
    if (rtspData.subRtsp.trim() && !rtspData.subRtsp.startsWith('rtsp://')) { setError('Sub stream phải bắt đầu bằng rtsp://'); return; }
    setIsSubmitting(true);
    setError('');
    try {
      const payload: any = { name: rtspData.name.trim(), rtspUrl: rtspData.rtsp.trim(), ip: rtspData.ip.trim() || undefined, record: true };
      if (rtspData.subRtsp.trim()) payload.subRtspUrl = rtspData.subRtsp.trim();
      await Promise.resolve(onAdd(payload));
      onClose();
    } catch (e: any) {
      const msg = e.message || 'Không thể kết nối RTSP. Kiểm tra lại đường dẫn và đảm bảo camera đang hoạt động.';
      setError(msg);
      addToast({ type: 'error', title: 'Lỗi thêm camera RTSP', message: msg });
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

        {/* Tab switcher */}
        <div className="flex border-b border-gray-200 dark:border-gray-700 bg-white dark:bg-[#333]">
          <button
            type="button"
            onClick={() => { setActiveMode('onvif'); setError(''); }}
            className={`flex-1 py-3 text-sm font-medium transition-colors flex items-center justify-center gap-1.5 ${activeMode === 'onvif' ? 'text-[#F04E30] border-b-2 border-[#F04E30]' : 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300'}`}
          >
            <Icon name="settings_input_antenna" className="text-base" /> ONVIF / Tự động
          </button>
          <button
            type="button"
            onClick={() => { setActiveMode('rtsp'); setError(''); }}
            className={`flex-1 py-3 text-sm font-medium transition-colors flex items-center justify-center gap-1.5 ${activeMode === 'rtsp' ? 'text-[#F04E30] border-b-2 border-[#F04E30]' : 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300'}`}
          >
            <Icon name="link" className="text-base" /> RTSP trực tiếp
          </button>
        </div>

        {activeMode === 'rtsp' ? (
          <div className="p-8 flex-1 overflow-y-auto">
            <form onSubmit={handleRtspSubmit} className="space-y-6">
              <div className="p-4 bg-blue-50 dark:bg-blue-900/10 border border-blue-200 dark:border-blue-800 rounded-lg flex items-start gap-3">
                <Icon name="info" className="text-blue-500 mt-0.5" />
                <div className="text-sm text-blue-800 dark:text-blue-200">
                  Nhập URL RTSP đầy đủ. Bất kỳ stream nào xem được qua VLC đều hoạt động được.
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
                    value={rtspData.name}
                    onChange={e => setRtspData({ ...rtspData, name: e.target.value })}
                    disabled={isSubmitting}
                    autoFocus
                  />
                </div>
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                  Đường dẫn RTSP <span className="text-red-500">*</span>
                </label>
                <div className="relative">
                  <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                    <Icon name="link" className="text-gray-400 text-lg" />
                  </div>
                  <input
                    required
                    className="w-full pl-10 rounded border-gray-300 dark:border-gray-600 dark:bg-gray-800 dark:text-white focus:ring-[#F04E30] focus:border-[#F04E30] shadow-sm font-mono text-sm"
                    placeholder="rtsp://user:pass@192.168.1.xxx:554/stream1"
                    type="text"
                    value={rtspData.rtsp}
                    onChange={e => setRtspData({ ...rtspData, rtsp: e.target.value })}
                    disabled={isSubmitting}
                  />
                </div>
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                  Sub stream RTSP <span className="text-gray-400 font-normal">(tuỳ chọn — dùng cho AI detect)</span>
                </label>
                <div className="relative">
                  <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                    <Icon name="linked_camera" className="text-gray-400 text-lg" />
                  </div>
                  <input
                    className="w-full pl-10 rounded border-gray-300 dark:border-gray-600 dark:bg-gray-800 dark:text-white focus:ring-[#F04E30] focus:border-[#F04E30] shadow-sm font-mono text-sm"
                    placeholder="rtsp://user:pass@192.168.1.xxx:554/stream2"
                    type="text"
                    value={rtspData.subRtsp}
                    onChange={e => setRtspData({ ...rtspData, subRtsp: e.target.value })}
                    disabled={isSubmitting}
                  />
                </div>
                <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">Nếu để trống, main stream sẽ được dùng cho cả record lẫn detect.</p>
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                  Địa chỉ IP <span className="text-gray-400 font-normal">(tuỳ chọn)</span>
                </label>
                <div className="relative">
                  <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                    <Icon name="dns" className="text-gray-400 text-lg" />
                  </div>
                  <input
                    className="w-full pl-10 rounded border-gray-300 dark:border-gray-600 dark:bg-gray-800 dark:text-white focus:ring-[#F04E30] focus:border-[#F04E30] shadow-sm font-mono"
                    placeholder="192.168.1.xxx"
                    type="text"
                    value={rtspData.ip}
                    onChange={e => setRtspData({ ...rtspData, ip: e.target.value })}
                    disabled={isSubmitting}
                  />
                </div>
                <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">Để trống nếu IP đã có trong đường dẫn RTSP.</p>
              </div>

              {error && (
                <div className="text-sm text-center p-3 rounded-lg border bg-red-50 text-red-700 border-red-200 dark:bg-red-900/20 dark:text-red-300 dark:border-red-800">
                  {error}
                </div>
              )}
            </form>
          </div>
        ) : (

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
        )} {/* end onvif tab */}

        <div className="px-6 py-4 bg-gray-50 dark:bg-[#252525] border-t border-gray-200 dark:border-gray-700 flex justify-end gap-3">
          <button
            onClick={onClose}
            className="px-5 py-2.5 border border-gray-300 dark:border-gray-600 text-gray-700 dark:text-gray-300 rounded hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors font-medium text-sm uppercase tracking-wide"
            disabled={isSubmitting}
          >
            Hủy bỏ
          </button>
          <button
            onClick={activeMode === 'rtsp' ? handleRtspSubmit : handleSubmit}
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

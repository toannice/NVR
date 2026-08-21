import { useState, useEffect, useRef, useCallback } from 'react'
import { Icon } from './Icon'
import { t } from '../i18n';

// ─── Cloud Settings Section ──────────────────────────────────────────────────

// Endpoint + account id are configuration, not secrets. The password is deliberately NOT
// here: anything in this file ends up in the built JS bundle, i.e. readable on every
// machine we ship. It is typed once on install and kept server-side (AES-GCM, CloudConfig).
const DEFAULT_CLOUD = {
  domain: 'https://ppbs.aiview.ai',
  port: 30004,
  username: '616fbb23773c9c73124d5ba4',
};

export const CloudSettingsSection = () => {
  const [mode, setMode] = useState<'default' | 'manual'>('default');
  const [domain, setDomain] = useState('');
  const [port, setPort] = useState(443);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [serial, setSerial] = useState('');
  const [enabled, setEnabled] = useState(false);
  const [connectionState, setConnectionState] = useState('DISABLED');
  const [lastError, setLastError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveMsg, setSaveMsg] = useState('');
  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchConfig = useCallback(async () => {
    try {
      const res = await fetch('/api/cloud/config');
      if (!res.ok) return;
      const data = await res.json();
      const savedDomain = data.domain || '';
      const isDefault = !savedDomain || savedDomain === DEFAULT_CLOUD.domain;
      setMode(isDefault ? 'default' : 'manual');
      setDomain(savedDomain);
      setPort(data.port || 443);
      setUsername(data.username || '');
      setSerial(data.serial || '');
      setEnabled(data.enabled || false);
      setConnectionState(data.connectionState || 'DISABLED');
      setLastError(data.lastError || null);
    } catch (_) {}
  }, []);

  useEffect(() => { fetchConfig(); }, [fetchConfig]);

  useEffect(() => {
    if (connectionState === 'CONNECTING') {
      pollingRef.current = setInterval(fetchConfig, 3000);
    } else {
      if (pollingRef.current) { clearInterval(pollingRef.current); pollingRef.current = null; }
    }
    return () => { if (pollingRef.current) clearInterval(pollingRef.current); };
  }, [connectionState, fetchConfig]);

  const handleModeChange = (newMode: 'default' | 'manual') => {
    setMode(newMode);
    if (newMode === 'default') {
      setDomain(DEFAULT_CLOUD.domain);
      setPort(DEFAULT_CLOUD.port);
      setUsername(DEFAULT_CLOUD.username);
      setPassword('');
    }
  };

  const handleGenerateSerial = async () => {
    if (!serial) return;
    try {
      const res = await fetch('/api/cloud/serial/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prototype: serial }),
      });
      if (res.ok) { const data = await res.json(); setSerial(data.serial); }
    } catch (_) {}
  };

  const handleSave = async () => {
    setSaving(true); setSaveMsg('');
    const payload = mode === 'default'
      ? { domain: DEFAULT_CLOUD.domain, port: DEFAULT_CLOUD.port, username: DEFAULT_CLOUD.username, password, serial, enabled }
      : { domain, port, username, password, serial, enabled };
    try {
      const res = await fetch('/api/cloud/config', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      if (res.ok) {
        setSaveMsg('Đã lưu');
        setPassword('');
        await fetchConfig();
      } else {
        const err = await res.json();
        setSaveMsg(err.error || 'Lỗi lưu cấu hình');
      }
    } catch (e: any) { setSaveMsg('Lỗi kết nối'); }
    setSaving(false);
  };

  const stateConfig: Record<string, { color: string; dot: string; label: string }> = {
    DISABLED:   { color: 'text-gray-500 dark:text-gray-400', dot: 'bg-gray-400', label: 'Đã tắt' },
    CONNECTING: { color: 'text-yellow-500 dark:text-yellow-400', dot: 'bg-yellow-400 animate-pulse', label: 'Đang kết nối...' },
    CONNECTED:  { color: 'text-green-600 dark:text-green-400', dot: 'bg-green-500', label: 'Đã kết nối' },
    ERROR:      { color: 'text-red-600 dark:text-red-400', dot: 'bg-red-500', label: 'Lỗi kết nối' },
  };
  const st = stateConfig[connectionState] ?? stateConfig.DISABLED;
  const isDefault = mode === 'default';
  const inputBase = 'w-full px-3 py-2 border rounded text-sm focus:outline-none focus:ring-2 focus:ring-[#EF4444]';
  const inputActive = 'border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-900 dark:text-white';
  const inputDisabled = 'border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-gray-900 text-gray-400 dark:text-gray-500 cursor-not-allowed';

  return (
    <div className="max-w-lg">
      <h1 className="text-2xl font-bold text-gray-800 dark:text-white flex items-center mb-6">
        <Icon name="cloud" className="text-[#EF4444] mr-3 text-3xl" />{t("Kết nối AIView Cloud")}</h1>

      <div className="bg-white dark:bg-[#1F2937] rounded-lg shadow border border-gray-200 dark:border-gray-700 divide-y divide-gray-100 dark:divide-gray-700">

        {/* Status row */}
        <div className="flex items-center px-6 py-4 gap-4">
          <span className="w-36 text-sm text-gray-600 dark:text-gray-400 shrink-0">{t("Trạng thái kết nối")}</span>
          <div className={`flex items-center gap-2 text-sm font-semibold ${st.color}`}>
            <span className={`w-2.5 h-2.5 rounded-full ${st.dot}`} />
            {connectionState === 'ERROR' && lastError ? t('Lỗi: {0}').replace('{0}', lastError) : t(st.label)}
          </div>
        </div>

        {/* Enable row */}
        <div className="flex items-center px-6 py-4 gap-4">
          <span className="w-36 text-sm text-gray-600 dark:text-gray-400 shrink-0">{t("Bật kết nối Cloud")}</span>
          <button
            onClick={() => setEnabled(v => !v)}
            className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${enabled ? 'bg-[#EF4444]' : 'bg-gray-300 dark:bg-gray-600'}`}
          >
            <span className={`inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform ${enabled ? 'translate-x-6' : 'translate-x-1'}`} />
          </button>
        </div>

        {/* Mode dropdown row */}
        <div className="flex items-center px-6 py-4 gap-4">
          <span className="w-36 text-sm text-gray-600 dark:text-gray-400 shrink-0">{t("Chế độ")}</span>
          <select
            value={mode}
            onChange={e => handleModeChange(e.target.value as 'default' | 'manual')}
            className="flex-1 px-3 py-2 border border-gray-300 dark:border-gray-600 rounded text-sm bg-white dark:bg-gray-800 text-gray-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-[#EF4444]"
          >
            <option value="default">Default</option>
            <option value="manual">Manual</option>
          </select>
        </div>

        {/* Domain row */}
        <div className="flex items-center px-6 py-4 gap-4">
          <span className={`w-36 text-sm shrink-0 ${isDefault ? 'text-gray-400 dark:text-gray-600' : 'text-gray-600 dark:text-gray-400'}`}>{t("Miền:")}</span>
          <input
            value={isDefault ? DEFAULT_CLOUD.domain : domain}
            onChange={e => setDomain(e.target.value)}
            disabled={isDefault}
            className={`flex-1 ${inputBase} ${isDefault ? inputDisabled : inputActive}`}
          />
        </div>

        {/* Port row */}
        <div className="flex items-center px-6 py-4 gap-4">
          <span className={`w-36 text-sm shrink-0 ${isDefault ? 'text-gray-400 dark:text-gray-600' : 'text-gray-600 dark:text-gray-400'}`}>{t("Cổng:")}</span>
          <input
            type="number"
            value={isDefault ? DEFAULT_CLOUD.port : port}
            onChange={e => setPort(Number(e.target.value))}
            disabled={isDefault}
            className={`flex-1 ${inputBase} ${isDefault ? inputDisabled : inputActive}`}
          />
        </div>

        {/* Username row */}
        <div className="flex items-center px-6 py-4 gap-4">
          <span className={`w-36 text-sm shrink-0 ${isDefault ? 'text-gray-400 dark:text-gray-600' : 'text-gray-600 dark:text-gray-400'}`}>{t("Tên đăng nhập:")}</span>
          <input
            value={isDefault ? DEFAULT_CLOUD.username : username}
            onChange={e => setUsername(e.target.value)}
            disabled={isDefault}
            className={`flex-1 ${inputBase} ${isDefault ? inputDisabled : inputActive} font-mono`}
          />
        </div>

        {/* Password row */}
        <div className="flex items-center px-6 py-4 gap-4">
          <span className="w-36 text-sm shrink-0 text-gray-600 dark:text-gray-400">{t("Mật khẩu:")}</span>
          <input
            type="password"
            value={password}
            onChange={e => setPassword(e.target.value)}
            placeholder={t("Nhập mật khẩu (để trống = giữ mật khẩu đã lưu)")}
            className={`flex-1 ${inputBase} ${inputActive}`}
          />
        </div>

        {/* Serial row */}
        <div className="flex items-center px-6 py-4 gap-4">
          <span className="w-36 text-sm text-gray-600 dark:text-gray-400 shrink-0">Serial:</span>
          <div className="flex-1 flex gap-2">
            <input
              value={serial}
              onChange={e => setSerial(e.target.value)}
              placeholder="VMSAV005134"
              className={`flex-1 ${inputBase} ${inputActive} font-mono`}
            />
            <button onClick={handleGenerateSerial}
              className="px-3 py-2 text-xs bg-gray-100 dark:bg-gray-700 text-gray-700 dark:text-gray-300 border border-gray-300 dark:border-gray-600 rounded hover:bg-gray-200 dark:hover:bg-gray-600 transition-colors whitespace-nowrap">{t("Tạo tiếp theo")}</button>
          </div>
        </div>

        {/* Save button row */}
        <div className="flex items-center px-6 py-4 gap-3">
          <button onClick={handleSave} disabled={saving}
            className="px-6 py-2 bg-[#EF4444] text-white rounded shadow hover:bg-red-600 transition-colors text-sm font-medium disabled:opacity-50">
            {saving ? t('Đang lưu...') : t('Áp dụng')}
          </button>
          {saveMsg && <span className={`text-sm ${saveMsg === 'Đã lưu' ? 'text-green-500' : 'text-red-500'}`}>{t(saveMsg)}</span>}
        </div>
      </div>
    </div>
  );
};

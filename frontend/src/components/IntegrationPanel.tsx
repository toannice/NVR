import { useState, useEffect, useCallback } from 'react'

// AiView Box integration settings (NVR_AIView_Integration_Protocol v1.1, §8 — auth hướng A).
// Admin toggles the integration and sets a dedicated username/password pair the Box uses to
// authenticate against the NVR's public /api/getprofilecamera endpoint. This credential is
// completely independent of the pattern-lock — locking the NVR UI does NOT disable the Box API.

type ToastType = 'success' | 'error' | 'info' | 'warning'

type Settings = {
  enabled: boolean
  username: string
  hasPassword: boolean
}

export default function IntegrationPanel({ addToast }: {
  addToast?: (toast: { type: ToastType; title: string; message: string }) => void
}) {
  const toast = (message: string, type: ToastType = 'info', title?: string) => {
    if (addToast) addToast({ type, title: title || (type === 'error' ? 'Lỗi' : 'Tích hợp Box'), message })
    else console.log(`[${type}] ${message}`)
  }

  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [enabled, setEnabled] = useState(false)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [hasPassword, setHasPassword] = useState(false)
  const [info, setInfo] = useState<{ protocol_version: string; nvr_version: string; max_cameras: number; rtsp_auth: boolean } | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await fetch('/api/integration/settings')
      if (!r.ok) throw new Error('HTTP ' + r.status)
      const s: Settings = await r.json()
      setEnabled(s.enabled)
      setUsername(s.username || '')
      setHasPassword(s.hasPassword)
      setPassword('')
    } catch (e: any) {
      toast('Không tải được cài đặt tích hợp: ' + (e?.message || e), 'error')
    } finally {
      setLoading(false)
    }
    // protocol info (public endpoint) — shown for verification against the contract
    try {
      const ri = await fetch('/api/getintegrationinfo')
      if (ri.ok) setInfo(await ri.json())
    } catch { /* non-fatal */ }
  }, [])

  useEffect(() => { load() }, [load])

  const save = async () => {
    if (enabled && username.trim() === '') {
      toast('Bật tích hợp cần có tên đăng nhập.', 'warning'); return
    }
    if (enabled && !hasPassword && password.trim() === '') {
      toast('Bật tích hợp cần đặt mật khẩu (lần đầu).', 'warning'); return
    }
    setSaving(true)
    try {
      const body: any = { enabled, username: username.trim() }
      if (password.trim() !== '') body.password = password
      const r = await fetch('/api/integration/settings', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      if (!r.ok) {
        const err = await r.json().catch(() => ({}))
        throw new Error(err.message || ('HTTP ' + r.status))
      }
      const s: Settings = await r.json()
      setEnabled(s.enabled)
      setUsername(s.username || '')
      setHasPassword(s.hasPassword)
      setPassword('')
      toast('Đã lưu cài đặt tích hợp AiView Box.', 'success')
    } catch (e: any) {
      toast('Lưu thất bại: ' + (e?.message || e), 'error')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="p-4 sm:p-6 max-w-3xl mx-auto text-gray-200">
      <div className="mb-6">
        <h2 className="text-xl font-semibold text-white">Tích hợp AiView Box</h2>
        <p className="text-sm text-gray-400 mt-1">
          Cho phép hệ thống AiView Box kết nối tới NVR này (cùng mạng LAN) để lấy danh sách camera và
          luồng RESTREAM. Box xác thực bằng cặp tên đăng nhập / mật khẩu riêng dưới đây —
          <span className="text-gray-300"> độc lập với mật khẩu mở khoá màn hình.</span>
        </p>
      </div>

      {loading ? (
        <div className="text-gray-400">Đang tải…</div>
      ) : (
        <div className="space-y-5 bg-[#1f2937] rounded-xl p-5 border border-white/10">
          {/* Enable toggle */}
          <label className="flex items-center justify-between cursor-pointer select-none">
            <div>
              <div className="font-medium text-white">Bật tích hợp AiView Box</div>
              <div className="text-xs text-gray-400">Tắt = mọi yêu cầu từ Box bị từ chối (403).</div>
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={enabled}
              onClick={() => setEnabled(v => !v)}
              className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${enabled ? 'bg-[#EF4444]' : 'bg-gray-600'}`}
            >
              <span className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${enabled ? 'translate-x-6' : 'translate-x-1'}`} />
            </button>
          </label>

          <div className="h-px bg-white/10" />

          {/* Username */}
          <div>
            <label className="block text-sm font-medium text-gray-300 mb-1">Tên đăng nhập (cho Box)</label>
            <input
              type="text"
              value={username}
              autoComplete="off"
              onChange={e => setUsername(e.target.value)}
              placeholder="vd: box"
              className="w-full rounded-lg bg-[#111827] border border-white/10 px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-[#EF4444]/50"
            />
          </div>

          {/* Password */}
          <div>
            <label className="block text-sm font-medium text-gray-300 mb-1">
              Mật khẩu (cho Box)
              {hasPassword && <span className="ml-2 text-xs text-emerald-400">• đã đặt</span>}
            </label>
            <input
              type="password"
              value={password}
              autoComplete="new-password"
              onChange={e => setPassword(e.target.value)}
              placeholder={hasPassword ? 'Để trống = giữ mật khẩu hiện tại' : 'Nhập mật khẩu mới'}
              className="w-full rounded-lg bg-[#111827] border border-white/10 px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-[#EF4444]/50"
            />
            <p className="text-xs text-gray-500 mt-1">
              Đổi mật khẩu ở đây sẽ <span className="text-gray-300">thu hồi</span> quyền truy cập của Box cũ.
            </p>
          </div>

          <div className="flex items-center gap-3 pt-1">
            <button
              onClick={save}
              disabled={saving}
              className="px-4 py-2 rounded-lg bg-[#EF4444] text-white font-medium hover:bg-[#dc2626] disabled:opacity-50 transition-colors"
            >
              {saving ? 'Đang lưu…' : 'Lưu'}
            </button>
            <button
              onClick={load}
              disabled={saving}
              className="px-3 py-2 rounded-lg bg-white/5 text-gray-300 hover:bg-white/10 text-sm transition-colors"
            >
              Tải lại
            </button>
            <span className={`text-sm ${enabled ? 'text-emerald-400' : 'text-gray-500'}`}>
              {enabled ? '● Đang bật' : '○ Đang tắt'}
            </span>
          </div>
        </div>
      )}

      {/* Protocol info — for quick verification against the integration contract */}
      {info && (
        <div className="mt-5 text-xs text-gray-500 bg-[#111827] rounded-lg p-4 border border-white/5 font-mono">
          <div className="text-gray-400 mb-2 font-sans font-medium">Thông tin giao thức (GET /api/getintegrationinfo)</div>
          <div>protocol_version: <span className="text-gray-300">{info.protocol_version}</span></div>
          <div>nvr_version: <span className="text-gray-300">{info.nvr_version}</span></div>
          <div>max_cameras: <span className="text-gray-300">{info.max_cameras}</span></div>
          <div>rtsp_auth: <span className="text-gray-300">{String(info.rtsp_auth)}</span></div>
        </div>
      )}

      <div className="mt-5 text-xs text-gray-500">
        <div className="font-medium text-gray-400 mb-1">Endpoint công khai cho Box (cùng LAN):</div>
        <ul className="list-disc list-inside space-y-0.5">
          <li><code className="text-gray-400">GET  /api/getintegrationinfo</code> — phiên bản &amp; giới hạn</li>
          <li><code className="text-gray-400">POST /api/getprofilecamera</code> — danh sách camera + URL restream (cần user/pass)</li>
          <li><code className="text-gray-400">POST /api/getcamerastatus</code> — trạng thái online/đã-xoá (định kỳ)</li>
        </ul>
      </div>
    </div>
  )
}

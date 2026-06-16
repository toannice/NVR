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
  passwordLen: number
}

type Connection = {
  ip: string
  username: string
  firstSeen: number
  lastSeen: number
  hitCount: number
  lastEndpoint: string
}

// "vài giây trước" / "3 phút trước" … relative time for the connections table.
function timeAgo(ms: number): string {
  const s = Math.max(0, Math.floor((Date.now() - ms) / 1000))
  if (s < 10) return 'vừa xong'
  if (s < 60) return `${s} giây trước`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m} phút trước`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} giờ trước`
  const d = Math.floor(h / 24)
  return `${d} ngày trước`
}

function fmtTime(ms: number): string {
  try { return new Date(ms).toLocaleString('vi-VN') } catch { return String(ms) }
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
  const [pwTouched, setPwTouched] = useState(false)   // has the operator edited the password field?
  const [hasPassword, setHasPassword] = useState(false)
  const [passwordLen, setPasswordLen] = useState(0)
  const [info, setInfo] = useState<{ protocol_version: string; nvr_version: string; max_cameras: number; rtsp_auth: boolean } | null>(null)
  const [connections, setConnections] = useState<Connection[]>([])
  const [connLoading, setConnLoading] = useState(false)

  // Masked dots matching the real password length — shown when set & not being edited.
  const maskedPassword = '•'.repeat(passwordLen)

  const loadConnections = useCallback(async () => {
    setConnLoading(true)
    try {
      const r = await fetch('/api/integration/connections')
      if (r.ok) setConnections(await r.json())
    } catch { /* non-fatal */ } finally {
      setConnLoading(false)
    }
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await fetch('/api/integration/settings')
      if (!r.ok) throw new Error('HTTP ' + r.status)
      const s: Settings = await r.json()
      setEnabled(s.enabled)
      setUsername(s.username || '')
      setHasPassword(s.hasPassword)
      setPasswordLen(s.passwordLen || 0)
      setPwTouched(false)
      setPassword(s.hasPassword ? '•'.repeat(s.passwordLen || 0) : '')
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
    loadConnections()
  }, [loadConnections])

  useEffect(() => { load() }, [load])

  // Auto-refresh the connections table while the panel is open.
  useEffect(() => {
    const t = setInterval(loadConnections, 15000)
    return () => clearInterval(t)
  }, [loadConnections])

  const save = async () => {
    // A password is being changed only when the operator actually edited the field.
    const newPassword = pwTouched ? password.trim() : ''
    if (enabled && username.trim() === '') {
      toast('Bật tích hợp cần có tên đăng nhập.', 'warning'); return
    }
    if (enabled && !hasPassword && newPassword === '') {
      toast('Bật tích hợp cần đặt mật khẩu (lần đầu).', 'warning'); return
    }
    setSaving(true)
    try {
      const body: any = { enabled, username: username.trim() }
      if (newPassword !== '') body.password = newPassword
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
      setPasswordLen(s.passwordLen || 0)
      setPwTouched(false)
      setPassword(s.hasPassword ? '•'.repeat(s.passwordLen || 0) : '')
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
              onChange={e => { setPwTouched(true); setPassword(e.target.value) }}
              onFocus={() => { if (!pwTouched && hasPassword) setPassword('') }}
              onBlur={() => { if (!pwTouched && hasPassword) setPassword(maskedPassword) }}
              placeholder={hasPassword ? 'Bấm để đổi mật khẩu' : 'Nhập mật khẩu mới'}
              className="w-full rounded-lg bg-[#111827] border border-white/10 px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-[#EF4444]/50"
            />
            <p className="text-xs text-gray-500 mt-1">
              {hasPassword
                ? <>Đang hiển thị <span className="text-gray-300">{passwordLen}</span> ký tự che (đúng độ dài mật khẩu thật). Bấm vào ô để đổi — đổi mật khẩu sẽ <span className="text-gray-300">thu hồi</span> quyền của Box cũ.</>
                : <>Đổi mật khẩu ở đây sẽ <span className="text-gray-300">thu hồi</span> quyền truy cập của Box cũ.</>}
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

      {/* Connections table — which Box IPs have successfully connected */}
      <div className="mt-6">
        <div className="flex items-center justify-between mb-2">
          <h3 className="text-sm font-semibold text-white">
            Thiết bị đã kết nối thành công
            <span className="ml-2 text-xs font-normal text-gray-500">({connections.length})</span>
          </h3>
          <button
            onClick={loadConnections}
            disabled={connLoading}
            className="px-2.5 py-1 rounded-md bg-white/5 text-gray-300 hover:bg-white/10 text-xs transition-colors disabled:opacity-50"
          >
            {connLoading ? 'Đang tải…' : '⟳ Làm mới'}
          </button>
        </div>
        <div className="overflow-x-auto rounded-xl border border-white/10 bg-[#1f2937]">
          <table className="w-full text-sm text-left">
            <thead className="text-xs uppercase text-gray-400 border-b border-white/10">
              <tr>
                <th className="px-4 py-2.5 font-medium">Địa chỉ IP</th>
                <th className="px-4 py-2.5 font-medium">Tên đăng nhập</th>
                <th className="px-4 py-2.5 font-medium">Hoạt động gần nhất</th>
                <th className="px-4 py-2.5 font-medium text-right">Số lượt</th>
                <th className="px-4 py-2.5 font-medium">Lần đầu kết nối</th>
              </tr>
            </thead>
            <tbody>
              {connections.length === 0 ? (
                <tr>
                  <td colSpan={5} className="px-4 py-6 text-center text-gray-500">
                    Chưa có thiết bị nào kết nối thành công.
                  </td>
                </tr>
              ) : connections.map(c => {
                const live = Date.now() - c.lastSeen < 120000   // active within last 2 minutes
                return (
                  <tr key={c.ip} className="border-b border-white/5 last:border-0 hover:bg-white/5">
                    <td className="px-4 py-2.5 font-mono text-gray-200">
                      <span className={`inline-block h-2 w-2 rounded-full mr-2 ${live ? 'bg-emerald-400' : 'bg-gray-500'}`} />
                      {c.ip}
                    </td>
                    <td className="px-4 py-2.5 text-gray-300">{c.username || '—'}</td>
                    <td className="px-4 py-2.5 text-gray-300" title={fmtTime(c.lastSeen)}>{timeAgo(c.lastSeen)}</td>
                    <td className="px-4 py-2.5 text-right text-gray-400">{c.hitCount}</td>
                    <td className="px-4 py-2.5 text-gray-500" title={c.lastEndpoint}>{fmtTime(c.firstSeen)}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
        <p className="text-xs text-gray-500 mt-1.5">
          Chấm <span className="text-emerald-400">●</span> xanh = đang hoạt động (trong 2 phút). Tự làm mới mỗi 15 giây.
        </p>
      </div>

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

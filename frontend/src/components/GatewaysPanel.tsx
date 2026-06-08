import { useState, useEffect, useCallback } from 'react'

// Edge gateway management (4-6 Plan A). Gateways are passive L4 proxies the NVR auto-discovers
// on the ZeroTier subnet. Flow: discover → approve → adopt camera (admin types ONVIF creds here;
// the gateway never stores them). Adopted cameras then appear as normal cameras everywhere.

type Gateway = {
  id: string
  name: string
  zerotierIp: string | null
  ztAddress: string | null
  agentPort: number
  status: string        // discovered | approved
  online: boolean
  fieldSubnet: string | null
  installer: string | null
  location: string | null
  lastSeenEpoch: number | null
  cameraCount: number
}

type PendingMember = {
  nodeId: string
  ipAssignments: string[]
}

type Addable = {
  localIp: string
  name: string
  // form state:
  username: string
  password: string
  source: 'proxied' | 'discovered'
  busy?: boolean
}

const Icon = ({ name, className = '' }: { name: string; className?: string }) => (
  <span className={`material-icons ${className}`}>{name}</span>
)

type ToastType = 'success' | 'error' | 'info' | 'warning'

export default function GatewaysPanel({ addToast, onCamerasChanged }: {
  addToast?: (toast: { type: ToastType; title: string; message: string }) => void
  onCamerasChanged?: () => void   // refresh the global camera list after a successful adopt
}) {
  const toast = (message: string, type: ToastType = 'info') => {
    const title = type === 'success' ? 'Thành công' : type === 'error' ? 'Lỗi' : type === 'warning' ? 'Cảnh báo' : 'Gateway'
    if (addToast) addToast({ type, title, message }); else console.log(`[${type}] ${message}`)
  }

  const [gateways, setGateways] = useState<Gateway[]>([])
  const [pending, setPending] = useState<PendingMember[]>([])
  const [loading, setLoading] = useState(true)
  const [scanning, setScanning] = useState(false)
  // per-gateway list of addable cameras + whether its panel is expanded
  const [openId, setOpenId] = useState<string | null>(null)
  const [addable, setAddable] = useState<Record<string, Addable[]>>({})
  const [busyAction, setBusyAction] = useState<string>('')

  const fetchGateways = useCallback(async () => {
    try {
      const r = await fetch('/api/gateways')
      if (r.ok) setGateways(await r.json())
    } catch { /* ignore */ }
    setLoading(false)
  }, [])

  const fetchPending = useCallback(async () => {
    try {
      const r = await fetch('/api/gateways/pending')
      if (r.ok) {
        const d = await r.json()
        setPending(d.configured ? (d.members || []) : [])
      }
    } catch { /* ignore */ }
  }, [])

  useEffect(() => {
    fetchGateways()
    fetchPending()
    const t = setInterval(() => { fetchGateways(); fetchPending() }, 10000)
    return () => clearInterval(t)
  }, [fetchGateways, fetchPending])

  const authorizeMember = async (nodeId: string) => {
    setBusyAction('authorize:' + nodeId)
    try {
      const r = await fetch(`/api/gateways/authorize/${nodeId}`, { method: 'POST' })
      if (r.ok) {
        toast('Đã cấp phép thiết bị vào mạng. Gateway sẽ xuất hiện trong ~30 giây.', 'success')
        setPending(prev => prev.filter(p => p.nodeId !== nodeId))
        await fetchGateways()
      } else {
        const d = await r.json().catch(() => ({}))
        toast(d.error || 'Cấp phép thất bại', 'error')
      }
    } catch { toast('Lỗi mạng khi cấp phép', 'error') }
    finally { setBusyAction('') }
  }

  const scanNow = async () => {
    setScanning(true)
    try {
      const r = await fetch('/api/gateways/scan', { method: 'POST' })
      const d = await r.json().catch(() => ({}))
      toast(`Quét xong: tìm thấy ${d.found ?? 0} gateway`, 'success')
      await fetchGateways()
    } catch { toast('Quét thất bại', 'error') }
    setScanning(false)
  }

  const approve = async (id: string) => {
    setBusyAction('approve:' + id)
    try {
      const r = await fetch(`/api/gateways/${id}/approve`, { method: 'POST' })
      if (r.ok) { toast('Đã phê duyệt gateway', 'success'); await fetchGateways() }
      else toast('Phê duyệt thất bại', 'error')
    } finally { setBusyAction('') }
  }

  const deleteGateway = async (g: Gateway) => {
    const msg = g.cameraCount > 0
      ? `Xóa gateway "${g.name}" và ${g.cameraCount} camera đã thêm qua nó? Không thể hoàn tác.`
      : `Xóa gateway "${g.name}"?`
    if (!window.confirm(msg)) return
    setBusyAction('delete:' + g.id)
    try {
      const r = await fetch(`/api/gateways/${g.id}`, { method: 'DELETE' })
      const d = await r.json().catch(() => ({}))
      if (r.ok && d.success) {
        toast(`Đã xóa gateway${d.camerasRemoved ? ` + ${d.camerasRemoved} camera` : ''}`, 'success')
        if (openId === g.id) setOpenId(null)
        await fetchGateways()
        onCamerasChanged?.()
      } else toast(d.error || 'Xóa gateway thất bại', 'error')
    } catch { toast('Lỗi mạng khi xóa gateway', 'error') }
    finally { setBusyAction('') }
  }

  const mergeAddable = (id: string, rows: Addable[]) => {
    setAddable(prev => {
      const existing = prev[id] || []
      const byIp = new Map(existing.map(r => [r.localIp, r]))
      for (const r of rows) if (!byIp.has(r.localIp)) byIp.set(r.localIp, r)
      return { ...prev, [id]: Array.from(byIp.values()) }
    })
  }

  const loadProxied = async (id: string) => {
    setBusyAction('proxied:' + id)
    try {
      const r = await fetch(`/api/gateways/${id}/adoptable`)
      const d = await r.json().catch(() => ({}))
      if (!r.ok) { toast(d.error || 'Không tải được danh sách', 'error'); return }
      const rows: Addable[] = (d.cameras || []).map((c: any) => ({
        localIp: c.localIp, name: c.name || c.localIp, username: 'admin', password: '', source: 'proxied' as const,
      }))
      mergeAddable(id, rows)
      if (rows.length === 0) toast('Chưa có camera nào được proxy trên gateway này', 'info')
    } finally { setBusyAction('') }
  }

  const discover = async (id: string) => {
    setBusyAction('discover:' + id)
    try {
      const r = await fetch(`/api/gateways/${id}/discover`, { method: 'POST' })
      const d = await r.json().catch(() => ({}))
      if (!r.ok) { toast(d.error || 'Quét LAN thất bại', 'error'); return }
      const rows: Addable[] = (d.devices || []).map((dev: any) => ({
        localIp: dev.ip, name: dev.name || dev.hardware || dev.ip, username: 'admin', password: '', source: 'discovered' as const,
      }))
      mergeAddable(id, rows)
      toast(`Quét LAN: ${rows.length} thiết bị`, 'success')
    } finally { setBusyAction('') }
  }

  const updateRow = (id: string, ip: string, patch: Partial<Addable>) => {
    setAddable(prev => ({ ...prev, [id]: (prev[id] || []).map(r => r.localIp === ip ? { ...r, ...patch } : r) }))
  }

  const adopt = async (id: string, row: Addable) => {
    if (!row.password) { toast('Nhập mật khẩu ONVIF', 'error'); return }
    updateRow(id, row.localIp, { busy: true })
    try {
      const r = await fetch(`/api/gateways/${id}/cameras`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ localIp: row.localIp, username: row.username, password: row.password, name: row.name }),
      })
      const d = await r.json().catch(() => ({}))
      if (r.ok && d.success) {
        toast(`Đã thêm camera ${row.name}${d.frigateRestarted ? '' : ' (Frigate chưa restart)'}`, 'success')
        // remove the row once adopted
        setAddable(prev => ({ ...prev, [id]: (prev[id] || []).filter(x => x.localIp !== row.localIp) }))
        await fetchGateways()
        onCamerasChanged?.()   // tell App to re-fetch /api/cameras so it shows up immediately
      } else {
        toast(d.error || 'Thêm camera thất bại', 'error')
        updateRow(id, row.localIp, { busy: false })
      }
    } catch {
      toast('Lỗi mạng khi thêm camera', 'error')
      updateRow(id, row.localIp, { busy: false })
    }
  }

  const statusBadge = (g: Gateway) => {
    if (g.status === 'discovered')
      return <span className="px-2 py-0.5 rounded-full text-xs bg-amber-100 text-amber-700">Chờ duyệt</span>
    return g.online
      ? <span className="px-2 py-0.5 rounded-full text-xs bg-green-100 text-green-700">● Online</span>
      : <span className="px-2 py-0.5 rounded-full text-xs bg-gray-200 text-gray-600">○ Offline</span>
  }

  return (
    <div className="p-4 md:p-6 max-w-4xl mx-auto">
      <div className="flex items-center justify-between mb-4">
        <div>
          <h1 className="text-xl font-bold text-gray-800">Gateway biên</h1>
          <p className="text-sm text-gray-500">Tự phát hiện trên ZeroTier. Duyệt gateway rồi thêm camera (nhập tài khoản ONVIF tại đây).</p>
        </div>
        <button onClick={scanNow} disabled={scanning}
          className="flex items-center gap-1 bg-[#EF4444] text-white px-4 py-2 rounded-lg text-sm disabled:opacity-50">
          <Icon name="radar" className="text-base" /> {scanning ? 'Đang quét…' : 'Quét ngay'}
        </button>
      </div>

      {pending.length > 0 && (
        <div className="mb-4 bg-amber-50 border border-amber-200 rounded-xl p-4">
          <div className="flex items-center gap-2 mb-1">
            <Icon name="verified_user" className="text-amber-600 text-base" />
            <h2 className="font-semibold text-amber-800 text-sm">Thiết bị mới chờ cấp phép ({pending.length})</h2>
          </div>
          <p className="text-xs text-amber-700/80 mb-3">
            Gateway vừa cắm điện đã liên hệ controller nhưng chưa được vào mạng. Cấp phép để NVR thấy và quản lý.
          </p>
          <div className="space-y-2">
            {pending.map(p => (
              <div key={p.nodeId} className="flex items-center justify-between bg-white border border-amber-200 rounded-lg px-3 py-2">
                <div className="min-w-0">
                  <div className="font-mono text-sm text-gray-800">{p.nodeId}</div>
                  <div className="text-xs text-gray-400">
                    {p.ipAssignments.length > 0 ? `IP dự kiến: ${p.ipAssignments.join(', ')}` : 'Chưa cấp IP'}
                  </div>
                </div>
                <button onClick={() => authorizeMember(p.nodeId)} disabled={busyAction === 'authorize:' + p.nodeId}
                  className="bg-amber-600 text-white px-3 py-1.5 rounded-lg text-sm shrink-0 disabled:opacity-50">
                  {busyAction === 'authorize:' + p.nodeId ? 'Đang cấp phép…' : 'Cấp phép'}
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      {loading ? (
        <p className="text-gray-500">Đang tải…</p>
      ) : gateways.length === 0 ? (
        <div className="bg-gray-50 border border-dashed border-gray-300 rounded-xl p-8 text-center text-gray-500">
          Chưa phát hiện gateway nào. Đảm bảo Pi đã chạy agent + join ZeroTier, rồi bấm “Quét ngay”.
        </div>
      ) : (
        <div className="space-y-3">
          {gateways.map(g => (
            <div key={g.id} className="bg-white border border-gray-200 rounded-xl shadow-sm">
              <div className="flex items-center justify-between p-4">
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="font-semibold text-gray-800 truncate">{g.name}</span>
                    {statusBadge(g)}
                  </div>
                  <div className="text-xs text-gray-500 mt-0.5">
                    {g.zerotierIp || '(chưa có IP)'} :{g.agentPort}
                    {g.ztAddress ? ` · ZT ${g.ztAddress}` : ''}
                    {g.fieldSubnet ? ` · LAN ${g.fieldSubnet}` : ''} · {g.cameraCount} camera
                  </div>
                  {(g.installer || g.location) && (
                    <div className="text-xs text-gray-400 mt-0.5 truncate">
                      {g.location ? `📍 ${g.location}` : ''}{g.location && g.installer ? ' · ' : ''}{g.installer ? `👤 ${g.installer}` : ''}
                    </div>
                  )}
                </div>
                <div className="flex items-center gap-2 shrink-0">
                  {g.status === 'discovered' ? (
                    <button onClick={() => approve(g.id)} disabled={busyAction === 'approve:' + g.id}
                      className="bg-blue-600 text-white px-3 py-1.5 rounded-lg text-sm disabled:opacity-50">Phê duyệt</button>
                  ) : (
                    <button onClick={() => setOpenId(openId === g.id ? null : g.id)}
                      className="flex items-center gap-1 bg-gray-100 text-gray-700 px-3 py-1.5 rounded-lg text-sm">
                      <Icon name={openId === g.id ? 'expand_less' : 'add_a_photo'} className="text-base" /> Thêm camera
                    </button>
                  )}
                  <button onClick={() => deleteGateway(g)} disabled={busyAction === 'delete:' + g.id}
                    title="Xóa gateway" aria-label="Xóa gateway"
                    className="flex items-center text-gray-400 hover:text-red-600 px-2 py-1.5 rounded-lg disabled:opacity-50">
                    <Icon name="delete_outline" className="text-base" />
                  </button>
                </div>
              </div>

              {openId === g.id && g.status === 'approved' && (
                <div className="border-t border-gray-100 p-4 bg-gray-50/60">
                  <div className="flex gap-2 mb-3">
                    <button onClick={() => loadProxied(g.id)} disabled={busyAction === 'proxied:' + g.id}
                      className="text-sm bg-white text-gray-800 border border-gray-300 px-3 py-1.5 rounded-lg disabled:opacity-50">
                      {busyAction === 'proxied:' + g.id ? 'Đang tải…' : 'Camera đã sẵn sàng'}
                    </button>
                    <button onClick={() => discover(g.id)} disabled={busyAction === 'discover:' + g.id}
                      className="text-sm bg-white text-gray-800 border border-gray-300 px-3 py-1.5 rounded-lg disabled:opacity-50">
                      {busyAction === 'discover:' + g.id ? 'Đang quét LAN…' : 'Quét LAN hiện trường'}
                    </button>
                  </div>

                  {(addable[g.id] || []).length === 0 ? (
                    <p className="text-sm text-gray-400">Bấm một trong hai nút trên để liệt kê camera.</p>
                  ) : (
                    <div className="space-y-2">
                      {(addable[g.id] || []).map(row => (
                        <div key={row.localIp} className="bg-white border border-gray-200 rounded-lg p-3">
                          <div className="flex items-center gap-2 mb-2">
                            <span className="font-medium text-gray-800">{row.localIp}</span>
                            <span className={`text-[10px] px-1.5 py-0.5 rounded-full ${row.source === 'proxied' ? 'bg-green-50 text-green-600' : 'bg-indigo-50 text-indigo-600'}`}>
                              {row.source === 'proxied' ? 'đã proxy' : 'tìm thấy'}
                            </span>
                          </div>
                          <div className="grid grid-cols-1 sm:grid-cols-4 gap-2 items-end">
                            <label className="block">
                              <span className="text-xs text-gray-500">Tên camera</span>
                              <input className="mt-0.5 w-full border border-gray-300 rounded px-2 py-1.5 text-sm text-gray-900 bg-white placeholder-gray-400" placeholder="vd: Cổng trước"
                                value={row.name} onChange={e => updateRow(g.id, row.localIp, { name: e.target.value })} />
                            </label>
                            <label className="block">
                              <span className="text-xs text-gray-500">Tài khoản ONVIF</span>
                              <input className="mt-0.5 w-full border border-gray-300 rounded px-2 py-1.5 text-sm text-gray-900 bg-white placeholder-gray-400" placeholder="vd: admin"
                                value={row.username} onChange={e => updateRow(g.id, row.localIp, { username: e.target.value })} />
                            </label>
                            <label className="block">
                              <span className="text-xs text-gray-500">Mật khẩu ONVIF</span>
                              <input className="mt-0.5 w-full border border-gray-300 rounded px-2 py-1.5 text-sm text-gray-900 bg-white placeholder-gray-400" type="password" placeholder="••••••"
                                value={row.password} onChange={e => updateRow(g.id, row.localIp, { password: e.target.value })} />
                            </label>
                            <button onClick={() => adopt(g.id, row)} disabled={row.busy}
                              className="bg-[#EF4444] text-white rounded px-3 py-2 text-sm disabled:opacity-50">
                              {row.busy ? 'Đang thêm…' : 'Thêm'}
                            </button>
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

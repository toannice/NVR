import { useState, useEffect, useCallback } from 'react'
import { t } from '../i18n';

// Edge gateway management (4-6 Plan A). Gateways are passive L4 proxies the NVR auto-discovers
// on the Nebula overlay subnet. Flow: discover → approve → adopt camera (admin types ONVIF creds here;
// the gateway never stores them). Adopted cameras then appear as normal cameras everywhere.

type Gateway = {
  id: string
  name: string
  zerotierIp: string | null   // overlay IP (API keeps the historical key; holds the Nebula overlay IP)
  agentPort: number
  status: string        // discovered | approved
  online: boolean
  fieldSubnet: string | null
  installer: string | null
  location: string | null
  lastSeenEpoch: number | null
  cameraCount: number
  certExpiry?: string | null
  certDaysLeft?: number | null
  health?: string        // online | degraded (cert<30d) | offline
}

// Overlay control-plane (HQ) health, from GET /api/gateways/hq.
type HqStatus = {
  configured: boolean
  overlay: string        // nebula | zerotier | none
  reachable: boolean
  lighthouse?: string | null
  pending?: number
  uptimeSec?: number
}

// A gateway awaiting "Cấp phép": `id` is what /authorize takes (the Nebula enrollment token),
// `label` the display name, `detail` the overlay IP assignment(s).
type PendingMember = {
  id: string
  label: string
  detail: string[]
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

// A camera already adopted through this gateway (as the NVR stores it).
type AdoptedCam = {
  slug: string
  name: string
  localIp: string | null
  channelId: string | null
  ptzEnabled?: boolean
  ptzMode?: string | null   // canonical 3-state: null/'none' | 'zoom' | 'full'
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
    const title = type === 'success' ? t('Thành công') : type === 'error' ? t('Lỗi') : type === 'warning' ? t('Cảnh báo') : 'Gateway'
    if (addToast) addToast({ type, title, message }); else console.log(`[${type}] ${message}`)
  }

  const [gateways, setGateways] = useState<Gateway[]>([])
  const [hq, setHq] = useState<HqStatus | null>(null)
  const [pending, setPending] = useState<PendingMember[]>([])
  const [loading, setLoading] = useState(true)
  const [scanning, setScanning] = useState(false)
  // per-gateway list of addable cameras + whether its panel is expanded
  const [openId, setOpenId] = useState<string | null>(null)
  const [addable, setAddable] = useState<Record<string, Addable[]>>({})
  // per-gateway list of cameras already adopted through it (for the delete-single-camera list)
  const [adopted, setAdopted] = useState<Record<string, AdoptedCam[]>>({})
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

  const fetchHq = useCallback(async () => {
    try {
      const r = await fetch('/api/gateways/hq')
      if (r.ok) setHq(await r.json())
    } catch { /* ignore */ }
  }, [])

  useEffect(() => {
    fetchGateways()
    fetchPending()
    fetchHq()
    const timer = setInterval(() => { fetchGateways(); fetchPending(); fetchHq() }, 10000)
    return () => clearInterval(timer)
  }, [fetchGateways, fetchPending, fetchHq])

  const authorizeMember = async (id: string) => {
    setBusyAction('authorize:' + id)
    try {
      const r = await fetch(`/api/gateways/authorize/${id}`, { method: 'POST' })
      if (r.ok) {
        toast(t('Đã cấp phép thiết bị vào mạng. Gateway sẽ xuất hiện trong ~30 giây.'), 'success')
        setPending(prev => prev.filter(p => p.id !== id))
        await fetchGateways()
      } else {
        const d = await r.json().catch(() => ({}))
        toast(d.error || t('Cấp phép thất bại'), 'error')
      }
    } catch { toast(t('Lỗi mạng khi cấp phép'), 'error') }
    finally { setBusyAction('') }
  }

  const scanNow = async () => {
    setScanning(true)
    try {
      const r = await fetch('/api/gateways/scan', { method: 'POST' })
      const d = await r.json().catch(() => ({}))
      toast(t('Quét xong: tìm thấy {0} gateway').replace('{0}', String(d.found ?? 0)), 'success')
      await fetchGateways()
    } catch { toast(t('Quét thất bại'), 'error') }
    setScanning(false)
  }

  const approve = async (id: string) => {
    setBusyAction('approve:' + id)
    try {
      const r = await fetch(`/api/gateways/${id}/approve`, { method: 'POST' })
      if (r.ok) { toast(t('Đã phê duyệt gateway'), 'success'); await fetchGateways() }
      else toast(t('Phê duyệt thất bại'), 'error')
    } finally { setBusyAction('') }
  }

  const deleteGateway = async (g: Gateway) => {
    const msg = g.cameraCount > 0
      ? t('Xóa gateway "{0}" và {1} camera đã thêm qua nó? Không thể hoàn tác.').replace('{0}', g.name).replace('{1}', String(g.cameraCount))
      : t('Xóa gateway "{0}"?').replace('{0}', g.name)
    if (!window.confirm(msg)) return
    setBusyAction('delete:' + g.id)
    try {
      const r = await fetch(`/api/gateways/${g.id}`, { method: 'DELETE' })
      const d = await r.json().catch(() => ({}))
      if (r.ok && d.success) {
        toast(t('Đã xóa gateway{0}').replace('{0}', d.camerasRemoved ? t(' + {0} camera').replace('{0}', String(d.camerasRemoved)) : ''), 'success')
        if (openId === g.id) setOpenId(null)
        await fetchGateways()
        onCamerasChanged?.()
      } else toast(d.error || t('Xóa gateway thất bại'), 'error')
    } catch { toast(t('Lỗi mạng khi xóa gateway'), 'error') }
    finally { setBusyAction('') }
  }

  // G8: revoke a lost/stolen gateway (or one whose SD is suspected cloned). Stronger than delete — it
  // also tells the HQ to revoke the cert so the Pi can't renew, and tombstones the row so the scanner
  // can't re-approve it. The gateway stays in the list as "Đã thu hồi" until hard-deleted.
  const revokeGateway = async (g: Gateway) => {
    const msg = t('THU HỒI gateway "{0}"?').replace('{0}', g.name) + '\n\n' +
      t('Dùng khi Pi bị mất/đánh cắp hoặc nghi SD bị sao chép. Sẽ:') + '\n' +
      t('• yêu cầu HQ thu hồi chứng chỉ (Pi không gia hạn được, cert hết hạn là mất mạng)') + '\n' +
      (g.cameraCount > 0 ? t('• xóa {0} camera đã thêm qua nó').replace('{0}', String(g.cameraCount)) + '\n' : '') +
      t('• đánh dấu gateway là "Đã thu hồi" (không tự kết nối lại).') + '\n\n' + t('Không thể hoàn tác.')
    if (!window.confirm(msg)) return
    setBusyAction('revoke:' + g.id)
    try {
      const r = await fetch(`/api/gateways/${g.id}/revoke`, { method: 'POST' })
      const d = await r.json().catch(() => ({}))
      if (r.ok && d.success) {
        toast(t('Đã thu hồi gateway{0}').replace('{0}', d.camerasRemoved ? t(' + xóa {0} camera').replace('{0}', String(d.camerasRemoved)) : ''), 'success')
        if (openId === g.id) setOpenId(null)
        await fetchGateways()
        onCamerasChanged?.()
      } else toast(d.error || t('Thu hồi gateway thất bại'), 'error')
    } catch { toast(t('Lỗi mạng khi thu hồi gateway'), 'error') }
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
      if (!r.ok) { toast(d.error || t('Không tải được danh sách'), 'error'); return }
      const rows: Addable[] = (d.cameras || []).map((c: any) => ({
        localIp: c.localIp, name: c.name || c.localIp, username: 'admin', password: '', source: 'proxied' as const,
      }))
      mergeAddable(id, rows)
      if (rows.length === 0) toast(t('Chưa có camera nào được proxy trên gateway này'), 'info')
    } finally { setBusyAction('') }
  }

  const discover = async (id: string) => {
    setBusyAction('discover:' + id)
    try {
      const r = await fetch(`/api/gateways/${id}/discover`, { method: 'POST' })
      const d = await r.json().catch(() => ({}))
      if (!r.ok) { toast(d.error || t('Quét LAN thất bại'), 'error'); return }
      const rows: Addable[] = (d.devices || []).map((dev: any) => ({
        localIp: dev.ip, name: dev.name || dev.hardware || dev.ip, username: 'admin', password: '', source: 'discovered' as const,
      }))
      mergeAddable(id, rows)
      toast(t('Quét LAN: {0} thiết bị').replace('{0}', String(rows.length)), 'success')
    } finally { setBusyAction('') }
  }

  const updateRow = (id: string, ip: string, patch: Partial<Addable>) => {
    setAddable(prev => ({ ...prev, [id]: (prev[id] || []).map(r => r.localIp === ip ? { ...r, ...patch } : r) }))
  }

  const adopt = async (id: string, row: Addable) => {
    if (!row.password) { toast(t('Nhập mật khẩu ONVIF'), 'error'); return }
    updateRow(id, row.localIp, { busy: true })
    try {
      const r = await fetch(`/api/gateways/${id}/cameras`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ localIp: row.localIp, username: row.username, password: row.password, name: row.name }),
      })
      const d = await r.json().catch(() => ({}))
      if (r.ok && d.success) {
        toast(t('Đã thêm camera {0}{1}').replace('{0}', row.name).replace('{1}', d.frigateRestarted ? '' : t(' (AI View NVR chưa restart)')), 'success')
        // remove the row once adopted
        setAddable(prev => ({ ...prev, [id]: (prev[id] || []).filter(x => x.localIp !== row.localIp) }))
        await fetchGateways()
        await fetchAdopted(id)   // refresh the "camera đã thêm" list
        onCamerasChanged?.()   // tell App to re-fetch /api/cameras so it shows up immediately
      } else {
        toast(d.error || t('Thêm camera thất bại'), 'error')
        updateRow(id, row.localIp, { busy: false })
      }
    } catch {
      toast(t('Lỗi mạng khi thêm camera'), 'error')
      updateRow(id, row.localIp, { busy: false })
    }
  }

  // Load the cameras already adopted through a gateway (NVR DB), for the delete list.
  const fetchAdopted = useCallback(async (id: string) => {
    try {
      const r = await fetch(`/api/gateways/${id}/cameras`)
      if (r.ok) {
        const d = await r.json().catch(() => ({}))
        setAdopted(prev => ({ ...prev, [id]: d.cameras || [] }))
      }
    } catch { /* ignore */ }
  }, [])

  // Auto-load adopted cameras whenever a gateway panel is expanded.
  useEffect(() => {
    if (openId) fetchAdopted(openId)
  }, [openId, fetchAdopted])

  // Set a camera's PTZ classification (none | zoom | full) straight from the gateway panel,
  // so the admin can fix an auto-detect mis-classification without opening the Edit modal.
  const setCamPtz = async (gatewayId: string, cam: AdoptedCam, mode: 'none' | 'zoom' | 'full') => {
    try {
      const r = await fetch(`/api/cameras/${encodeURIComponent(cam.slug)}/ptz-enabled`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mode }),
      })
      if (r.ok) {
        setAdopted(prev => ({ ...prev, [gatewayId]: (prev[gatewayId] || []).map(x =>
          x.slug === cam.slug ? { ...x, ptzEnabled: mode !== 'none', ptzMode: mode } : x) }))
        toast(t('Đã cập nhật PTZ'), 'success')
        onCamerasChanged?.()
      } else toast(t('Cập nhật PTZ thất bại'), 'error')
    } catch { toast(t('Lỗi mạng khi cập nhật PTZ'), 'error') }
  }

  const deleteCamera = async (id: string, cam: AdoptedCam) => {
    if (!window.confirm(t('Xóa camera "{0}" khỏi gateway? Không thể hoàn tác.').replace('{0}', cam.name))) return
    setBusyAction('delcam:' + cam.slug)
    try {
      const r = await fetch(`/api/gateways/${id}/cameras/${encodeURIComponent(cam.slug)}`, { method: 'DELETE' })
      const d = await r.json().catch(() => ({}))
      if (r.ok && d.success) {
        toast(t('Đã xóa camera {0}').replace('{0}', cam.name), 'success')
        setAdopted(prev => ({ ...prev, [id]: (prev[id] || []).filter(x => x.slug !== cam.slug) }))
        await fetchGateways()
        onCamerasChanged?.()   // refresh the global camera list
      } else toast(d.error || t('Xóa camera thất bại'), 'error')
    } catch { toast(t('Lỗi mạng khi xóa camera'), 'error') }
    finally { setBusyAction('') }
  }

  const statusBadge = (g: Gateway) => {
    if (g.status === 'revoked')
      return <span className="px-2 py-0.5 rounded-full text-xs bg-red-100 text-red-700" title={t("Chứng chỉ đã bị thu hồi")}>{t("⊘ Đã thu hồi")}</span>
    if (g.status === 'discovered')
      return <span className="px-2 py-0.5 rounded-full text-xs bg-amber-100 text-amber-700">{t("Chờ duyệt")}</span>
    if (!g.online)
      return <span className="px-2 py-0.5 rounded-full text-xs bg-gray-200 text-gray-600">○ {t('Offline')}</span>
    if (g.health === 'degraded')
      return <span className="px-2 py-0.5 rounded-full text-xs bg-orange-100 text-orange-700" title={t('Cert còn {0} ngày').replace('{0}', String(g.certDaysLeft))}>{t("▲ Cert sắp hết")}</span>
    return <span className="px-2 py-0.5 rounded-full text-xs bg-green-100 text-green-700">● {t('Online')}</span>
  }

  return (
    <div className="p-4 md:p-6 max-w-4xl mx-auto">
      <div className="flex items-center justify-between mb-4">
        <div>
          <h1 className="text-xl font-bold text-gray-800">{t("Gateway biên")}</h1>
          <p className="text-sm text-gray-500">{t("Tự phát hiện trên mạng Nebula. Duyệt gateway rồi thêm camera (nhập tài khoản ONVIF tại đây).")}</p>
        </div>
        <button onClick={scanNow} disabled={scanning}
          className="flex items-center gap-1 bg-[#EF4444] text-white px-4 py-2 rounded-lg text-sm disabled:opacity-50">
          <Icon name="radar" className="text-base" /> {scanning ? t('Đang quét…') : t('Quét ngay')}
        </button>
      </div>

      {hq?.configured && hq.overlay === 'nebula' && (
        <div className={`mb-4 flex items-center gap-2 rounded-xl px-4 py-2 text-sm border ${
          hq.reachable ? 'bg-green-50 border-green-200 text-green-800' : 'bg-red-50 border-red-200 text-red-800'}`}>
          <Icon name={hq.reachable ? 'cloud_done' : 'cloud_off'} className="text-base" />
          <span className="font-medium">HQ {hq.reachable ? 'online' : t('KHÔNG phản hồi')}</span>
          {hq.reachable && hq.lighthouse && <span className="text-xs opacity-80">· lighthouse {hq.lighthouse}</span>}
          {hq.reachable && (hq.pending ?? 0) > 0 && <span className="text-xs opacity-80">· {t('{0} chờ cấp phép').replace('{0}', String(hq.pending))}</span>}
        </div>
      )}

      {pending.length > 0 && (
        <div className="mb-4 bg-amber-50 border border-amber-200 rounded-xl p-4">
          <div className="flex items-center gap-2 mb-1">
            <Icon name="verified_user" className="text-amber-600 text-base" />
            <h2 className="font-semibold text-amber-800 text-sm">{t('Thiết bị mới chờ cấp phép ({0})').replace('{0}', String(pending.length))}</h2>
          </div>
          <p className="text-xs text-amber-700/80 mb-3">{t("Gateway vừa cắm điện đã enroll nhưng chưa được cấp phép vào mạng Nebula. Cấp phép để NVR thấy và quản lý.")}</p>
          <div className="space-y-2">
            {pending.map(p => (
              <div key={p.id} className="flex items-center justify-between bg-white border border-amber-200 rounded-lg px-3 py-2">
                <div className="min-w-0">
                  <div className="font-mono text-sm text-gray-800 truncate">{p.label}</div>
                  <div className="text-xs text-gray-400">
                    {p.detail.length > 0 && p.detail.some(d => d) ? t('IP dự kiến: {0}').replace('{0}', p.detail.filter(d => d).join(', ')) : t('Chưa cấp IP')}
                  </div>
                </div>
                <button onClick={() => authorizeMember(p.id)} disabled={busyAction === 'authorize:' + p.id}
                  className="bg-amber-600 text-white px-3 py-1.5 rounded-lg text-sm shrink-0 disabled:opacity-50">
                  {busyAction === 'authorize:' + p.id ? t('Đang cấp phép…') : t('Cấp phép')}
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      {loading ? (
        <p className="text-gray-500">{t("Đang tải…")}</p>
      ) : gateways.length === 0 ? (
        <div className="bg-gray-50 border border-dashed border-gray-300 rounded-xl p-8 text-center text-gray-500">{t("Chưa phát hiện gateway nào. Đảm bảo Pi đã chạy agent + đã enroll vào mạng Nebula (đã “Cấp phép”), rồi bấm “Quét ngay”.")}</div>
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
                    {g.zerotierIp || t('(chưa có IP)')} :{g.agentPort}
                    {g.fieldSubnet ? ` · LAN ${g.fieldSubnet}` : ''} · {g.cameraCount} camera
                    {g.certDaysLeft != null && (
                      <span className={g.certDaysLeft <= 30 ? 'text-orange-600' : ''}> · cert {g.certDaysLeft}d</span>
                    )}
                  </div>
                  {(g.installer || g.location) && (
                    <div className="text-xs text-gray-400 mt-0.5 truncate">
                      {g.location ? `📍 ${g.location}` : ''}{g.location && g.installer ? ' · ' : ''}{g.installer ? `👤 ${g.installer}` : ''}
                    </div>
                  )}
                </div>
                <div className="flex items-center gap-2 shrink-0">
                  {g.status === 'revoked' ? null : g.status === 'discovered' ? (
                    <button onClick={() => approve(g.id)} disabled={busyAction === 'approve:' + g.id}
                      className="bg-blue-600 text-white px-3 py-1.5 rounded-lg text-sm disabled:opacity-50">{t("Phê duyệt")}</button>
                  ) : (
                    <button onClick={() => setOpenId(openId === g.id ? null : g.id)}
                      className="flex items-center gap-1 bg-gray-100 text-gray-700 px-3 py-1.5 rounded-lg text-sm">
                      <Icon name={openId === g.id ? 'expand_less' : 'add_a_photo'} className="text-base" />{t("Thêm camera")}</button>
                  )}
                  {g.status !== 'revoked' && (
                    <button onClick={() => revokeGateway(g)} disabled={busyAction === 'revoke:' + g.id}
                      title={t("Thu hồi gateway (Pi mất/bị đánh cắp)")} aria-label={t("Thu hồi gateway")}
                      className="flex items-center text-gray-400 hover:text-red-600 px-2 py-1.5 rounded-lg disabled:opacity-50">
                      <Icon name="gpp_bad" className="text-base" />
                    </button>
                  )}
                  <button onClick={() => deleteGateway(g)} disabled={busyAction === 'delete:' + g.id}
                    title={g.status === 'revoked' ? t('Xóa hẳn gateway đã thu hồi') : t('Xóa gateway')} aria-label={t("Xóa gateway")}
                    className="flex items-center text-gray-400 hover:text-red-600 px-2 py-1.5 rounded-lg disabled:opacity-50">
                    <Icon name="delete_outline" className="text-base" />
                  </button>
                </div>
              </div>

              {openId === g.id && g.status === 'approved' && (
                <div className="border-t border-gray-100 p-4 bg-gray-50/60">
                  {(adopted[g.id] || []).length > 0 && (
                    <div className="mb-4">
                      <h3 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">
                        {t('Camera đã thêm ({0})').replace('{0}', String((adopted[g.id] || []).length))}
                      </h3>
                      <div className="space-y-1.5">
                        {(adopted[g.id] || []).map(cam => (
                          <div key={cam.slug} className="flex items-center justify-between bg-white border border-gray-200 rounded-lg px-3 py-2">
                            <div className="min-w-0">
                              <div className="text-sm font-medium text-gray-800 truncate">{cam.name}</div>
                              <div className="text-xs text-gray-400 truncate">
                                {cam.localIp || t('(không rõ IP)')}{cam.channelId ? ` · kênh ${cam.channelId}` : ''}
                              </div>
                            </div>
                            <div className="flex items-center gap-2 shrink-0">
                              <select
                                value={!cam.ptzEnabled ? 'none' : (cam.ptzMode === 'zoom' ? 'zoom' : 'full')}
                                onChange={e => setCamPtz(g.id, cam, e.target.value as 'none' | 'zoom' | 'full')}
                                title={t("Phân loại PTZ của camera")}
                                className="text-xs rounded border border-gray-300 bg-white text-gray-800 px-1.5 py-1">
                                <option value="none">{t("Không PTZ")}</option>
                                <option value="zoom">{t("Chỉ Zoom")}</option>
                                <option value="full">{t("PTZ đầy đủ")}</option>
                              </select>
                              <button onClick={() => deleteCamera(g.id, cam)} disabled={busyAction === 'delcam:' + cam.slug}
                                title={t("Xóa camera")} aria-label={t("Xóa camera")}
                                className="flex items-center text-gray-400 hover:text-red-600 px-2 py-1.5 rounded-lg disabled:opacity-50">
                                <Icon name="delete_outline" className="text-base" />
                              </button>
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  <div className="flex gap-2 mb-3">
                    <button onClick={() => loadProxied(g.id)} disabled={busyAction === 'proxied:' + g.id}
                      className="text-sm bg-white text-gray-800 border border-gray-300 px-3 py-1.5 rounded-lg disabled:opacity-50">
                      {busyAction === 'proxied:' + g.id ? t('Đang tải…') : t('Camera đã sẵn sàng')}
                    </button>
                    <button onClick={() => discover(g.id)} disabled={busyAction === 'discover:' + g.id}
                      className="text-sm bg-white text-gray-800 border border-gray-300 px-3 py-1.5 rounded-lg disabled:opacity-50">
                      {busyAction === 'discover:' + g.id ? t('Đang quét LAN…') : t('Quét LAN hiện trường')}
                    </button>
                  </div>

                  {(addable[g.id] || []).length === 0 ? (
                    <p className="text-sm text-gray-400">{t("Bấm một trong hai nút trên để liệt kê camera.")}</p>
                  ) : (
                    <div className="space-y-2">
                      {(addable[g.id] || []).map(row => (
                        <div key={row.localIp} className="bg-white border border-gray-200 rounded-lg p-3">
                          <div className="flex items-center gap-2 mb-2">
                            <span className="font-medium text-gray-800">{row.localIp}</span>
                            <span className={`text-[10px] px-1.5 py-0.5 rounded-full ${row.source === 'proxied' ? 'bg-green-50 text-green-600' : 'bg-indigo-50 text-indigo-600'}`}>
                              {row.source === 'proxied' ? t('đã proxy') : t('tìm thấy')}
                            </span>
                          </div>
                          <div className="grid grid-cols-1 sm:grid-cols-4 gap-2 items-end">
                            <label className="block">
                              <span className="text-xs text-gray-500">{t("Tên camera")}</span>
                              <input className="mt-0.5 w-full border border-gray-300 rounded px-2 py-1.5 text-sm text-gray-900 bg-white placeholder-gray-400" placeholder={t("vd: Cổng trước")}
                                value={row.name} onChange={e => updateRow(g.id, row.localIp, { name: e.target.value })} />
                            </label>
                            <label className="block">
                              <span className="text-xs text-gray-500">{t("Tài khoản ONVIF")}</span>
                              <input className="mt-0.5 w-full border border-gray-300 rounded px-2 py-1.5 text-sm text-gray-900 bg-white placeholder-gray-400" placeholder={`${t('vd:')} admin`}
                                value={row.username} onChange={e => updateRow(g.id, row.localIp, { username: e.target.value })} />
                            </label>
                            <label className="block">
                              <span className="text-xs text-gray-500">{t("Mật khẩu ONVIF")}</span>
                              <input className="mt-0.5 w-full border border-gray-300 rounded px-2 py-1.5 text-sm text-gray-900 bg-white placeholder-gray-400" type="password" placeholder="••••••"
                                value={row.password} onChange={e => updateRow(g.id, row.localIp, { password: e.target.value })} />
                            </label>
                            <button onClick={() => adopt(g.id, row)} disabled={row.busy}
                              className="bg-[#EF4444] text-white rounded px-3 py-2 text-sm disabled:opacity-50">
                              {row.busy ? t('Đang thêm…') : t('Thêm')}
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

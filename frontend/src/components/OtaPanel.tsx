import { useState, useEffect, useCallback, useRef } from 'react'
import { t, locale } from '../i18n'
import { Icon } from './Icon'

type ToastType = 'success' | 'error' | 'info' | 'warning'

/** Nguyên văn status.json do watchdog + ota-apply.sh ghi. */
type OtaStatusFile = {
  state: string
  version: string
  progress: number
  message: string
  requestId: string
  trigger: string
  updatedAt: string
  startedAt: string
  errorCode: string
  log: string[]
}

type OtaStateFile = {
  installedVersion: string
  floorVersion: string
  lastCheck: string
  lastResult: string
  lastResultAt: string
  history: Array<{
    at: string; fromVersion: string; toVersion: string; outcome: string; detail: string
  }>
}

type OtaPackage = { filename: string; size: number; uploadedAt: string }

type OtaStatusResponse = {
  currentVersion: string
  deployVersion: string
  model: string
  maxUploadBytes: number
  freeBytes: number
  busy: boolean
  status: OtaStatusFile | null
  state: OtaStateFile | null
  packages: OtaPackage[]
}

function fmtBytes(n: number): string {
  if (!n || n < 0) return '—'
  if (n < 1024) return `${n} B`
  if (n < 1048576) return `${(n / 1024).toFixed(0)} KB`
  if (n < 1073741824) return `${(n / 1048576).toFixed(0)} MB`
  return `${(n / 1073741824).toFixed(2)} GB`
}

function fmtTime(iso: string): string {
  if (!iso) return '—'
  try { return new Date(iso).toLocaleString(locale()) } catch { return iso }
}

/**
 * Nhóm trạng thái theo màu. Ba nhóm, không phải mỗi state một màu:
 * đang chạy (xanh dương), xong tốt (xanh lá), có vấn đề (đỏ/vàng).
 */
function stateStyle(state: string): { color: string; bg: string; icon: string; label: string } {
  switch (state) {
    case 'COMMITTED':
      return { color: 'text-green-600 dark:text-green-400', bg: 'bg-green-50 dark:bg-green-900/20', icon: 'check_circle', label: t('Cập nhật thành công') }
    case 'ROLLED_BACK':
      return { color: 'text-amber-600 dark:text-amber-400', bg: 'bg-amber-50 dark:bg-amber-900/20', icon: 'undo', label: t('Đã lùi về bản cũ') }
    case 'ROLLBACK_FAILED':
      return { color: 'text-red-600 dark:text-red-400', bg: 'bg-red-50 dark:bg-red-900/20', icon: 'error', label: t('Lùi thất bại — cần can thiệp') }
    case 'REJECTED':
      return { color: 'text-amber-600 dark:text-amber-400', bg: 'bg-amber-50 dark:bg-amber-900/20', icon: 'block', label: t('Gói bị từ chối') }
    case 'IDLE':
      return { color: 'text-gray-500 dark:text-gray-400', bg: 'bg-gray-50 dark:bg-gray-800', icon: 'schedule', label: t('Không có việc đang chạy') }
    default:
      return { color: 'text-blue-600 dark:text-blue-400', bg: 'bg-blue-50 dark:bg-blue-900/20', icon: 'sync', label: t('Đang xử lý') }
  }
}

/**
 * Diễn giải mã lỗi thành câu người vận hành hiểu được VÀ biết phải làm gì.
 *
 * Mã lỗi trong status.json là để grep log; người đứng trước màn hình cần biết bước tiếp theo.
 * Đây là chỗ duy nhất dịch mã → hành động, nên khi thêm gate mới thì thêm vào đây.
 */
function explainError(code: string): string | null {
  const map: Record<string, string> = {
    SIG_INVALID: t('Chữ ký của gói không khớp khoá tin cậy trên máy. Gói có thể đã bị sửa, hoặc được ký bằng khoá khác — kiểm lại đúng file chưa.'),
    NO_TRUSTED_KEY: t('Máy chưa có khoá công khai nào để đối chiếu. Chạy: sudo ./scripts/install-ota.sh'),
    MODEL_MISMATCH: t('Gói dành cho model thiết bị khác. Tải đúng gói cho model của máy này.'),
    BELOW_FLOOR: t('Gói cũ hơn bản đã từng chạy thành công. Máy từ chối hạ cấp vì bản cũ có thể chứa lỗ hổng đã được vá.'),
    MINFROM_NOT_MET: t('Máy đang ở bản quá cũ để nhảy thẳng lên bản này. Phải cài bản trung gian trước (xem chi tiết trong nhật ký).'),
    MANIFEST_EXPIRED: t('Thông tin bản cập nhật đã hết hạn. Có thể đường mạng đang bị chặn, hoặc bản phát hành quá cũ.'),
    DISK_INSUFFICIENT: t('Không đủ dung lượng trống. Xoá bớt gói cũ hoặc dữ liệu ghi hình rồi thử lại.'),
    HEALTH_FAIL: t('Bản mới không lên được nên máy đã tự lùi về bản cũ. Máy vẫn đang hoạt động bình thường.'),
    ROLLBACK_FAILED: t('Cập nhật thất bại VÀ không lùi được. Máy có thể đang không ghi hình — cần vào máy xử lý ngay.'),
    PRECONDITION_UNHEALTHY: t('Máy đang không khoẻ nên chưa cập nhật được. Sửa lỗi hiện tại trước.'),
    OVERLAY_PATH_DENIED: t('Gói muốn ghi vào file không được phép. Gói này không đáng tin — đừng cài.'),
    IMAGE_SHA_MISMATCH: t('Nội dung gói không khớp thông tin đã ký. File có thể tải bị lỗi — tải lại.'),
    PKG_NOT_ARCHIVE: t('File không phải gói nén hợp lệ. Kiểm lại đúng file chưa.'),
    MANIFEST_MISMATCH: t('Gói và thông tin bản cập nhật không thuộc cùng một bản phát hành.'),
  }
  return map[code] || null
}

export default function OtaPanel({ addToast }: {
  addToast?: (toast: { type: ToastType; title: string; message: string }) => void
}) {
  const toast = (message: string, type: ToastType = 'info', title?: string) => {
    if (addToast) addToast({ type, title: title || (type === 'error' ? t('Lỗi') : t('Cập nhật')), message })
  }

  const [data, setData] = useState<OtaStatusResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [uploading, setUploading] = useState(false)
  const [uploadPct, setUploadPct] = useState(0)
  const [uploadName, setUploadName] = useState('')
  const [dragOver, setDragOver] = useState(false)
  const [showLog, setShowLog] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)
  const xhrRef = useRef<XMLHttpRequest | null>(null)

  const load = useCallback(async () => {
    try {
      const r = await fetch('/api/ota/status')
      if (!r.ok) throw new Error(`HTTP ${r.status}`)
      setData(await r.json())
    } catch (e) {
      console.error('ota status', e)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  // Poll nhanh khi đang chạy dở (người ta đang xem), chậm khi rảnh.
  // Container nvr-wrapper bị recreate GIỮA lúc cập nhật nên fetch sẽ lỗi vài nhịp — đó là
  // bình thường và không được coi là thất bại; cứ poll tiếp tới khi nó lên lại.
  useEffect(() => {
    const busy = data?.busy || uploading
    const ms = busy ? 2000 : 15000
    const id = setInterval(load, ms)
    return () => clearInterval(id)
  }, [data?.busy, uploading, load])

  const upload = (file: File) => {
    if (!data) return
    if (file.size > data.maxUploadBytes) {
      toast(t('Gói {0} vượt trần {1}').replace('{0}', fmtBytes(file.size)).replace('{1}', fmtBytes(data.maxUploadBytes)), 'error')
      return
    }
    setUploading(true); setUploadPct(0); setUploadName(file.name)

    // XHR chứ không fetch: fetch() không báo tiến trình upload được, mà gói 233MB qua LAN
    // mất cả phút — không có thanh tiến trình thì người dùng tưởng treo và bấm lại.
    const xhr = new XMLHttpRequest()
    xhrRef.current = xhr
    xhr.open('POST', `/api/ota/upload?filename=${encodeURIComponent(file.name)}`)
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) setUploadPct(Math.round((e.loaded / e.total) * 100))
    }
    xhr.onload = () => {
      setUploading(false); xhrRef.current = null
      if (xhr.status >= 200 && xhr.status < 300) {
        toast(t('Đã tải lên {0}').replace('{0}', file.name), 'success')
        load()
      } else {
        let msg = `HTTP ${xhr.status}`
        try { const j = JSON.parse(xhr.responseText); msg = j.error + (j.detail ? ` — ${j.detail}` : '') } catch { /* giữ msg mặc định */ }
        toast(msg, 'error')
      }
    }
    xhr.onerror = () => {
      setUploading(false); xhrRef.current = null
      toast(t('Mất kết nối khi đang tải lên'), 'error')
    }
    xhr.onabort = () => { setUploading(false); xhrRef.current = null }
    xhr.send(file)
  }

  const apply = async (filename: string) => {
    if (!confirm(t('Áp dụng gói {0}?\n\nMáy sẽ khởi động lại các thành phần và tự lùi về bản cũ nếu có lỗi. Live view có thể gián đoạn khoảng 1-2 phút.').replace('{0}', filename))) return
    try {
      const r = await fetch(`/api/ota/apply?filename=${encodeURIComponent(filename)}`, { method: 'POST' })
      const j = await r.json()
      if (!r.ok) throw new Error(j.error + (j.detail ? ` — ${j.detail}` : ''))
      toast(j.message, 'success')
      load()
    } catch (e) {
      toast((e as Error).message, 'error')
    }
  }

  const checkNow = async () => {
    try {
      const r = await fetch('/api/ota/check-now', { method: 'POST' })
      const j = await r.json()
      if (!r.ok) throw new Error(j.error)
      toast(j.message, 'info')
      load()
    } catch (e) {
      toast((e as Error).message, 'error')
    }
  }

  const removePkg = async (filename: string) => {
    if (!confirm(t('Xoá gói {0}?').replace('{0}', filename))) return
    try {
      const r = await fetch(`/api/ota/package?filename=${encodeURIComponent(filename)}`, { method: 'DELETE' })
      const j = await r.json()
      if (!r.ok) throw new Error(j.error)
      toast(j.message, 'success')
      load()
    } catch (e) {
      toast((e as Error).message, 'error')
    }
  }

  if (loading) {
    return <div className="text-gray-500 dark:text-gray-400 flex items-center">
      <Icon name="sync" className="animate-spin mr-2" />{t('Đang tải…')}
    </div>
  }
  if (!data) {
    return <div className="text-red-600 dark:text-red-400">{t('Không đọc được trạng thái cập nhật')}</div>
  }

  const st = data.status
  const style = stateStyle(st?.state || 'IDLE')
  const running = data.busy
  const errExplain = st?.errorCode ? explainError(st.errorCode) : null

  return (
    <div className="max-w-4xl">
      <h1 className="text-2xl font-bold text-gray-800 dark:text-white flex items-center mb-6">
        <Icon name="system_update" className="text-[#EF4444] mr-3 text-3xl" />{t('Cập nhật hệ thống')}
      </h1>

      {/* ── Phiên bản ─────────────────────────────────────────────── */}
      <div className="bg-white dark:bg-[#1F2937] rounded-lg shadow p-6 mb-6">
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div>
            <div className="text-xs text-gray-500 dark:text-gray-400 uppercase tracking-wide">{t('Phiên bản')}</div>
            <div className="text-xl font-bold text-gray-800 dark:text-white">{data.currentVersion}</div>
          </div>
          <div>
            <div className="text-xs text-gray-500 dark:text-gray-400 uppercase tracking-wide">{t('Model')}</div>
            <div className="text-sm font-medium text-gray-700 dark:text-gray-200 mt-1">
              {data.model || <span className="text-amber-600 dark:text-amber-400">{t('chưa đặt')}</span>}
            </div>
          </div>
          <div>
            <div className="text-xs text-gray-500 dark:text-gray-400 uppercase tracking-wide">{t('Dung lượng trống')}</div>
            <div className="text-sm font-medium text-gray-700 dark:text-gray-200 mt-1">{fmtBytes(data.freeBytes)}</div>
          </div>
          <div>
            <div className="text-xs text-gray-500 dark:text-gray-400 uppercase tracking-wide">{t('Kiểm lần cuối')}</div>
            <div className="text-sm font-medium text-gray-700 dark:text-gray-200 mt-1">
              {fmtTime(data.state?.lastCheck || '')}
            </div>
          </div>
        </div>

        {/* Sàn chống hạ cấp: chỉ hiện khi nó CAO HƠN bản đang chạy — lúc đó nó mới có ý nghĩa
            với người xem (đã từng chạy bản cao hơn rồi lùi về), còn bằng nhau thì là chuyện
            bình thường không cần chiếm chỗ trên màn hình. */}
        {data.state?.floorVersion && data.state.floorVersion !== data.currentVersion && (
          <div className="mt-4 pt-4 border-t border-gray-200 dark:border-gray-700 flex items-start text-sm">
            <Icon name="shield" className="text-gray-400 mr-2 text-lg" />
            <span className="text-gray-600 dark:text-gray-300">
              {t('Sàn chống hạ cấp')}: <b>{data.state.floorVersion}</b>
              <span className="text-gray-500 dark:text-gray-400"> — {t('máy sẽ từ chối mọi gói cũ hơn mức này')}</span>
            </span>
          </div>
        )}

        <div className="mt-4 flex gap-3">
          <button onClick={checkNow} disabled={running}
            className="flex items-center px-4 py-2 border border-gray-300 dark:border-gray-600 rounded text-sm text-gray-700 dark:text-gray-200 hover:bg-gray-50 dark:hover:bg-gray-700 disabled:opacity-50">
            <Icon name="cloud_download" className="mr-2 text-lg" />{t('Kiểm tra bản mới')}
          </button>
        </div>
      </div>

      {/* ── Trạng thái ────────────────────────────────────────────── */}
      {st && st.state !== 'IDLE' && (
        <div className={`rounded-lg shadow p-6 mb-6 ${style.bg}`}>
          <div className="flex items-start">
            <Icon name={style.icon} className={`${style.color} mr-3 text-2xl ${running ? 'animate-spin' : ''}`} />
            <div className="flex-1 min-w-0">
              <div className={`font-bold ${style.color}`}>
                {style.label}{st.version && ` — ${st.version}`}
              </div>
              <div className="text-sm text-gray-700 dark:text-gray-200 mt-1 break-words">{st.message}</div>

              {errExplain && (
                <div className="mt-3 text-sm bg-white/60 dark:bg-black/20 rounded p-3 text-gray-700 dark:text-gray-200">
                  {errExplain}
                  <div className="text-xs text-gray-500 dark:text-gray-400 mt-1 font-mono">{st.errorCode}</div>
                </div>
              )}

              {running && (
                <div className="mt-3">
                  <div className="w-full bg-white/60 dark:bg-black/20 rounded-full h-2">
                    <div className="bg-blue-500 h-2 rounded-full transition-all duration-500"
                      style={{ width: `${st.progress}%` }} />
                  </div>
                  <div className="text-xs text-gray-500 dark:text-gray-400 mt-1">
                    {st.state} · {st.progress}%
                  </div>
                </div>
              )}

              {st.log?.length > 0 && (
                <button onClick={() => setShowLog(!showLog)}
                  className="mt-3 text-xs text-gray-600 dark:text-gray-300 underline">
                  {showLog ? t('Ẩn nhật ký') : t('Xem nhật ký chi tiết ({0} dòng)').replace('{0}', String(st.log.length))}
                </button>
              )}
              {showLog && (
                <pre className="mt-2 text-xs bg-gray-900 text-gray-200 rounded p-3 overflow-x-auto max-h-64 overflow-y-auto">
                  {st.log.join('\n')}
                </pre>
              )}
            </div>
          </div>
        </div>
      )}

      {/* ── Tải gói lên ───────────────────────────────────────────── */}
      <div className="bg-white dark:bg-[#1F2937] rounded-lg shadow p-6 mb-6">
        <h2 className="font-bold text-gray-800 dark:text-white mb-1">{t('Tải gói cập nhật lên')}</h2>
        <p className="text-sm text-gray-500 dark:text-gray-400 mb-4">
          {t('Dùng khi cập nhật tại hiện trường, không cần internet. Máy sẽ kiểm chữ ký của gói trước khi áp dụng.')}
        </p>

        {uploading ? (
          <div className="border-2 border-blue-300 dark:border-blue-700 rounded-lg p-6 bg-blue-50 dark:bg-blue-900/20">
            <div className="flex items-center justify-between mb-2">
              <span className="text-sm font-medium text-gray-700 dark:text-gray-200 truncate mr-4">{uploadName}</span>
              <span className="text-sm text-gray-600 dark:text-gray-300 shrink-0">{uploadPct}%</span>
            </div>
            <div className="w-full bg-white dark:bg-gray-800 rounded-full h-2">
              <div className="bg-blue-500 h-2 rounded-full transition-all" style={{ width: `${uploadPct}%` }} />
            </div>
            <button onClick={() => xhrRef.current?.abort()}
              className="mt-3 text-xs text-red-600 dark:text-red-400 underline">{t('Huỷ')}</button>
          </div>
        ) : (
          <div
            onDragOver={(e) => { e.preventDefault(); setDragOver(true) }}
            onDragLeave={() => setDragOver(false)}
            onDrop={(e) => {
              e.preventDefault(); setDragOver(false)
              const f = e.dataTransfer.files?.[0]
              if (f) upload(f)
            }}
            onClick={() => fileInput.current?.click()}
            className={`border-2 border-dashed rounded-lg p-8 text-center cursor-pointer transition-colors ${
              dragOver
                ? 'border-[#EF4444] bg-red-50 dark:bg-red-900/20'
                : 'border-gray-300 dark:border-gray-600 hover:border-gray-400 dark:hover:border-gray-500'
            } ${running ? 'opacity-50 pointer-events-none' : ''}`}
          >
            <Icon name="upload_file" className="text-4xl text-gray-400 mb-2" />
            <div className="text-sm text-gray-600 dark:text-gray-300">
              {t('Kéo file gói vào đây, hoặc bấm để chọn')}
            </div>
            <div className="text-xs text-gray-400 dark:text-gray-500 mt-1">
              .nvrup · .tar.gz · .zip — {t('tối đa')} {fmtBytes(data.maxUploadBytes)}
            </div>
          </div>
        )}
        <input ref={fileInput} type="file" accept=".nvrup,.tar.gz,.gz,.zip" className="hidden"
          onChange={(e) => { const f = e.target.files?.[0]; if (f) upload(f); e.target.value = '' }} />
      </div>

      {/* ── Gói đã tải lên ────────────────────────────────────────── */}
      {data.packages.length > 0 && (
        <div className="bg-white dark:bg-[#1F2937] rounded-lg shadow p-6 mb-6">
          <h2 className="font-bold text-gray-800 dark:text-white mb-4">{t('Gói đã có trên máy')}</h2>
          <div className="space-y-2">
            {data.packages.map((p) => (
              <div key={p.filename}
                className="flex items-center justify-between p-3 border border-gray-200 dark:border-gray-700 rounded">
                <div className="min-w-0 flex-1 mr-4">
                  <div className="text-sm font-medium text-gray-800 dark:text-gray-100 truncate">{p.filename}</div>
                  <div className="text-xs text-gray-500 dark:text-gray-400">
                    {fmtBytes(p.size)} · {fmtTime(p.uploadedAt)}
                  </div>
                </div>
                <div className="flex gap-2 shrink-0">
                  <button onClick={() => apply(p.filename)} disabled={running}
                    className="px-3 py-1.5 bg-[#EF4444] text-white text-sm rounded hover:bg-red-600 disabled:opacity-50">
                    {t('Áp dụng')}
                  </button>
                  <button onClick={() => removePkg(p.filename)} disabled={running}
                    className="px-3 py-1.5 border border-gray-300 dark:border-gray-600 text-sm rounded text-gray-600 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-700 disabled:opacity-50">
                    {t('Xoá')}
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* ── Lịch sử ───────────────────────────────────────────────── */}
      {data.state?.history && data.state.history.length > 0 && (
        <div className="bg-white dark:bg-[#1F2937] rounded-lg shadow p-6">
          <h2 className="font-bold text-gray-800 dark:text-white mb-4">{t('Lịch sử cập nhật')}</h2>
          <div className="space-y-2">
            {[...data.state.history].reverse().map((h, i) => {
              const s = stateStyle(h.outcome)
              return (
                <div key={i} className="flex items-start text-sm py-2 border-b border-gray-100 dark:border-gray-700 last:border-0">
                  <Icon name={s.icon} className={`${s.color} mr-2 text-lg shrink-0`} />
                  <div className="flex-1 min-w-0">
                    <div className="text-gray-800 dark:text-gray-100">
                      {h.fromVersion || '?'} → <b>{h.toVersion}</b>
                      <span className={`ml-2 text-xs ${s.color}`}>{s.label}</span>
                    </div>
                    {h.detail && <div className="text-xs text-gray-500 dark:text-gray-400 break-words">{h.detail}</div>}
                  </div>
                  <div className="text-xs text-gray-400 dark:text-gray-500 shrink-0 ml-2">{fmtTime(h.at)}</div>
                </div>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}

import { useState, useEffect, useCallback } from 'react';

const Icon = ({ name, className = "" }: { name: string; className?: string }) => (
    <span className={`material-icons ${className}`}>{name}</span>
);

interface GlobalDetectToggleProps {
    addToast?: (toast: { type: 'success' | 'error' | 'info'; title: string; message: string }) => void;
}

/**
 * Global switch: turn Frigate's own on-device object detection ("AI nội bộ") off across every
 * camera at once, to save CPU/GPU when an external Box AI already handles detection. Separate
 * from the per-camera zone editor in AIConfigView — this writes the top-level `detect.enabled`
 * in config.yml (plus forces every camera's own override) and restarts Frigate.
 */
const GlobalDetectToggle = ({ addToast }: GlobalDetectToggleProps) => {
    const [enabled, setEnabled] = useState<boolean | null>(null);
    const [loading, setLoading] = useState(true);
    const [applying, setApplying] = useState(false);
    const [applyStatus, setApplyStatus] = useState<string | null>(null);
    const [confirmTarget, setConfirmTarget] = useState<boolean | null>(null);

    const fetchStatus = useCallback(async () => {
        try {
            const r = await fetch('/api/settings/detect');
            if (r.ok) {
                const data = await r.json();
                setEnabled(!!data.enabled);
            }
        } catch (e) {
            console.error(e);
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => { fetchStatus(); }, [fetchStatus]);

    const doApply = async (target: boolean) => {
        setConfirmTarget(null);
        setApplying(true);
        setApplyStatus('Đang lưu cấu hình...');
        try {
            const saveRes = await fetch('/api/settings/detect', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled: target }),
            });
            if (!saveRes.ok) {
                const err = await saveRes.json().catch(() => ({}));
                addToast?.({ type: 'error', title: 'Lỗi lưu cấu hình', message: err.error || 'Không thể ghi config.yml.' });
                setApplying(false);
                setApplyStatus(null);
                return;
            }

            setApplyStatus('Đang gửi lệnh khởi động lại Frigate...');
            const applyRes = await fetch('/api/settings/detect/apply', { method: 'POST' });
            if (!applyRes.ok) {
                addToast?.({ type: 'error', title: 'Lỗi', message: 'Không gửi được lệnh khởi động lại Frigate.' });
                setApplying(false);
                setApplyStatus(null);
                return;
            }

            setApplyStatus('Đang khởi động lại Frigate (khoảng 1 phút)...');
            const startTime = Date.now();
            let healthy = false;
            while (Date.now() - startTime < 180_000) {
                await new Promise(r => setTimeout(r, 4000));
                try {
                    const check = await fetch('/api/frigate/api/version', { signal: AbortSignal.timeout(4000) });
                    if (check.ok) { healthy = true; break; }
                } catch { /* still restarting */ }
            }

            setApplying(false);
            setApplyStatus(null);

            if (healthy) {
                setEnabled(target);
                addToast?.({
                    type: 'success',
                    title: target ? 'Đã bật AI nội bộ (Frigate)' : 'Đã tắt AI nội bộ (Frigate)',
                    message: target
                        ? 'Frigate đã khởi động lại và tiếp tục tự phát hiện đối tượng trên mọi camera.'
                        : 'Frigate đã khởi động lại và ngừng tự phát hiện đối tượng trên mọi camera.',
                });
                fetchStatus();
            } else {
                addToast?.({
                    type: 'error',
                    title: 'Timeout khởi động lại',
                    message: 'Frigate chưa phản hồi sau 3 phút. Kiểm tra lại trạng thái container nvr-wrapper/frigate thủ công.',
                });
            }
        } catch (e) {
            console.error('Global detect toggle error:', e);
            setApplying(false);
            setApplyStatus(null);
            addToast?.({ type: 'error', title: 'Lỗi kết nối', message: 'Không kết nối được tới server.' });
        }
    };

    const requestToggle = () => {
        if (enabled === null || applying) return;
        setConfirmTarget(!enabled);
    };

    return (
        <div className="mx-4 mt-3 p-3 rounded-lg border border-gray-200 dark:border-gray-700 bg-white dark:bg-[#1F2937] flex flex-wrap items-center gap-3">
            <Icon name="memory" className="text-gray-500" />
            <div className="mr-auto min-w-[220px]">
                <div className="font-semibold text-sm">AI nội bộ (Frigate)</div>
                <div className="text-xs text-gray-500">
                    {loading
                        ? 'Đang tải trạng thái...'
                        : applying
                            ? applyStatus
                            : enabled
                                ? 'Đang BẬT — NVR tự phát hiện người/xe bằng Frigate trên tất cả camera.'
                                : 'Đang TẮT — chỉ dùng khi đã có Box AI ngoài xử lý nhận diện thay thế.'}
                </div>
            </div>
            <button
                onClick={requestToggle}
                disabled={loading || applying || enabled === null}
                className={`relative inline-flex h-6 w-11 shrink-0 items-center rounded-full transition-colors disabled:opacity-50 ${
                    enabled ? 'bg-[#EF4444]' : 'bg-gray-300 dark:bg-gray-600'
                }`}
                title={enabled ? 'Tắt AI nội bộ (Frigate)' : 'Bật AI nội bộ (Frigate)'}
            >
                <span className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${enabled ? 'translate-x-6' : 'translate-x-1'}`} />
            </button>
            {applying && <Icon name="autorenew" className="animate-spin text-gray-400" />}

            {confirmTarget !== null && (
                <div className="fixed inset-0 z-50 bg-black/60 flex items-center justify-center p-4" onClick={() => setConfirmTarget(null)}>
                    <div className="bg-white dark:bg-[#1F2937] rounded-lg max-w-md w-full p-5" onClick={e => e.stopPropagation()}>
                        <div className="flex items-center gap-2 mb-3">
                            <Icon name="warning" className="text-amber-500 text-2xl" />
                            <h3 className="font-bold text-lg">
                                {confirmTarget ? 'Bật AI nội bộ (Frigate)?' : 'Tắt AI nội bộ (Frigate)?'}
                            </h3>
                        </div>
                        {confirmTarget ? (
                            <p className="text-sm text-gray-600 dark:text-gray-300">
                                NVR sẽ bật lại Frigate object detection trên <b>tất cả camera</b>, tốn thêm CPU/GPU.
                                Frigate sẽ khởi động lại, mất khoảng <b>1 phút</b> — live view/ghi hình có thể gián đoạn tạm thời trong lúc đó.
                            </p>
                        ) : (
                            <p className="text-sm text-gray-600 dark:text-gray-300">
                                NVR sẽ <b>tắt hoàn toàn</b> khả năng tự phát hiện người/xe của Frigate (AI nội bộ) trên <b>tất cả camera</b> để tiết kiệm CPU/GPU.
                                Camera sẽ <b>không</b> còn tự sinh sự kiện/ghi theo chuyển động dựa trên Frigate nữa.
                                <br /><br />
                                <b>Chỉ tắt nếu bạn đã kết nối Box AI ngoài</b> để xử lý nhận diện thay thế — nếu không, camera sẽ mất khả năng phát hiện AI.
                                <br /><br />
                                Frigate sẽ khởi động lại, mất khoảng <b>1 phút</b> — live view/ghi hình có thể gián đoạn tạm thời trong lúc đó.
                            </p>
                        )}
                        <div className="flex justify-end gap-2 mt-5">
                            <button
                                onClick={() => setConfirmTarget(null)}
                                className="px-4 py-2 text-sm rounded-lg border border-gray-300 dark:border-gray-600 hover:bg-gray-100 dark:hover:bg-gray-700"
                            >
                                Hủy
                            </button>
                            <button
                                onClick={() => doApply(confirmTarget)}
                                className={`px-4 py-2 text-sm rounded-lg text-white font-medium ${
                                    confirmTarget ? 'bg-[#EF4444] hover:bg-red-600' : 'bg-amber-600 hover:bg-amber-700'
                                }`}
                            >
                                {confirmTarget ? 'Bật AI nội bộ' : 'Xác nhận tắt'}
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default GlobalDetectToggle;

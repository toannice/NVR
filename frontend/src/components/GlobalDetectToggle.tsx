import { useState, useEffect, useCallback } from 'react';
import { useModalKeys } from '../hooks/useModalKeys';
import { t } from '../i18n';

const Icon = ({ name, className = "" }: { name: string; className?: string }) => (
    <span className={`material-icons ${className}`}>{name}</span>
);

interface GlobalDetectToggleProps {
    addToast?: (toast: { type: 'success' | 'error' | 'info'; title: string; message: string }) => void;
    /** Tăng lên khi nơi khác (overlay trong AIConfigView) muốn mở hộp thoại BẬT AI tổng. */
    enableRequest?: number;
    /** Gọi sau khi công tắc đổi trạng thái xong, để view khác nạp lại. */
    onChanged?: () => void;
}

/**
 * Global switch: turn the NVR's own on-device object detection ("AI View NVR") off across every
 * camera at once, to save CPU/GPU when Bkav AI View already handles detection. Separate
 * from the per-camera zone editor in AIConfigView — this writes the top-level `detect.enabled`
 * in config.yml (plus forces every camera's own override) and restarts the detection engine.
 * Lưu ý: tên hiển thị cho người dùng luôn là "AI View NVR" — không lộ tên engine (Frigate).
 */
const GlobalDetectToggle = ({ addToast, enableRequest, onChanged }: GlobalDetectToggleProps) => {
    const [enabled, setEnabled] = useState<boolean | null>(null);
    const [loading, setLoading] = useState(true);
    const [applying, setApplying] = useState(false);
    const [applyStatus, setApplyStatus] = useState<string | null>(null);
    const [confirmTarget, setConfirmTarget] = useState<boolean | null>(null);
    // Danh sách camera chưa cấu hình AI, chờ người dùng chọn có cấp vùng Toàn cảnh hay không.
    const [zonePrompt, setZonePrompt] = useState<string[] | null>(null);
    const [zonePromptSelected, setZonePromptSelected] = useState<Set<string>>(new Set());

    // Esc = huỷ, Enter = xác nhận bật/tắt AI View NVR.
    useModalKeys({
        onClose: () => setConfirmTarget(null),
        onSubmit: () => { if (confirmTarget !== null) doApply(confirmTarget); },
        enabled: confirmTarget !== null,
    });

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

    // Overlay "AI View NVR đang tắt" bấm nút Bật → mở đúng hộp thoại xác nhận này, để người dùng
    // vẫn thấy đầy đủ cảnh báo về việc khởi động lại thay vì bật lén phía sau lưng.
    useEffect(() => {
        if (enableRequest) setConfirmTarget(true);
    }, [enableRequest]);

    /**
     * Lưu trạng thái công tắc rồi restart MỘT lần.
     *
     * Khi bật, backend trả về danh sách camera chưa ai cấu hình AI (`ai_intent = unset`). Ta hỏi
     * người dùng có cấp vùng "Toàn cảnh" cho chúng không TRƯỚC khi restart, để cả việc cấp vùng
     * lẫn việc bật công tắc gói vào cùng một lần khởi động lại thay vì hai.
     *
     * Cố tình KHÔNG tự cấp vùng cho mọi camera: camera mà người dùng đã chủ động xoá hết vùng để
     * tắt riêng sẽ bị bật lại trái ý họ. Backend đã lọc sẵn bằng ai_intent, phần còn lại là hỏi.
     */
    const doApply = async (target: boolean, grantZonesTo?: string[]) => {
        setConfirmTarget(null);
        setApplying(true);
        setApplyStatus(t('Đang lưu cấu hình...'));
        try {
            const saveRes = await fetch('/api/settings/detect', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled: target }),
            });
            if (!saveRes.ok) {
                const err = await saveRes.json().catch(() => ({}));
                addToast?.({ type: 'error', title: t('Lỗi lưu cấu hình'), message: err.error || t('Không thể ghi config.yml.') });
                setApplying(false);
                setApplyStatus(null);
                return;
            }
            const saveData = await saveRes.json().catch(() => ({} as any));

            // Bật lên và có camera chưa cấu hình → dừng lại hỏi, chưa restart vội.
            const needZone: string[] = saveData.camerasNeedingDefaultZone || [];
            if (target && grantZonesTo === undefined && needZone.length > 0) {
                setApplying(false);
                setApplyStatus(null);
                setZonePrompt(needZone);
                setZonePromptSelected(new Set(needZone));
                return;
            }

            if (grantZonesTo && grantZonesTo.length > 0) {
                setApplyStatus(t('Đang cấp vùng Toàn cảnh...'));
                await fetch('/api/settings/detect/default-zones', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ cameras: grantZonesTo }),
                });
            }

            setApplyStatus(t('Đang khởi động lại AI View NVR (khoảng 1 phút)...'));
            const applyRes = await fetch('/api/settings/detect/apply', { method: 'POST' });
            const result = await applyRes.json().catch(() => ({} as any));

            setApplying(false);
            setApplyStatus(null);

            if (result.success) {
                setEnabled(target);
                addToast?.({
                    type: 'success',
                    title: target ? t('Đã bật AI View NVR') : t('Đã tắt AI View NVR'),
                    message: target
                        ? t('AI View NVR đã khởi động lại và tiếp tục tự phát hiện đối tượng.')
                        : t('AI View NVR đã ngừng tự phát hiện đối tượng. Cấu hình vùng vẫn được giữ nguyên.'),
                });
            } else if (result.rolledBack) {
                // Backend đã tự trả về config cũ. Phải nói thẳng là thay đổi KHÔNG có hiệu lực,
                // đừng để người dùng tưởng đã xong.
                addToast?.({ type: 'error', title: t('Đã khôi phục cấu hình cũ'), message: result.message });
            } else {
                addToast?.({
                    type: 'error',
                    title: t('Không áp dụng được'),
                    message: result.message || t('AI View NVR chưa phản hồi. Liên hệ kỹ thuật để kiểm tra dịch vụ.'),
                });
            }
            fetchStatus();
            onChanged?.();
        } catch (e) {
            console.error('Global detect toggle error:', e);
            setApplying(false);
            setApplyStatus(null);
            addToast?.({ type: 'error', title: t('Lỗi kết nối'), message: t('Không kết nối được tới server.') });
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
                <div className="font-semibold text-sm">AI View NVR</div>
                <div className="text-xs text-gray-500">
                    {loading
                        ? t('Đang tải trạng thái...')
                        : applying
                            ? applyStatus
                            : enabled
                                ? t('Đang BẬT — NVR tự phát hiện người/xe trên tất cả camera.')
                                : t('Đang TẮT — chỉ dùng khi đã có Bkav AI View xử lý nhận diện thay thế.')}
                </div>
            </div>
            <button
                onClick={requestToggle}
                disabled={loading || applying || enabled === null}
                className={`relative inline-flex h-6 w-11 shrink-0 items-center rounded-full transition-colors disabled:opacity-50 ${
                    enabled ? 'bg-[#EF4444]' : 'bg-gray-300 dark:bg-gray-600'
                }`}
                title={enabled ? t('Tắt AI View NVR') : t('Bật AI View NVR')}
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
                                {confirmTarget ? 'Bật AI View NVR?' : 'Tắt AI View NVR?'}
                            </h3>
                        </div>
                        {confirmTarget ? (
                            <p className="text-sm text-gray-600 dark:text-gray-300">{t("NVR sẽ bật lại khả năng tự phát hiện đối tượng trên")}<b>{t("tất cả camera")}</b>, tốn thêm CPU/GPU.
                                AI View NVR sẽ khởi động lại, mất khoảng <b>{t("1 phút")}</b>{t("— live view/ghi hình có thể gián đoạn tạm thời trong lúc đó.")}</p>
                        ) : (
                            <p className="text-sm text-gray-600 dark:text-gray-300">{t("NVR sẽ")}<b>{t("tắt hoàn toàn")}</b>{t("khả năng tự phát hiện người/xe của")}<b>AI View NVR</b>{t("trên")}<b>{t("tất cả camera")}</b> để tiết kiệm CPU/GPU.
                                Camera sẽ <b>{t("không")}</b>{t("còn tự sinh sự kiện nhận diện của AI View NVR nữa.")}<br /><br />
                                <b>{t("Chỉ tắt nếu bạn đã có Bkav AI View")}</b>{t("(AiView Box hoặc camera Bkav tự nhận diện) để xử lý thay thế — nếu không, camera sẽ mất khả năng phát hiện AI.")}<br /><br />{t("AI View NVR sẽ khởi động lại, mất khoảng")}<b>{t("1 phút")}</b>{t("— live view/ghi hình có thể gián đoạn tạm thời trong lúc đó.")}</p>
                        )}
                        <div className="flex justify-end gap-2 mt-5">
                            <button
                                onClick={() => setConfirmTarget(null)}
                                className="px-4 py-2 text-sm rounded-lg border border-gray-300 dark:border-gray-600 hover:bg-gray-100 dark:hover:bg-gray-700"
                            >{t("Hủy")}</button>
                            <button
                                onClick={() => doApply(confirmTarget)}
                                className={`px-4 py-2 text-sm rounded-lg text-white font-medium ${
                                    confirmTarget ? 'bg-[#EF4444] hover:bg-red-600' : 'bg-amber-600 hover:bg-amber-700'
                                }`}
                            >
                                {confirmTarget ? 'Bật AI View NVR' : 'Xác nhận tắt'}
                            </button>
                        </div>
                    </div>
                </div>
            )}

            {/* Hỏi trước khi cấp vùng mặc định — chỉ hiện với camera CHƯA ai cấu hình AI bao giờ.
                Camera đã bị tắt riêng có chủ đích không nằm trong danh sách này. */}
            {zonePrompt && (
                <div className="fixed inset-0 z-50 bg-black/60 flex items-center justify-center p-4">
                    <div className="bg-white dark:bg-[#1F2937] rounded-xl p-5 max-w-md w-full shadow-2xl">
                        <h3 className="font-bold text-lg mb-2">{t("Cấp vùng nhận diện mặc định?")}</h3>
                        <p className="text-sm text-gray-600 dark:text-gray-300 mb-3">
                            {zonePrompt.length} camera chưa có vùng nhận diện nào nên sẽ <b>{t("không")}</b> phát hiện gì
                            dù AI đã bật. Cấp vùng <b>{t("Toàn cảnh")}</b>{t("(bắt Người + Phương tiện, phủ kín khung hình) cho các camera này?")}</p>
                        <div className="max-h-52 overflow-y-auto border border-gray-200 dark:border-gray-700 rounded-lg mb-4">
                            {zonePrompt.map(slug => (
                                <label key={slug} className="flex items-center gap-2 px-3 py-2 text-sm hover:bg-gray-50 dark:hover:bg-gray-800 cursor-pointer">
                                    <input
                                        type="checkbox"
                                        checked={zonePromptSelected.has(slug)}
                                        onChange={e => {
                                            const next = new Set(zonePromptSelected);
                                            if (e.target.checked) next.add(slug); else next.delete(slug);
                                            setZonePromptSelected(next);
                                        }}
                                    />
                                    <span className="truncate">{slug}</span>
                                </label>
                            ))}
                        </div>
                        <div className="flex justify-end gap-2">
                            <button
                                onClick={() => { setZonePrompt(null); doApply(true, []); }}
                                className="px-4 py-2 text-sm rounded-lg border border-gray-300 dark:border-gray-600 hover:bg-gray-100 dark:hover:bg-gray-700"
                            >{t("Bỏ qua")}</button>
                            <button
                                onClick={() => { const sel = Array.from(zonePromptSelected); setZonePrompt(null); doApply(true, sel); }}
                                className="px-4 py-2 text-sm rounded-lg bg-[#EF4444] hover:bg-red-600 text-white font-medium"
                            >
                                Cấp vùng ({zonePromptSelected.size})
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default GlobalDetectToggle;

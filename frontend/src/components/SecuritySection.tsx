import { useCallback, useEffect, useState } from 'react';
import { useModalKeys } from '../hooks/useModalKeys';
import PatternLock from './PatternLock';
import RecoveryCodeCard from './RecoveryCodeCard';
import SecurityQuestionsForm, { type AnswerItem, type CatalogQuestion } from './SecurityQuestionsForm';
import { Icon } from './Icon';
import { t } from '../i18n';

interface SecurityStatus {
    hasRecoveryCode: boolean;
    recoveryCreatedAt: number | null;
    hasQuestions: boolean;
    questions: CatalogQuestion[];
}

type Action = null | 'regenerate' | 'set-questions' | 'clear-questions';

const fmtDate = (ms: number | null) => {
    if (!ms) return '—';
    const d = new Date(ms);
    const p = (n: number) => String(n).padStart(2, '0');
    return `${p(d.getDate())}/${p(d.getMonth() + 1)}/${d.getFullYear()}`;
};

const jsonPost = async (url: string, body: unknown) => {
    const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    let data: any = {};
    try { data = await res.json(); } catch { /* empty */ }
    return { res, data };
};

export default function SecuritySection({ addToast }: { addToast?: (t: any) => void }) {
    const [status, setStatus] = useState<SecurityStatus | null>(null);
    const [catalog, setCatalog] = useState<CatalogQuestion[]>([]);
    const [action, setAction] = useState<Action>(null);
    const [pendingItems, setPendingItems] = useState<AnswerItem[] | null>(null);
    const [newCode, setNewCode] = useState('');
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState('');

    const load = useCallback(async () => {
        try {
            const res = await fetch('/api/lock/security-status');
            if (res.ok) setStatus(await res.json());
        } catch { /* leave previous state */ }
    }, []);

    useEffect(() => { load(); }, [load]);

    const openAction = async (next: Action) => {
        setError('');
        setNewCode('');
        setPendingItems(null);
        if (next === 'set-questions' && catalog.length === 0) {
            try {
                const res = await fetch('/api/lock/questions/catalog');
                const data = await res.json();
                setCatalog(data.questions || []);
            } catch { setCatalog([]); }
        }
        setAction(next);
    };

    const close = () => { setAction(null); setNewCode(''); setPendingItems(null); setError(''); };

    // Esc đóng modal Bảo mật. Không gắn Enter: các thao tác ở đây (tạo mã mới, đổi câu
    // hỏi) đều làm mã/câu hỏi cũ hết hiệu lực ngay, phải bấm nút có chủ đích.
    useModalKeys({ onClose: close, enabled: action !== null });

    // Every mutation re-asks for the pattern: an unattended logged-in browser must not be
    // enough to mint a new recovery code or swap the security questions.
    const onPatternDrawn = async (pattern: string) => {
        setBusy(true);
        setError('');
        try {
            if (action === 'regenerate') {
                const { res, data } = await jsonPost('/api/lock/recovery/regenerate', { pattern });
                if (res.ok && data.recoveryCode) {
                    setNewCode(data.recoveryCode);
                    await load();
                } else setError(data.error || t('Không tạo được mã mới'));
            } else if (action === 'set-questions' && pendingItems) {
                const { res, data } = await jsonPost('/api/lock/questions/set', { pattern, items: pendingItems });
                if (res.ok && data.success) {
                    addToast?.({ type: 'success', title: t('Đã lưu'), message: t('Câu hỏi bảo mật đã được cập nhật') });
                    await load();
                    close();
                } else setError(data.error || t('Không lưu được'));
            } else if (action === 'clear-questions') {
                const { res, data } = await jsonPost('/api/lock/questions/clear', { pattern });
                if (res.ok && data.success) {
                    addToast?.({ type: 'success', title: t('Đã xóa'), message: t('Đã xóa câu hỏi bảo mật') });
                    await load();
                    close();
                } else setError(data.error || t('Không xóa được'));
            }
        } catch {
            setError(t('Lỗi kết nối server'));
        } finally {
            setBusy(false);
        }
    };

    const card = 'bg-white dark:bg-[#1F2937] rounded-xl border border-gray-200 dark:border-gray-700 p-6';

    return (
        <div className="max-w-3xl space-y-6">
            <div>
                <h2 className="text-xl font-bold text-gray-900 dark:text-white">{t("Bảo mật")}</h2>
                <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">{t("Các cách đặt lại mật mã mở khóa khi không có Internet.")}</p>
            </div>

            {/* Recovery code */}
            <div className={card}>
                <div className="flex items-start justify-between gap-4">
                    <div className="flex-1">
                        <h3 className="font-semibold text-gray-900 dark:text-white flex items-center gap-2">
                            <Icon name="vpn_key" className="text-amber-500 text-xl" />{t("Mã khôi phục")}</h3>
                        <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">
                            {status?.hasRecoveryCode
                                ? t('Đang có hiệu lực · tạo ngày {0}').replace('{0}', fmtDate(status.recoveryCreatedAt))
                                : t('Chưa có mã khôi phục còn hiệu lực — hãy tạo ngay.')}
                        </p>
                        <p className="text-xs text-gray-400 dark:text-gray-500 mt-2">{t("Mã chỉ hiển thị một lần lúc tạo và dùng được một lần.")}</p>
                    </div>
                    <button
                        onClick={() => openAction('regenerate')}
                        className="shrink-0 bg-blue-600 hover:bg-blue-500 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
                    >{t("Tạo mã mới")}</button>
                </div>
            </div>

            {/* Security questions */}
            <div className={card}>
                <div className="flex items-start justify-between gap-4">
                    <div className="flex-1">
                        <h3 className="font-semibold text-gray-900 dark:text-white flex items-center gap-2">
                            <Icon name="help_outline" className="text-blue-500 text-xl" />{t("Câu hỏi bảo mật")}</h3>
                        {status?.hasQuestions ? (
                            <ul className="text-sm text-gray-500 dark:text-gray-400 mt-2 space-y-1 list-disc list-inside">
                                {status.questions.map((q) => <li key={q.id}>{q.text}</li>)}
                            </ul>
                        ) : (
                            <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">{t("Chưa đặt.")}</p>
                        )}
                    </div>
                    <div className="shrink-0 flex flex-col gap-2">
                        <button
                            onClick={() => openAction('set-questions')}
                            className="bg-blue-600 hover:bg-blue-500 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
                        >
                            {status?.hasQuestions ? t('Đổi câu hỏi') : t('Đặt câu hỏi')}
                        </button>
                        {status?.hasQuestions && (
                            <button
                                onClick={() => openAction('clear-questions')}
                                className="text-red-500 hover:text-red-400 text-sm font-medium px-4 py-2 rounded-lg border border-red-300 dark:border-red-800 transition-colors"
                            >{t("Xóa")}</button>
                        )}
                    </div>
                </div>
            </div>

            <p className="text-xs text-gray-400 dark:text-gray-500 leading-relaxed">
                {t('Nếu mất cả mã khôi phục lẫn câu trả lời, chỉ còn cách đặt lại tại chỗ trên máy (cần có mặt vật lý) — hãy giữ tờ mã khôi phục trong hồ sơ bàn giao.')}
            </p>

            {/* ── Modal ─────────────────────────────────────────────── */}
            {action && (
                <div className="fixed inset-0 z-[9998] bg-black/60 flex items-center justify-center p-4" onClick={close}>
                    <div
                        className="bg-white dark:bg-[#1F2937] rounded-2xl p-6 w-full max-w-md max-h-[90vh] overflow-y-auto"
                        onClick={(e) => e.stopPropagation()}
                    >
                        {newCode ? (
                            <RecoveryCodeCard
                                code={newCode}
                                variant="light"
                                onConfirm={close}
                                confirmLabel={t("Đóng")}
                                intro={t("Mã cũ đã hết hiệu lực ngay lập tức. Đây là mã mới.")}
                            />
                        ) : action === 'set-questions' && !pendingItems ? (
                            <>
                                <h3 className="font-bold text-gray-900 dark:text-white mb-4">{t("Đặt câu hỏi bảo mật")}</h3>
                                <SecurityQuestionsForm
                                    catalog={catalog}
                                    initialIds={status?.questions.map((q) => q.id)}
                                    variant="light"
                                    onSubmit={(items) => setPendingItems(items)}
                                    onCancel={close}
                                    cancelLabel={t("Hủy")}
                                    submitLabel={t("Tiếp tục")}
                                />
                            </>
                        ) : (
                            <div className="flex flex-col items-center">
                                <h3 className="font-bold text-gray-900 dark:text-white mb-1">{t("Xác nhận bằng mã vẽ")}</h3>
                                <p className="text-sm text-gray-500 dark:text-gray-400 mb-4 text-center">
                                    {action === 'regenerate' && t('Mã khôi phục hiện tại sẽ hết hiệu lực ngay lập tức.')}
                                    {action === 'clear-questions' && t('Xóa câu hỏi bảo mật sẽ bớt một cách khôi phục.')}
                                    {action === 'set-questions' && t('Vẽ mã hiện tại để lưu câu hỏi mới.')}
                                </p>
                                <div style={{ width: 240, height: 240 }} className={busy ? 'opacity-50 pointer-events-none' : ''}>
                                    <PatternLock size={240} onComplete={onPatternDrawn} error={!!error} disabled={busy} />
                                </div>
                                {error && <p className="text-sm text-red-500 mt-3">{error}</p>}
                                <button onClick={close} className="mt-4 text-sm text-gray-500 hover:text-gray-700 dark:hover:text-gray-300 underline">{t("Hủy")}</button>
                            </div>
                        )}
                    </div>
                </div>
            )}
        </div>
    );
}

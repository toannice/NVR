import { useEffect, useRef, useState } from 'react';
import QRCode from 'qrcode';
import { t } from '../i18n';

interface RecoveryCodeCardProps {
    code: string;
    /** Called when the user confirms they saved it. Omit to render without the confirm gate. */
    onConfirm?: () => void;
    confirmLabel?: string;
    /** Shown above the code — differs between first setup, post-reset and manual regeneration. */
    intro?: string;
    /** Dark glass (lock screen) vs light card (settings page). */
    variant?: 'dark' | 'light';
}

const deviceName = () => window.location.hostname || 'nvr';

const stamp = () => {
    const d = new Date();
    const p = (n: number) => String(n).padStart(2, '0');
    return `${p(d.getDate())}/${p(d.getMonth() + 1)}/${d.getFullYear()} ${p(d.getHours())}:${p(d.getMinutes())}`;
};

const fileStamp = () => {
    const d = new Date();
    const p = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}`;
};

export default function RecoveryCodeCard({
    code,
    onConfirm,
    confirmLabel = t('Hoàn tất & vào hệ thống'),
    intro = t('Dùng mã này để đặt lại mật khẩu khi không có Internet.'),
    variant = 'dark',
}: RecoveryCodeCardProps) {
    const [acknowledged, setAcknowledged] = useState(false);
    const [copied, setCopied] = useState(false);
    const [qrUrl, setQrUrl] = useState<string>('');
    const printRef = useRef<HTMLDivElement>(null);

    // Rendered locally by the qrcode lib — no network call, works on an air-gapped box.
    useEffect(() => {
        QRCode.toDataURL(code, { margin: 1, width: 220, errorCorrectionLevel: 'M' })
            .then(setQrUrl)
            .catch(() => setQrUrl(''));
    }, [code]);

    // Opening the web UI from the NVR itself would drop the recovery file onto the very
    // machine it protects, so warn instead of silently letting that happen.
    const onDevice = ['localhost', '127.0.0.1', '::1'].includes(window.location.hostname);

    const handleCopy = async () => {
        try {
            await navigator.clipboard.writeText(code);
        } catch {
            // Clipboard API needs a secure context; fall back to a throwaway textarea.
            const ta = document.createElement('textarea');
            ta.value = code;
            document.body.appendChild(ta);
            ta.select();
            document.execCommand('copy');
            document.body.removeChild(ta);
        }
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
    };

    const handleDownload = () => {
        const body = [
            t('AIVIEW NVR — MÃ KHÔI PHỤC MẬT KHẨU'),
            '=====================================',
            '',
            `${t('Thiết bị')} : ${deviceName()}`,
            `${t('Ngày tạo')} : ${stamp()}`,
            '',
            `${t('MÃ')}       : ${code}`,
            '',
            t('HƯỚNG DẪN'),
            t('  1. Tại màn hình khóa, bấm "Quên mật khẩu?".'),
            t('  2. Chọn "Nhập mã khôi phục".'),
            t('  3. Nhập đúng mã ở trên, sau đó vẽ mật mã mới.'),
            '',
            t('LƯU Ý'),
            t('  - Mã chỉ dùng được MỘT lần. Dùng xong hệ thống cấp mã mới.'),
            t('  - Không cần Internet.'),
            t('  - Cất giữ như chìa khóa thiết bị. Ai có mã này đặt lại được mật khẩu.'),
            '',
        ].join('\n');
        const blob = new Blob([body], { type: 'text/plain;charset=utf-8' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `AIVIEW-NVR-RECOVERY-${deviceName()}-${fileStamp()}.txt`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    };

    const handlePrint = () => window.print();

    const dark = variant === 'dark';
    const btn = dark
        ? 'flex-1 flex items-center justify-center gap-1.5 bg-white/10 hover:bg-white/20 border border-white/20 text-white text-xs font-medium py-2.5 rounded-xl transition-colors'
        : 'flex-1 flex items-center justify-center gap-1.5 bg-gray-100 dark:bg-gray-700 hover:bg-gray-200 dark:hover:bg-gray-600 border border-gray-300 dark:border-gray-600 text-gray-800 dark:text-gray-100 text-xs font-medium py-2.5 rounded-xl transition-colors';

    return (
        <div className={`w-full ${dark ? 'max-w-[300px]' : ''} flex flex-col items-center`}>
            <div className="w-14 h-14 rounded-2xl flex items-center justify-center mb-3 shadow-lg" style={{ background: 'linear-gradient(135deg, #f59e0b 0%, #d97706 100%)' }}>
                <span className="material-icons text-white text-3xl">vpn_key</span>
            </div>
            <h2 className={`text-lg font-bold mb-1 ${dark ? 'text-white' : 'text-gray-900 dark:text-white'}`}>{t("Mã khôi phục")}</h2>
            <p className={`text-xs text-center mb-4 leading-relaxed ${dark ? 'text-white/60' : 'text-gray-500 dark:text-gray-400'}`}>{intro}</p>

            <div className={`w-full rounded-xl px-3 py-4 mb-3 text-center ${dark ? 'bg-black/30 border border-white/20' : 'bg-gray-50 dark:bg-gray-900 border border-gray-300 dark:border-gray-600'}`}>
                <code className={`font-mono text-base tracking-wider break-all ${dark ? 'text-amber-300' : 'text-amber-600 dark:text-amber-400'}`}>{code}</code>
            </div>

            {qrUrl && (
                <div className="bg-white p-2 rounded-lg mb-3">
                    <img src={qrUrl} alt={t("QR mã khôi phục")} width={120} height={120} />
                </div>
            )}

            <div className="w-full flex gap-2 mb-2">
                <button type="button" onClick={handleCopy} className={btn}>
                    <span className="material-icons text-sm">{copied ? 'check' : 'content_copy'}</span>
                    {copied ? t('Đã chép') : t('Sao chép')}
                </button>
                <button type="button" onClick={handleDownload} className={btn}>
                    <span className="material-icons text-sm">download</span>{t("Tải file")}</button>
                <button type="button" onClick={handlePrint} className={btn}>
                    <span className="material-icons text-sm">print</span>{t("In")}
                </button>
            </div>

            <p className={`text-[11px] text-center leading-relaxed mb-3 ${dark ? 'text-amber-300/80' : 'text-amber-600 dark:text-amber-400'}`}>{t("⚠ Mã chỉ hiển thị MỘT lần và chỉ dùng được một lần. Hãy lưu lại ngay.")}</p>

            {onDevice && (
                <p className={`text-[11px] text-center leading-relaxed mb-3 px-2 py-2 rounded-lg ${dark ? 'text-yellow-200 bg-yellow-500/10' : 'text-yellow-800 bg-yellow-100 dark:text-yellow-200 dark:bg-yellow-900/30'}`}>
                    Bạn đang mở trên chính máy NVR. Hãy chép mã sang thiết bị khác hoặc in ra giấy —
                    lưu file trên máy này không bảo vệ được gì.
                </p>
            )}

            {onConfirm && (
                <>
                    <label className={`flex items-center gap-2 text-xs mb-3 cursor-pointer select-none ${dark ? 'text-white/70' : 'text-gray-600 dark:text-gray-300'}`}>
                        <input
                            type="checkbox"
                            checked={acknowledged}
                            onChange={(e) => setAcknowledged(e.target.checked)}
                            className="w-4 h-4 accent-blue-500"
                        />{t("Tôi đã lưu mã khôi phục")}</label>
                    <button
                        type="button"
                        disabled={!acknowledged}
                        onClick={onConfirm}
                        className="w-full bg-blue-600 hover:bg-blue-500 disabled:opacity-40 disabled:cursor-not-allowed text-white font-bold py-3 rounded-xl shadow-lg transition-all"
                    >
                        {confirmLabel}
                    </button>
                </>
            )}

            {/* Only element visible to the printer — see .print-sheet in index.css */}
            <div className="print-sheet" ref={printRef}>
                <h1 style={{ fontSize: 18, marginBottom: 4 }}>{t("AIVIEW NVR — MÃ KHÔI PHỤC MẬT KHẨU")}</h1>
                <p style={{ fontSize: 12, margin: '2px 0' }}>Thiết bị: {deviceName()}</p>
                <p style={{ fontSize: 12, margin: '2px 0' }}>Ngày tạo: {stamp()}</p>
                <div style={{ border: '2px solid #000', padding: 12, margin: '14px 0', textAlign: 'center' }}>
                    <div style={{ fontFamily: 'monospace', fontSize: 22, letterSpacing: 2 }}>{code}</div>
                </div>
                {qrUrl && <img src={qrUrl} alt="" width={110} height={110} />}
                <ol style={{ fontSize: 11, lineHeight: 1.6, paddingLeft: 18 }}>
                    <li>{t("Tại màn hình khóa, bấm “Quên mật khẩu?”.")}</li>
                    <li>{t("Chọn “Nhập mã khôi phục”, nhập đúng mã ở trên.")}</li>
                    <li>{t("Vẽ mật mã mới hai lần để hoàn tất.")}</li>
                </ol>
                <p style={{ fontSize: 11, marginTop: 8 }}>{t("Mã dùng được MỘT lần, không cần Internet. Cất giữ như chìa khóa thiết bị.")}</p>
                <p style={{ fontSize: 11, marginTop: 22 }}>{t("Người nhận ký: ______________________")}</p>
            </div>
        </div>
    );
}

import { useState, useEffect, useRef, useCallback } from 'react';
import PatternLock from './PatternLock';
import RecoveryCodeCard from './RecoveryCodeCard';
import SecurityQuestionsForm, { type CatalogQuestion, type AnswerItem } from './SecurityQuestionsForm';
import { t } from '../i18n';

interface PatternLockScreenProps {
    isSetupMode: boolean;
    onUnlock: () => void;
    onSetupComplete: () => void;
}

type SetupStep = 'draw-first' | 'draw-confirm' | 'recovery-code' | 'questions';
type RecoverStep = 'choose' | 'code' | 'questions' | 'new-draw' | 'new-confirm' | 'recovery-code';
type ScreenMode = 'setup' | 'verify' | 'recover';

const jsonPost = async (url: string, body: unknown) => {
    const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    let data: any = {};
    try { data = await res.json(); } catch { /* empty body */ }
    return { res, data };
};

/** Server phrases lockouts as "... 300 giây." — surface it as a live countdown. */
const parseLockSeconds = (msg?: string): number | null => {
    const m = msg?.match(/(\d+)\s*giây/);
    return m ? parseInt(m[1], 10) : null;
};

export default function PatternLockScreen({ isSetupMode, onUnlock, onSetupComplete }: PatternLockScreenProps) {
    const [mode, setMode] = useState<ScreenMode>(isSetupMode ? 'setup' : 'verify');
    const [setupStep, setSetupStep] = useState<SetupStep>('draw-first');
    const [recoverStep, setRecoverStep] = useState<RecoverStep>('choose');

    const firstPatternRef = useRef<string>('');
    const resetTokenRef = useRef<string>('');

    const [recoveryCode, setRecoveryCode] = useState('');
    const [afterCodeAction, setAfterCodeAction] = useState<'setup-questions' | 'unlock'>('unlock');

    const [codeInput, setCodeInput] = useState('');
    const [questions, setQuestions] = useState<CatalogQuestion[]>([]);
    const [catalog, setCatalog] = useState<CatalogQuestion[]>([]);
    const [answers, setAnswers] = useState<string[]>(['', '', '']);

    const [hasRecoveryCode, setHasRecoveryCode] = useState(false);
    const [hasQuestions, setHasQuestions] = useState(false);

    const [message, setMessage] = useState('');
    const [msgType, setMsgType] = useState<'info' | 'success' | 'error'>('info');
    const [isDisabled, setIsDisabled] = useState(false);
    const [lockedUntil, setLockedUntil] = useState<number | null>(null);
    const [lockCountdown, setLockCountdown] = useState(0);

    const showInfo = useCallback((msg: string) => { setMessage(msg); setMsgType('info'); }, []);
    const showSuccess = (msg: string) => { setMessage(msg); setMsgType('success'); };
    const showError = (msg: string, durationMs = 1800) => {
        setMessage(msg);
        setMsgType('error');
        setIsDisabled(true);
        setTimeout(() => {
            setMsgType('info');
            setIsDisabled(false);
        }, durationMs);
    };
    const clearMsg = () => { setMessage(''); setMsgType('info'); };

    // Which recovery paths this device actually has — drives the "Quên mật khẩu?" menu.
    const refreshStatus = useCallback(async () => {
        try {
            const res = await fetch('/api/lock/status');
            const data = await res.json();
            setHasRecoveryCode(!!data.hasRecoveryCode);
            setHasQuestions(!!data.hasQuestions);
        } catch { /* offline UI still renders */ }
    }, []);

    useEffect(() => { if (!isSetupMode) refreshStatus(); }, [isSetupMode, refreshStatus]);

    // Lockout countdown
    useEffect(() => {
        if (!lockedUntil) return;
        const interval = setInterval(() => {
            const remaining = Math.ceil((lockedUntil - Date.now()) / 1000);
            if (remaining <= 0) {
                setLockedUntil(null);
                setIsDisabled(false);
                setLockCountdown(0);
                showInfo(t('Hãy thử lại'));
            } else {
                setLockCountdown(remaining);
            }
        }, 500);
        return () => clearInterval(interval);
    }, [lockedUntil, showInfo]);

    const applyLockIfAny = (data: any): boolean => {
        const secs = parseLockSeconds(data?.error);
        if (secs) {
            setLockedUntil(Date.now() + secs * 1000);
            setMessage(data.error);
            setMsgType('error');
            return true;
        }
        return false;
    };

    // ─── SETUP FLOW ───────────────────────────────────────────────

    const handleSetupPattern1 = (pattern: string) => {
        if (pattern.length < 4) { showError(t('Mã vẽ phải nối tối thiểu 4 điểm')); return; }
        firstPatternRef.current = pattern;
        setSetupStep('draw-confirm');
        showInfo(t('Vẽ lại để xác nhận'));
    };

    const handleSetupPattern2 = async (pattern: string) => {
        if (pattern !== firstPatternRef.current) {
            showError(t('Hai lần vẽ không khớp. Vẽ lại từ đầu.'));
            setSetupStep('draw-first');
            return;
        }
        setIsDisabled(true);
        showInfo(t('Đang thiết lập...'));
        try {
            const { res, data } = await jsonPost('/api/lock/setup', { pattern });
            if (res.ok && data.success && data.recoveryCode) {
                setRecoveryCode(data.recoveryCode);
                setAfterCodeAction('setup-questions');
                setSetupStep('recovery-code');
                clearMsg();
            } else {
                showError(data.error || t('Thiết lập thất bại'));
                setSetupStep('draw-first');
            }
        } catch {
            showError(t('Lỗi kết nối server'));
            setSetupStep('draw-first');
        } finally {
            setIsDisabled(false);
        }
    };

    const loadCatalog = async () => {
        try {
            const res = await fetch('/api/lock/questions/catalog');
            const data = await res.json();
            setCatalog(data.questions || []);
        } catch { setCatalog([]); }
    };

    const handleRecoveryAcknowledged = async () => {
        if (afterCodeAction === 'setup-questions') {
            await loadCatalog();
            setSetupStep('questions');
            clearMsg();
        } else {
            onUnlock();
        }
    };

    const submitQuestions = async (items: AnswerItem[]) => {
        setIsDisabled(true);
        try {
            const { res, data } = await jsonPost('/api/lock/questions/set', {
                pattern: firstPatternRef.current,
                items,
            });
            if (res.ok && data.success) {
                onSetupComplete();
            } else {
                showError(data.error || t('Không lưu được câu hỏi bảo mật'));
            }
        } catch {
            showError(t('Lỗi kết nối server'));
        } finally {
            setIsDisabled(false);
        }
    };

    // ─── VERIFY ───────────────────────────────────────────────────

    const handleVerifyDraw = async (pattern: string) => {
        setIsDisabled(true);
        try {
            const { res, data } = await jsonPost('/api/lock/verify', { pattern });
            if (res.ok && data.success) {
                showSuccess(t('Mở khóa thành công'));
                // Machines upgraded from the old Gmail-OTP build have no recovery code yet.
                // Mint one now, while we still hold the pattern, instead of leaving them
                // with no offline way back in.
                if (!hasRecoveryCode) {
                    const minted = await jsonPost('/api/lock/recovery/regenerate', { pattern });
                    if (minted.res.ok && minted.data.recoveryCode) {
                        setRecoveryCode(minted.data.recoveryCode);
                        setAfterCodeAction('unlock');
                        setMode('setup');
                        setSetupStep('recovery-code');
                        clearMsg();
                        return;
                    }
                }
                setTimeout(onUnlock, 250);
            } else if (!applyLockIfAny(data)) {
                showError(data.error || t('Mã vẽ không đúng'));
            }
        } catch {
            showError(t('Lỗi kết nối server'));
        } finally {
            setIsDisabled(false);
        }
    };

    // ─── RECOVERY ─────────────────────────────────────────────────

    const openRecovery = async () => {
        await refreshStatus();
        setMode('recover');
        setRecoverStep('choose');
        setCodeInput('');
        setAnswers(['', '', '']);
        clearMsg();
    };

    const startCodePath = () => {
        setRecoverStep('code');
        setCodeInput('');
        clearMsg();
    };

    const startQuestionPath = async () => {
        try {
            const res = await fetch('/api/lock/recovery/questions');
            const data = await res.json();
            setQuestions(data.questions || []);
            setAnswers(['', '', '']);
            setRecoverStep('questions');
            clearMsg();
        } catch {
            showError(t('Không tải được câu hỏi bảo mật'));
        }
    };

    const submitCode = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!codeInput.trim()) return;
        setIsDisabled(true);
        try {
            const { res, data } = await jsonPost('/api/lock/recovery/code', { code: codeInput });
            if (res.ok && data.success && data.resetToken) {
                resetTokenRef.current = data.resetToken;
                showSuccess(t('Xác minh thành công'));
                setTimeout(() => { clearMsg(); setRecoverStep('new-draw'); }, 600);
            } else if (!applyLockIfAny(data)) {
                showError(data.error || t('Mã khôi phục không đúng'));
                setCodeInput('');
            }
        } catch {
            showError(t('Lỗi kết nối server'));
        } finally {
            setIsDisabled(false);
        }
    };

    const submitAnswers = async (e: React.FormEvent) => {
        e.preventDefault();
        if (answers.some((a) => !a.trim())) { showError(t('Hãy trả lời cả 3 câu')); return; }
        setIsDisabled(true);
        try {
            const { res, data } = await jsonPost('/api/lock/recovery/questions', { answers });
            if (res.ok && data.success && data.resetToken) {
                resetTokenRef.current = data.resetToken;
                showSuccess(t('Xác minh thành công'));
                setTimeout(() => { clearMsg(); setRecoverStep('new-draw'); }, 600);
            } else if (!applyLockIfAny(data)) {
                showError(data.error || t('Câu trả lời không chính xác'));
                setAnswers(['', '', '']);
            }
        } catch {
            showError(t('Lỗi kết nối server'));
        } finally {
            setIsDisabled(false);
        }
    };

    const handleResetNewPattern1 = (pattern: string) => {
        if (pattern.length < 4) { showError(t('Mã vẽ phải nối tối thiểu 4 điểm')); return; }
        firstPatternRef.current = pattern;
        setRecoverStep('new-confirm');
        showInfo(t('Xác nhận mã mới'));
    };

    const handleResetNewPattern2 = async (pattern: string) => {
        if (pattern !== firstPatternRef.current) {
            showError(t('Hai lần vẽ không khớp. Vẽ lại.'));
            setRecoverStep('new-draw');
            return;
        }
        setIsDisabled(true);
        showInfo(t('Đang cập nhật...'));
        try {
            const { res, data } = await jsonPost('/api/lock/reset-complete', {
                resetToken: resetTokenRef.current,
                pattern,
            });
            if (res.ok && data.success && data.recoveryCode) {
                setRecoveryCode(data.recoveryCode);
                setAfterCodeAction('unlock');
                setRecoverStep('recovery-code');
                clearMsg();
            } else {
                showError(data.error || t('Khôi phục thất bại'));
                setRecoverStep('new-draw');
            }
        } catch {
            showError(t('Lỗi kết nối server'));
            setRecoverStep('new-draw');
        } finally {
            setIsDisabled(false);
        }
    };

    const onPatternComplete = (pattern: string) => {
        if (mode === 'setup') {
            if (setupStep === 'draw-first') handleSetupPattern1(pattern);
            else if (setupStep === 'draw-confirm') handleSetupPattern2(pattern);
        } else if (mode === 'verify') {
            handleVerifyDraw(pattern);
        } else if (mode === 'recover') {
            if (recoverStep === 'new-draw') handleResetNewPattern1(pattern);
            else if (recoverStep === 'new-confirm') handleResetNewPattern2(pattern);
        }
    };

    // ─── RENDERERS ────────────────────────────────────────────────

    const msgColorClass =
        msgType === 'error' ? 'text-red-400' :
        msgType === 'success' ? 'text-green-400' :
        'text-white/60';

    const renderPattern = (customMessage?: string) => (
        <div className="flex flex-col items-center">
            <div className="h-8 flex items-center justify-center mb-2">
                <p className={`text-sm font-medium transition-colors ${lockedUntil ? 'text-red-400' : msgColorClass}`}>
                    {lockedUntil ? t('🔒 Khóa {0}s').replace('{0}', String(lockCountdown)) : customMessage || message}
                </p>
            </div>
            <div className={`${msgType === 'error' ? 'animate-shake' : ''}`} style={{ width: 260, height: 260 }}>
                <PatternLock
                    size={260}
                    onComplete={onPatternComplete}
                    error={msgType === 'error'}
                    disabled={isDisabled || !!lockedUntil}
                />
            </div>
        </div>
    );

    const backButton = (onClick: () => void, label = t('Quay lại')) => (
        <button type="button" onClick={onClick} className="mt-3 text-white/40 text-xs hover:text-white/60 underline">
            {label}
        </button>
    );

    const renderChoose = () => {
        const none = !hasRecoveryCode && !hasQuestions;
        return (
            <div className="w-full max-w-[280px] flex flex-col items-center">
                <h2 className="text-white text-lg font-semibold mb-1">{t("Khôi phục mật khẩu")}</h2>
                <p className="text-white/50 text-xs mb-4 text-center">
                    {none ? t('Thiết bị này chưa cài phương thức khôi phục nào.') : t('Chọn cách xác minh:')}
                </p>

                {hasRecoveryCode && (
                    <button
                        type="button"
                        onClick={startCodePath}
                        className="w-full flex items-center gap-3 bg-white/5 hover:bg-white/10 border border-white/15 rounded-xl px-4 py-3 mb-2 text-left transition-colors"
                    >
                        <span className="material-icons text-amber-400">vpn_key</span>
                        <span className="flex-1">
                            <span className="block text-white text-sm font-medium">{t("Nhập mã khôi phục")}</span>
                            <span className="block text-white/40 text-[11px]">{t("Không cần mạng")}</span>
                        </span>
                        <span className="material-icons text-white/30 text-base">chevron_right</span>
                    </button>
                )}

                {hasQuestions && (
                    <button
                        type="button"
                        onClick={startQuestionPath}
                        className="w-full flex items-center gap-3 bg-white/5 hover:bg-white/10 border border-white/15 rounded-xl px-4 py-3 mb-2 text-left transition-colors"
                    >
                        <span className="material-icons text-blue-400">help_outline</span>
                        <span className="flex-1">
                            <span className="block text-white text-sm font-medium">{t("Câu hỏi bảo mật")}</span>
                            <span className="block text-white/40 text-[11px]">{t("Không cần mạng")}</span>
                        </span>
                        <span className="material-icons text-white/30 text-base">chevron_right</span>
                    </button>
                )}

                {none && (
                    <p className="text-white/50 text-xs leading-relaxed text-center bg-white/5 rounded-xl px-4 py-3">{t("Liên hệ kỹ thuật để đặt lại tại chỗ trên máy (cần có mặt vật lý).")}</p>
                )}

                {backButton(() => { setMode('verify'); clearMsg(); })}
            </div>
        );
    };

    const renderCodeEntry = () => (
        <form onSubmit={submitCode} className="w-full max-w-[280px] flex flex-col items-center">
            <div className="h-8 flex items-center justify-center mb-2 text-center">
                <p className={`text-sm font-medium ${lockedUntil ? 'text-red-400' : msgColorClass}`}>
                    {lockedUntil ? t('🔒 Khóa {0}s').replace('{0}', String(lockCountdown)) : message}
                </p>
            </div>
            <h2 className="text-white text-lg font-semibold mb-4">{t("Nhập mã khôi phục")}</h2>
            <input
                autoFocus
                type="text"
                autoComplete="off"
                spellCheck={false}
                className="w-full bg-white/10 border border-white/20 rounded-xl px-4 py-3 text-white font-mono tracking-widest text-center uppercase focus:outline-none focus:ring-2 focus:ring-blue-500 mb-4"
                placeholder="XXXXX-XXXXX-XXXXX-XXXXX"
                value={codeInput}
                onChange={(e) => setCodeInput(e.target.value.toUpperCase())}
                disabled={isDisabled || !!lockedUntil}
            />
            <button
                type="submit"
                disabled={isDisabled || !!lockedUntil}
                className="w-full bg-blue-600 hover:bg-blue-500 disabled:opacity-50 text-white font-bold py-3 rounded-xl shadow-lg transition-all"
            >
                {isDisabled ? t('Đang kiểm tra...') : t('Xác nhận')}
            </button>
            {backButton(() => { setRecoverStep('choose'); clearMsg(); })}
        </form>
    );

    const renderQuestionEntry = () => (
        <form onSubmit={submitAnswers} className="w-full max-w-[280px] flex flex-col items-center">
            <div className="h-8 flex items-center justify-center mb-2 text-center">
                <p className={`text-sm font-medium ${lockedUntil ? 'text-red-400' : msgColorClass}`}>
                    {lockedUntil ? t('🔒 Khóa {0}s').replace('{0}', String(lockCountdown)) : message}
                </p>
            </div>
            <h2 className="text-white text-lg font-semibold mb-4">{t("Câu hỏi bảo mật")}</h2>
            {questions.map((q, i) => (
                <div key={q.id} className="w-full mb-3">
                    <p className="text-white/70 text-xs mb-1.5">{q.text}</p>
                    <input
                        type="text"
                        autoComplete="off"
                        className="w-full bg-white/10 border border-white/20 rounded-xl px-3 py-2.5 text-white text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                        value={answers[i] ?? ''}
                        onChange={(e) => setAnswers((prev) => { const n = [...prev]; n[i] = e.target.value; return n; })}
                        disabled={isDisabled || !!lockedUntil}
                    />
                </div>
            ))}
            <button
                type="submit"
                disabled={isDisabled || !!lockedUntil}
                className="w-full bg-blue-600 hover:bg-blue-500 disabled:opacity-50 text-white font-bold py-3 rounded-xl shadow-lg transition-all mt-1"
            >
                {isDisabled ? t('Đang kiểm tra...') : t('Xác nhận')}
            </button>
            {backButton(() => { setRecoverStep('choose'); clearMsg(); })}
        </form>
    );

    let content: React.ReactNode = null;
    if (mode === 'setup') {
        if (setupStep === 'draw-first') content = renderPattern(t('Vẽ mã mở khóa mới'));
        else if (setupStep === 'draw-confirm') content = renderPattern(t('Vẽ lại để xác nhận'));
        else if (setupStep === 'recovery-code') content = (
            <RecoveryCodeCard
                code={recoveryCode}
                onConfirm={handleRecoveryAcknowledged}
                confirmLabel={afterCodeAction === 'setup-questions' ? t('Tiếp tục') : t('Vào hệ thống')}
                intro={afterCodeAction === 'setup-questions'
                    ? t('Dùng mã này để đặt lại mật khẩu khi không có Internet.')
                    : t('Thiết bị chưa có mã khôi phục. Đây là mã mới của bạn.')}
            />
        );
        else if (setupStep === 'questions') content = (
            <div className="w-full max-w-[300px] flex flex-col items-center">
                <h2 className="text-white text-lg font-semibold mb-1">{t("Câu hỏi bảo mật")}</h2>
                <p className="text-white/50 text-xs mb-4 text-center leading-relaxed">
                    {t('Tuỳ chọn — thêm một cách đặt lại mật khẩu khi không có mạng. Bỏ qua thì bạn chỉ còn mã khôi phục.')}
                </p>
                <SecurityQuestionsForm
                    catalog={catalog}
                    onSubmit={submitQuestions}
                    onCancel={onSetupComplete}
                    cancelLabel={t("Bỏ qua")}
                    submitLabel={t("Lưu & hoàn tất")}
                    busy={isDisabled}
                />
            </div>
        );
    } else if (mode === 'verify') {
        content = (
            <div className="flex flex-col items-center">
                {renderPattern(t('Vẽ hình để mở khóa'))}
                <button
                    onClick={openRecovery}
                    className="mt-6 text-blue-400/80 text-sm hover:text-blue-300 transition-colors underline decoration-blue-400/30 underline-offset-4"
                >{t("Quên mật khẩu?")}</button>
            </div>
        );
    } else if (mode === 'recover') {
        if (recoverStep === 'choose') content = renderChoose();
        else if (recoverStep === 'code') content = renderCodeEntry();
        else if (recoverStep === 'questions') content = renderQuestionEntry();
        else if (recoverStep === 'new-draw') content = renderPattern(t('Vẽ mật mã mới'));
        else if (recoverStep === 'new-confirm') content = renderPattern(t('Xác nhận mã mới'));
        else if (recoverStep === 'recovery-code') content = (
            <RecoveryCodeCard
                code={recoveryCode}
                onConfirm={onUnlock}
                confirmLabel={t("Vào hệ thống")}
                intro={t("Mã khôi phục cũ đã hết hiệu lực. Đây là mã mới — hãy lưu lại.")}
            />
        );
    }

    return (
        <div className="fixed inset-0 z-[9999] flex flex-col items-center justify-center" style={{ background: 'linear-gradient(135deg, #0f172a 0%, #1e1b4b 50%, #0f172a 100%)' }}>
            <div className="absolute inset-0 overflow-hidden pointer-events-none">
                <div className="absolute rounded-full opacity-10" style={{ width: 600, height: 600, top: '-20%', left: '-15%', background: 'radial-gradient(circle, #6366f1 0%, transparent 70%)' }} />
                <div className="absolute rounded-full opacity-10" style={{ width: 400, height: 400, bottom: '-10%', right: '-10%', background: 'radial-gradient(circle, #3b82f6 0%, transparent 70%)' }} />
            </div>

            <div className="relative flex flex-col items-center px-8 py-10 rounded-3xl" style={{ background: 'rgba(255,255,255,0.05)', backdropFilter: 'blur(20px)', border: '1px solid rgba(255,255,255,0.1)', boxShadow: '0 25px 50px rgba(0,0,0,0.5)', minWidth: 320, minHeight: 480 }}>
                <div className="flex flex-col items-center mb-6">
                    <div className="w-16 h-16 rounded-2xl flex items-center justify-center mb-4 shadow-lg" style={{ background: 'linear-gradient(135deg, #ef4444 0%, #dc2626 100%)' }}>
                        <span className="material-icons text-white text-3xl">videocam</span>
                    </div>
                    <h1 className="text-white text-xl font-bold tracking-wide">NVR AI View</h1>
                    <p className="text-white/50 text-xs mt-1 tracking-widest uppercase">
                        {mode === 'setup' ? t('Thiết lập bảo mật') : mode === 'recover' ? t('Khôi phục mật khẩu') : t('Xác thực hệ thống')}
                    </p>
                </div>

                {content}

                <p className="text-white/25 text-xs mt-8 text-center max-w-[220px] leading-relaxed">
                    NVR v2.1 • Secured with AI View
                </p>
            </div>
            <p className="absolute bottom-4 text-white/20 text-xs">{t("Phát triển bởi AI View Team")}</p>
        </div>
    );
}

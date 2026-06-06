import { useState, useEffect, useRef } from 'react';
import PatternLock from './PatternLock';

interface PatternLockScreenProps {
    isSetupMode: boolean;
    onUnlock: () => void;
    onSetupComplete: () => void;
}

type SetupStep = 'draw-first' | 'draw-confirm' | 'email-entry' | 'otp-verify';
type ResetStep = 'email-entry' | 'otp-verify' | 'new-draw' | 'new-confirm';
type ScreenMode = 'setup' | 'verify' | 'reset';

export default function PatternLockScreen({ isSetupMode, onUnlock, onSetupComplete }: PatternLockScreenProps) {
    const [mode, setMode] = useState<ScreenMode>(isSetupMode ? 'setup' : 'verify');
    const [setupStep, setSetupStep] = useState<SetupStep>('draw-first');
    const [resetStep, setResetStep] = useState<ResetStep>('email-entry');

    const firstPatternRef = useRef<string>('');
    const [email, setEmail] = useState('');
    const [otp, setOtp] = useState('');

    const [message, setMessage] = useState('');
    const [msgType, setMsgType] = useState<'info' | 'success' | 'error'>('info');
    const [isDisabled, setIsDisabled] = useState(false);
    const [attempts, setAttempts] = useState(0);
    const [lockedUntil, setLockedUntil] = useState<number | null>(null);
    const [lockCountdown, setLockCountdown] = useState(0);
    const [resendCooldown, setResendCooldown] = useState(0);

    // Lockout countdown
    useEffect(() => {
        if (!lockedUntil) return;
        const interval = setInterval(() => {
            const remaining = Math.ceil((lockedUntil - Date.now()) / 1000);
            if (remaining <= 0) {
                setLockedUntil(null);
                setIsDisabled(false);
                setLockCountdown(0);
                showInfo(mode === 'verify' ? 'Hãy thử lại' : '');
                setAttempts(0);
            } else {
                setLockCountdown(remaining);
            }
        }, 500);
        return () => clearInterval(interval);
    }, [lockedUntil, mode]);

    // Resend cooldown countdown
    useEffect(() => {
        if (resendCooldown <= 0) return;
        const t = setTimeout(() => setResendCooldown(c => c - 1), 1000);
        return () => clearTimeout(t);
    }, [resendCooldown]);

    const showInfo = (msg: string) => { setMessage(msg); setMsgType('info'); };
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

    // ─── API HANDLERS ─────────────────────────────────────────────

    const requestOtp = async (targetEmail: string): Promise<boolean> => {
        setIsDisabled(true);
        showInfo('Đang gửi mã...');
        try {
            const res = await fetch('/api/lock/request-otp', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email: targetEmail }),
            });
            const data = await res.json();
            if (res.ok && data.success) {
                showSuccess('Mã xác thực đã được gửi đến Gmail!');
                setResendCooldown(60);
                return true;
            } else {
                showError(data.error || 'Không thể gửi email. Kiểm tra lại địa chỉ Gmail.');
                return false;
            }
        } catch {
            showError('Lỗi kết nối server. Vui lòng thử lại.');
            return false;
        } finally {
            setIsDisabled(false);
        }
    };

    const requestResetOtp = async (targetEmail: string): Promise<boolean> => {
        setIsDisabled(true);
        showInfo('Đang gửi mã...');
        try {
            const res = await fetch('/api/lock/reset-request', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email: targetEmail }),
            });
            const data = await res.json();
            if (res.ok && data.success) {
                showSuccess('Mã khôi phục đã được gửi đến Gmail!');
                setResendCooldown(60);
                return true;
            } else {
                showError(data.error || 'Gmail không đúng hoặc chưa đăng ký tài khoản.');
                return false;
            }
        } catch {
            showError('Lỗi kết nối server. Vui lòng thử lại.');
            return false;
        } finally {
            setIsDisabled(false);
        }
    };

    // ─── SETUP FLOW ───────────────────────────────────────────────

    const handleSetupPattern1 = (pattern: string) => {
        firstPatternRef.current = pattern;
        setSetupStep('draw-confirm');
        showInfo('Vẽ lại để xác nhận');
    };

    const handleSetupPattern2 = (pattern: string) => {
        if (pattern !== firstPatternRef.current) {
            firstPatternRef.current = '';
            setSetupStep('draw-first');
            showError('Hình không khớp! Vẽ lại từ đầu');
            return;
        }
        setSetupStep('email-entry');
        clearMsg();
    };

    const handleSetupEmailSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!email.includes('@')) return showError('Địa chỉ email không hợp lệ');
        const ok = await requestOtp(email);
        if (ok) { setOtp(''); setSetupStep('otp-verify'); }
    };

    const handleSetupOtpSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (otp.length !== 6) return showError('Mã phải có đúng 6 chữ số');

        setIsDisabled(true);
        showInfo('Đang xác thực...');
        try {
            const res = await fetch('/api/lock/setup', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ pattern: firstPatternRef.current, email, otp }),
            });
            const data = await res.json();
            if (res.ok && data.success) {
                showSuccess('✓ Thiết lập thành công!');
                setTimeout(() => onSetupComplete(), 800);
            } else {
                showError(data.error || 'Mã xác thực sai hoặc đã hết hạn');
            }
        } catch {
            showError('Lỗi kết nối server. Vui lòng thử lại.');
        } finally {
            setIsDisabled(false);
        }
    };

    // ─── VERIFY FLOW ──────────────────────────────────────────────

    const handleVerifyDraw = async (pattern: string) => {
        if (isDisabled || lockedUntil) return;
        setIsDisabled(true);
        try {
            const res = await fetch('/api/lock/verify', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ pattern }),
            });
            const data = await res.json();
            if (res.ok && data.success) {
                showSuccess('✓ Đã mở khóa');
                setTimeout(() => onUnlock(), 400);
            } else {
                const newAttempts = attempts + 1;
                setAttempts(newAttempts);
                if (newAttempts >= 5) {
                    const until = Date.now() + 60000;
                    setLockedUntil(until);
                    showError('Đã khóa 60 giây', 60000);
                } else {
                    showError(`Sai mật khẩu! Còn ${5 - newAttempts} lần thử`);
                }
            }
        } catch {
            showError('Lỗi kết nối server');
        } finally {
            setIsDisabled(false);
        }
    };

    // ─── RESET (FORGOT) FLOW ──────────────────────────────────────

    const handleResetEmailSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!email.includes('@')) return showError('Địa chỉ email không hợp lệ');
        const ok = await requestResetOtp(email);
        if (ok) { setOtp(''); setResetStep('otp-verify'); }
    };

    const handleResetOtpSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (otp.length !== 6) return showError('Mã phải có đúng 6 chữ số');

        setIsDisabled(true);
        showInfo('Đang xác thực mã...');
        try {
            const res = await fetch('/api/lock/verify-otp', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email, otp }),
            });
            const data = await res.json();
            if (res.ok && data.success) {
                showSuccess('✓ Mã hợp lệ!');
                setTimeout(() => { clearMsg(); setResetStep('new-draw'); }, 700);
            } else {
                showError(data.error || 'Mã không đúng hoặc đã hết hạn');
            }
        } catch {
            showError('Lỗi kết nối server. Vui lòng thử lại.');
        } finally {
            setIsDisabled(false);
        }
    };

    const handleResetNewPattern1 = (pattern: string) => {
        firstPatternRef.current = pattern;
        setResetStep('new-confirm');
        showInfo('Vẽ lại để xác nhận');
    };

    const handleResetNewPattern2 = async (pattern: string) => {
        if (pattern !== firstPatternRef.current) {
            firstPatternRef.current = '';
            setResetStep('new-draw');
            showError('Không khớp! Vẽ lại mã mới');
            return;
        }

        setIsDisabled(true);
        showInfo('Đang lưu...');
        try {
            const res = await fetch('/api/lock/reset-complete', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ pattern, email, otp }),
            });
            const data = await res.json();
            if (res.ok && data.success) {
                showSuccess('✓ Đổi mật khẩu thành công!');
                setTimeout(() => onUnlock(), 800);
            } else {
                showError(data.error || 'Lỗi khôi phục. Vui lòng thử lại.');
            }
        } catch {
            showError('Lỗi kết nối server. Vui lòng thử lại.');
        } finally {
            setIsDisabled(false);
        }
    };

    // ─── ROUTER ───────────────────────────────────────────────────

    const onPatternComplete = (pattern: string) => {
        if (mode === 'setup') {
            if (setupStep === 'draw-first') handleSetupPattern1(pattern);
            else if (setupStep === 'draw-confirm') handleSetupPattern2(pattern);
        } else if (mode === 'verify') {
            handleVerifyDraw(pattern);
        } else if (mode === 'reset') {
            if (resetStep === 'new-draw') handleResetNewPattern1(pattern);
            else if (resetStep === 'new-confirm') handleResetNewPattern2(pattern);
        }
    };

    const handleForgotClick = () => {
        setMode('reset');
        setResetStep('email-entry');
        setEmail('');
        setOtp('');
        setResendCooldown(0);
        clearMsg();
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
                    {lockedUntil ? `🔒 Khóa ${lockCountdown}s` : customMessage || message}
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

    const renderInput = (
        title: string,
        placeholder: string,
        value: string,
        onChange: (v: string) => void,
        onSubmit: (e: React.FormEvent) => void,
        btnLabel: string,
        onResend?: () => void,
        isOtp?: boolean,
    ) => (
        <form onSubmit={onSubmit} className="w-full max-w-[260px] flex flex-col items-center">
            <div className="h-8 flex items-center justify-center mb-2 text-center">
                <p className={`text-sm font-medium transition-colors ${msgColorClass}`}>{message}</p>
            </div>
            <h2 className="text-white text-lg font-semibold mb-4">{title}</h2>
            <input
                autoFocus
                type={isOtp ? 'text' : 'email'}
                inputMode={isOtp ? 'numeric' : undefined}
                maxLength={isOtp ? 6 : undefined}
                className="w-full bg-white/10 border border-white/20 rounded-xl px-4 py-3 text-white focus:outline-none focus:ring-2 focus:ring-blue-500 mb-4 transition-all"
                placeholder={placeholder}
                value={value}
                onChange={(e) => onChange(e.target.value)}
                disabled={isDisabled}
            />
            <button
                type="submit"
                disabled={isDisabled}
                className="w-full bg-blue-600 hover:bg-blue-500 disabled:opacity-50 text-white font-bold py-3 rounded-xl shadow-lg focus:ring-2 focus:ring-offset-2 focus:ring-offset-slate-900 transition-all"
            >
                {isDisabled ? 'Đang xử lý...' : btnLabel}
            </button>
            {onResend && (
                <button
                    type="button"
                    onClick={onResend}
                    disabled={resendCooldown > 0 || isDisabled}
                    className="mt-3 text-xs disabled:opacity-40 disabled:cursor-default text-blue-400/70 hover:text-blue-300 transition-colors"
                >
                    {resendCooldown > 0 ? `Gửi lại sau ${resendCooldown}s` : 'Gửi lại mã'}
                </button>
            )}
            <button
                type="button"
                onClick={() => {
                    if (mode === 'reset') { setMode('verify'); setResendCooldown(0); }
                    else if (mode === 'setup') { setSetupStep('draw-first'); setResendCooldown(0); }
                    clearMsg();
                }}
                className="mt-3 text-white/40 text-xs hover:text-white/60 underline"
            >
                Quay lại
            </button>
        </form>
    );

    let content;
    if (mode === 'setup') {
        if (setupStep === 'draw-first') content = renderPattern('Vẽ mã mở khóa mới');
        else if (setupStep === 'draw-confirm') content = renderPattern('Vẽ lại để xác nhận');
        else if (setupStep === 'email-entry') content = renderInput('Nhập Gmail bảo mật', 'example@gmail.com', email, setEmail, handleSetupEmailSubmit, 'Gửi mã xác thực');
        else if (setupStep === 'otp-verify') content = renderInput('Xác thực Gmail', '6 chữ số', otp, setOtp, handleSetupOtpSubmit, 'Hoàn tất thiết lập', () => requestOtp(email), true);
    } else if (mode === 'verify') {
        content = (
            <div className="flex flex-col items-center">
                {renderPattern('Vẽ hình để mở khóa')}
                {!lockedUntil && (
                    <button
                        onClick={handleForgotClick}
                        className="mt-6 text-blue-400/80 text-sm hover:text-blue-300 transition-colors underline decoration-blue-400/30 underline-offset-4"
                    >
                        Quên mật khẩu?
                    </button>
                )}
                <div className="flex gap-1.5 mt-4">
                    {Array.from({ length: 5 }).map((_, i) => (
                        <div key={i} className={`w-2 h-2 rounded-full transition-all ${i < attempts ? 'bg-red-400' : 'bg-white/20'}`} />
                    ))}
                </div>
            </div>
        );
    } else if (mode === 'reset') {
        if (resetStep === 'email-entry') content = renderInput('Khôi phục mật khẩu', 'Nhập Gmail đã đăng ký', email, setEmail, handleResetEmailSubmit, 'Gửi mã khôi phục');
        else if (resetStep === 'otp-verify') content = renderInput('Nhập mã xác thực', 'Nhập mã 6 số từ Gmail', otp, setOtp, handleResetOtpSubmit, 'Xác thực mã', () => requestResetOtp(email), true);
        else if (resetStep === 'new-draw') content = renderPattern('Vẽ mật mã mới');
        else if (resetStep === 'new-confirm') content = renderPattern('Xác nhận mã mới');
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
                        {mode === 'setup' ? 'Thiết lập bảo mật' : mode === 'reset' ? 'Khôi phục mật khẩu' : 'Xác thực hệ thống'}
                    </p>
                </div>

                {content}

                <p className="text-white/25 text-xs mt-8 text-center max-w-[220px] leading-relaxed">
                    NVR v2.1 • Secured with AI View
                </p>
            </div>
            <p className="absolute bottom-4 text-white/20 text-xs">Phát triển bởi AI View Team</p>
        </div>
    );
}

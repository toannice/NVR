import { useState, useEffect, useRef } from 'react';
import PatternLock from './PatternLock';

interface PatternLockScreenProps {
    isSetupMode: boolean;
    onUnlock: () => void;
    onSetupComplete: () => void;
}

type SetupStep = 'draw-first' | 'draw-confirm';

export default function PatternLockScreen({
    isSetupMode,
    onUnlock,
    onSetupComplete,
}: PatternLockScreenProps) {
    // Setup mode state
    const [setupStep, setSetupStep] = useState<SetupStep>('draw-first');
    const firstPatternRef = useRef<string>('');

    // Shared state
    const [message, setMessage] = useState('');
    const [isError, setIsError] = useState(false);
    const [isDisabled, setIsDisabled] = useState(false);
    const [attempts, setAttempts] = useState(0);
    const [lockedUntil, setLockedUntil] = useState<number | null>(null);
    const [lockCountdown, setLockCountdown] = useState(0);

    // Countdown timer for attempts lockout
    useEffect(() => {
        if (!lockedUntil) return;
        const interval = setInterval(() => {
            const remaining = Math.ceil((lockedUntil - Date.now()) / 1000);
            if (remaining <= 0) {
                setLockedUntil(null);
                setIsDisabled(false);
                setLockCountdown(0);
                setMessage('Hãy thử lại');
                setAttempts(0);
            } else {
                setLockCountdown(remaining);
            }
        }, 500);
        return () => clearInterval(interval);
    }, [lockedUntil]);

    const showError = (msg: string, durationMs = 1200) => {
        setMessage(msg);
        setIsError(true);
        setIsDisabled(true);
        setTimeout(() => {
            setIsError(false);
            setIsDisabled(false);
        }, durationMs);
    };

    // ─── SETUP MODE ───────────────────────────────────────────────
    const handleSetupFirstDraw = (pattern: string) => {
        firstPatternRef.current = pattern;
        setSetupStep('draw-confirm');
        setMessage('Vẽ lại để xác nhận');
        setIsError(false);
    };

    const handleSetupConfirmDraw = async (pattern: string) => {
        if (pattern !== firstPatternRef.current) {
            firstPatternRef.current = '';
            setSetupStep('draw-first');
            showError('Hình không khớp! Vẽ lại từ đầu');
            return;
        }

        setIsDisabled(true);
        try {
            const res = await fetch('/api/lock/setup', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ pattern }),
            });
            const data = await res.json();
            if (res.ok && data.success) {
                setMessage('✓ Thiết lập thành công!');
                setIsError(false);
                setTimeout(() => onSetupComplete(), 600);
            } else {
                showError(data.error || 'Lỗi thiết lập');
                setIsDisabled(false);
            }
        } catch {
            showError('Lỗi kết nối server');
            setIsDisabled(false);
        }
    };

    const handleSetupDraw = (pattern: string) => {
        if (setupStep === 'draw-first') {
            handleSetupFirstDraw(pattern);
        } else {
            handleSetupConfirmDraw(pattern);
        }
    };

    // ─── VERIFY MODE ──────────────────────────────────────────────
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
                setMessage('✓ Đã mở khóa');
                setIsError(false);
                setTimeout(() => onUnlock(), 400);
            } else {
                const newAttempts = attempts + 1;
                setAttempts(newAttempts);

                if (newAttempts >= 5) {
                    // Lock for 30 seconds
                    const until = Date.now() + 30000;
                    setLockedUntil(until);
                    setIsDisabled(true);
                    showError('Sai quá nhiều lần! Đợi 30 giây', 30000);
                } else {
                    showError(`Sai hình vẽ! Còn ${5 - newAttempts} lần thử`);
                }
            }
        } catch {
            showError('Lỗi kết nối server');
        }
    };

    // ─── INITIAL MESSAGES ─────────────────────────────────────────
    useEffect(() => {
        if (isSetupMode) {
            setMessage('Vẽ hình mở khóa của bạn');
        } else {
            setMessage('Vẽ hình để mở khóa');
        }
    }, [isSetupMode]);

    const onPatternComplete = isSetupMode ? handleSetupDraw : handleVerifyDraw;

    // Progress dots for setup
    const stepIndicator = isSetupMode && (
        <div className="flex gap-2 mb-2">
            <div
                className={`h-2 w-8 rounded-full transition-all duration-300 ${setupStep === 'draw-first' ? 'bg-blue-400' : 'bg-blue-400/30'
                    }`}
            />
            <div
                className={`h-2 w-8 rounded-full transition-all duration-300 ${setupStep === 'draw-confirm' ? 'bg-blue-400' : 'bg-blue-400/30'
                    }`}
            />
        </div>
    );

    return (
        <div
            className="fixed inset-0 z-[9999] flex flex-col items-center justify-center"
            style={{
                background: 'linear-gradient(135deg, #0f172a 0%, #1e1b4b 50%, #0f172a 100%)',
            }}
        >
            {/* Decorative background circles */}
            <div className="absolute inset-0 overflow-hidden pointer-events-none">
                <div
                    className="absolute rounded-full opacity-10"
                    style={{
                        width: 600,
                        height: 600,
                        top: '-20%',
                        left: '-15%',
                        background: 'radial-gradient(circle, #6366f1 0%, transparent 70%)',
                    }}
                />
                <div
                    className="absolute rounded-full opacity-10"
                    style={{
                        width: 400,
                        height: 400,
                        bottom: '-10%',
                        right: '-10%',
                        background: 'radial-gradient(circle, #3b82f6 0%, transparent 70%)',
                    }}
                />
            </div>

            {/* Glassmorphism card */}
            <div
                className="relative flex flex-col items-center px-8 py-10 rounded-3xl"
                style={{
                    background: 'rgba(255,255,255,0.05)',
                    backdropFilter: 'blur(20px)',
                    border: '1px solid rgba(255,255,255,0.1)',
                    boxShadow: '0 25px 50px rgba(0,0,0,0.5)',
                    minWidth: 320,
                }}
            >
                {/* Logo / Title */}
                <div className="flex flex-col items-center mb-6">
                    <div
                        className="w-16 h-16 rounded-2xl flex items-center justify-center mb-4 shadow-lg"
                        style={{ background: 'linear-gradient(135deg, #ef4444 0%, #dc2626 100%)' }}
                    >
                        <span className="material-icons text-white text-3xl">videocam</span>
                    </div>
                    <h1 className="text-white text-xl font-bold tracking-wide">NVR AI View</h1>
                    <p className="text-white/50 text-xs mt-1 tracking-widest uppercase">
                        {isSetupMode ? 'Thiết lập mật khẩu' : 'Xác thực danh tính'}
                    </p>
                </div>

                {/* Step indicator */}
                {stepIndicator}

                {/* Message */}
                <div className="h-8 flex items-center justify-center mb-2">
                    <p
                        className={`text-sm font-medium transition-all duration-200 ${isError ? 'text-red-400' : lockedUntil ? 'text-yellow-400' : 'text-blue-300'
                            }`}
                    >
                        {lockedUntil
                            ? `🔒 Thử lại sau ${lockCountdown}s`
                            : message}
                    </p>
                </div>

                {/* Pattern Lock Grid */}
                <div
                    className={`${isError ? 'animate-shake' : ''}`}
                    style={{ width: 260, height: 260 }}
                >
                    <PatternLock
                        size={260}
                        onComplete={onPatternComplete}
                        error={isError}
                        disabled={isDisabled || !!lockedUntil}
                    />
                </div>

                {/* Attempt dots (verify mode) */}
                {!isSetupMode && (
                    <div className="flex gap-1.5 mt-3">
                        {Array.from({ length: 5 }).map((_, i) => (
                            <div
                                key={i}
                                className={`w-2 h-2 rounded-full transition-all duration-300 ${i < attempts ? 'bg-red-400 scale-110' : 'bg-white/20'
                                    }`}
                            />
                        ))}
                    </div>
                )}

                {/* Hint text */}
                <p className="text-white/25 text-xs mt-4 text-center max-w-[220px] leading-relaxed">
                    {isSetupMode
                        ? setupStep === 'draw-first'
                            ? 'Kéo qua ít nhất 3 điểm để tạo hình mở khóa'
                            : 'Vẽ lại đúng hình vừa tạo để xác nhận'
                        : 'Kéo qua các điểm theo đúng hình đã tạo'}
                </p>
            </div>

            {/* Version tag */}
            <p className="absolute bottom-4 text-white/20 text-xs">NVR v2.0 • Secured</p>
        </div>
    );
}

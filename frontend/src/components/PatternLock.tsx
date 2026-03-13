import { useRef, useEffect, useCallback, useState } from 'react';

interface Point {
    x: number;
    y: number;
    index: number;
}

interface PatternLockProps {
    onComplete: (pattern: string) => void;
    error?: boolean;
    disabled?: boolean;
    size?: number;
}

// Grid positions: 3x3 = 9 dots, indices 0-8
const DOT_POSITIONS = [
    { col: 0, row: 0 }, { col: 1, row: 0 }, { col: 2, row: 0 },
    { col: 0, row: 1 }, { col: 1, row: 1 }, { col: 2, row: 1 },
    { col: 0, row: 2 }, { col: 1, row: 2 }, { col: 2, row: 2 },
];

const COLORS = {
    dot: 'rgba(255,255,255,0.25)',
    dotSelected: '#60A5FA',
    dotRing: 'rgba(96,165,250,0.3)',
    line: 'rgba(96,165,250,0.7)',
    dotError: '#EF4444',
    lineError: 'rgba(239,68,68,0.7)',
    dotRingError: 'rgba(239,68,68,0.3)',
    dotCenter: '#fff',
};

export default function PatternLock({
    onComplete,
    error = false,
    disabled = false,
    size = 300,
}: PatternLockProps) {
    const canvasRef = useRef<HTMLCanvasElement>(null);
    const selectedRef = useRef<number[]>([]);
    const isDrawingRef = useRef(false);
    const currentPosRef = useRef<{ x: number; y: number } | null>(null);
    const [shakeKey, setShakeKey] = useState(0);

    const getDotRadius = () => size * 0.065;
    const getPadding = () => size * 0.13;

    // Compute dot center coordinates from col/row
    const getDotCenters = useCallback((): Point[] => {
        const pad = getPadding();
        const step = (size - pad * 2) / 2;
        return DOT_POSITIONS.map((pos, i) => ({
            x: pad + pos.col * step,
            y: pad + pos.row * step,
            index: i,
        }));
    }, [size]);

    const getHitDot = useCallback(
        (cx: number, cy: number): number | null => {
            const r = getDotRadius() * 1.8; // slightly larger hit area
            for (const dot of getDotCenters()) {
                const dx = dot.x - cx;
                const dy = dot.y - cy;
                if (Math.sqrt(dx * dx + dy * dy) <= r) return dot.index;
            }
            return null;
        },
        [getDotCenters]
    );

    const draw = useCallback(() => {
        const canvas = canvasRef.current;
        if (!canvas) return;
        const ctx = canvas.getContext('2d');
        if (!ctx) return;

        ctx.clearRect(0, 0, size, size);

        const dots = getDotCenters();
        const selected = selectedRef.current;
        const isError = error;
        const r = getDotRadius();

        const lineColor = isError ? COLORS.lineError : COLORS.line;
        const dotColor = isError ? COLORS.dotError : COLORS.dotSelected;
        const ringColor = isError ? COLORS.dotRingError : COLORS.dotRing;

        // Draw connecting lines between selected dots
        if (selected.length > 1) {
            ctx.beginPath();
            ctx.strokeStyle = lineColor;
            ctx.lineWidth = 2.5;
            ctx.lineCap = 'round';
            ctx.lineJoin = 'round';
            ctx.globalAlpha = 0.8;
            const first = dots[selected[0]];
            ctx.moveTo(first.x, first.y);
            for (let i = 1; i < selected.length; i++) {
                const d = dots[selected[i]];
                ctx.lineTo(d.x, d.y);
            }
            ctx.stroke();
            ctx.globalAlpha = 1;
        }

        // Draw trailing line to current pointer position
        if (isDrawingRef.current && selected.length > 0 && currentPosRef.current) {
            const last = dots[selected[selected.length - 1]];
            ctx.beginPath();
            ctx.strokeStyle = lineColor;
            ctx.lineWidth = 2;
            ctx.globalAlpha = 0.5;
            ctx.moveTo(last.x, last.y);
            ctx.lineTo(currentPosRef.current.x, currentPosRef.current.y);
            ctx.stroke();
            ctx.globalAlpha = 1;
        }

        // Draw dots
        dots.forEach((dot) => {
            const isSelected = selected.includes(dot.index);

            // Outer glow ring for selected dots
            if (isSelected) {
                ctx.beginPath();
                ctx.arc(dot.x, dot.y, r * 1.9, 0, Math.PI * 2);
                ctx.fillStyle = ringColor;
                ctx.fill();
            }

            // Main dot body
            ctx.beginPath();
            ctx.arc(dot.x, dot.y, r, 0, Math.PI * 2);
            ctx.fillStyle = isSelected ? dotColor : COLORS.dot;
            ctx.fill();

            // Center bright dot
            ctx.beginPath();
            ctx.arc(dot.x, dot.y, r * 0.38, 0, Math.PI * 2);
            ctx.fillStyle = isSelected ? COLORS.dotCenter : 'rgba(255,255,255,0.5)';
            ctx.fill();
        });
    }, [getDotCenters, error, size]);

    // Redraw on every frame while drawing, or on state changes
    useEffect(() => {
        draw();
    }, [draw, shakeKey]);

    // Trigger shake animation on error
    useEffect(() => {
        if (error) {
            setShakeKey((k) => k + 1);
        }
    }, [error]);

    const getCanvasPos = (e: React.MouseEvent | React.TouchEvent): { x: number; y: number } | null => {
        const canvas = canvasRef.current;
        if (!canvas) return null;
        const rect = canvas.getBoundingClientRect();
        const scaleX = size / rect.width;
        const scaleY = size / rect.height;

        if ('touches' in e) {
            const touch = e.touches[0] || e.changedTouches[0];
            if (!touch) return null;
            return {
                x: (touch.clientX - rect.left) * scaleX,
                y: (touch.clientY - rect.top) * scaleY,
            };
        }
        return {
            x: (e.clientX - rect.left) * scaleX,
            y: (e.clientY - rect.top) * scaleY,
        };
    };

    const handleStart = useCallback(
        (e: React.MouseEvent | React.TouchEvent) => {
            if (disabled) return;
            e.preventDefault();
            const pos = getCanvasPos(e);
            if (!pos) return;

            const hit = getHitDot(pos.x, pos.y);
            if (hit === null) return;

            isDrawingRef.current = true;
            selectedRef.current = [hit];
            currentPosRef.current = pos;
            draw();
        },
        [disabled, getHitDot, draw]
    );

    const handleMove = useCallback(
        (e: React.MouseEvent | React.TouchEvent) => {
            if (!isDrawingRef.current || disabled) return;
            e.preventDefault();
            const pos = getCanvasPos(e);
            if (!pos) return;

            currentPosRef.current = pos;

            const hit = getHitDot(pos.x, pos.y);
            if (hit !== null && !selectedRef.current.includes(hit)) {
                selectedRef.current = [...selectedRef.current, hit];
            }

            draw();
        },
        [disabled, getHitDot, draw]
    );

    const handleEnd = useCallback(
        (e: React.MouseEvent | React.TouchEvent) => {
            if (!isDrawingRef.current) return;
            e.preventDefault();
            isDrawingRef.current = false;
            currentPosRef.current = null;

            const pattern = selectedRef.current.join('-');
            draw();

            if (selectedRef.current.length >= 3) {
                onComplete(pattern);
            }

            // Reset after short delay
            setTimeout(() => {
                if (!error) {
                    selectedRef.current = [];
                    draw();
                }
            }, 400);
        },
        [onComplete, draw, error]
    );

    // Reset dots on error change (after showing error briefly)
    useEffect(() => {
        if (!error) {
            selectedRef.current = [];
            draw();
        }
    }, [error, draw]);

    return (
        <canvas
            ref={canvasRef}
            width={size}
            height={size}
            className={`touch-none select-none cursor-pointer ${error ? 'animate-shake' : ''}`}
            style={{ width: '100%', maxWidth: size, maxHeight: size, aspectRatio: '1/1' }}
            onMouseDown={handleStart}
            onMouseMove={handleMove}
            onMouseUp={handleEnd}
            onMouseLeave={handleEnd}
            onTouchStart={handleStart}
            onTouchMove={handleMove}
            onTouchEnd={handleEnd}
        />
    );
}

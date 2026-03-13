/**
 * BoundingBoxCanvas Component
 *
 * Renders a transparent <canvas> absolutely positioned over the
 * video player. Draws AI detection bounding boxes from Frigate
 * MQTT events in real-time using requestAnimationFrame.
 *
 * Features:
 * - Color-coded boxes per label (person=red, car=blue, etc.)
 * - Semi-transparent fill + label + score badge
 * - GPU-composited (pointer-events: none — doesn't block video interactions)
 * - Fades out boxes as they age
 */
import { useEffect, useRef } from 'react';
import type { Detection } from '../hooks/useMqttEvents';

interface BoundingBoxCanvasProps {
    cameraId: string;
    detections: Detection[];
    enabled?: boolean;
}

const LABEL_COLORS: Record<string, string> = {
    person: '#EF4444', // Red
    car: '#3B82F6', // Blue
    truck: '#8B5CF6', // Purple
    bicycle: '#F59E0B', // Amber
    dog: '#10B981', // Green
    cat: '#EC4899', // Pink
    default: '#F97316', // Orange
};

function getLabelColor(label: string): string {
    return LABEL_COLORS[label.toLowerCase()] ?? LABEL_COLORS.default;
}

export default function BoundingBoxCanvas({ detections, enabled = true }: BoundingBoxCanvasProps) {
    const canvasRef = useRef<HTMLCanvasElement>(null);
    const animRef = useRef<number>(0);

    useEffect(() => {
        const canvas = canvasRef.current;
        if (!canvas) return;
        const ctx = canvas.getContext('2d');
        if (!ctx) return;

        const draw = () => {
            // Keep canvas sized to its CSS layout size
            const { offsetWidth: w, offsetHeight: h } = canvas;
            if (canvas.width !== w || canvas.height !== h) {
                canvas.width = w;
                canvas.height = h;
            }

            ctx.clearRect(0, 0, w, h);

            if (!enabled || detections.length === 0) {
                animRef.current = requestAnimationFrame(draw);
                return;
            }

            const now = Date.now();

            for (const det of detections) {
                const [nx, ny, nw, nh] = det.box;

                // Bounding box pixel coords
                const x = nx * w;
                const y = ny * h;
                const bw = nw * w;
                const bh = nh * h;

                // Fade based on age (0→1 over first 300ms, then 1 until stale)
                const age = now - det.timestamp;
                const alpha = Math.min(1, age / 300);

                const color = getLabelColor(det.label);

                // Semi-transparent fill
                ctx.globalAlpha = alpha * 0.15;
                ctx.fillStyle = color;
                ctx.fillRect(x, y, bw, bh);

                // Border stroke
                ctx.globalAlpha = alpha * 0.9;
                ctx.strokeStyle = color;
                ctx.lineWidth = 2;
                ctx.strokeRect(x, y, bw, bh);

                // Label background pill
                const labelText = `${det.label} ${Math.round(det.score * 100)}%`;
                ctx.font = 'bold 11px Inter, system-ui, sans-serif';
                const textMetrics = ctx.measureText(labelText);
                const pillW = textMetrics.width + 10;
                const pillH = 18;
                const pillX = x;
                const pillY = y - pillH - 2 < 0 ? y + 2 : y - pillH - 2;

                ctx.fillStyle = color;
                ctx.globalAlpha = alpha;
                ctx.beginPath();
                ctx.roundRect(pillX, pillY, pillW, pillH, 4);
                ctx.fill();

                // Label text
                ctx.fillStyle = '#FFFFFF';
                ctx.globalAlpha = alpha;
                ctx.fillText(labelText, pillX + 5, pillY + pillH - 4);
            }

            ctx.globalAlpha = 1;
            animRef.current = requestAnimationFrame(draw);
        };

        draw();
        return () => cancelAnimationFrame(animRef.current);
    }, [detections, enabled]);

    return (
        <canvas
            ref={canvasRef}
            style={{
                position: 'absolute',
                inset: 0,
                width: '100%',
                height: '100%',
                pointerEvents: 'none',        // Click-through: doesn't block video
                zIndex: 30,
            }}
        />
    );
}

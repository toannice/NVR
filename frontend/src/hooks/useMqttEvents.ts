/**
 * useMqttEvents Hook
 *
 * Connects to the backend MQTT→WebSocket bridge (port 3001)
 * and exposes real-time AI detection data for canvas rendering.
 *
 * Usage:
 *   const { detections, isConnected } = useMqttEvents();
 *   // detections = Map<cameraId, Detection[]>
 */
import { useState, useEffect, useRef, useCallback } from 'react';

export interface Detection {
    eventId: string;
    label: string;
    score: number;
    box: [number, number, number, number]; // [x, y, width, height] normalized 0–1
    timestamp: number;
    attributes?: Record<string, string>;
}

type DetectionMap = Map<string, Map<string, Detection>>;

function getWsUrl(): string {
    const { hostname, port, protocol } = window.location;
    const wsProtocol = protocol === 'https:' ? 'wss:' : 'ws:';
    return `${wsProtocol}//${hostname}:${port}/ws/mqtt`;
}

export function useMqttEvents() {
    const [detections, setDetections] = useState<DetectionMap>(new Map());
    const [isConnected, setIsConnected] = useState(false);
    const wsRef = useRef<WebSocket | null>(null);
    const reconnectTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

    const connect = useCallback(() => {
        if (wsRef.current?.readyState === WebSocket.OPEN) return;

        const url = getWsUrl();
        const ws = new WebSocket(url);
        wsRef.current = ws;

        ws.onopen = () => {
            setIsConnected(true);
            if (reconnectTimer.current) {
                clearTimeout(reconnectTimer.current);
                reconnectTimer.current = null;
            }
        };

        ws.onclose = () => {
            setIsConnected(false);
            // Auto-reconnect after 3 seconds
            reconnectTimer.current = setTimeout(connect, 3000);
        };

        ws.onerror = () => {
            ws.close();
        };

        ws.onmessage = (event) => {
            try {
                const msg = JSON.parse(event.data);

                if (msg.type === 'detection') {
                    setDetections(prev => {
                        const next = new Map(prev);
                        if (!next.has(msg.camera)) {
                            next.set(msg.camera, new Map());
                        }
                        next.get(msg.camera)!.set(msg.eventId, {
                            eventId: msg.eventId,
                            label: msg.label,
                            score: msg.score,
                            box: msg.box,
                            timestamp: msg.timestamp,
                            attributes: msg.attributes,
                        });
                        return next;
                    });
                } else if (msg.type === 'event_end') {
                    setDetections(prev => {
                        const next = new Map(prev);
                        next.get(msg.camera)?.delete(msg.eventId);
                        return next;
                    });
                }
            } catch {
                // malformed message, ignore
            }
        };
    }, []);

    useEffect(() => {
        connect();
        return () => {
            if (reconnectTimer.current) clearTimeout(reconnectTimer.current);
            wsRef.current?.close();
        };
    }, [connect]);

    // Clear stale detections (older than 5 seconds)
    useEffect(() => {
        const cleanup = setInterval(() => {
            const now = Date.now();
            setDetections(prev => {
                let changed = false;
                const next = new Map(prev);
                next.forEach((eventMap, camera) => {
                    const newEventMap = new Map(eventMap);
                    newEventMap.forEach((det, eventId) => {
                        if (now - det.timestamp > 5000) {
                            newEventMap.delete(eventId);
                            changed = true;
                        }
                    });
                    if (newEventMap.size === 0) {
                        next.delete(camera);
                    } else {
                        next.set(camera, newEventMap);
                    }
                });
                return changed ? next : prev;
            });
        }, 2000);

        return () => clearInterval(cleanup);
    }, []);

    // Helper to get detections for a specific camera as array
    const getCameraDetections = useCallback((cameraId: string): Detection[] => {
        return Array.from(detections.get(cameraId)?.values() ?? []);
    }, [detections]);

    return { detections, isConnected, getCameraDetections };
}

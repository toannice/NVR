import { useEffect, useRef, useState } from 'react';

interface WebRTCPlayerProps {
    name: string;
    className?: string;
    onClick?: () => void;
}

export const WebRTCPlayer = ({ name, className, onClick }: WebRTCPlayerProps) => {
    const videoRef = useRef<HTMLVideoElement>(null);
    const pcRef = useRef<RTCPeerConnection | null>(null);
    const [error, setError] = useState<string>('');
    const [isConnecting, setIsConnecting] = useState(true);

    useEffect(() => {
        let pc: RTCPeerConnection | null = null;

        const startStream = async () => {
            try {
                setIsConnecting(true);
                setError('');

                console.log(`[WebRTC] Starting stream for ${name}`);

                // Create RTCPeerConnection
                pc = new RTCPeerConnection({
                    iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
                });
                pcRef.current = pc;

                // Add transceiver for receiving video
                pc.addTransceiver('video', { direction: 'recvonly' });
                pc.addTransceiver('audio', { direction: 'recvonly' });

                // Handle incoming stream
                pc.ontrack = (event) => {
                    console.log(`[WebRTC] Received track for ${name}:`, event.track.kind);
                    if (videoRef.current && event.streams[0]) {
                        videoRef.current.srcObject = event.streams[0];
                        videoRef.current.play().catch(e => console.log('[WebRTC] Autoplay blocked:', e));
                        setIsConnecting(false);
                    }
                };

                pc.onconnectionstatechange = () => {
                    console.log(`[WebRTC] Connection state for ${name}:`, pc?.connectionState);
                    if (pc?.connectionState === 'failed' || pc?.connectionState === 'closed') {
                        setError('Connection failed');
                        setIsConnecting(false);
                    }
                };

                // Create offer
                const offer = await pc.createOffer();
                await pc.setLocalDescription(offer);

                // Send offer to go2rtc via proxy (to avoid CORS)
                console.log(`[WebRTC] Sending offer to go2rtc for ${name}`);
                const response = await fetch(`/api/frigate/api/go2rtc/webrtc?src=${name}`, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                    },
                    body: JSON.stringify({
                        type: offer.type,
                        sdp: offer.sdp
                    })
                });

                if (!response.ok) {
                    throw new Error(`HTTP ${response.status}: ${await response.text()}`);
                }

                const answer = await response.json();
                console.log(`[WebRTC] Received answer for ${name}`);

                // Set remote description
                await pc.setRemoteDescription(new RTCSessionDescription(answer));

                console.log(`[WebRTC] Stream started successfully for ${name}`);

            } catch (err: any) {
                console.error(`[WebRTC] Error for ${name}:`, err);
                setError(err.message || 'Failed to start stream');
                setIsConnecting(false);
            }
        };

        startStream();

        return () => {
            console.log(`[WebRTC] Cleanup for ${name}`);
            if (pc) {
                pc.close();
                pcRef.current = null;
            }
            if (videoRef.current) {
                videoRef.current.srcObject = null;
            }
        };
    }, [name]);

    return (
        <div className="relative w-full h-full">
            <video
                ref={videoRef}
                className={className}
                autoPlay
                playsInline
                muted
                onClick={onClick}
                style={{ objectFit: 'cover', background: 'black' }}
            />
            {isConnecting && !error && (
                <div className="absolute inset-0 flex items-center justify-center bg-black/60 text-white text-xs">
                    <div className="flex flex-col items-center gap-2">
                        <div className="w-6 h-6 border-2 border-white border-t-transparent rounded-full animate-spin"></div>
                        <span>Connecting...</span>
                    </div>
                </div>
            )}
            {error && (
                <div className="absolute inset-0 flex items-center justify-center bg-black/80 text-red-400 text-xs">
                    <div className="flex flex-col items-center gap-2">
                        <span className="material-icons text-2xl">error_outline</span>
                        <span>{error}</span>
                    </div>
                </div>
            )}
        </div>
    );
};

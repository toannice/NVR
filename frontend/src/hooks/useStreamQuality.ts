import { useState, useEffect } from 'react';

type StreamQuality = 'main' | 'sub';
type StreamProtocol = 'webrtc' | 'hls';

export interface StreamConfig {
    quality: StreamQuality;
    protocol: StreamProtocol;
    streamName: string;
    url: string;
}

/**
 * useStreamQuality
 * Dynamically switches video streams based on the current viewing context to save bandwidth/CPU
 * - Fullscreen / Single view -> main stream (WebRTC)
 * - Grid multi-view -> sub stream (WebRTC/HLS)
 * Includes progressive fallback: WebRTC Main -> WebRTC Sub -> HLS Sub
 */
export function useStreamQuality(
    cameraId: string,
    isFullscreen: boolean,
    isGrid: boolean
) {
    const [config, setConfig] = useState<StreamConfig>({
        quality: isFullscreen ? 'main' : 'sub',
        protocol: 'webrtc',
        streamName: cameraId,
        url: ''
    });

    // Handle stream switching logic when viewing context changes
    useEffect(() => {
        const quality: StreamQuality = isFullscreen ? 'main' : 'sub';
        // Stick to cameraId as the stream name in go2rtc for now
        const streamName = cameraId;

        setConfig(prev => ({
            ...prev,
            quality,
            streamName,
        }));
    }, [cameraId, isFullscreen, isGrid]);

    // Format the URL based on the current config
    useEffect(() => {
        let url = '';
        if (config.protocol === 'webrtc') {
            // Correct Frigate 0.16 WebRTC endpoint
            url = `/api/frigate/live/webrtc?src=${config.streamName}`;
        } else {
            // Correct Frigate 0.16 HLS endpoint
            url = `/api/frigate/live/hls/${config.streamName}/index.m3u8`;
        }

        setConfig(prev => ({ ...prev, url }));
    }, [config.protocol, config.streamName]);

    // Trigger fallback to HLS if WebRTC fails
    const handleStreamError = () => {
        setConfig(prev => {
            // If already on HLS, we stop trying to fallback further
            if (prev.protocol === 'hls') return prev;

            console.warn(`[Stream] WebRTC failed for ${prev.streamName}. Falling back to HLS...`);

            // Fallback: WebRTC -> HLS (same stream name)
            return {
                ...prev,
                protocol: 'hls'
            };
        });
    };

    return {
        streamConfig: config,
        handleStreamError
    };
}

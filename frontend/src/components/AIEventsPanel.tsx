import { useState, useEffect, useCallback } from 'react';

const Icon = ({ name, className = "" }: { name: string; className?: string }) => (
    <span className={`material-icons ${className}`}>{name}</span>
);

interface EventItem {
    id: string;
    camera: string;
    camera_name: string;
    label: string;
    score: number | null;
    start_time: number;   // epoch sec
    end_time: number | null;
    source: string;       // "box" | "frigate"
    clip_status: string | null;
    has_clip: boolean;
    has_snapshot: boolean;
    clip_url: string | null;
    snapshot_url: string | null;
}

interface AIEventsPanelProps {
    cameras: any[];
}

const fmtTime = (sec: number) => new Date(sec * 1000).toLocaleString('vi-VN');

const AIEventsPanel = ({ cameras }: AIEventsPanelProps) => {
    const [events, setEvents] = useState<EventItem[]>([]);
    const [loading, setLoading] = useState(false);
    const [cameraFilter, setCameraFilter] = useState<string>('');
    const [viewSource, setViewSource] = useState<'all' | 'box' | 'frigate'>('all');
    const [playing, setPlaying] = useState<EventItem | null>(null);

    const fetchEvents = useCallback(async () => {
        setLoading(true);
        try {
            let url = `/api/events?limit=200&source=${viewSource}`;
            if (cameraFilter) url += `&camera=${encodeURIComponent(cameraFilter)}`;
            const r = await fetch(url);
            if (r.ok) setEvents(await r.json());
        } catch (e) {
            console.error(e);
        } finally {
            setLoading(false);
        }
    }, [cameraFilter, viewSource]);

    useEffect(() => { fetchEvents(); }, [fetchEvents]);

    // Auto-refresh so clips that were still "processing" flip to ready.
    useEffect(() => {
        const t = setInterval(fetchEvents, 15000);
        return () => clearInterval(t);
    }, [fetchEvents]);

    return (
        <div className="h-full flex flex-col p-4 text-gray-800 dark:text-gray-200">
            {/* Toolbar */}
            <div className="flex flex-wrap items-center gap-3 mb-4">
                <h2 className="font-bold text-lg mr-auto">Sự kiện AI</h2>

                {/* Nguồn xem — đổi ?source= (không ảnh hưởng chế độ lưu trữ 5B) */}
                <div className="flex rounded-lg overflow-hidden border border-gray-300 dark:border-gray-600 text-sm">
                    {([
                        { id: 'all', label: 'Tất cả' },
                        { id: 'box', label: 'Box AI' },
                        { id: 'frigate', label: 'Frigate' },
                    ] as const).map(s => (
                        <button
                            key={s.id}
                            onClick={() => setViewSource(s.id)}
                            className={`px-3 py-1.5 font-medium ${
                                viewSource === s.id
                                    ? 'bg-[#EF4444] text-white'
                                    : 'bg-white dark:bg-[#374151] text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-600'
                            }`}
                        >
                            {s.label}
                        </button>
                    ))}
                </div>

                <select
                    value={cameraFilter}
                    onChange={e => setCameraFilter(e.target.value)}
                    className="bg-white dark:bg-[#374151] border border-gray-300 dark:border-gray-600 rounded px-3 py-1.5 text-sm"
                >
                    <option value="">Tất cả camera</option>
                    {cameras.map((c: any) => (
                        <option key={c.id} value={c.id}>{c.name || c.id}</option>
                    ))}
                </select>

                <button onClick={fetchEvents} className="p-1.5 rounded hover:bg-gray-100 dark:hover:bg-gray-700" title="Làm mới">
                    <Icon name="refresh" />
                </button>
            </div>

            {/* List */}
            <div className="flex-1 overflow-y-auto custom-scrollbar">
                {loading && events.length === 0 ? (
                    <div className="text-center text-gray-500 py-10">Đang tải…</div>
                ) : events.length === 0 ? (
                    <div className="text-center text-gray-500 py-10">Không có sự kiện nào</div>
                ) : (
                    <div className="grid gap-3 [grid-template-columns:repeat(auto-fill,minmax(260px,1fr))]">
                        {events.map(evt => (
                            <div key={evt.id} className="bg-white dark:bg-[#1F2937] border border-gray-200 dark:border-gray-700 rounded-lg overflow-hidden">
                                <div className="relative aspect-video bg-gray-200 dark:bg-gray-800">
                                    {evt.snapshot_url ? (
                                        <img src={evt.snapshot_url} alt={evt.label} loading="lazy" className="w-full h-full object-cover" />
                                    ) : (
                                        <div className="w-full h-full flex items-center justify-center text-gray-400">
                                            <Icon name="image_not_supported" className="text-3xl" />
                                        </div>
                                    )}
                                    <span className={`absolute top-2 left-2 text-xs px-1.5 py-0.5 rounded font-semibold ${
                                        evt.source === 'box' ? 'bg-[#EF4444] text-white' : 'bg-gray-700 text-white'
                                    }`}>
                                        {evt.source === 'box' ? 'BOX AI' : 'FRIGATE'}
                                    </span>
                                    {evt.has_clip && (
                                        <button
                                            onClick={() => setPlaying(evt)}
                                            className="absolute inset-0 flex items-center justify-center bg-black/0 hover:bg-black/30 transition-colors"
                                        >
                                            <Icon name="play_circle" className="text-white text-5xl drop-shadow-lg" />
                                        </button>
                                    )}
                                    {evt.clip_status === 'processing' && (
                                        <span className="absolute bottom-2 right-2 text-xs px-1.5 py-0.5 rounded bg-amber-500 text-white">Đang cắt clip…</span>
                                    )}
                                    {evt.clip_status === 'failed' && (
                                        <span className="absolute bottom-2 right-2 text-xs px-1.5 py-0.5 rounded bg-gray-500 text-white">Không có clip</span>
                                    )}
                                </div>
                                <div className="p-3">
                                    <div className="font-semibold text-sm truncate" title={evt.label}>{evt.label}</div>
                                    <div className="text-xs text-gray-500 mt-1 flex items-center gap-1">
                                        <Icon name="videocam" className="text-sm" /> {evt.camera_name}
                                    </div>
                                    <div className="text-xs text-gray-400 mt-0.5">{fmtTime(evt.start_time)}</div>
                                </div>
                            </div>
                        ))}
                    </div>
                )}
            </div>

            {/* Clip player modal */}
            {playing && (
                <div className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4" onClick={() => setPlaying(null)}>
                    <div className="bg-[#1F2937] rounded-lg overflow-hidden max-w-3xl w-full" onClick={e => e.stopPropagation()}>
                        <div className="flex items-center justify-between px-4 py-2 border-b border-gray-700">
                            <span className="text-white text-sm font-medium truncate">{playing.label} — {playing.camera_name}</span>
                            <button onClick={() => setPlaying(null)} className="text-gray-400 hover:text-white"><Icon name="close" /></button>
                        </div>
                        {/* eslint-disable-next-line jsx-a11y/media-has-caption */}
                        <video src={playing.clip_url!} controls autoPlay className="w-full bg-black max-h-[70vh]" />
                    </div>
                </div>
            )}
        </div>
    );
};

export default AIEventsPanel;

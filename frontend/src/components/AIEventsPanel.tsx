import { useState, useEffect, useCallback } from 'react';
import { useModalKeys } from '../hooks/useModalKeys';
import { t, locale, useLang } from '../i18n';
import { AI_TYPES, aiTypeInfo, aiTypeColorClass } from '../ai/aiTypes';

const Icon = ({ name, className = "" }: { name: string; className?: string }) => (
    <span className={`material-icons ${className}`}>{name}</span>
);

interface EventItem {
    id: string;
    camera: string;
    camera_name: string;
    label: string;
    ai_type: string | null;
    object_type: string | null;
    extra: string | null;
    box_x: number | null;
    box_y: number | null;
    box_w: number | null;
    box_h: number | null;
    score: number | null;
    start_time: number;   // epoch sec
    end_time: number | null;
    source: string;       // "box" | "bkav" | "frigate" (frigate hiển thị là "AI View NVR")
    clip_status: string | null;
    has_clip: boolean;
    has_snapshot: boolean;
    clip_url: string | null;
    snapshot_url: string | null;
}

interface AIEventsPanelProps {
    cameras: any[];
}

const fmtTime = (sec: number) => new Date(sec * 1000).toLocaleString(locale());

// Nhãn hiển thị: nhận string vào (ai_type/object_type/extra), sửa bài AI mới CHỈ ở
// `ai/aiTypes.ts` — không sửa ở đây. Sự kiện cũ (trước khi có cột ai_type) rơi về `label` thô.
const buildDisplayLabel = (evt: EventItem, lang: 'vi' | 'en'): string => {
    if (!evt.ai_type) return evt.label;
    if (evt.ai_type === 'frigate') return evt.object_type || evt.label;
    const info = aiTypeInfo(evt.ai_type);
    const base = lang === 'en' ? info.en : info.vi;
    const obj = evt.object_type ? `: ${evt.object_type}` : '';
    const extra = evt.extra ? ` (${evt.extra})` : '';
    return `${base}${obj}${extra}`;
};

const AIEventsPanel = ({ cameras }: AIEventsPanelProps) => {
    const { lang } = useLang();
    const [events, setEvents] = useState<EventItem[]>([]);
    const [loading, setLoading] = useState(false);
    const [cameraFilter, setCameraFilter] = useState<string>('');
    const [viewSource, setViewSource] = useState<'all' | 'box' | 'frigate'>('all');
    const [aiTypeFilter, setAiTypeFilter] = useState<string>('');
    // AI View NVR đang tắt thì nguồn duy nhất còn sinh sự kiện là Bkav AI View. Mặc định "Tất cả"
    // lúc đó chỉ toàn sự kiện cũ đã chết, lấp mất sự kiện mới — nên tự chọn sẵn Bkav AI View.
    useEffect(() => {
        fetch('/api/settings/detect')
            .then(r => r.ok ? r.json() : null)
            .then(d => { if (d && d.enabled === false) setViewSource('box'); })
            .catch(() => { /* không lấy được trạng thái → giữ mặc định "Tất cả" */ });
    }, []);
    const [playing, setPlaying] = useState<EventItem | null>(null);

    // Esc đóng cửa sổ xem clip.
    useModalKeys({ onClose: () => setPlaying(null), enabled: !!playing });

    const fetchEvents = useCallback(async () => {
        setLoading(true);
        try {
            let url = `/api/events?limit=200&source=${viewSource}`;
            if (cameraFilter) url += `&camera=${encodeURIComponent(cameraFilter)}`;
            if (aiTypeFilter) url += `&ai_type=${encodeURIComponent(aiTypeFilter)}`;
            const r = await fetch(url);
            if (r.ok) setEvents(await r.json());
        } catch (e) {
            console.error(e);
        } finally {
            setLoading(false);
        }
    }, [cameraFilter, viewSource, aiTypeFilter]);

    useEffect(() => { fetchEvents(); }, [fetchEvents]);

    // Auto-refresh so clips that were still "processing" flip to ready.
    useEffect(() => {
        const timer = setInterval(fetchEvents, 15000);
        return () => clearInterval(timer);
    }, [fetchEvents]);

    return (
        <div className="h-full flex flex-col p-4 text-gray-800 dark:text-gray-200">
            {/* Toolbar */}
            <div className="flex flex-wrap items-center gap-3 mb-4">
                <h2 className="font-bold text-lg mr-auto">{t("Sự kiện AI")}</h2>

                {/* Nguồn xem — đổi ?source= (không ảnh hưởng chế độ lưu trữ 5B) */}
                <div className="flex rounded-lg overflow-hidden border border-gray-300 dark:border-gray-600 text-sm">
                    {([
                        { id: 'all', label: 'Tất cả' },
                        { id: 'box', label: 'Bkav AI View' },
                        { id: 'frigate', label: 'AI View NVR' },
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
                            {t(s.label)}
                        </button>
                    ))}
                </div>

                {/* Lọc theo loại bài AI — màu nhẹ khớp với chip trên từng thẻ sự kiện */}
                <div className="flex flex-wrap rounded-lg overflow-hidden border border-gray-300 dark:border-gray-600 text-sm">
                    <button
                        onClick={() => setAiTypeFilter('')}
                        className={`px-3 py-1.5 font-medium whitespace-nowrap ${
                            aiTypeFilter === ''
                                ? 'bg-[#EF4444] text-white'
                                : 'bg-white dark:bg-[#374151] text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-600'
                        }`}
                    >
                        {t('Tất cả loại')}
                    </button>
                    {Object.entries(AI_TYPES).map(([key, info]) => (
                        <button
                            key={key}
                            onClick={() => setAiTypeFilter(key)}
                            className={`px-3 py-1.5 font-medium whitespace-nowrap ${
                                aiTypeFilter === key
                                    ? aiTypeColorClass(key) + ' ring-1 ring-inset ring-current'
                                    : 'bg-white dark:bg-[#374151] text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-600'
                            }`}
                        >
                            {lang === 'en' ? info.en : info.vi}
                        </button>
                    ))}
                </div>

                <select
                    value={cameraFilter}
                    onChange={e => setCameraFilter(e.target.value)}
                    className="bg-white dark:bg-[#374151] border border-gray-300 dark:border-gray-600 rounded px-3 py-1.5 text-sm"
                >
                    <option value="">{t("Tất cả camera")}</option>
                    {cameras.map((c: any) => (
                        <option key={c.id} value={c.id}>{c.name || c.id}</option>
                    ))}
                </select>

                <button onClick={fetchEvents} className="p-1.5 rounded hover:bg-gray-100 dark:hover:bg-gray-700" title={t("Làm mới")}>
                    <Icon name="refresh" />
                </button>
            </div>

            {/* List */}
            <div className="flex-1 overflow-y-auto custom-scrollbar">
                {loading && events.length === 0 ? (
                    <div className="text-center text-gray-500 py-10">{t("Đang tải…")}</div>
                ) : events.length === 0 ? (
                    <div className="text-center text-gray-500 py-10">{t("Không có sự kiện nào")}</div>
                ) : (
                    <div className="grid gap-3 [grid-template-columns:repeat(auto-fill,minmax(260px,1fr))]">
                        {events.map(evt => (
                            <div key={evt.id} className="bg-white dark:bg-[#1F2937] border border-gray-200 dark:border-gray-700 rounded-lg overflow-hidden">
                                <div className="relative aspect-video bg-gray-200 dark:bg-gray-800">
                                    {evt.snapshot_url ? (
                                        <img src={evt.snapshot_url} alt={buildDisplayLabel(evt, lang)} loading="lazy" className="w-full h-full object-cover" />
                                    ) : (
                                        <div className="w-full h-full flex items-center justify-center text-gray-400">
                                            <Icon name="image_not_supported" className="text-3xl" />
                                        </div>
                                    )}
                                    {/* Khoanh vùng đối tượng nhận diện — chỉ vẽ khi backend đã chuẩn hoá tỉ lệ
                                        (hộp vượt khung thì backend để trống, không gửi lên). */}
                                    {evt.box_x != null && evt.box_y != null && evt.box_w != null && evt.box_h != null && (
                                        <div
                                            className="absolute border-2 pointer-events-none rounded-sm"
                                            style={{
                                                left: `${evt.box_x * 100}%`,
                                                top: `${evt.box_y * 100}%`,
                                                width: `${evt.box_w * 100}%`,
                                                height: `${evt.box_h * 100}%`,
                                                borderColor: '#EF4444',
                                                boxShadow: '0 0 0 1px rgba(0,0,0,0.4)',
                                            }}
                                        />
                                    )}
                                    <span className={`absolute top-2 left-2 text-xs px-1.5 py-0.5 rounded font-semibold ${
                                        evt.source === 'frigate' ? 'bg-gray-700 text-white' : 'bg-[#EF4444] text-white'
                                    }`}>
                                        {evt.source === 'frigate' ? 'AI VIEW NVR' : 'BKAV AI VIEW'}
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
                                        <span className="absolute bottom-2 right-2 text-xs px-1.5 py-0.5 rounded bg-amber-500 text-white">{t("Đang cắt clip…")}</span>
                                    )}
                                    {evt.clip_status === 'failed' && (
                                        <span className="absolute bottom-2 right-2 text-xs px-1.5 py-0.5 rounded bg-gray-500 text-white">{t("Không có clip")}</span>
                                    )}
                                </div>
                                <div className="p-3">
                                    <div className="flex items-center gap-1.5 flex-wrap">
                                        {evt.ai_type && (
                                            <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${aiTypeColorClass(evt.ai_type)}`}>
                                                {lang === 'en' ? aiTypeInfo(evt.ai_type).en : aiTypeInfo(evt.ai_type).vi}
                                            </span>
                                        )}
                                    </div>
                                    <div className="font-semibold text-sm truncate mt-1" title={buildDisplayLabel(evt, lang)}>
                                        {buildDisplayLabel(evt, lang)}
                                    </div>
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
                            <span className="text-white text-sm font-medium truncate">{buildDisplayLabel(playing, lang)} — {playing.camera_name}</span>
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

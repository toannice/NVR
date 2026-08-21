import { useState, useEffect, useRef } from 'react';
import LiveCamera from './LiveCamera';
import { useModalKeys } from '../hooks/useModalKeys';
import { t } from '../i18n';

const Icon = ({ name, className = "" }: { name: string, className?: string }) => (
    <span className={`material-icons ${className} `}>{name}</span>
);

// Các nhãn xe của Frigate được gộp thành một mục "Phương tiện" trong UI (xem toggle
// isVehicleEnabled bên dưới) — dòng mô tả trong danh sách vùng phải gộp giống hệt.
const VEHICLE_LABELS = ['car', 'motorcycle', 'bus', 'truck'];

/** Phải khớp AiConfigState.DEFAULT_ZONE_NAME ở backend. */
const DEFAULT_ZONE_NAME = 'Toàn cảnh';

/** "Người | Phương tiện" — tóm tắt vùng này đang bắt gì. Rỗng = chưa chọn gì. */
const describeZoneObjects = (objects?: string[]): string => {
    const objs = objects || [];
    const parts: string[] = [];
    if (objs.includes('person')) parts.push(t('Người'));
    if (objs.some(o => VEHICLE_LABELS.includes(o))) parts.push(t('Phương tiện'));
    // Nhãn lạ (cấu hình tay trong config.yml) vẫn phải hiện, không nuốt im lặng.
    const others = objs.filter(o => o !== 'person' && !VEHICLE_LABELS.includes(o));
    parts.push(...others);
    return parts.join(' | ');
};

interface AIConfigViewProps {
    cameras: any[];
    addToast?: (toast: { type: 'success' | 'error' | 'info', title: string, message: string }) => void;
    /** Mở hộp thoại bật AI tổng (công tắc nằm ở AITab) — để người dùng bật ngay tại chỗ bị chặn,
     *  khỏi phải tự đi tìm công tắc. */
    onRequestEnableGlobal?: () => void;
    /** AITab tăng số này mỗi khi công tắc tổng đổi trạng thái, để view nạp lại. */
    globalVersion?: number;
}

const AIConfigView = ({ cameras, addToast, onRequestEnableGlobal, globalVersion }: AIConfigViewProps) => {
    // Do not filter out offline cameras since status might be mocked/unavailable initially
    const onlineCameras = cameras;

    const [selectedCamera, setSelectedCamera] = useState<string>('');
    const [config, setConfig] = useState<any>(null);
    const [isLoading, setIsLoading] = useState(false);
    const [isApplying, setIsApplying] = useState(false);
    const [applyStatus, setApplyStatus] = useState<string | null>(null);

    // AI tổng đang tắt → không cho cấu hình từng camera (overlay chặn). null = chưa biết.
    const [globalEnabled, setGlobalEnabled] = useState<boolean | null>(null);
    // Vùng "Toàn cảnh" của camera này còn nguyên trạng hệ thống cấp? Quyết định vùng người dùng
    // vẽ đầu tiên có được phép thay thế nó không.
    const [defaultZoneUntouched, setDefaultZoneUntouched] = useState(false);
    // Vùng Toàn cảnh vừa bị thay thế, giữ lại để bấm Hoàn tác.
    const [undoDefaultZone, setUndoDefaultZone] = useState<{ coordinates: string, objects: string[] } | null>(null);
    // Có thay đổi đã lưu nhưng chưa restart AI View NVR.
    const [pendingApply, setPendingApply] = useState(false);


    // List of multiple zones. Key = name.
    const [zones, setZones] = useState<{ [key: string]: { coordinates: string, objects: string[] } }>({});

    // Active Drawing
    const [points, setPoints] = useState<[number, number][]>([]);
    // If we are editing an existing zone, this matches its name
    const [editingZoneName, setEditingZoneName] = useState<string | null>(null);

    // Modal State
    const [showNameModal, setShowNameModal] = useState(false);

    // Esc đóng modal đặt tên vùng; Enter đã được xử lý trên chính ô input.
    useModalKeys({ onClose: () => setShowNameModal(false), enabled: showNameModal });
    const [newZoneName, setNewZoneName] = useState('');

    const mediaRef = useRef<HTMLDivElement>(null);
    const containerRef = useRef<HTMLDivElement>(null);

    // Initial load
    useEffect(() => {
        if (onlineCameras.length > 0 && !selectedCamera) {
            setSelectedCamera(onlineCameras[0].id);
        }
        // If selected camera is no longer in the list (went offline), deselect or select first available
        if (selectedCamera && !onlineCameras.find(c => c.id === selectedCamera)) {
            if (onlineCameras.length > 0) {
                setSelectedCamera(onlineCameras[0].id);
            } else {
                setSelectedCamera(''); // No cameras online
            }
        }
    }, [cameras, onlineCameras, selectedCamera]);

    // Load config — nạp lại cả khi công tắc AI tổng vừa đổi trạng thái.
    useEffect(() => {
        if (selectedCamera) {
            fetchConfig();
        }
    }, [selectedCamera, globalVersion]);


    const fetchConfig = async () => {
        setIsLoading(true);
        try {
            const res = await fetch(`/api/cameras/${selectedCamera}/ai`);
            if (res.ok) {
                const data = await res.json();
                // Mọi trạng thái LUÔN lấy từ server — không tự đoán, nếu không badge sẽ báo
                // "Đang hoạt động" trong khi AI thực tế đang tắt (bug cũ).
                // Vùng cũng chỉ hiển thị đúng những gì có thật trong config.yml: camera mới đã
                // được backend cấp sẵn vùng "Toàn cảnh" khi ghi file, frontend không bịa thêm.
                setConfig({ detect: { enabled: data.enabled } });
                setZones(data.zones || {});
                setGlobalEnabled(data.globalEnabled !== false);
                setDefaultZoneUntouched(!!data.defaultZoneUntouched);
                setPendingApply(!!data.pendingApply);
                setUndoDefaultZone(null);
                setPoints([]);
                setEditingZoneName(null);
            }
        } catch (e) {
            console.error(e);
        } finally {
            setIsLoading(false);
        }
    };

    const parseCoordinates = (str: string) => {
        if (!str) return [];
        const coords = str.split(',').map(Number);
        const pts: [number, number][] = [];
        for (let i = 0; i < coords.length; i += 2) {
            pts.push([coords[i], coords[i + 1]]);
        }
        return pts;
    };

    const handleImageClick = (e: React.MouseEvent) => {
        if (!mediaRef.current) return;
        const rect = mediaRef.current.getBoundingClientRect();
        const x = (e.clientX - rect.left) / rect.width; // 0..1
        const y = (e.clientY - rect.top) / rect.height; // 0..1

        // Check for closing polygon
        if (points.length >= 2) {
            const first = points[0];
            const dist = Math.sqrt(Math.pow(x - first[0], 2) + Math.pow(y - first[1], 2));
            if (dist < 0.03) { // 3% threshold
                // Close polygon
                setShowNameModal(true);
                return;
            }
        }

        setPoints(prev => [...prev, [Math.max(0, Math.min(1, x)), Math.max(0, Math.min(1, y))]]);
    };

    /**
     * Kiểm tra tên vùng ngay lúc gõ. Đã test với validator Frigate 0.16.3: tiếng Việt có dấu và
     * khoảng trắng đều hợp lệ, nên KHÔNG bỏ dấu. Chỉ chặn hai thứ Frigate/YAML không chịu được:
     * trùng tên camera ("Zones cannot share names with cameras" → crash-loop) và trùng tên vùng
     * đã có (YAML key trùng, vùng cũ bị nuốt mất mà người dùng không hay).
     */
    const zoneNameError = (raw: string): string | null => {
        const name = raw.trim();
        if (!name) return null;   // chưa gõ gì thì chưa báo lỗi, chỉ vô hiệu hoá nút Lưu
        if (cameras.some((c: any) => (c.id || '') === name)) {
            return `"${name}" trùng tên một camera — AI View NVR sẽ không khởi động được`;
        }
        if (Object.keys(zones).some(z => z.toLowerCase() === name.toLowerCase())) {
            return `Đã có vùng tên "${name}" trên camera này`;
        }
        return null;
    };

    const handleNameSubmit = () => {
        const name = newZoneName.trim();
        if (!name || zoneNameError(name)) return;

        // Save to local state
        const coordStr = points.map(p => `${p[0].toFixed(3)},${p[1].toFixed(3)}`).join(',');

        setZones(prev => {
            const next = { ...prev, [name]: { coordinates: coordStr, objects: [] as string[] } };
            // Vùng đầu tiên người dùng tự vẽ thay thế vùng "Toàn cảnh" hệ thống cấp — nhưng CHỈ
            // khi nó còn nguyên trạng mặc định (defaultZoneUntouched). Người dùng đã sửa Toàn cảnh
            // thì đó là cấu hình của họ, xoá đi là mất dữ liệu.
            if (defaultZoneUntouched && prev[DEFAULT_ZONE_NAME] && name !== DEFAULT_ZONE_NAME) {
                setUndoDefaultZone(prev[DEFAULT_ZONE_NAME]);
                delete next[DEFAULT_ZONE_NAME];
            }
            return next;
        });

        // Select this zone for editing
        setEditingZoneName(name);
        setShowNameModal(false);
        setNewZoneName('');
        setPoints([]); // Clear drawing points, we rely on the zone data now
    };

    /** Trả lại vùng Toàn cảnh vừa bị thay thế (nút Hoàn tác trên toast). */
    const undoDefaultZoneRemoval = () => {
        if (!undoDefaultZone) return;
        setZones(prev => ({ ...prev, [DEFAULT_ZONE_NAME]: undoDefaultZone }));
        setUndoDefaultZone(null);
    };

    const handleEditZone = (name: string) => {
        setEditingZoneName(name);
    };

    const handleDeleteZone = (name?: string) => {
        const zoneToDelete = name || editingZoneName;
        if (zoneToDelete) {
            const newZ = { ...zones };
            delete newZ[zoneToDelete];
            setZones(newZ);
            if (editingZoneName === zoneToDelete) {
                setEditingZoneName(null);
            }
        }
        setPoints([]);
    };

    // Toggle Object for *Editing* Zone
    const toggleObject = (obj: string) => {
        if (!editingZoneName) return;
        const zone = zones[editingZoneName];
        if (!zone) return;

        const currentObjs = zone.objects || [];
        let newObjs;
        if (currentObjs.includes(obj)) {
            newObjs = currentObjs.filter(o => o !== obj);
        } else {
            newObjs = [...currentObjs, obj];
        }

        setZones(prev => ({
            ...prev,
            [editingZoneName]: {
                ...prev[editingZoneName],
                objects: newObjs
            }
        }));
    };

    /**
     * Lưu cấu hình vùng — KHÔNG restart. Người dùng bấm "Áp dụng" riêng để gộp mọi thay đổi vào
     * một lần restart, thay vì mỗi camera một lần (mỗi lần ~1 phút thủng bản ghi).
     */
    const handleSave = async () => {
        setIsApplying(true);
        setApplyStatus(t('Đang lưu cấu hình...'));
        // Công tắc "Bật nhận diện" của người dùng là nguồn quyết định. Trước đây payload chỉ nhìn
        // số lượng vùng nên gạt công tắc rồi Lưu là bị bỏ qua im lặng — báo thành công mà không
        // có gì xảy ra. Vẫn cần có vùng thì AI mới chạy được, nên là phép AND.
        const hasZones = Object.keys(zones).length > 0;
        const shouldEnable = hasZones && config?.detect?.enabled !== false;
        const payload = { enabled: shouldEnable, zones };

        try {
            const saveRes = await fetch(`/api/cameras/${selectedCamera}/ai`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });

            setIsApplying(false);
            setApplyStatus(null);

            if (!saveRes.ok) {
                const err = await saveRes.json().catch(() => ({}));
                addToast?.({ type: 'error', title: t('Không lưu được'), message: err.error || t('Lỗi khi lưu cấu hình') });
                return;
            }

            setPendingApply(true);
            addToast?.({
                type: 'success',
                title: t('Đã lưu'),
                message: t('Bấm "Áp dụng" để khởi động lại AI View NVR và đưa cấu hình vào chạy.')
            });
            fetchConfig();
        } catch (e) {
            console.error('Save error:', e);
            setIsApplying(false);
            setApplyStatus(null);
            addToast?.({ type: 'error', title: t('Lỗi kết nối'), message: t('Lỗi kết nối server') });
        }
    };

    /**
     * Áp dụng: restart AI View NVR một lần cho mọi thay đổi đã lưu. Backend tự khôi phục config
     * cũ nếu Frigate không khởi động lại được, và báo lại qua cờ rolledBack.
     */
    const handleApply = async () => {
        setIsApplying(true);
        setApplyStatus(t('Đang khởi động lại AI View NVR (khoảng 1 phút)...'));
        try {
            const res = await fetch(`/api/cameras/${selectedCamera}/ai/apply`, { method: 'POST' });
            const data = await res.json().catch(() => ({}));
            setIsApplying(false);
            setApplyStatus(null);

            if (data.success) {
                setPendingApply(false);
                addToast?.({ type: 'success', title: t('Đã áp dụng'), message: data.message || t('Cấu hình AI đã vào chạy.') });
            } else if (data.rolledBack) {
                // Config mới làm Frigate chết, backend đã tự trả về bản cũ — phải nói rõ là thay
                // đổi KHÔNG được lưu, đừng để người dùng tưởng đã xong.
                addToast?.({ type: 'error', title: t('Đã khôi phục cấu hình cũ'), message: data.message });
            } else {
                addToast?.({ type: 'error', title: t('Áp dụng thất bại'), message: data.message || data.error || t('Không rõ nguyên nhân.') });
            }
            fetchConfig();
        } catch (e) {
            console.error('Apply error:', e);
            setIsApplying(false);
            setApplyStatus(null);
            addToast?.({ type: 'error', title: t('Lỗi kết nối'), message: t('Lỗi kết nối server') });
        }
    };


    // Render Logic
    // Current drawing points
    const renderDrawing = () => {
        if (points.length === 0) return null;
        // Convert to percentage (0-100) for viewBox="0 0 100 100"
        const pointsStr = points.map(p => `${p[0] * 100},${p[1] * 100}`).join(' ');

        return (
            <>
                <polyline points={pointsStr} fill="none" stroke="#EF4444" strokeWidth="0.5" strokeDasharray="1 1" />
                {points.map((p, i) => (
                    <circle key={i} cx={`${p[0] * 100}`} cy={`${p[1] * 100}`} r="1" fill="white" stroke="#EF4444" strokeWidth="0.5" />
                ))}
            </>
        )
    }

    // Existing zones
    const renderZones = () => {
        return Object.entries(zones).map(([name, zone]) => {
            const pts = parseCoordinates(zone.coordinates);
            const pointsStr = pts.map(p => `${p[0] * 100},${p[1] * 100}`).join(' ');
            const isSelected = editingZoneName === name;

            return (
                <g key={name} onClick={(e) => { e.stopPropagation(); handleEditZone(name); }} className="cursor-pointer hover:opacity-80">
                    <polygon
                        points={pointsStr}
                        fill={isSelected ? "rgba(239, 68, 68, 0.4)" : "rgba(239, 68, 68, 0.2)"}
                        stroke={isSelected ? "#EF4444" : "rgba(239, 68, 68, 0.5)"}
                        strokeWidth="0.5"
                    />
                    {/* Centered Label */}
                    <text
                        x={`${pts.reduce((acc, p) => acc + p[0], 0) / pts.length * 100}`}
                        y={`${pts.reduce((acc, p) => acc + p[1], 0) / pts.length * 100}`}
                        fill="white"
                        fontSize="3"
                        textAnchor="middle"
                        className="pointer-events-none drop-shadow-md"
                    >
                        {name}
                    </text>
                </g>
            );
        });
    };

    const currentZone = editingZoneName ? zones[editingZoneName] : null;
    const isPersonEnabled = currentZone?.objects?.includes('person');
    const isVehicleEnabled = currentZone?.objects?.some(o => ['car', 'motorcycle', 'bus', 'truck'].includes(o));

    return (
        <div className="flex h-[calc(100vh-4rem)] bg-black text-white font-sans overflow-hidden relative">
            {/* AI tổng đang tắt: chặn hẳn việc cấu hình từng camera. Cho sửa lúc này chỉ khiến
                người dùng tưởng AI đang chạy trong khi thực tế không camera nào nhận diện cả. */}
            {globalEnabled === false && (
                <div className="absolute inset-0 z-30 bg-black/80 backdrop-blur-sm flex items-center justify-center p-6">
                    <div className="max-w-md w-full bg-gray-900 border border-gray-700 rounded-2xl p-6 text-center shadow-2xl">
                        <Icon name="motion_photos_off" className="text-5xl text-gray-500 mb-3" />
                        <h3 className="text-lg font-bold text-white mb-2">{t("AI View NVR đang tắt")}</h3>
                        <p className="text-sm text-gray-400 mb-1">{t("Toàn bộ camera đang không tự nhận diện người/phương tiện. Bật lại để cấu hình vùng.")}</p>
                        <p className="text-xs text-gray-500 mb-5">{t("Cấu hình vùng bạn đã lưu trước đó vẫn được giữ nguyên.")}</p>
                        <button
                            onClick={onRequestEnableGlobal}
                            className="w-full py-2.5 rounded-lg bg-[#EF4444] hover:bg-red-600 text-white font-bold transition-colors"
                        >{t("Bật AI View NVR")}</button>
                        <p className="text-[10px] text-gray-600 mt-3">{t("Bật sẽ khởi động lại AI View NVR (~1 phút), live view và ghi hình gián đoạn trong lúc đó.")}</p>
                    </div>
                </div>
            )}

            {/* Left: Camera List */}
            <aside className="w-64 border-r border-gray-800 flex flex-col bg-black">
                <div className="p-4 border-b border-gray-800">
                    <h2 className="font-bold text-lg flex items-center text-gray-200">
                        <Icon name="videocam" className="mr-2 text-[#EF4444]" />{t("Danh sách Camera")}</h2>
                </div>
                <div className="flex-1 overflow-y-auto p-2 space-y-1 custom-scrollbar">
                    {onlineCameras.length === 0 && (
                        <div className="text-gray-500 text-sm text-center py-4">{t("Không có camera online")}</div>
                    )}
                    {onlineCameras.map(cam => (
                        <button
                            key={cam.id}
                            onClick={() => { setSelectedCamera(cam.id); setPoints([]); setEditingZoneName(null); }}
                            className={`w-full flex items-center space-x-3 p-3 rounded-lg transition-colors text-left ${selectedCamera === cam.id
                                ? 'bg-red-900/30 text-[#EF4444] border border-red-900'
                                : 'text-gray-400 hover:bg-gray-900'
                                }`}
                        >
                            <Icon name="lens" className={`text-xs ${selectedCamera === cam.id ? 'text-[#EF4444]' : 'text-green-500'}`} />
                            <div className="flex flex-col">
                                <span className="font-medium text-sm">{cam.name}</span>
                            </div>
                        </button>
                    ))}
                </div>
            </aside>

            {/* Center: Work Area */}
            <main className="flex-1 flex flex-col bg-black relative">
                <div className="absolute top-4 left-1/2 transform -translate-x-1/2 z-10 flex gap-2">
                    <div className="bg-black/50 backdrop-blur px-4 py-2 rounded-full border border-gray-700 flex items-center space-x-4">
                        <h1 className="text-sm font-bold text-gray-200">Cài đặt AI: {onlineCameras.find(c => c.id === selectedCamera)?.name || selectedCamera}</h1>
                    </div>
                </div>

                <div className="flex-1 relative flex items-center justify-center bg-gray-900 overflow-hidden" ref={containerRef}>
                    {selectedCamera ? (
                        <div className="relative inline-block shadow-2xl border border-gray-800 w-full h-full flex items-center justify-center bg-black">
                            <div 
                                className="relative flex justify-center items-center w-full h-full max-h-[85vh] cursor-crosshair select-none aspect-video"
                                ref={mediaRef}
                                onClick={handleImageClick}
                            >
                                <LiveCamera
                                    name={selectedCamera}
                                    className="w-full h-full object-contain pointer-events-none"
                                    isFullscreen={false}
                                    isGrid={false}
                                />
                                {/* SVG Overlay */}
                                <svg
                                    className="absolute inset-0 w-full h-full pointer-events-none"
                                    viewBox="0 0 100 100"
                                    preserveAspectRatio="none"
                                    style={{ pointerEvents: 'none' }} // Ensure click passes through unless on element
                                >
                                    {/* We need elements to be clickable, so set pointer-events auto on them via class if needed, checking parent */}
                                    {renderZones()}
                                    {renderDrawing()}
                                </svg>
                            </div>

                            {/* Closing Hint */}
                            {points.length >= 2 && (
                                <div
                                    className="absolute w-4 h-4 bg-white/50 rounded-full border border-white animate-pulse pointer-events-none"
                                    style={{
                                        left: `${points[0][0] * 100}%`,
                                        top: `${points[0][1] * 100}%`,
                                        transform: 'translate(-50%, -50%)'
                                    }}
                                />
                            )}
                        </div>
                    ) : (
                        <div className="text-gray-500">{t("Chọn camera để bắt đầu")}</div>
                    )}
                </div>
            </main>

            {/* Right: Controls */}
            <aside className="w-80 border-l border-gray-800 flex flex-col bg-black p-6 z-20 shadow-xl overflow-y-auto">
                <div className="mb-4 text-center">
                    <h3 className="text-xl font-bold text-white mb-1">{t("Cấu hình Vùng")}</h3>
                    <p className="text-xs text-gray-500">{t("Quản lý khu vực nhận diện")}</p>
                </div>

                {/* --- Section: List of Zones --- */}
                {!editingZoneName && (
                    <div className="mb-6 space-y-2">
                        <div className="flex items-center justify-between mb-2">
                            <span className="text-sm font-semibold text-gray-400 uppercase tracking-wider">{t("Danh sách vùng")}</span>
                            <span className="bg-gray-800 text-xs px-2 py-0.5 rounded-full text-gray-400">{Object.keys(zones).length}</span>
                        </div>

                        {Object.keys(zones).length === 0 ? (
                            <div className="text-gray-500 text-sm text-center py-8 border border-dashed border-gray-800 rounded-xl bg-gray-900/30">
                                <Icon name="gesture" className="text-2xl mb-2 opacity-50" /><br />{t("Chưa có vùng nào.")}<br />{t("Hãy vẽ vùng mới trên hình.")}</div>
                        ) : (
                            <div className="space-y-1">
                                {Object.keys(zones).map(name => (
                                    <div key={name} className="flex items-center justify-between p-3 bg-gray-900 hover:bg-gray-800 rounded-lg border border-gray-800 transition-colors group">
                                        <div onClick={() => handleEditZone(name)} className="flex-1 cursor-pointer flex items-start gap-2 min-w-0">
                                            <div className="w-2 h-2 rounded-full bg-[#EF4444] mt-1.5 shrink-0"></div>
                                            <div className="min-w-0">
                                                <span className="block text-gray-200 font-medium text-sm truncate" title={name}>{name}</span>
                                                <span
                                                    className={`block text-[10px] truncate ${describeZoneObjects(zones[name]?.objects) ? 'text-gray-500' : 'text-amber-500'}`}
                                                    title={describeZoneObjects(zones[name]?.objects) || t('Chưa chọn đối tượng nào — vùng này sẽ không bắt gì')}
                                                >
                                                    {describeZoneObjects(zones[name]?.objects) || t('Chưa chọn đối tượng')}
                                                </span>
                                            </div>
                                        </div>
                                        <div className="flex items-center gap-1 opacity-60 group-hover:opacity-100 transition-opacity">
                                            <button
                                                onClick={() => handleEditZone(name)}
                                                className="p-1.5 hover:bg-gray-700 rounded text-blue-400"
                                                title={t("Sửa")}
                                            >
                                                <Icon name="edit" className="text-xs" />
                                            </button>
                                            <button
                                                onClick={() => handleDeleteZone(name)}
                                                className="p-1.5 hover:bg-gray-700 rounded text-red-400"
                                                title={t("Xóa")}
                                            >
                                                <Icon name="delete" className="text-xs" />
                                            </button>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        )}

                        {points.length > 0 && (
                            <button
                                onClick={() => setPoints([])}
                                className="w-full mt-4 py-2 rounded-lg border border-gray-600 text-gray-300 text-sm hover:bg-gray-800 transition-colors flex items-center justify-center gap-2"
                            >
                                <Icon name="close" className="text-sm" />{t("Hủy vẽ vùng mới")}</button>
                        )}
                    </div>
                )}


                {editingZoneName && (
                    <div className="space-y-4 animate-fade-in">
                        <div className="bg-gray-900 p-4 rounded-xl border border-gray-800">
                            <div className="flex items-center justify-between mb-4 pb-4 border-b border-gray-800">
                                <h4 className="font-bold text-white flex items-center gap-2">
                                    <Icon name="edit" className="text-[#EF4444] text-sm" />
                                    {editingZoneName}
                                </h4>
                                <button onClick={() => setEditingZoneName(null)} className="text-xs text-gray-400 hover:text-white flex items-center bg-gray-800 px-2 py-1 rounded">
                                    <Icon name="arrow_back" className="text-xs mr-1" />{t("Quay lại")}</button>
                            </div>

                            {/* Detect toggle for this zone */}
                            <div className="flex items-center justify-between mb-4 bg-black p-3 rounded-lg border border-gray-700">
                                <div>
                                    <p className="text-sm text-gray-200 font-semibold">{t("Bật nhận diện (Detect)")}</p>
                                    <p className="text-[10px] text-gray-500">{t("Bật để AI View NVR quét vùng này")}</p>
                                </div>
                                <button
                                    onClick={() => setConfig((prev: any) => ({
                                        ...prev,
                                        detect: { ...prev?.detect, enabled: !prev?.detect?.enabled }
                                    }))}
                                    className={`w-12 h-6 rounded-full p-1 transition-colors ${config?.detect?.enabled ? 'bg-[#EF4444]' : 'bg-gray-700'}`}
                                >
                                    <div className={`w-4 h-4 rounded-full bg-white transform transition-transform ${config?.detect?.enabled ? 'translate-x-6' : 'translate-x-0'}`} />
                                </button>
                            </div>

                            <p className="text-xs text-gray-500 mb-3 uppercase font-semibold">{t("Đối tượng cần phát hiện:")}</p>

                            <div className="space-y-2">
                                <div
                                    onClick={() => toggleObject('person')}
                                    className={`p-3 rounded-lg border cursor-pointer transition-all flex items-center justify-between ${isPersonEnabled
                                        ? 'bg-red-900/20 border-[#EF4444]'
                                        : 'bg-black border-gray-700 hover:border-gray-500'
                                        }`}
                                >
                                    <div className="flex items-center space-x-3">
                                        <div className={`w-4 h-4 rounded flex items-center justify-center border ${isPersonEnabled ? 'bg-[#EF4444] border-[#EF4444]' : 'border-gray-500'}`}>
                                            {isPersonEnabled && <Icon name="check" className="text-[10px] text-white" />}
                                        </div>
                                        <span className={`text-sm ${isPersonEnabled ? 'text-white font-bold' : 'text-gray-400'}`}>{t("Người (Person)")}</span>
                                    </div>
                                    <Icon name="person" className={isPersonEnabled ? 'text-[#EF4444] text-lg' : 'text-gray-600 text-lg'} />
                                </div>

                                <div
                                    onClick={() => {
                                        const isEnabled = isVehicleEnabled;
                                        if (isEnabled) {
                                            // Remove all
                                            const z = zones[editingZoneName];
                                            const newObjs = (z.objects || []).filter(o => !['car', 'motorcycle', 'bus', 'truck'].includes(o));
                                            setZones(prev => ({ ...prev, [editingZoneName]: { ...z, objects: newObjs } }));
                                        } else {
                                            // Add all
                                            const z = zones[editingZoneName];
                                            const newObjs = [...(z.objects || []), 'car', 'motorcycle', 'bus', 'truck'];
                                            setZones(prev => ({ ...prev, [editingZoneName]: { ...z, objects: newObjs } }));
                                        }
                                    }}
                                    className={`p-3 rounded-lg border cursor-pointer transition-all flex items-center justify-between ${isVehicleEnabled
                                        ? 'bg-red-900/20 border-[#EF4444]'
                                        : 'bg-black border-gray-700 hover:border-gray-500'
                                        }`}
                                >
                                    <div className="flex items-center space-x-3">
                                        <div className={`w-4 h-4 rounded flex items-center justify-center border ${isVehicleEnabled ? 'bg-[#EF4444] border-[#EF4444]' : 'border-gray-500'}`}>
                                            {isVehicleEnabled && <Icon name="check" className="text-[10px] text-white" />}
                                        </div>
                                        <span className={`text-sm ${isVehicleEnabled ? 'text-white font-bold' : 'text-gray-400'}`}>{t("Phương tiện (Xe)")}</span>
                                    </div>
                                    <Icon name="directions_car" className={isVehicleEnabled ? 'text-[#EF4444] text-lg' : 'text-gray-600 text-lg'} />
                                </div>
                            </div>
                        </div>

                        <button
                            onClick={() => handleDeleteZone()}
                            className="w-full py-2.5 rounded-lg border border-red-900/50 text-red-400 hover:bg-red-900/20 transition-colors flex items-center justify-center gap-2 text-sm"
                        >
                            <Icon name="delete" className="text-sm" />{t("Xóa vùng này")}</button>
                    </div>
                )}

                {/* --- Section: AI Status (Always Visible, No Toggle) --- */}
                <div className="mb-6 pt-6 border-t border-gray-800">
                    <div className={`flex items-center gap-3 p-3 rounded-lg border ${config?.detect?.enabled
                        ? 'bg-green-900/20 border-green-800'
                        : 'bg-gray-900 border-gray-700'
                        }`}>
                        <div className={`w-2.5 h-2.5 rounded-full ${config?.detect?.enabled ? 'bg-green-400 animate-pulse' : 'bg-gray-500'
                            }`} />
                        <div>
                            <p className="text-sm font-semibold text-gray-200">
                                AI View NVR: {config?.detect?.enabled ? 'Đang hoạt động' : 'Đã tắt'}
                            </p>
                            <p className="text-[10px] text-gray-500">
                                {config?.detect?.enabled
                                    ? `${Object.keys(zones).length} vùng đang được giám sát`
                                    : 'Vẽ vùng và nhấn Lưu để kích hoạt AI View NVR'}
                            </p>
                        </div>
                    </div>
                </div>

                <div className="mt-auto pt-6 border-t border-gray-800 space-y-2">
                    {/* Apply banner */}
                    {isApplying && (
                        <div className="flex items-center gap-2 p-3 bg-yellow-900/30 border border-yellow-700 rounded-lg text-yellow-300 text-xs">
                            <div className="w-4 h-4 border-2 border-yellow-300/30 border-t-yellow-300 rounded-full animate-spin shrink-0" />
                            <span>{applyStatus}</span>
                        </div>
                    )}
                    {/* Vùng Toàn cảnh mặc định vừa bị thay thế — cho đường lùi thay vì xoá im lặng */}
                    {undoDefaultZone && (
                        <div className="flex items-center gap-2 p-3 bg-blue-900/30 border border-blue-700 rounded-lg text-blue-200 text-xs">
                            <Icon name="info" className="text-sm shrink-0" />
                            <span className="flex-1">Đã thay vùng “{DEFAULT_ZONE_NAME}” mặc định bằng vùng bạn vừa vẽ.</span>
                            <button onClick={undoDefaultZoneRemoval} className="underline font-semibold hover:text-white shrink-0">{t("Hoàn tác")}</button>
                        </div>
                    )}

                    {/* Đã lưu nhưng chưa restart — nói rõ cấu hình CHƯA chạy */}
                    {pendingApply && !isApplying && (
                        <div className="flex items-start gap-2 p-3 bg-amber-900/30 border border-amber-700 rounded-lg text-amber-200 text-xs">
                            <Icon name="pending_actions" className="text-sm shrink-0" />
                            <span>{t("Có thay đổi đã lưu nhưng")}<b>{t("chưa chạy")}</b>{t(". Bấm “Áp dụng” để khởi động lại AI View NVR (~1 phút, live view và ghi hình gián đoạn trong lúc đó).")}</span>
                        </div>
                    )}

                    <button
                        onClick={handleSave}
                        disabled={isLoading || isApplying}
                        className="w-full py-3 rounded-lg bg-[#EF4444] hover:bg-red-600 text-white font-bold shadow-lg shadow-red-900/50 transition-all flex items-center justify-center disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        {isApplying ? (
                            <div className="w-5 h-5 border-2 border-white/30 border-t-white rounded-full animate-spin mr-2" />
                        ) : (
                            <Icon name="save" className="mr-2" />
                        )}
                        {isApplying ? 'Đang xử lý...' : 'Lưu cấu hình'}
                    </button>

                    <button
                        onClick={handleApply}
                        disabled={isLoading || isApplying || !pendingApply}
                        className="w-full py-3 rounded-lg border border-gray-600 text-gray-200 font-semibold hover:bg-gray-800 transition-all flex items-center justify-center disabled:opacity-40 disabled:cursor-not-allowed"
                        title={pendingApply ? t('Khởi động lại AI View NVR để cấu hình vào chạy') : t('Không có thay đổi nào chờ áp dụng')}
                    >
                        <Icon name="play_circle" className="mr-2" />{t("Áp dụng")}</button>
                </div>
            </aside>

            {/* Name Modal */}
            {showNameModal && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm">
                    <div className="bg-[#1F2937] p-6 rounded-xl border border-gray-700 w-96 shadow-2xl">
                        <div className="flex justify-between items-center mb-4">
                            <h3 className="text-lg font-bold">{t("Thêm Vùng (Add Zone)")}</h3>
                            <button onClick={() => setShowNameModal(false)}><Icon name="close" /></button>
                        </div>
                        <div className="mb-4">
                            <label className="block text-sm text-gray-400 mb-1">{t("Tên vùng")}<span className="text-red-500">*</span></label>
                            <input
                                autoFocus
                                type="text"
                                className="w-full bg-gray-800 border border-gray-600 rounded p-2 text-white focus:border-[#EF4444] focus:ring-1 focus:ring-[#EF4444]"
                                placeholder={t("Ví dụ: Gara, Cổng chính...")}
                                value={newZoneName}
                                onChange={e => setNewZoneName(e.target.value)}
                                onKeyDown={e => e.key === 'Enter' && handleNameSubmit()}
                            />
                            {zoneNameError(newZoneName) && (
                                <p className="text-xs text-red-400 mt-2">{zoneNameError(newZoneName)}</p>
                            )}
                        </div>
                        <div className="flex justify-end gap-2">
                            <button
                                onClick={() => setShowNameModal(false)}
                                className="px-4 py-2 rounded bg-gray-700 hover:bg-gray-600 text-gray-300 transition-colors"
                            >{t("Huỷ")}</button>
                            <button
                                onClick={handleNameSubmit}
                                disabled={!newZoneName.trim() || !!zoneNameError(newZoneName)}
                                className="px-4 py-2 rounded bg-[#EF4444] hover:bg-red-600 text-white font-medium transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                            >{t("Lưu")}</button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default AIConfigView;


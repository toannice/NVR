import { useState, useEffect, useRef } from 'react';
import LiveCamera from './LiveCamera';

const Icon = ({ name, className = "" }: { name: string, className?: string }) => (
    <span className={`material-icons ${className} `}>{name}</span>
);

interface AIConfigViewProps {
    cameras: any[];
    addToast?: (toast: { type: 'success' | 'error' | 'info', title: string, message: string }) => void;
}

const AIConfigView = ({ cameras, addToast }: AIConfigViewProps) => {
    // Do not filter out offline cameras since status might be mocked/unavailable initially
    const onlineCameras = cameras;

    const [selectedCamera, setSelectedCamera] = useState<string>('');
    const [config, setConfig] = useState<any>(null);
    const [isLoading, setIsLoading] = useState(false);
    const [isApplying, setIsApplying] = useState(false);
    const [applyStatus, setApplyStatus] = useState<string | null>(null);


    // List of multiple zones. Key = name.
    const [zones, setZones] = useState<{ [key: string]: { coordinates: string, objects: string[] } }>({});

    // Active Drawing
    const [points, setPoints] = useState<[number, number][]>([]);
    // If we are editing an existing zone, this matches its name
    const [editingZoneName, setEditingZoneName] = useState<string | null>(null);

    // Modal State
    const [showNameModal, setShowNameModal] = useState(false);
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

    // Load config
    useEffect(() => {
        if (selectedCamera) {
            fetchConfig();
        }
    }, [selectedCamera]);


    const fetchConfig = async () => {
        setIsLoading(true);
        try {
            const res = await fetch(`/api/cameras/${selectedCamera}/ai`);
            if (res.ok) {
                const data = await res.json();
                setConfig({ detect: { enabled: data.enabled } });
                // Load existing zones
                setZones(data.zones || {});
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

    const handleNameSubmit = () => {
        if (!newZoneName) return;

        // Save to local state
        const coordStr = points.map(p => `${p[0].toFixed(3)},${p[1].toFixed(3)}`).join(',');

        setZones(prev => ({
            ...prev,
            [newZoneName]: {
                coordinates: coordStr,
                objects: [] // Default empty, user selects in sidebar
            }
        }));

        // Select this zone for editing
        setEditingZoneName(newZoneName);
        setShowNameModal(false);
        setNewZoneName('');
        setPoints([]); // Clear drawing points, we rely on the zone data now
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

    // handleSaveAndApply: write config and then restart Frigate
    const handleSaveAndApply = async () => {
        setIsApplying(true);
        setApplyStatus('Đang lưu cấu hình...');
        const shouldEnable = Object.keys(zones).length > 0;
        const payload = { enabled: shouldEnable, zones: zones };

        try {
            // 1. Save config
            const saveRes = await fetch(`/api/cameras/${selectedCamera}/ai`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });

            if (!saveRes.ok) {
                addToast?.({ type: 'error', title: 'Lỗi', message: 'Lỗi khi lưu cấu hình' });
                setIsApplying(false);
                setApplyStatus(null);
                return;
            }

            // 2. Restart AI (Apply)
            setApplyStatus('Đang gửi lệnh khởi động lại AI...');
            const applyRes = await fetch(`/api/cameras/${selectedCamera}/ai/apply`, { method: 'POST' });

            if (!applyRes.ok) {
                addToast?.({ type: 'error', title: 'Lỗi', message: 'Không thể kích hoạt restart Frigate' });
                setIsApplying(false);
                setApplyStatus(null);
                return;
            }

            // 3. Poll until Frigate responds healthy (max 3 min)
            setApplyStatus('Đang khởi động lại AI (có thể mất 1-2 phút)...');
            const startTime = Date.now();
            let healthy = false;
            while (Date.now() - startTime < 180_000) {
                await new Promise(r => setTimeout(r, 4000));
                try {
                    const check = await fetch('/api/frigate/api/version', { signal: AbortSignal.timeout(4000) });
                    if (check.ok) { healthy = true; break; }
                } catch { /* still restarting */ }
            }

            if (healthy) {
                setApplyStatus(null);
                setIsApplying(false);
                addToast?.({
                    type: 'success',
                    title: 'Đã lưu & áp dụng thành công',
                    message: 'AI Frigate đã được cập nhật cấu hình mới.'
                });
                fetchConfig();
            } else {
                setApplyStatus(null);
                setIsApplying(false);
                addToast?.({ type: 'error', title: 'Timeout', message: 'Frigate chưa về online sau 3 phút. Vui lòng kiểm tra.' });
            }
        } catch (e) {
            console.error('Save & Apply error:', e);
            setApplyStatus(null);
            setIsApplying(false);
            addToast?.({ type: 'error', title: 'Lỗi kết nối', message: 'Lỗi kết nối server' });
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
        <div className="flex h-[calc(100vh-4rem)] bg-black text-white font-sans overflow-hidden">
            {/* Left: Camera List */}
            <aside className="w-64 border-r border-gray-800 flex flex-col bg-black">
                <div className="p-4 border-b border-gray-800">
                    <h2 className="font-bold text-lg flex items-center text-gray-200">
                        <Icon name="videocam" className="mr-2 text-[#EF4444]" />
                        Danh sách Camera
                    </h2>
                </div>
                <div className="flex-1 overflow-y-auto p-2 space-y-1 custom-scrollbar">
                    {onlineCameras.length === 0 && (
                        <div className="text-gray-500 text-sm text-center py-4">Không có camera online</div>
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
                        <div className="text-gray-500">Chọn camera để bắt đầu</div>
                    )}
                </div>
            </main>

            {/* Right: Controls */}
            <aside className="w-80 border-l border-gray-800 flex flex-col bg-black p-6 z-20 shadow-xl overflow-y-auto">
                <div className="mb-4 text-center">
                    <h3 className="text-xl font-bold text-white mb-1">Cấu hình Vùng</h3>
                    <p className="text-xs text-gray-500">Quản lý khu vực nhận diện</p>
                </div>

                {/* --- Section: List of Zones --- */}
                {!editingZoneName && (
                    <div className="mb-6 space-y-2">
                        <div className="flex items-center justify-between mb-2">
                            <span className="text-sm font-semibold text-gray-400 uppercase tracking-wider">Danh sách vùng</span>
                            <span className="bg-gray-800 text-xs px-2 py-0.5 rounded-full text-gray-400">{Object.keys(zones).length}</span>
                        </div>

                        {Object.keys(zones).length === 0 ? (
                            <div className="text-gray-500 text-sm text-center py-8 border border-dashed border-gray-800 rounded-xl bg-gray-900/30">
                                <Icon name="gesture" className="text-2xl mb-2 opacity-50" /><br />
                                Chưa có vùng nào.<br />
                                Hãy vẽ vùng mới trên hình.
                            </div>
                        ) : (
                            <div className="space-y-1">
                                {Object.keys(zones).map(name => (
                                    <div key={name} className="flex items-center justify-between p-3 bg-gray-900 hover:bg-gray-800 rounded-lg border border-gray-800 transition-colors group">
                                        <div onClick={() => handleEditZone(name)} className="flex-1 cursor-pointer flex items-center gap-2">
                                            <div className="w-2 h-2 rounded-full bg-[#EF4444]"></div>
                                            <span className="text-gray-200 font-medium text-sm truncate max-w-[120px]" title={name}>{name}</span>
                                        </div>
                                        <div className="flex items-center gap-1 opacity-60 group-hover:opacity-100 transition-opacity">
                                            <button
                                                onClick={() => handleEditZone(name)}
                                                className="p-1.5 hover:bg-gray-700 rounded text-blue-400"
                                                title="Sửa"
                                            >
                                                <Icon name="edit" className="text-xs" />
                                            </button>
                                            <button
                                                onClick={() => handleDeleteZone(name)}
                                                className="p-1.5 hover:bg-gray-700 rounded text-red-400"
                                                title="Xóa"
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
                                <Icon name="close" className="text-sm" /> Hủy vẽ vùng mới
                            </button>
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
                                    <Icon name="arrow_back" className="text-xs mr-1" /> Quay lại
                                </button>
                            </div>

                            {/* Detect toggle for this zone */}
                            <div className="flex items-center justify-between mb-4 bg-black p-3 rounded-lg border border-gray-700">
                                <div>
                                    <p className="text-sm text-gray-200 font-semibold">Bật nhận diện (Detect)</p>
                                    <p className="text-[10px] text-gray-500">Bật để AI quét vùng này</p>
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

                            <p className="text-xs text-gray-500 mb-3 uppercase font-semibold">Đối tượng cần phát hiện:</p>

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
                                        <span className={`text-sm ${isPersonEnabled ? 'text-white font-bold' : 'text-gray-400'}`}>Người (Person)</span>
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
                                        <span className={`text-sm ${isVehicleEnabled ? 'text-white font-bold' : 'text-gray-400'}`}>Phương tiện (Xe)</span>
                                    </div>
                                    <Icon name="directions_car" className={isVehicleEnabled ? 'text-[#EF4444] text-lg' : 'text-gray-600 text-lg'} />
                                </div>
                            </div>
                        </div>

                        <button
                            onClick={() => handleDeleteZone()}
                            className="w-full py-2.5 rounded-lg border border-red-900/50 text-red-400 hover:bg-red-900/20 transition-colors flex items-center justify-center gap-2 text-sm"
                        >
                            <Icon name="delete" className="text-sm" /> Xóa vùng này
                        </button>
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
                                AI: {config?.detect?.enabled ? 'Đang hoạt động' : 'Đã tắt'}
                            </p>
                            <p className="text-[10px] text-gray-500">
                                {config?.detect?.enabled
                                    ? `${Object.keys(zones).length} vùng đang được giám sát`
                                    : 'Vẽ vùng và nhấn Lưu để kích hoạt AI'}
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
                    {/* Apply (save + restart Frigate) */}
                    <button
                        onClick={handleSaveAndApply}
                        disabled={isLoading || isApplying}
                        className="w-full py-3 rounded-lg bg-[#EF4444] hover:bg-red-600 text-white font-bold shadow-lg shadow-red-900/50 transition-all flex items-center justify-center disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        {isApplying ? (
                            <div className="w-5 h-5 border-2 border-white/30 border-t-white rounded-full animate-spin mr-2" />
                        ) : (
                            <Icon name="save" className="mr-2" />
                        )}
                        {isApplying ? 'Đang xử lý...' : 'Lưu cấu hình AI'}
                    </button>
                </div>
            </aside>

            {/* Name Modal */}
            {showNameModal && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm">
                    <div className="bg-[#1F2937] p-6 rounded-xl border border-gray-700 w-96 shadow-2xl">
                        <div className="flex justify-between items-center mb-4">
                            <h3 className="text-lg font-bold">Thêm Vùng (Add Zone)</h3>
                            <button onClick={() => setShowNameModal(false)}><Icon name="close" /></button>
                        </div>
                        <div className="mb-4">
                            <label className="block text-sm text-gray-400 mb-1">Tên vùng <span className="text-red-500">*</span></label>
                            <input
                                autoFocus
                                type="text"
                                className="w-full bg-gray-800 border border-gray-600 rounded p-2 text-white focus:border-[#EF4444] focus:ring-1 focus:ring-[#EF4444]"
                                placeholder="Ví dụ: Gara, Cổng chính..."
                                value={newZoneName}
                                onChange={e => setNewZoneName(e.target.value)}
                                onKeyDown={e => e.key === 'Enter' && handleNameSubmit()}
                            />
                        </div>
                        <div className="flex justify-end gap-2">
                            <button
                                onClick={() => setShowNameModal(false)}
                                className="px-4 py-2 rounded bg-gray-700 hover:bg-gray-600 text-gray-300 transition-colors"
                            >
                                Cancel
                            </button>
                            <button
                                onClick={handleNameSubmit}
                                className="px-4 py-2 rounded bg-[#EF4444] hover:bg-red-600 text-white font-medium transition-colors"
                            >
                                Save
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default AIConfigView;


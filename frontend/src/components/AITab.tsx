import { useState } from 'react';
import AIEventsPanel from './AIEventsPanel';
import AIConfigView from './AIConfigView';
import GlobalDetectToggle from './GlobalDetectToggle';
import { t } from '../i18n';

interface AITabProps {
    cameras: any[];
    addToast?: (t: { type: 'success' | 'error' | 'info'; title: string; message: string }) => void;
}

/**
 * "Cấu hình AI" tab shell with two sub-views:
 *  - Cấu hình: the zone/detection editor (AIConfigView) — mở mặc định.
 *  - Sự kiện: list of AI events (Bkav AI View pushed clips + AI View NVR).
 */
const AITab = ({ cameras, addToast }: AITabProps) => {
    const [sub, setSub] = useState<'events' | 'config'>('config');
    // Overlay "AI đang tắt" trong AIConfigView bấm nút Bật → tăng số này để GlobalDetectToggle mở
    // hộp thoại xác nhận của nó (một chỗ duy nhất xử lý việc bật/tắt, không nhân đôi logic).
    const [enableRequest, setEnableRequest] = useState(0);
    // Tăng mỗi khi công tắc tổng đổi trạng thái → AIConfigView nạp lại.
    const [globalVersion, setGlobalVersion] = useState(0);

    return (
        <div className="h-full flex flex-col">
            <div className="flex gap-2 px-4 pt-3 border-b border-gray-200 dark:border-gray-700">
                {[
                    { id: 'config', label: t('Cấu hình vùng') },
                    { id: 'events', label: t('Sự kiện') },
                ].map(tab => (
                    <button
                        key={tab.id}
                        onClick={() => setSub(tab.id as 'events' | 'config')}
                        className={`px-4 py-2 text-sm font-medium border-b-2 -mb-px ${
                            sub === tab.id
                                ? 'border-[#EF4444] text-[#EF4444]'
                                : 'border-transparent text-gray-500 hover:text-gray-700 dark:hover:text-gray-300'
                        }`}
                    >
                        {tab.label}
                    </button>
                ))}
            </div>
            <GlobalDetectToggle
                addToast={addToast}
                enableRequest={enableRequest}
                onChanged={() => setGlobalVersion(v => v + 1)}
            />
            <div className="flex-1 overflow-hidden">
                {sub === 'events'
                    ? <AIEventsPanel cameras={cameras} />
                    : <AIConfigView
                        cameras={cameras}
                        addToast={addToast}
                        onRequestEnableGlobal={() => setEnableRequest(v => v + 1)}
                        globalVersion={globalVersion}
                    />}
            </div>
        </div>
    );
};

export default AITab;

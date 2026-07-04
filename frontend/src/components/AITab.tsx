import { useState } from 'react';
import AIEventsPanel from './AIEventsPanel';
import AIConfigView from './AIConfigView';
import GlobalDetectToggle from './GlobalDetectToggle';

interface AITabProps {
    cameras: any[];
    addToast?: (t: { type: 'success' | 'error' | 'info'; title: string; message: string }) => void;
}

/**
 * "Sự kiện AI" tab shell with two sub-views:
 *  - Sự kiện: list of AI events (Box-pushed clips + optionally Frigate), the new events section.
 *  - Cấu hình AI: the existing zone/detection editor (AIConfigView).
 */
const AITab = ({ cameras, addToast }: AITabProps) => {
    const [sub, setSub] = useState<'events' | 'config'>('events');

    return (
        <div className="h-full flex flex-col">
            <div className="flex gap-2 px-4 pt-3 border-b border-gray-200 dark:border-gray-700">
                {[
                    { id: 'events', label: 'Sự kiện' },
                    { id: 'config', label: 'Cấu hình AI' },
                ].map(t => (
                    <button
                        key={t.id}
                        onClick={() => setSub(t.id as 'events' | 'config')}
                        className={`px-4 py-2 text-sm font-medium border-b-2 -mb-px ${
                            sub === t.id
                                ? 'border-[#EF4444] text-[#EF4444]'
                                : 'border-transparent text-gray-500 hover:text-gray-700 dark:hover:text-gray-300'
                        }`}
                    >
                        {t.label}
                    </button>
                ))}
            </div>
            <GlobalDetectToggle addToast={addToast} />
            <div className="flex-1 overflow-hidden">
                {sub === 'events'
                    ? <AIEventsPanel cameras={cameras} />
                    : <AIConfigView cameras={cameras} addToast={addToast} />}
            </div>
        </div>
    );
};

export default AITab;

import { useState, useEffect, useRef } from 'react'
import { Icon } from './Icon'
import LiveCamera from './LiveCamera'
import { t } from '../i18n';

export const MiniPlayer = ({ camId, onClose, onExpand }: { camId: string, onClose: () => void, onExpand: () => void }) => {
  const [position, setPosition] = useState({ x: window.innerWidth - 340, y: window.innerHeight - 240 });
  const [isDragging, setIsDragging] = useState(false);
  const dragStart = useRef({ x: 0, y: 0 });

  const handleMouseDown = (e: React.MouseEvent) => {
    setIsDragging(true);
    dragStart.current = { x: e.clientX - position.x, y: e.clientY - position.y };
  };

  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      if (isDragging) {
        setPosition({ x: e.clientX - dragStart.current.x, y: e.clientY - dragStart.current.y });
      }
    };
    const handleMouseUp = () => setIsDragging(false);

    if (isDragging) {
      document.addEventListener('mousemove', handleMouseMove);
      document.addEventListener('mouseup', handleMouseUp);
    }
    return () => {
      document.removeEventListener('mousemove', handleMouseMove);
      document.removeEventListener('mouseup', handleMouseUp);
    };
  }, [isDragging]);

  return (
    <div
      className="fixed z-[100] w-80 h-52 bg-black rounded-lg shadow-2xl overflow-hidden border border-gray-600 flex flex-col"
      style={{ left: position.x, top: position.y }}
    >
      <div
        className="h-8 bg-gray-800 flex items-center justify-between px-2 cursor-move"
        onMouseDown={handleMouseDown}
      >
        <span className="text-white text-xs truncate max-w-[150px] font-mono">{camId}</span>
        <div className="flex space-x-1">
          <button onClick={onExpand} className="text-gray-300 hover:text-white p-1" title={t("Mở rộng")}><Icon name="open_in_full" className="text-sm" /></button>
          <button onClick={onClose} className="text-gray-300 hover:text-red-400 p-1" title={t("Đóng")}><Icon name="close" className="text-sm" /></button>
        </div>
      </div>
      <div className="flex-1 bg-black overflow-hidden relative" onDoubleClick={onClose}>
        <LiveCamera name={camId} className="w-full h-full object-cover object-top" isFullscreen={true} isGrid={false} />
      </div>
    </div>
  );
};

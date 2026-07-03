import { useState, useEffect } from 'react'
import { Icon } from './Icon'
import customLogo from '../assets/aicam-logo-white1.png'

export const Header = ({ activeTab, setActiveTab, currentTime }: {
  activeTab: string,
  setActiveTab: (t: string) => void,
  currentTime: Date
}) => {
  const [appVersion, setAppVersion] = useState<string>('');
  const [deployVersion, setDeployVersion] = useState<string>('');
  useEffect(() => {
    fetch('/api/version')
      .then(r => r.json())
      .then(d => {
        setAppVersion(d.version || '');
        setDeployVersion(d.deployVersion || '');
      })
      .catch(() => {});
  }, []);

  return (
  <header className="h-14 flex items-center justify-between px-4 shadow-md z-20 shrink-0 text-white overflow-hidden relative" style={{ backgroundColor: '#EF4444' }}>
    <div className="flex items-center space-x-2 shrink-0">
      <img src={customLogo} alt="AI View Logo" className="h-10 w-auto object-contain rounded-sm" />
      {appVersion && (
        <span
          title={`Build: ${deployVersion || 'unknown'}`}
          className="hidden sm:inline-block bg-white/20 text-white text-[10px] font-mono px-1.5 py-0.5 rounded border border-white/30 leading-tight cursor-default select-none"
        >
          v{appVersion}
        </span>
      )}
    </div>

    <nav className="flex items-center space-x-1 flex-1 overflow-x-auto justify-center px-4 no-scrollbar">
      {['Live View', 'Playback', 'Downloads', 'Sự kiện AI', 'Setup'].map(id => (
        <button
          key={id}
          onClick={() => setActiveTab(id)}
          className={`px-4 py-2 rounded-full font-medium transition-colors text-sm whitespace-nowrap ${activeTab === id
            ? 'bg-white text-[#EF4444] shadow-sm'
            : 'text-white hover:bg-white/20'
            } `}
        >
          {id === 'Live View' ? 'Trực tiếp' : id === 'Playback' ? 'Xem lại' : id === 'Downloads' ? 'Tải xuống' : id === 'Sự kiện AI' ? 'Sự kiện AI' : 'Cấu hình'}
        </button>
      ))}
    </nav>

    <div className="flex items-center space-x-3 text-sm shrink-0">
      <span className="hidden xl:inline font-medium opacity-90 whitespace-nowrap">NVR AI View - {currentTime.toLocaleTimeString('vi-VN')}</span>
      <button
        onClick={() => {
          if (confirm('Bạn có muốn đăng xuất không?')) {
            (window as any).logout();
          }
        }}
        className="flex items-center space-x-1 hover:text-gray-200 transition-colors shrink-0"
      >
        <Icon name="logout" className="text-base" />
        <span className="hidden sm:inline">Đăng xuất</span>
      </button>
    </div>
  </header>
  )
}

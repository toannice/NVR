import { useState, useEffect, useCallback } from 'react'
import { Icon } from './Icon'
import { useModalKeys } from '../hooks/useModalKeys'
import { t, locale } from '../i18n';

export const DownloadsView = () => {
  const [activeTab, setActiveTab] = useState<'video' | 'image'>('video');
  const [files, setFiles] = useState<any[]>([]);
  const [screenshots, setScreenshots] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [previewUrl, setPreviewUrl] = useState('');
  const [imagePreviewUrl, setImagePreviewUrl] = useState('');

  // Esc đóng cửa sổ xem trước (ảnh ưu tiên hơn vì nó mở sau/nằm trên).
  useModalKeys({ onClose: () => setImagePreviewUrl(''), enabled: !!imagePreviewUrl });
  useModalKeys({ onClose: () => setPreviewUrl(''), enabled: !!previewUrl && !imagePreviewUrl });

  // Fetch Data
  const fetchData = useCallback(() => {
    setLoading(true);
    if (activeTab === 'video') {
      fetch('/api/list-exports')
        .then(res => res.json())
        .then(data => setFiles(data))
        .catch(e => console.error(e))
        .finally(() => setLoading(false));
    } else {
      fetch('/api/list-screenshots')
        .then(res => res.json())
        .then(data => setScreenshots(data))
        .catch(e => console.error(e))
        .finally(() => setLoading(false));
    }
  }, [activeTab]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // Actions
  const handleDeleteVideo = async (filename: string) => {
    if (!confirm(t('Bạn có chắc chắn muốn xóa video "{0}" không?').replace('{0}', filename))) return;
    try {
      const res = await fetch(`/api/exports/${filename}`, { method: 'DELETE' });
      if (res.ok) {
        setFiles(prev => prev.filter(f => f.name !== filename));
      } else {
        alert(t('Xóa thất bại'));
      }
    } catch (e) {
      console.error(e);
      alert(t('Lỗi kết nối'));
    }
  };

  const handleDeleteScreenshot = async (filename: string) => {
    if (!confirm(t('Bạn có chắc chắn muốn xóa ảnh "{0}" không?').replace('{0}', filename))) return;
    try {
      const res = await fetch(`/api/screenshots/${filename}`, { method: 'DELETE' });
      if (res.ok) {
        setScreenshots(prev => prev.filter(s => s.name !== filename));
      } else {
        alert(t('Xóa thất bại'));
      }
    } catch (e) {
      console.error(e);
      alert(t('Lỗi kết nối'));
    }
  };

  return (
    <div className="flex-1 bg-gray-100 dark:bg-gray-900 p-8 overflow-y-auto">
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-2xl font-bold text-gray-800 dark:text-white flex items-center gap-2">
          <Icon name={activeTab === 'video' ? 'movie' : 'image'} className="text-[#EF4444]" />{t("Thư viện lưu trữ")}</h2>

        {/* Tabs */}
        <div className="flex bg-white dark:bg-gray-800 rounded-lg p-1 shadow-sm border border-gray-200 dark:border-gray-700">
          <button
            onClick={() => setActiveTab('video')}
            className={`px-4 py-2 rounded-md text-sm font-medium transition-colors ${activeTab === 'video'
              ? 'bg-[#EF4444] text-white shadow'
              : 'text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700'
              }`}
          >
            Video
          </button>
          <button
            onClick={() => setActiveTab('image')}
            className={`px-4 py-2 rounded-md text-sm font-medium transition-colors ${activeTab === 'image'
              ? 'bg-[#EF4444] text-white shadow'
              : 'text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700'
              }`}
          >{t("Ảnh chụp")}</button>
        </div>
      </div>

      {loading ? (
        <div className="flex items-center justify-center p-12">
          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-[#EF4444]"></div>
          <span className="ml-3 text-gray-500">{t("Đang tải dữ liệu...")}</span>
        </div>
      ) : (
        <div className="bg-white dark:bg-[#1F2937] rounded-lg shadow overflow-hidden">
          <table className="w-full text-left">
            <thead className="bg-gray-50 dark:bg-gray-800 border-b border-gray-200 dark:border-gray-700">
              <tr>
                <th className="p-4 font-semibold text-gray-600 dark:text-gray-300">{t("Tên file")}</th>
                <th className="p-4 font-semibold text-gray-600 dark:text-gray-300">{t("Kích thước")}</th>
                <th className="p-4 font-semibold text-gray-600 dark:text-gray-300">{t("Thời gian tạo")}</th>
                <th className="p-4 font-semibold text-right text-gray-600 dark:text-gray-300">{t("Hành động")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
              {/* Empty State */}
              {activeTab === 'video' && files.length === 0 && (
                <tr><td colSpan={4} className="p-8 text-center text-gray-500">{t("Chưa có video nào.")}</td></tr>
              )}
              {activeTab === 'image' && screenshots.length === 0 && (
                <tr><td colSpan={4} className="p-8 text-center text-gray-500">{t("Chưa có ảnh chụp nào.")}</td></tr>
              )}

              {/* Video List */}
              {activeTab === 'video' && files.map(f => (
                <tr key={f.name} className="hover:bg-gray-50 dark:hover:bg-gray-800 transition-colors">
                  <td className="p-4 font-medium text-gray-800 dark:text-white flex items-center gap-2">
                    <Icon name="movie" className="text-blue-500" /> {f.name}
                  </td>
                  <td className="p-4 text-gray-600 dark:text-gray-400">{f.size}</td>
                  <td className="p-4 text-gray-600 dark:text-gray-400">{new Date(f.time).toLocaleString(locale())}</td>
                  <td className="p-4 text-right space-x-2">
                    <button onClick={() => setPreviewUrl(f.path)} className="p-2 bg-blue-50 text-blue-600 rounded hover:bg-blue-100" title="Xem">
                      <Icon name="visibility" />
                    </button>
                    <a href={f.path} download className="p-2 bg-gray-100 text-gray-700 rounded hover:bg-gray-200 inline-block" title={t("Tải về")}>
                      <Icon name="download" />
                    </a>
                    <button onClick={() => handleDeleteVideo(f.name)} className="p-2 bg-red-50 text-red-600 rounded hover:bg-red-100" title={t("Xóa")}>
                      <Icon name="delete" />
                    </button>
                  </td>
                </tr>
              ))}

              {/* Screenshot List */}
              {activeTab === 'image' && screenshots.map(s => (
                <tr key={s.name} className="hover:bg-gray-50 dark:hover:bg-gray-800 transition-colors">
                  <td className="p-4 font-medium text-gray-800 dark:text-white flex items-center gap-2">
                    <Icon name="image" className="text-green-500" /> {/* Image Icon */}
                    {s.name}
                  </td>
                  <td className="p-4 text-gray-600 dark:text-gray-400">{s.size}</td>
                  <td className="p-4 text-gray-600 dark:text-gray-400">{new Date(s.time).toLocaleString(locale())}</td>
                  <td className="p-4 text-right space-x-2">
                    <button onClick={() => setImagePreviewUrl(s.path)} className="p-2 bg-blue-50 text-blue-600 rounded hover:bg-blue-100" title="Xem">
                      <Icon name="visibility" />
                    </button>
                    <a href={s.path} download className="p-2 bg-gray-100 text-gray-700 rounded hover:bg-gray-200 inline-block" title={t("Tải về")}>
                      <Icon name="download" />
                    </a>
                    <button onClick={() => handleDeleteScreenshot(s.name)} className="p-2 bg-red-50 text-red-600 rounded hover:bg-red-100" title={t("Xóa")}>
                      <Icon name="delete" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Video Modal */}
      {previewUrl && (
        <div className="fixed inset-0 z-50 bg-black/80 flex items-center justify-center p-4" onClick={() => setPreviewUrl('')}>
          <div className="relative w-full max-w-4xl bg-black rounded overflow-hidden shadow-2xl" onClick={e => e.stopPropagation()}>
            <button onClick={() => setPreviewUrl('')} className="absolute top-4 right-4 text-white bg-black/50 rounded-full p-2 hover:bg-black/80 z-10">
              <Icon name="close" />
            </button>
            <video src={previewUrl} controls autoPlay className="w-full h-auto max-h-[80vh]" />
          </div>
        </div>
      )}

      {/* Image Modal */}
      {imagePreviewUrl && (
        <div className="fixed inset-0 z-50 bg-black/90 flex items-center justify-center p-4" onClick={() => setImagePreviewUrl('')}>
          <div className="relative max-w-[90vw] max-h-[90vh]" onClick={e => e.stopPropagation()}>
            <button onClick={() => setImagePreviewUrl('')} className="absolute -top-12 right-0 text-white p-2 hover:text-gray-300">
              <Icon name="close" className="text-3xl" />
            </button>
            <img src={imagePreviewUrl} alt="Preview" className="max-w-full max-h-[85vh] rounded-lg shadow-2xl border border-gray-700" />
          </div>
        </div>
      )}
    </div>
  );
}

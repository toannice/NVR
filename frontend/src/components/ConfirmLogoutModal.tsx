import { Icon } from './Icon'
import { useModalKeys } from '../hooks/useModalKeys'
import { useT } from '../i18n'

export function ConfirmLogoutModal({ isOpen, onClose, onConfirm }: { isOpen: boolean, onClose: () => void, onConfirm: () => void }) {
  const t = useT();
  // Đăng xuất không phải thao tác nguy hiểm nên cho Enter = xác nhận luôn, khác ConfirmDeleteModal.
  useModalKeys({ onClose, onSubmit: onConfirm, enabled: isOpen });
  if (!isOpen) return null;
  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/60 backdrop-blur-sm p-4 animate-in fade-in duration-200">
      <div className="bg-white dark:bg-[#1F2937] w-full max-w-sm rounded-[20px] shadow-2xl overflow-hidden transform scale-100 transition-all border border-gray-200 dark:border-gray-700">
        <div className="p-8 text-center">
          <div className="w-16 h-16 bg-red-100 dark:bg-red-900/20 rounded-full flex items-center justify-center mx-auto mb-6 ring-4 ring-red-50 dark:ring-red-900/10">
            <Icon name="logout" className="text-3xl text-[#EF4444]" />
          </div>
          <h3 className="text-xl font-bold text-gray-900 dark:text-white mb-2">{t('Đăng xuất')}</h3>
          <p className="text-gray-500 dark:text-gray-400 mb-8 leading-relaxed">{t('Bạn có muốn đăng xuất không?')}</p>
          <div className="flex gap-3">
            <button onClick={onClose} className="flex-1 py-3 px-4 bg-gray-100 hover:bg-gray-200 dark:bg-gray-700 dark:hover:bg-gray-600 text-gray-700 dark:text-gray-200 font-semibold rounded-xl transition-colors">{t('Hủy bỏ')}</button>
            <button onClick={onConfirm} className="flex-1 py-3 px-4 bg-[#EF4444] hover:bg-[#D63A1D] text-white font-semibold rounded-xl shadow-lg shadow-red-500/30 transition-all transform active:scale-[0.98]">{t('Đăng xuất')}</button>
          </div>
        </div>
      </div>
    </div>
  );
}

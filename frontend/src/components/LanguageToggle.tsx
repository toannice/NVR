import { useLang } from '../i18n';

/**
 * Công tắc ngôn ngữ "ENG | Tiếng Việt" trên thanh tiêu đề. Cả hai nhãn luôn hiện (không phải
 * dropdown) để người dùng thấy ngay đang ở ngôn ngữ nào và đổi bằng một cú bấm.
 *
 * Bề rộng cố định cho từng nhãn (`w-[…]`) — nếu để co giãn thì chữ đậm/nhạt khi đổi ngôn ngữ làm
 * cả cụm rộng hẹp khác nhau, kéo theo mọi thứ bên phải nhảy chỗ.
 */
export const LanguageToggle = () => {
    const { lang, setLang } = useLang();

    const base = 'text-xs leading-none whitespace-nowrap transition-colors text-center';

    return (
        <div className="flex items-center gap-1.5 shrink-0 select-none" role="group" aria-label="Language / Ngôn ngữ">
            <button
                type="button"
                onClick={() => setLang('en')}
                aria-pressed={lang === 'en'}
                className={`${base} w-[26px] ${lang === 'en' ? 'font-bold text-white' : 'font-medium text-white/60 hover:text-white/90'}`}
            >
                ENG
            </button>
            <span aria-hidden="true" className="text-white/40 text-xs leading-none">|</span>
            <button
                type="button"
                onClick={() => setLang('vi')}
                aria-pressed={lang === 'vi'}
                className={`${base} w-[58px] ${lang === 'vi' ? 'font-bold text-white' : 'font-medium text-white/60 hover:text-white/90'}`}
            >
                Tiếng Việt
            </button>
        </div>
    );
};

export default LanguageToggle;

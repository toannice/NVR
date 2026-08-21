import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { I18nContext, type I18nValue, type Lang, STORAGE_KEY, getCurrentLang, setCurrentLang } from './index';

/**
 * Bọc toàn bộ ứng dụng. `key={lang}` là phần quan trọng: `t()` là hàm cấp module nên React không
 * biết nó phụ thuộc ngôn ngữ — đổi key buộc dựng lại cả cây, mọi chỗ gọi `t()` chạy lại và lấy
 * đúng ngôn ngữ mới. Không có nó thì bấm đổi ngôn ngữ chỉ vài component ngẫu nhiên đổi chữ.
 */
export const I18nProvider = ({ children }: { children: ReactNode }) => {
    const [lang, setLangState] = useState<Lang>(getCurrentLang);

    const value = useMemo<I18nValue>(() => ({
        lang,
        setLang: (l: Lang) => {
            setCurrentLang(l);
            try { localStorage.setItem(STORAGE_KEY, l); } catch { /* không lưu được thì thôi */ }
            setLangState(l);
        },
    }), [lang]);

    // Trình đọc màn hình và tính năng dịch của trình duyệt đều đọc <html lang>.
    useEffect(() => { document.documentElement.lang = lang; }, [lang]);

    return (
        <I18nContext.Provider value={value}>
            <div key={lang} className="contents">{children}</div>
        </I18nContext.Provider>
    );
};

export default I18nProvider;

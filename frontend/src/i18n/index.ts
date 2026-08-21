import { createContext, useContext } from 'react';
import { EN } from './en';

export type Lang = 'vi' | 'en';

export const STORAGE_KEY = 'nvr_lang';

/**
 * i18n dùng **chính chuỗi tiếng Việt làm khoá** thay vì khoá tự đặt kiểu `setup.camera.add`.
 * Giao diện đã có sẵn hơn 500 chuỗi tiếng Việt viết thẳng trong JSX; đặt khoá mới cho từng chuỗi
 * vừa tốn công vừa dễ sót — sót một khoá là màn hình hiện ra chữ `setup.camera.add`. Với cách này,
 * thiếu bản dịch thì tệ nhất người dùng thấy tiếng Việt (vẫn đọc được), không bao giờ thấy khoá
 * kỹ thuật hay ô trống.
 *
 * `t()` là hàm cấp module chứ không phải hook. Lý do: 27 file cần dịch, nhiều file khai báo vài
 * component trong cùng một file — chèn `const t = useT()` vào đúng từng thân hàm là chỗ dễ hỏng
 * nhất. Đổi lại, I18nProvider gắn `key={lang}` nên đổi ngôn ngữ sẽ dựng lại toàn bộ cây giao diện,
 * mọi chỗ gọi `t()` đọc lại ngôn ngữ mới. Cái giá là form đang gõ dở bị xoá khi đổi ngôn ngữ —
 * chấp nhận được vì đó là hành động cố ý và hiếm.
 *
 * File này KHÔNG export component nào (provider nằm ở provider.tsx) — react-refresh cấm một file
 * vừa export component vừa export hàm thường.
 */

let currentLang: Lang = 'vi';

/** Cảnh báo một lần cho mỗi chuỗi chưa dịch — chỉ ở bản dev, để còn soát nốt. */
const warned = new Set<string>();

export const t = (vi: string): string => {
    if (currentLang === 'vi') return vi;
    const en = EN[vi];
    if (en === undefined) {
        if (import.meta.env.DEV && !warned.has(vi)) {
            warned.add(vi);
            console.warn('[i18n] thiếu bản dịch EN:', JSON.stringify(vi));
        }
        return vi;
    }
    return en;
};

export const readStoredLang = (): Lang => {
    try {
        const v = localStorage.getItem(STORAGE_KEY);
        if (v === 'en' || v === 'vi') return v;
    } catch { /* localStorage bị chặn (chế độ riêng tư) → dùng mặc định */ }
    return 'vi';
};

/** Provider gọi lúc đổi ngôn ngữ; đặt TRƯỚC setState để lần render kế đọc đúng ngôn ngữ. */
export const setCurrentLang = (l: Lang) => { currentLang = l; };

export const getCurrentLang = (): Lang => currentLang;

// Đặt ngay lúc nạp module: t() có thể được gọi trước lần render đầu tiên.
currentLang = readStoredLang();

export interface I18nValue {
    lang: Lang;
    setLang: (l: Lang) => void;
}

export const I18nContext = createContext<I18nValue>({ lang: 'vi', setLang: () => {} });

export const useLang = () => useContext(I18nContext);

/** Locale cho toLocaleString/toLocaleTimeString — giờ và ngày cũng phải đổi theo ngôn ngữ. */
export const useLocale = (): string => (useContext(I18nContext).lang === 'en' ? 'en-GB' : 'vi-VN');

/** Bản không-hook, dùng trong hàm tiện ích ngoài component. */
export const locale = (): string => (currentLang === 'en' ? 'en-GB' : 'vi-VN');

/** Giữ cho chỗ nào muốn dùng kiểu hook — trả về đúng hàm `t` cấp module. */
export const useT = () => t;

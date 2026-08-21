/**
 * Đăng ký DUY NHẤT cho các loại bài AI (từ camera Bkav tự nhận diện lẫn AiView Box) — thêm bài
 * AI mới CHỈ sửa ở file này. Mã (`key`) khớp đúng giá trị `ai_type` camera/Box gửi lên
 * (`vehicledetection`, `facerecognition`...), không phân biệt hoa thường khi tra cứu.
 *
 * Màu dùng tông nhạt (light: bg-*-100/text-*-700, dark: bg-*-900/40/text-*-300) theo yêu cầu
 * "màu nhẹ nhàng" — tránh màu bão hoà mạnh.
 */
export interface AiTypeInfo {
    vi: string;
    en: string;
    /** Tailwind color family dùng cho badge, vd 'sky' → bg-sky-100 text-sky-700 dark:bg-sky-900/40 dark:text-sky-300 */
    color: string;
}

export const AI_TYPES: Record<string, AiTypeInfo> = {
    vehicledetection: { vi: 'Phát hiện xe cộ', en: 'Vehicle Detection', color: 'sky' },
    facerecognition: { vi: 'Nhận diện khuôn mặt', en: 'Face Recognition', color: 'violet' },
    intrusion: { vi: 'Xâm nhập vùng cấm', en: 'Intrusion Detection', color: 'rose' },
    loitering: { vi: 'Phát hiện lảng vảng', en: 'Loitering Detection', color: 'amber' },
    crowd: { vi: 'Phát hiện đông người', en: 'Crowd Detection', color: 'orange' },
    fall: { vi: 'Phát hiện té ngã', en: 'Fall Detection', color: 'red' },
    frigate: { vi: 'AI View NVR', en: 'AI View NVR', color: 'slate' },
};

const FALLBACK_COLOR = 'gray';

/** Mã lạ không có trong registry → hiện nguyên mã + màu xám, không vỡ giao diện. */
export const aiTypeInfo = (aiType: string | null | undefined): AiTypeInfo => {
    const key = (aiType ?? '').trim().toLowerCase();
    if (key && AI_TYPES[key]) return AI_TYPES[key];
    return { vi: aiType || 'Khác', en: aiType || 'Other', color: FALLBACK_COLOR };
};

export const aiTypeLabel = (aiType: string | null | undefined, lang: 'vi' | 'en'): string => {
    const info = aiTypeInfo(aiType);
    return lang === 'en' ? info.en : info.vi;
};

const COLOR_CLASS: Record<string, string> = {
    sky: 'bg-sky-100 text-sky-700 dark:bg-sky-900/40 dark:text-sky-300',
    violet: 'bg-violet-100 text-violet-700 dark:bg-violet-900/40 dark:text-violet-300',
    rose: 'bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-300',
    amber: 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300',
    orange: 'bg-orange-100 text-orange-700 dark:bg-orange-900/40 dark:text-orange-300',
    red: 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300',
    slate: 'bg-slate-100 text-slate-700 dark:bg-slate-900/40 dark:text-slate-300',
    gray: 'bg-gray-100 text-gray-700 dark:bg-gray-700/60 dark:text-gray-300',
};

export const aiTypeColorClass = (aiType: string | null | undefined): string =>
    COLOR_CLASS[aiTypeInfo(aiType).color] ?? COLOR_CLASS[FALLBACK_COLOR];

/** Danh sách mã bài AI đã biết, dùng để dựng hàng nút lọc (kèm "Tất cả" ở phía gọi). */
export const KNOWN_AI_TYPE_KEYS = Object.keys(AI_TYPES);

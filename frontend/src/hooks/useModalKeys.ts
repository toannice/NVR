import { useEffect, useRef } from 'react'

// Phím tắt dùng chung cho mọi modal: Esc = đóng, Enter = đồng ý.
//
// Vì sao cần hook thay vì gắn onKeyDown lên từng modal: modal có thể lồng nhau
// (ví dụ modal "Thêm Camera qua LAN/ONVIF" mở tiếp modal "Cấu hình Camera ONVIF").
// Nếu mỗi modal tự nghe window keydown thì một lần Esc sẽ đóng cả hai. Hook giữ một
// stack: chỉ modal trên cùng phản ứng với phím.
const stack: symbol[] = [];

type Opts = {
  /** Gọi khi bấm Esc. Bỏ trống = Esc không làm gì. */
  onClose?: () => void;
  /** Gọi khi bấm Enter. Bỏ trống = Enter không làm gì. */
  onSubmit?: () => void;
  /** false = tạm ngưng (dùng khi modal đang ẩn hoặc đang gửi request). */
  enabled?: boolean;
};

export function useModalKeys({ onClose, onSubmit, enabled = true }: Opts) {
  // Giữ callback trong ref để không phải đăng ký lại listener mỗi lần re-render.
  const cb = useRef({ onClose, onSubmit });
  cb.current = { onClose, onSubmit };

  useEffect(() => {
    if (!enabled) return;
    const id = Symbol('modal');
    stack.push(id);

    const onKey = (e: KeyboardEvent) => {
      if (stack[stack.length - 1] !== id) return;   // không phải modal trên cùng
      if (e.defaultPrevented) return;
      // Đang gõ tiếng Việt bằng bộ gõ (IME): Enter là để chọn chữ, không phải submit.
      if (e.isComposing || e.keyCode === 229) return;

      if (e.key === 'Escape' && cb.current.onClose) {
        e.preventDefault();
        e.stopPropagation();
        cb.current.onClose();
        return;
      }

      if (e.key === 'Enter' && cb.current.onSubmit) {
        if (e.shiftKey || e.altKey || e.ctrlKey || e.metaKey) return;
        const t = e.target as HTMLElement | null;
        const tag = t?.tagName;
        // Enter trên các phần tử này đã có nghĩa riêng — đừng chiếm chỗ:
        // TEXTAREA xuống dòng · BUTTON/A kích hoạt chính nó · SELECT chọn mục.
        if (tag === 'TEXTAREA' || tag === 'BUTTON' || tag === 'A' || tag === 'SELECT') return;
        if (t?.isContentEditable) return;
        e.preventDefault();
        cb.current.onSubmit();
      }
    };

    // capture=true để chạy trước handler của thư viện con (vd hls.js/video controls).
    window.addEventListener('keydown', onKey, true);
    return () => {
      window.removeEventListener('keydown', onKey, true);
      const i = stack.indexOf(id);
      if (i >= 0) stack.splice(i, 1);
    };
  }, [enabled]);
}

---
name: doc-templates
description: >
  Template format cho 3 loại tài liệu kỹ thuật của skill project-doc-generator.
  Định nghĩa cấu trúc bắt buộc gồm AiView Document Header chuẩn (logo, bảng phiên bản,
  thông tin đơn vị, lịch sử sửa đổi, mục lục) và nội dung chi tiết từng tài liệu.
  Áp dụng cho cả file .docx và .md.
---

# Templates cho 3 Loại Tài Liệu

---

## Quy tắc bắt buộc — AiView Document Header

**KHÔNG dựng trang bìa bằng tay.** Trang bìa AiView chuẩn (logo + font nhúng +
bảng phiên bản + lịch sử sửa đổi) đã có sẵn trong `assets/template.docx`. Việc duy
nhất cần làm là **điền giá trị vào các placeholder `{{...}}`** rồi để
`scripts/fill_template.py` copy template + thay placeholder + chèn thân.

Các placeholder có thật trong `assets/template.docx`:

| Placeholder | Vị trí | Nguồn dữ liệu |
|---|---|---|
| `{{DOC_TITLE_VERSION}}` | Tiêu đề lớn trên nền bìa (chữ trắng) | Tên ngắn tài liệu, xuống dòng bằng `\n` |
| `{{TEMPLATE_VERSION}}` | Bảng "Phiên bản / Ngày" | = `version` trong `.doc-meta.json` |
| `{{TEMPLATE_DATE}}` | Bảng "Phiên bản / Ngày" | Ngày chạy skill (DD/MM/YYYY) |
| `{{DOC_TITLE}}` | Tên tài liệu đầy đủ | Xem từng doc bên dưới |
| `{{DOC_NUMBER}}` | "Tài liệu số" | `V{version}` (ví dụ: `V1.0.0.0`) |
| `{{UNIT}}` | "Đơn vị" | `team` trong `.doc-meta.json` |
| `{{DATE_WRITTEN}}` | "Ngày viết" + cột Ngày của lịch sử sửa đổi | Ngày chạy skill (DD/MM/YYYY) |
| `{{DATE_ISSUED}}` | "Ngày ban hành" | Ngày chạy skill — hoặc hỏi nếu khác |
| `{{PREPARED_BY}}` | "Được soạn bởi" | `team` trong `.doc-meta.json` |
| `{{AUTHORS}}` | Bảng "Người tổng hợp" | `team` trong `.doc-meta.json` |
| `{{APPROVER}}` | Bảng "Người duyệt" | `approver` trong `.doc-meta.json` |
| `{{DOC_VERSION}}` | Cột "Bản sửa đổi" của lịch sử sửa đổi | = `version` trong `.doc-meta.json` |
| `{{INITIAL_DESCRIPTION}}` | Cột "Mô tả" của lịch sử sửa đổi | Mô tả ngắn riêng từng tài liệu |
| `{{CONTENT_START}}` | Marker sau "Mục lục" — nơi chèn phần thân | (filler thay bằng nội dung `bodyN.md`) |

Logo đầu/cuối bìa, dòng "The Future of AI Camera", và toàn bộ style (Heading1–4,
Normal) đã nằm trong template — không cần tạo lại.

### Cách điền (cơ học, không mất format)

Viết toàn bộ giá trị bìa thành 1 file JSON, ví dụ `meta1.json`:

```json
{
  "DOC_TITLE_VERSION": "Tài liệu Cấu hình\nMôi trường Biên dịch",
  "TEMPLATE_VERSION": "0.0.0.1", "TEMPLATE_DATE": "16/06/2026",
  "DOC_TITLE": "Tài liệu Cấu hình Môi trường Biên dịch — AIView NVR",
  "DOC_NUMBER": "V0.0.0.1",
  "UNIT": "Bộ phận AI View - Công ty Cổ phần Bkav AI", "DATE_WRITTEN": "16/06/2026",
  "DATE_ISSUED": "16/06/2026", "PREPARED_BY": "Bkav AIView Team",
  "AUTHORS": "ToanHM", "APPROVER": "HaDM",
  "DOC_VERSION": "0.0.0.1",
  "INITIAL_DESCRIPTION": "Khởi tạo tài liệu môi trường biên dịch cho AIView NVR v0.0.0.1"
}
```

**Mục lục:** `fill_template.py` tự sinh **mục lục TĨNH** (text + dot leader + số
trang thật) khi máy có `soffice`/`libreoffice` + `pdftotext` — render thử 1 lần để
dò trang của từng heading rồi thay TOC field bằng các dòng tĩnh (level-1 in đậm,
level-2/3 thụt lề, số trang zero-pad căn phải). Mỗi dòng là **hyperlink nội bộ**
(bookmark `_TocN` tại heading) → click nhảy tới mục. Số trang ổn định vì nội dung
luôn bắt đầu ở trang sau trang Mục lục (page break). Nếu thiếu 2 công cụ trên, filler để
lại TOC field chuẩn của Word (mở Word + F9 để điền). Bản `.md` không có TOC.

---

## Tài liệu 1 — Hướng dẫn Cài đặt V{N}

**Đối tượng:** Kỹ thuật viên, người triển khai  
**File:** `doc1_huong_dan_cai_dat.docx` + `.md`  
**Tính chất:** TĨNH — chỉ tạo version mới (V2, V3…) khi quy trình đóng gói/deploy thay đổi thực sự.
Nếu cách cài đặt không đổi so với version trước → không cần tạo lại, dùng link cũ.

**Giá trị placeholder riêng:**
- `{DOC_TITLE}` → `Hướng dẫn Cài đặt {Tên Project} — V{N}` (N = số thứ tự phương pháp, không phải version phần mềm)
- `{DOC_NUMBER}` → `V{version}` (version phần mềm lần đầu áp dụng phương pháp này)
- `{INITIAL_DESCRIPTION}` → `Hướng dẫn cài đặt offline bundle cho {Tên Project}`

### Cấu trúc nội dung (ngắn gọn — chỉ đủ để chạy được):

```
1. Yêu cầu
   - Máy đích: Ubuntu, quyền sudo
   - Không bắt buộc có internet (bundle đã đủ mọi thứ)

2. Các bước cài đặt

   Bước 1 — Tải file bundle
   Tải file V{version}-{YYYYMMDD}.tar.gz từ Release Version tương ứng trên Drive.

   Bước 2 — Giải nén
   tar -xzf V{version}-{YYYYMMDD}.tar.gz
   cd nvr-bundle-{YYYYMMDD}

   Bước 3 — Chạy script triển khai
   sudo ./deploy-offline.sh
   Script sẽ tự động: cài Docker (offline), load images, tạo cấu hình, khởi động stack.
   Làm theo hướng dẫn của script (điền secrets khi được hỏi, chọn chế độ OTA hay offline).

   Bước 4 — Kiểm tra
   Truy cập https://<IP_MÁY> (bỏ qua cảnh báo cert tự ký).
   Đăng nhập → xác nhận giao diện NVR hiển thị bình thường.

3. Cập nhật về sau
   - Offline: tải bundle mới → tar -xzf → sudo ./deploy-offline.sh (script giữ .env cũ)
   - OTA (nếu đã chọn): watchdog tự pull image mới từ GHCR khi có bản cập nhật
```

---

## Tài liệu 2 — Changelog & Release Info

**Đối tượng:** Dev, Leader, Project Manager  
**File:** `doc2_changelog.docx` + `.md`

**Giá trị placeholder riêng:**
- `{DOC_TITLE}` → `Changelog & Release Notes — {Tên Project}`
- `{DOC_NUMBER}` → `V{version}`
- `{INITIAL_DESCRIPTION}` → `Khởi tạo changelog cho {Tên Project} v{version}`

---

## File `release_info.md` — Đặt trong thư mục Release Version trên Drive

**Không phải docx — chỉ là file text/md đơn giản, tạo bằng MCP `create_file`.**

```markdown
# {TênProject} — Release V{version}

**Ngày phát hành:** DD/MM/YYYY

## Tài liệu

- **Hướng dẫn cài đặt:** [Hướng dẫn cài đặt V{N}]({Drive link})
- **Cài đặt môi trường biên dịch (dành cho dev):** [Cài đặt môi trường biên dịch]({Drive link})

## Package

File bundle: `V{version}-{YYYYMMDD}.tar.gz`

## Thay đổi chính trong version này

{3–5 dòng tóm tắt từ doc2_changelog}
```

**Nguồn link:**
- "Hướng dẫn cài đặt V{N}" = file docx trên Drive (search trong folder root, lấy link share)
- "Cài đặt môi trường biên dịch" = folder ID cố định `1OtPxfxjempLKShy28xij62AP2C1Il4R5`

---

## Nguồn dữ liệu cho changelog (Doc2):

1. **Git log** (ưu tiên):
```bash
git log --oneline --no-merges -30 2>/dev/null
git log --pretty=format:"%h %s %ad" --date=short -50 2>/dev/null
```

2. **Người dùng nhập thủ công** nếu git log không đủ thông tin.

Kết hợp 2 nguồn, ưu tiên git log.

---
name: project-doc-generator
description: >
  Tự động tạo bộ tài liệu kỹ thuật hoàn chỉnh từ codebase dự án phần mềm, bao gồm:
  (1) Tài liệu cấu hình môi trường biên dịch (cho dev/kỹ thuật viên),
  (2) Hướng dẫn cài đặt & triển khai từng bước (cho kỹ thuật viên cơ bản/người dùng),
  (3) Tài liệu release version với changelog tuần tự và package bundle.
  Kích hoạt skill này khi người dùng yêu cầu: "tạo tài liệu cho project", "sinh tài liệu kỹ thuật",
  "tạo docs dự án", "tạo release version", "viết hướng dẫn cài đặt", "tạo changelog",
  "document hóa project", hoặc bất kỳ yêu cầu nào liên quan đến tạo tài liệu từ source code.
  Luôn dùng skill này khi người dùng muốn tạo tài liệu kỹ thuật cho dự án phần mềm, dù họ không
  dùng đúng từ khóa trên.
---

# Project Documentation Generator

Skill tự động đọc toàn bộ codebase và sinh ra 3 loại tài liệu kỹ thuật bằng tiếng Việt,
lưu trữ cả local và Google Drive (nếu MCP Drive khả dụng).

Mọi tài liệu đầu ra đều tuân theo **chuẩn trình bày AiView** — xem chi tiết header bắt buộc
tại `references/doc-templates.md` (phần "Quy tắc bắt buộc — AiView Document Header").

## Quy trình tổng quát

```
1. Thu thập thông tin dự án (lần đầu) hoặc xác nhận version mới
2. Phân tích codebase
3. Tạo 3 tài liệu song song (mỗi tài liệu dùng AiView header chuẩn)
4. So sánh với version trước (nếu có)
5. Đóng gói bundle
6. Upload Drive (nếu MCP khả dụng) hoặc xuất file local
```

---

## Bước 1 — Thu thập thông tin khởi tạo

### Lần đầu chạy (chưa có `docs/.doc-meta.json`)

Hỏi người dùng các thông tin sau (hỏi tất cả trong 1 lần):

```
- Tên project (ví dụ: AIView_NVR) → dùng làm prefix thư mục: AIView_NVR_Va.b.c
- Version khởi tạo (mặc định: 1.0.0.0)
- OS chính của project (để viết hướng dẫn): Ubuntu / Windows / macOS / Tất cả
- Mô tả ngắn về dự án (1-2 câu, nếu không có trong README)
```

Các trường sau có **giá trị mặc định cố định** — KHÔNG hỏi, tự điền trừ khi người dùng
nói rõ muốn thay đổi:

| Trường | Giá trị mặc định |
|---|---|
| `UNIT` | `Bộ phận AI View - Công ty Cổ phần Bkav AI` |
| `PREPARED_BY` | `Bkav AIView Team` |
| `AUTHORS` | `ToanHM` |
| `APPROVER` | `HaDM` |

Lưu vào `docs/.doc-meta.json` sau khi thu thập.

### Lần tiếp theo (đã có `.doc-meta.json`)

Đọc file meta, hỏi xác nhận:
- Version mới là gì? (gợi ý tăng patch: `a.b.c` → `a.b.(c+1)`)
- Tên thư mục output có giữ format `{TênProject}_V{version}` không?
- Có thay đổi gì về OS/stack so với version trước không?

---

## Bước 2 — Phân tích codebase

Đọc các file theo thứ tự ưu tiên:

```bash
# 1. File cấu hình môi trường & dependencies
find . -name "package.json" -o -name "requirements.txt" -o -name "pyproject.toml" \
       -o -name "pom.xml" -o -name "Cargo.toml" -o -name "go.mod" \
       -o -name "Dockerfile" -o -name "docker-compose*.yml" \
       -o -name ".env.example" -o -name "*.env.template" \
       -o -name "CMakeLists.txt" -o -name "Makefile" \
       -o -name "*.gradle" -o -name "*.gemspec" | head -30

# 2. README và docs có sẵn
find . -name "README*" -o -name "INSTALL*" -o -name "CHANGELOG*" \
       -o -name "docs/**/*.md" 2>/dev/null | head -20

# 3. Git history (nếu có)
git log --oneline --no-walk --tags 2>/dev/null | head -20
git log --oneline -30 2>/dev/null

# 4. Kiểm tra package/installer
find . -name "*.deb" -o -name "*.exe" -o -name "*.msi" \
       -o -name "*.AppImage" -o -name "setup.py" \
       -o -name "installer*" 2>/dev/null | head -10
```

### Suy luận thông tin còn thiếu

**Yêu cầu phần cứng** — nếu không có trong code, suy luận từ stack:
- ML/AI framework (TensorFlow, PyTorch, YOLO, ...) → cần GPU, VRAM tối thiểu
- Video processing (OpenCV, FFmpeg) → CPU cores, RAM
- Database nặng → disk I/O, RAM
- Nếu không chắc: **hỏi người dùng xác nhận** trước khi ghi vào tài liệu

**Yêu cầu hệ điều hành** — nếu không rõ: hỏi người dùng

---

## Bước 2.5 — Kiểm tra version trước (lịch sử sửa đổi)

Trước khi tạo tài liệu, tìm version trước gần nhất để điền bảng lịch sử sửa đổi:

```bash
# 1. Tìm local trước
ls docs/history/ 2>/dev/null | sort -V | tail -1

# 2. Nếu không có local → tìm trên Drive (MCP search_files)
#    search: "{TênProject} V" trong folder Release Version (ID: 168doQ29WfTK-adfLmhL85fKG_80Xs2yk)
```

**Nếu tìm thấy version trước:**
- Đọc `.doc-meta.json` của version đó (local) hoặc tên folder (Drive) để lấy ngày phát hành
- Xây `HISTORY` array cho meta.json: tất cả version cũ hơn version hiện tại, sắp xếp từ cũ đến mới
- filler tự chèn thêm hàng vào bảng "Lịch sử sửa đổi" (xem `scripts/fill_template.py`)

```json
"HISTORY": [
  {"version":"1.0.0.0","date":"17/06/2026","authors":"ToanHM","approver":"HaDM","description":"Khởi tạo"}
]
```

**Nếu không tìm thấy:** không cần HISTORY, bảng sẽ có 1 dòng (version hiện tại).

---

## Bước 3 — Tạo tài liệu

Xem hướng dẫn chi tiết format từng tài liệu tại: `references/doc-templates.md`

**Cơ chế tạo `.docx` = copy template mẫu + điền placeholder** (KHÔNG dựng Word từ
đầu, KHÔNG cần skill `docx`). Bản mẫu trang bìa AiView thật (logo + font nhúng +
bảng phiên bản) nằm sẵn tại `assets/template.docx`, fill bằng `scripts/fill_template.py`.

**"Hướng dẫn cài đặt V{N}" là tài liệu TĨNH** — chỉ tạo mới (V2, V3, ...) khi cách
đóng gói hoặc quy trình deploy thực sự thay đổi. Nếu không thay đổi, **bỏ qua** bước
tạo doc này và dùng link Drive của version V cũ nhất đang có.

Với mỗi tài liệu, làm 2 việc:

1. **Viết phần thân thành Markdown** (`bodyN.md`) — chỉ nội dung sau trang bìa.
   Parser hỗ trợ: `#`/`##`/`###`/`####` → Heading1–4, bảng `| ... |`, bullet `- `,
   code block ```` ``` ````, inline `**bold**` và `` `code` ``.
2. **Viết giá trị bìa thành JSON** (`metaN.json`) — map đúng tên placeholder của
   template (xem bảng trong `references/doc-templates.md`).

Rồi chạy filler (yêu cầu `python3` + `python-docx`):

```bash
SKILL=.claude/skills/project-doc-generator
python3 "$SKILL/scripts/fill_template.py" \
    "$SKILL/assets/template.docx" metaN.json bodyN.md "{out}/docN_*.docx"
```

Filler tự: thay placeholder bìa ở cấp *run* (giữ nguyên format/logo/font) → chèn
thân vào marker `{{CONTENT_START}}` → xóa marker → lưu `.docx`.

**Cũng tạo bản `.md`** cho mỗi tài liệu (ghép header rút gọn + `bodyN.md`) để
xem nhanh và để bước so sánh version đọc.

**Verify sau khi fill:** mở lại `.docx`, đảm bảo KHÔNG còn chuỗi `{{...}}` sót lại.

**Bắt buộc:** Mọi placeholder bìa AiView (logo, bảng phiên bản, đơn vị, lịch sử
sửa đổi, mục lục) đã có sẵn trong `assets/template.docx` — chỉ điền giá trị, KHÔNG
bỏ qua hay tự dựng lại trang bìa.

### Cấu trúc thư mục output

```
{TênProject}_V{version}/
├── doc1_huong_dan_cai_dat.docx      ← "Hướng dẫn cài đặt V{N}" — chỉ tạo nếu method đổi
├── doc1_huong_dan_cai_dat.md
├── doc2_changelog.docx
├── doc2_changelog.md
├── release_info.md                  ← 2 link: cài đặt môi trường + hướng dẫn cài đặt đúng V
└── bao_cao_so_sanh.md               ← chỉ tạo nếu có version trước
```

---

## Bước 4 — So sánh với version trước

**Điều kiện:** Chỉ chạy nếu tìm thấy thư mục version trước trong `docs/history/`.

```bash
# Tìm version trước gần nhất
ls docs/history/ | sort -V | tail -1
```

**Nếu không tìm thấy:** Thông báo "Không tìm thấy version trước để so sánh — đây là lần tạo tài liệu đầu tiên." rồi bỏ qua bước này.

**Nếu tìm thấy:** So sánh theo logic trong `references/comparison-logic.md`

Kết quả ghi vào `bao_cao_so_sanh.md` với format:
```markdown
# Báo cáo So sánh Tài liệu
**Version hiện tại:** X.X.X.X  
**Version trước:** Y.Y.Y.Y  
**Ngày tạo:** DD/MM/YYYY

## ✅ Không thay đổi
- ...

## ⚠️ Có sai khác / Cập nhật
| Mục | Version cũ | Version mới | Ghi chú |
|-----|-----------|------------|---------|
| ... | ... | ... | ... |

## ❌ Mâu thuẫn giữa các tài liệu
- [Tên mục]: Doc1 ghi "X" nhưng Doc2 ghi "Y" → Cần review
```

---

## Bước 5 — Đóng gói Bundle

### Chạy package.sh
```bash
# Từ project root (máy dev — đã build/pull xong image)
./package.sh              # bundle đầy đủ (~5GB với Frigate)
./package.sh --no-frigate # bỏ Frigate nếu máy đích đã có sẵn
```

Sau khi package.sh chạy xong, **đổi tên** output thành `V{version}-{YYYYMMDD}.tar.gz`:
```bash
DATE=$(date +%Y%m%d)
mv nvr-bundle-${DATE}.tar.gz V{version}-${DATE}.tar.gz
# Ví dụ: V1.0.0.1-20260617.tar.gz
```

### Tạo release_info.md (đặt trong thư mục output)

```markdown
# AIView NVR — Release V{version}

**Ngày phát hành:** DD/MM/YYYY

## Tải về & Cài đặt

- **Hướng dẫn cài đặt:** [{tên doc — vd "Hướng dẫn cài đặt V1"}]({Drive link}
- **Cài đặt môi trường biên dịch (dành cho dev):** [Cài đặt môi trường biên dịch]({Drive link})

## Package

File bundle: `V{version}-{YYYYMMDD}.tar.gz`

## Thay đổi trong version này

{Tóm tắt 3–5 dòng changelog chính}
```

**Lấy link Drive "Hướng dẫn cài đặt V{N}" hiện tại:**
- Dùng MCP `search_files` tìm theo tên trong folder root `1U8PC6Tpq1zdIFIkY2FJD3gJIxtJpfAm9`
- Nếu chưa có → tạo mới (xem Bước 3), upload rồi lấy link

---

## Bước 6 — Upload Google Drive

### Kiểm tra MCP Drive
```javascript
// Thử gọi MCP Drive tool — nếu thành công thì upload, nếu lỗi thì bỏ qua
```

**Nếu MCP Drive khả dụng:**
Upload theo cấu trúc Drive định nghĩa tại `references/drive-structure.md`:

1. **"Hướng dẫn cài đặt V{N}"** (chỉ khi tạo mới) — upload docx bằng `rclone` vào folder root
2. **Tạo thư mục** `Release Version/Version V{version}/` (MCP `create_file` với mimeType folder)
3. **Upload tar.gz** (`V{version}-{date}.tar.gz`) vào thư mục version — dùng `rclone`
4. **Upload release_info.md** (MCP `create_file` — text nhỏ, không cần rclone)
5. Lấy link share các file vừa upload → điền vào `release_info.md` nếu cần cập nhật link

**Nếu MCP Drive không khả dụng:**
- Thông báo: "Google Drive MCP chưa được cấu hình. Tất cả tài liệu đã được lưu local tại `{TênProject}_V{version}/`."
- Xuất Word (.docx) để người dùng tự upload

---

## Lưu lịch sử

Sau khi hoàn thành, lưu bản copy vào `docs/history/`:
```bash
cp -r {TênProject}_V{version}/ docs/history/{TênProject}_V{version}/
```

Cập nhật `docs/.doc-meta.json` với version mới và timestamp.

---

## Xử lý edge cases

| Tình huống | Xử lý |
|-----------|-------|
| Lần đầu chạy, chưa có `.doc-meta.json` | Hỏi thông tin khởi tạo, tạo file meta |
| Không tìm thấy version trước | Bỏ qua so sánh, thông báo cho người dùng |
| Yêu cầu phần cứng không có trong code | Web search theo stack + hỏi người dùng xác nhận |
| Project đa OS | Viết hướng dẫn cho cả 3 OS trong các section riêng |
| Biến môi trường / secrets | Ghi `YOUR_API_KEY_HERE` làm placeholder |
| Không có package installer | Thông báo, để trống thư mục package |
| Tính năng bị xóa trong version mới | Ghi: "Đã xóa vì [lý do] / Thay thế bởi [X]" |
| MCP Drive không khả dụng | Xuất Word local, hướng dẫn upload thủ công |
| Stack đa ngôn ngữ | Ghi tất cả dependencies, nhóm theo stack |
| Người duyệt chưa được khai báo | Hỏi lần đầu, lưu vào `.doc-meta.json` |

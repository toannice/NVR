# Logic So sánh Tài liệu Giữa Các Version

## Mục đích

So sánh tài liệu version hiện tại với version trước để phát hiện:
1. Thay đổi hợp lệ (đã cập nhật đúng)
2. Mâu thuẫn giữa các tài liệu (Doc1 và Doc2 ghi khác nhau về cùng 1 thứ)
3. Thiếu sót (có trong version trước nhưng không có trong version mới)

---

## Các mục cần so sánh

### Doc1 (Môi trường) vs Doc1 cũ

| Mục kiểm tra | Cách so sánh |
|-------------|-------------|
| Phiên bản OS | So sánh string chính xác |
| Phiên bản runtime (Python, Node, ...) | So sánh version number |
| RAM/CPU tối thiểu | So sánh số |
| Danh sách dependencies | Diff danh sách |
| Biến môi trường | So sánh tên biến (không so sánh giá trị placeholder) |

### Doc2 (Hướng dẫn) vs Doc2 cũ

| Mục kiểm tra | Cách so sánh |
|-------------|-------------|
| Số bước cài đặt | Đếm và so sánh |
| Lệnh cài đặt | So sánh string lệnh |
| Port/URL mặc định | So sánh chính xác |
| Cấu trúc thư mục | So sánh path |

### Cross-check: Doc1 vs Doc2 (cùng version)

Đây là kiểm tra **mâu thuẫn chéo** — quan trọng nhất:

| Mục | Doc1 ghi gì | Doc2 cần nhất quán |
|-----|------------|-------------------|
| Phiên bản Python/Node/... | `python 3.10+` | Lệnh cài: `apt install python3.10` |
| Port ứng dụng | Không đề cập / Để trống | Port trong lệnh chạy |
| Biến môi trường | Danh sách biến | File .env.example trong hướng dẫn |
| OS được hỗ trợ | Ubuntu 20.04+ | Lệnh cài dùng `apt` (→ không có Windows) |

---

## Thuật toán so sánh

### Bước 1: Trích xuất dữ liệu có cấu trúc từ .md

Đọc các file `.md` (dễ parse hơn .docx) từ cả version hiện tại và version trước.

Tìm các pattern:
```python
# Version numbers
r'\d+\.\d+(\.\d+)?(\.\d+)?'  # vd: 3.10, 18.04, 1.2.3

# Package/dependency lines  
r'^[-*]\s+`?[\w\-\.]+`?\s*(==|>=|~=|@)\s*[\d\.]+'  # vd: - numpy>=1.21

# Commands
r'```bash(.*?)```'  # lệnh trong code block

# Environment variables
r'^[A-Z_]+=.*$'  # vd: DATABASE_URL=...
```

### Bước 2: So sánh và phân loại

```
KHÔNG THAY ĐỔI: giống nhau hoàn toàn
CẬP NHẬT: khác nhau, có lý do rõ ràng (version bump, thêm mới)
MÂU THUẪN: Doc1 và Doc2 cùng version nói khác nhau về cùng 1 thứ
THIẾU SÓT: có trong version cũ, không có trong version mới (không có ghi chú xóa)
```

### Bước 3: Tạo báo cáo

Luôn tạo file `bao_cao_so_sanh.md` với đầy đủ 4 mục trên.

Nếu phát hiện **MÂU THUẪN** hoặc **THIẾU SÓT**: in cảnh báo rõ ràng ra terminal và yêu cầu người dùng review trước khi đóng gói bundle.

```
⚠️  CẢNH BÁO: Phát hiện 2 mâu thuẫn trong tài liệu
Xem chi tiết tại: bao_cao_so_sanh.md#mau-thuan
Bạn có muốn tiếp tục đóng gói bundle không? (y/n)
```

---

## Ví dụ báo cáo thực tế

```markdown
# Báo cáo So sánh Tài liệu
**Version hiện tại:** 1.1.0  
**Version trước:** 1.0.0  
**Ngày tạo:** 25/05/2026

## ✅ Không thay đổi (3 mục)
- Yêu cầu phần cứng: RAM 8GB tối thiểu
- OS hỗ trợ: Ubuntu 20.04+
- Port mặc định: 8080

## ⚠️ Có cập nhật (2 mục)
| Mục | Version 1.0.0 | Version 1.1.0 | Ghi chú |
|-----|--------------|--------------|---------|
| Python version | 3.9+ | 3.10+ | Nâng cấp do dùng match statement |
| Thêm biến môi trường | - | REDIS_URL | Tính năng cache mới |

## ❌ Mâu thuẫn giữa Doc1 và Doc2 (1 mục)
| Mục | Doc1 ghi | Doc2 ghi | Cần xử lý |
|-----|---------|---------|----------|
| Node.js version | 18 LTS | 16.x | Không nhất quán — cần xác nhận |

## 🔍 Thiếu sót so với version trước (0 mục)
Không phát hiện thiếu sót.
```

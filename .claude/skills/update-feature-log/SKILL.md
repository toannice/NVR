---
name: update-feature-log
description: >
  Thêm một dòng vào Google Sheet theo dõi tính năng / lỗi NVR tại
  https://docs.google.com/spreadsheets/d/1XgUfYxgW0Aev7JvSE1m3Qs9MXyxaI0AmuXeAPI2m91k
  Kích hoạt khi người dùng nói: "cập nhật docs", "ghi vào sheet", "thêm tính năng vào sheet",
  "log lỗi", "update feature log", "/update-feature-log", hoặc yêu cầu ghi một bug/tính năng
  vào spreadsheet theo dõi dự án.
---

# Update Feature Log — Ghi tính năng / lỗi vào Google Sheet

Sheet ID: `1XgUfYxgW0Aev7JvSE1m3Qs9MXyxaI0AmuXeAPI2m91k`  
Sheet GID: `1860962147`  
Cột: **A** Sản phẩm · **B** Module · **C** Yêu cầu · **D** Lỗi giải · **E** License · **F** Ghi chú

---

## Bước 1 — Phân tích yêu cầu người dùng

Đọc mô tả người dùng và xác định:

**Loại entry:**
- **`feature`** (tính năng mới): có License → điền đủ cột A, B, C, D, E, F
- **`bug`** (lỗi / việc cần làm): KHÔNG có License → chỉ điền A, B, C, F; để trống D và E

**Mapping cột từ mô tả người dùng:**
| Cột | Ý nghĩa | Cách suy ra |
|-----|---------|-------------|
| A — Sản phẩm | Tên sản phẩm (vd: `NVR`, `Box`) | Thường nói rõ ở đầu ("NVR: ...") |
| B — Module | Tên module / tính năng ngắn gọn | Phần đề cập đến component/chức năng |
| C — Yêu cầu | Mô tả ngắn gọn yêu cầu / vấn đề | Phần "sau này cần...", "fix...", "tách..." |
| D — Lỗi giải | Công nghệ / giải pháp đã dùng (chỉ feature) | Phần "dùng...", "bằng...", "qua..." |
| E — License | License của thư viện/tool dùng (chỉ feature) | Nếu nói rõ hoặc suy ra từ D |
| F — Ghi chú | Thông tin bổ sung, context, trạng thái | Phần hiện trạng, giới hạn, workaround |

Tóm tắt lại các giá trị sẽ ghi cho người dùng xem trước khi tiến hành.

---

## Bước 2 — Kiểm tra authentication

Chạy:

```bash
python3 -c "import gspread; print('gspread', gspread.__version__)" 2>/dev/null || echo "MISSING"
ls ~/.config/gspread/credentials.json 2>/dev/null && echo "CREDS_OK" || echo "CREDS_MISSING"
ls ~/.config/gspread/authorized_user.json 2>/dev/null && echo "TOKEN_OK" || echo "TOKEN_MISSING"
```

**Nếu `CREDS_MISSING`:** Dừng lại và hiện hướng dẫn setup:

```
=== SETUP LẦN ĐẦU (chỉ làm 1 lần) ===
1. Vào https://console.cloud.google.com/ → chọn project
2. APIs & Services → Enable APIs → bật "Google Sheets API" + "Google Drive API"
3. APIs & Services → Credentials → Create Credentials → OAuth 2.0 Client ID
   - Application type: Desktop App · Name: NVR Sheet Updater
4. Download JSON → lưu vào:
   mkdir -p ~/.config/gspread
   mv ~/Downloads/client_secret_*.json ~/.config/gspread/credentials.json
5. Chạy lại skill — trình duyệt mở để xác thực, token tự lưu.
=======================================
```

**Nếu `CREDS_OK`:** tiếp bước 3.

---

## Bước 3 — Append dòng vào sheet

Chạy Python script inline qua Bash. Thay thế các giá trị `PRODUCT`, `MODULE`, `REQUIREMENT`, `SOLUTION`, `LICENSE`, `NOTE` bằng nội dung đã phân tích ở Bước 1. Với loại **bug**: truyền `SOLUTION=""` và `LICENSE=""`.

```bash
python3 << 'PYEOF'
import gspread, sys

SPREADSHEET_ID = "1XgUfYxgW0Aev7JvSE1m3Qs9MXyxaI0AmuXeAPI2m91k"
SHEET_GID      = 1860962147
CREDS          = "/root/.config/gspread/credentials.json"
TOKEN          = "/root/.config/gspread/authorized_user.json"

# ==== THAY GIÁ TRỊ Ở ĐÂY ====
ENTRY_TYPE  = "bug"           # "bug" hoặc "feature"
PRODUCT     = "NVR"
MODULE      = "Authentication Git"
REQUIREMENT = "Tách thành 2 tài khoản dev và thương mại riêng"
SOLUTION    = ""              # chỉ feature
LICENSE     = ""              # chỉ feature
NOTE        = ""
# ==============================

import os
creds_path = os.path.expanduser("~/.config/gspread/credentials.json")
token_path = os.path.expanduser("~/.config/gspread/authorized_user.json")

try:
    gc = gspread.oauth(
        credentials_filename=creds_path,
        authorized_user_filename=token_path,
        scopes=[
            "https://www.googleapis.com/auth/spreadsheets",
            "https://www.googleapis.com/auth/drive.file",
        ],
    )
except Exception as e:
    print(f"AUTH ERROR: {e}"); sys.exit(1)

try:
    sp = gc.open_by_key(SPREADSHEET_ID)
except Exception as e:
    print(f"OPEN ERROR: {e}"); sys.exit(1)

ws = next((w for w in sp.worksheets() if w.id == SHEET_GID), sp.get_worksheet(0))

if ENTRY_TYPE == "feature":
    row = [PRODUCT, MODULE, REQUIREMENT, SOLUTION, LICENSE, NOTE]
else:
    row = [PRODUCT, MODULE, REQUIREMENT, "", "", NOTE]

ws.append_row(row, value_input_option="USER_ENTERED")
n = len(ws.get_all_values())
print(f"OK: dòng {n} đã thêm vào sheet '{ws.title}'")
print(f"  A={row[0]} | B={row[1]} | C={row[2][:50]}")
PYEOF
```

> **Quan trọng:** Khi chạy Bash block trên, điền đúng giá trị từ Bước 1 vào phần `THAY GIÁ TRỊ`. Không để hardcode ví dụ mẫu.

---

## Bước 4 — Báo kết quả

Sau khi script chạy thành công:
- Hiển thị tóm tắt dòng vừa thêm (A, B, C và F nếu có)
- Link trực tiếp đến sheet: `https://docs.google.com/spreadsheets/d/1XgUfYxgW0Aev7JvSE1m3Qs9MXyxaI0AmuXeAPI2m91k/edit#gid=1860962147`
- Nếu lỗi: hiện error message đầy đủ và gợi ý fix cụ thể

---

## Ví dụ

**Input người dùng:**
> NVR: hiện tại đang fix cứng cho một thiết bị mới có Authen git dùng chung. Sau này tìm cách tách ra 2 tài khoản dev và chạy riêng.

**Phân tích:**
- Loại: `bug` (không có license, là việc cần làm sau)
- A: `NVR`
- B: `Authentication Git`
- C: `Tách thành 2 tài khoản dev và thương mại riêng`
- D: _(trống)_
- E: _(trống)_
- F: `Hiện fix cứng 1 tài khoản dùng chung cho thiết bị mới`

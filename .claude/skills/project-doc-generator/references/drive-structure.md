# Cấu trúc Google Drive & Hướng dẫn Upload

## Thư mục gốc Drive (destination)

**Target folder ID:** `1U8PC6Tpq1zdIFIkY2FJD3gJIxtJpfAm9`
**URL:** https://drive.google.com/drive/u/0/folders/1U8PC6Tpq1zdIFIkY2FJD3gJIxtJpfAm9

Tất cả upload phải nằm bên trong folder ID này (dùng làm `parent` khi tạo file/folder).

## Cấu trúc thư mục Drive

```
Root/   ← ID: 1U8PC6Tpq1zdIFIkY2FJD3gJIxtJpfAm9
├── Cài đặt môi trường biên dịch   ← folder ID: 1OtPxfxjempLKShy28xij62AP2C1Il4R5 (doc dev, cố định)
├── Hướng dẫn cài đặt V1           ← docx tĩnh (chỉ tạo V2 khi method đổi) — upload bằng rclone
└── Release Version/               ← folder ID: 168doQ29WfTK-adfLmhL85fKG_80Xs2yk
    └── Version V{a.b.c.d}/        ← tạo mới mỗi release (MCP create_file, mimeType: folder)
        ├── V{a.b.c.d}-{YYYYMMDD}.tar.gz   ← bundle offline — upload bằng rclone
        └── release_info.md                ← text đơn giản: 2 link + changelog tóm tắt (MCP create_file)
```

---

## Tài liệu dạng .docx (template AiView)

Dùng `assets/template.docx` local + `scripts/fill_template.py` để tạo file .docx.  
Upload lên Drive bằng `rclone` (MCP `create_file` không đủ để upload file có logo ~85KB):

```bash
~/.local/bin/rclone copyto "Hướng dẫn cài đặt V1.docx" \
    "gdrive:Hướng dẫn cài đặt V1.docx" --drive-root-folder-id=1fgTDrT8vZCndNYTV4SQIMkRYbH3q1ksP -v

~/.local/bin/rclone copyto "Môi trường biên dịch V1.docx" \
    "gdrive:Môi trường biên dịch V1.docx" --drive-root-folder-id=1OtPxfxjempLKShy28xij62AP2C1Il4R5 -v
```

**Quy tắc đặt tên file (quan trọng):** Tên file trên Drive và local PHẢI là tên người đọc thấy — không dùng tên kỹ thuật nội bộ:

| Tài liệu | Tên đúng | Tên SAI |
|---|---|---|
| Hướng dẫn cài đặt | `Hướng dẫn cài đặt V1.docx` | `doc1_huong_dan_cai_dat.docx` |
| Môi trường biên dịch | `Môi trường biên dịch V1.docx` | `doc3_moi_truong_bien_dich.docx` |
| Changelog | `Changelog V{version}.docx` | `doc2_changelog.docx` |

`release_info.md` (text thuần) dùng MCP `create_file` trực tiếp — không cần rclone.

---

## Quy trình upload mỗi release

1. **Tạo thư mục version** `Version V{a.b.c.d}/` trong folder Release Version — MCP `create_file` (mimeType: `application/vnd.google-apps.folder`, parent: `168doQ29WfTK-adfLmhL85fKG_80Xs2yk`)
2. **Upload tar.gz** vào thư mục vừa tạo — `rclone`:
   ```bash
   ~/.local/bin/rclone copy V{version}-{date}.tar.gz \
       "gdrive:" --drive-root-folder-id=<id_thư_mục_version> -v
   ```
3. **Upload release_info.md** — MCP `create_file` (text/plain, parent: id thư mục version)
4. **Upload "Hướng dẫn cài đặt V{N}.docx"** (chỉ khi method mới) vào folder root — `rclone`:
   ```bash
   ~/.local/bin/rclone copy "Hướng dẫn cài đặt V1.docx" \
       "gdrive:" --drive-root-folder-id=1U8PC6Tpq1zdIFIkY2FJD3gJIxtJpfAm9 -v
   ```

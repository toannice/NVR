# Hướng dẫn Quản lý & Cập nhật Đội ngũ NVR (Fleet Management Guide)

Tài liệu này hướng dẫn bạn cách thiết lập hệ thống để có thể ngồi tại nhà mà vẫn cập nhật được toàn bộ các máy trạm NVR ở các tỉnh thành khác nhau.

---

## Bước 1: Thiết lập Docker Hub (Kho chứa bản vá)

Bạn cần một nơi để "đựng" code đã đóng gói để các máy trạm có thể tải về.

1.  **Tạo tài khoản:** Đăng ký tại [hub.docker.com](https://hub.docker.com/).
2.  **Tạo Repository:**
    *   Đặt tên là `nvr-wrapper`.
    *   **Public (Công khai):** Miễn phí hoàn toàn, ai cũng có thể thấy code của bạn.
    *   **Private (Riêng tư):** Chỉ bạn mới thấy, bảo mật tuyệt đối (Docker Hub cho phép 1 repository private miễn phí). **Nên dùng Private.**

---

## Bước 2: Build & Push Image (Thực hiện trên máy cá nhân của bạn)

Mỗi khi bạn sửa code Frontend hoặc Backend xong:

1.  **Đăng nhập từ Terminal:**
    ```bash
    docker login
    ```
2.  **Build và Gắn nhãn (Tag):**
    ```bash
    # Thay 'toan_nvr' bằng username Docker Hub của bạn
    docker build -t toan_nvr/nvr-wrapper:latest .
    ```
3.  **Đẩy lên mây (Push):**
    ```bash
    docker push toan_nvr/nvr-wrapper:latest
    ```

---

## Bước 3: Thiết lập Tailscale (Mạng quản lý ảo)

Để bạn có thể "chạm" vào các máy ở xa mà không bị chặn bởi tường lửa:

1.  **Đăng ký:** [tailscale.com](https://tailscale.com/).
2.  **Lấy Auth Key:** Vào `Settings` -> `Keys` -> `Generate auth key`.
3.  **Tự động hóa:** Tôi đã lưu sẵn key của bạn vào file [.env](file:///home/toan/toanDoc/Documents/my-nvr/.env). Khi bạn chạy `./run.sh` trên máy mới, nó sẽ tự nhận diện và hỏi bạn có muốn kết nối không. Rất nhanh chóng!

---

## Bước 4: Cập nhật hàng loạt bằng Ansible (Bấm nút là xong)

Khi đã có Tailscale, bạn không cần login từng máy nữa.

1.  **Khai báo danh sách máy:** Mở file `/etc/ansible/hosts` trên máy bạn và thêm các IP Tailscale của máy trạm:
    ```ini
    [nvr_nodes]
    100.65.10.20
    100.65.10.21
    100.65.11.5
    ```
2.  **Chỉnh sửa file [deploy.yml](file:///home/toan/toanDoc/Documents/my-nvr/deploy.yml):** 
    Sửa dòng `nvr_wrapper_image` thành tên repository bạn đã tạo ở Bước 1.
3.  **Ra lệnh cập nhật:**
    ```bash
    ansible-playbook deploy.yml
    ```
    *Kết quả: Toàn bộ 100 máy sẽ tự động Pull bản mới nhất từ Docker Hub và khởi động lại trong 30 giây.*

---

## Tổng kết Quy trình làm việc hàng ngày:
1. Sửa code trên máy cá nhân.
2. `docker build` + `docker push` lên Docker Hub.
3. Chạy `ansible-playbook deploy.yml`.
4. **Xong.** Toàn bộ hệ thống đã được cập nhật bản mới nhất.

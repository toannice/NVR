# Third-Party Licenses / NOTICES

**Sản phẩm:** NVR stack (nvr-wrapper + watchdog + edge-agent + enroll app)
**Cập nhật:** 2026-06-12

Tài liệu này liệt kê toàn bộ thành phần bên thứ ba được **phân phối kèm** sản phẩm và
giấy phép tương ứng, nhằm thoả nghĩa vụ attribution của các giấy phép permissive
(MIT/BSD/Apache) và weak-copyleft (EPL/LGPL/GPL-with-Classpath-Exception).

## Mô hình phân phối (vì sao không bị "lây" copyleft)

Sản phẩm là tập hợp các **process độc lập** chạy trong các container/binary riêng,
giao tiếp qua mạng (MQTT/HTTP/RTSP/WebRTC). Đây là **mere aggregation / distribution**,
không phải *combined/derivative work*. Code ứng dụng (`vn.aiview.nvr`, edge-agent,
signing-service) **không** link tĩnh vào bất kỳ thành phần copyleft mạnh nào.

Nghĩa vụ thực tế còn lại chỉ là **attribution** (kèm file này + license text) và,
với các thành phần EPL/GPL+CE phân phối nguyên trạng, **cung cấp source khi được yêu cầu**
(xem mục "Written Offer" cuối file). Không có nghĩa vụ mở mã phần độc quyền của bạn.

---

## ⚠️ Cảnh báo AGPL — Mô hình nhận dạng (object detection models)

**Frigate core là MIT.** Tuy nhiên Frigate **không bundle** model YOLO của Ultralytics
vì Ultralytics là **AGPL-3.0**. Hệ quả ràng buộc với sản phẩm này:

- Chỉ ship/khuyến nghị model **không-AGPL**: SSDLite MobileNet (mặc định CPU),
  Coral EdgeTPU model, hoặc model giấy phép permissive.
- **TUYỆT ĐỐI không** bundle / preload model Ultralytics YOLO (v5/v8/v11…) vào image
  hay config mặc định — sẽ kéo nghĩa vụ AGPL vào bản phân phối.
- Frigate+ (model service trả phí) là **dịch vụ riêng có ToS riêng**, không redistribute.

---

## Bảng kiểm kê thành phần

### A. Docker base images & engines (ship nguyên trạng)

| Thành phần | Phiên bản | License (SPDX) | Bản quyền | Nguồn |
|---|---|---|---|---|
| Frigate | 0.16.3 | MIT | © 2020 Blake Blackshear | https://github.com/blakeblackshear/frigate |
| go2rtc (trong Frigate) | (theo Frigate 0.16.3) | MIT | © Alexey Khit | https://github.com/AlexxIT/go2rtc |
| Eclipse Mosquitto | 2.x | EPL-2.0 OR EDL-1.0 | © Eclipse Foundation, Roger Light | https://github.com/eclipse/mosquitto |
| nginx | stable-alpine | BSD-2-Clause | © Igor Sysoev, Nginx Inc. | https://nginx.org |
| Eclipse Temurin / OpenJDK | 21 (jdk build, jre runtime) | GPL-2.0-only WITH Classpath-exception-2.0 | © Oracle and/or its affiliates | https://adoptium.net |
| Alpine Linux base | (theo image) | mix: MIT / BSD / GPL-2.0 (busybox), MIT (musl) | Alpine + upstream | https://alpinelinux.org |
| node (build stage 22-alpine) | — *(build-time only, KHÔNG ship)* | mix | OpenJS Foundation | https://nodejs.org |

### B. Backend & Watchdog — JVM deps (bundle vào fat-dist trong image)

| Thành phần | Phiên bản | License (SPDX) | Bản quyền |
|---|---|---|---|
| Ktor (server/client + plugins) | 3.0.1 | Apache-2.0 | © JetBrains s.r.o. |
| Kotlin stdlib | 2.0.21 | Apache-2.0 | © JetBrains s.r.o. |
| kotlinx.coroutines | 1.9.0 | Apache-2.0 | © JetBrains s.r.o. |
| kotlinx.serialization (json) | 1.7.3 | Apache-2.0 | © JetBrains s.r.o. |
| JetBrains Exposed (core/jdbc/java-time) | 0.56.0 | Apache-2.0 | © JetBrains s.r.o. |
| Xerial sqlite-jdbc | 3.47.0.0 | Apache-2.0 | © Taro L. Saito, Xerial |
| SnakeYAML | 2.3 | Apache-2.0 | © the SnakeYAML contributors |
| at.favre.lib bcrypt | 0.10.2 | Apache-2.0 | © Patrick Favre-Bulle |
| Logback (classic/core) | 1.4.14 | EPL-1.0 OR LGPL-2.1 | © QOS.ch |
| SLF4J (kéo theo logback) | (theo logback) | MIT | © QOS.ch |
| Eclipse Paho MQTT v3 | 1.2.5 | EPL-1.0 OR EDL-1.0 | © Eclipse Foundation |
| Jakarta Mail (com.sun.mail / Angus) | 2.0.1 | EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0 OR BSD-3-Clause | © Oracle, Eclipse Foundation |
| Netty (kéo theo Ktor Netty) | (theo Ktor 3.0.1) | Apache-2.0 | © The Netty Project |

### C. Frontend — runtime deps (bundle vào `dist/` phục vụ tĩnh)

| Thành phần | Phiên bản | License (SPDX) | Bản quyền |
|---|---|---|---|
| React | 19.2.x | MIT | © Meta Platforms, Inc. |
| react-dom | 19.2.x | MIT | © Meta Platforms, Inc. |
| hls.js | 1.6.x | Apache-2.0 | © Dailymotion |
| Tailwind CSS (output) | 4.1.x | MIT | © Tailwind Labs, Inc. |

> *Build-time only (KHÔNG có trong artifact ship, không cần attribution): vite, typescript,
> eslint, postcss, autoprefixer, @vitejs/plugin-react và các @types/@eslint devDependencies.*

### C-bis. Fonts & Icons (self-hosted — bundle vào `dist/`, KHÔNG tải CDN)

| Thành phần | License (SPDX) | Bản quyền | Ghi chú |
|---|---|---|---|
| Material Icons (font) | Apache-2.0 | © Google LLC | self-host `frontend/public/fonts/material-icons.woff2`; `@font-face` trong `src/index.css`. **Đã gỡ link Google CDN** → chạy được air-gapped |

> Trước đây nạp runtime từ `fonts.googleapis.com` → vỡ icon khi không có Internet.
> Nay self-host nên không còn phụ thuộc mạng ngoài. Font **Inter** (CSS `font-family`)
> KHÔNG được bundle — chỉ là preference, tự fallback `system-ui` nếu máy không có (an toàn offline).

### D. Go sidecars & overlay (binary ship/cài trên thiết bị)

| Thành phần | License (SPDX) | Bản quyền | Ghi chú |
|---|---|---|---|
| dc-bridge → Pion WebRTC | MIT | © The Pion authors | binary `data/dc-bridge` (linux/amd64) |
| Nebula | MIT | © Slack Technologies, Inc. | overlay runtime trên HQ + gateway |
| edge-agent (`gateway/edge-agent`, `gateway/nebula/edge-agent`) | *(code nội bộ)* | © 2026 [TÊN CÔNG TY — CHỜ ĐĂNG KÝ] | go.mod: **chỉ Go stdlib**, không dep ngoài |
| signing-service (`gateway/nebula/hq/signing-service`) | *(code nội bộ)* | © 2026 [TÊN CÔNG TY — CHỜ ĐĂNG KÝ] | go.mod: **chỉ Go stdlib**, không dep ngoài |
| Go standard library / runtime | BSD-3-Clause | © The Go Authors | linh vào các binary tĩnh CGO_ENABLED=0 |

### E. Android enroll app (`gateway/android-app`) — nếu phân phối APK

| Thành phần | License (SPDX) | Bản quyền |
|---|---|---|
| AndroidX (core-ktx, lifecycle, activity-compose) | Apache-2.0 | © The Android Open Source Project / Google LLC |
| Jetpack Compose (BOM 2024.10.01, ui, material3, icons) | Apache-2.0 | © The Android Open Source Project / Google LLC |
| kotlinx-coroutines-android | Apache-2.0 | © JetBrains s.r.o. |
| kotlinx-serialization-json | Apache-2.0 | © JetBrains s.r.o. |
| OkHttp | Apache-2.0 | © Square, Inc. |

---

## Nghĩa vụ theo từng nhóm license

- **MIT / BSD-2 / BSD-3 / ISC:** kèm license text + copyright notice (đã liệt kê trên + Appendix). Không hạn chế thương mại.
- **Apache-2.0:** kèm bản copy của license (Appendix A.3); nếu upstream có file `NOTICE`, giữ kèm; ghi rõ nếu bạn sửa file của họ.
- **EPL-1.0 / EPL-2.0:** weak copyleft **mức file**. Ship nguyên trạng → chỉ cần attribution + cung cấp source của *chính thành phần đó* khi được yêu cầu (Written Offer). Code của bạn không bị ảnh hưởng.
- **LGPL-2.1 (lựa chọn thay thế của Logback):** chọn nhánh EPL-1.0 để đơn giản; nếu dùng LGPL, không link tĩnh-sửa-đổi (ta dùng nguyên trạng → OK).
- **GPL-2.0 WITH Classpath-exception-2.0 (OpenJDK, Jakarta Mail GPL branch):** Classpath Exception cho phép app chạy/link trên runtime **không** bị GPL. Ship JRE/jar nguyên trạng → chỉ attribution + Written Offer cho source của thành phần đó.

---

# Appendix A — Full license texts

## A.1 — The MIT License (MIT)

Áp dụng cho: Frigate, go2rtc, React, react-dom, Tailwind CSS, SLF4J, Pion WebRTC, Nebula.

```
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## A.2 — BSD 2-Clause License

Áp dụng cho: nginx.

```
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## A.2b — BSD 3-Clause License

Áp dụng cho: Go standard library / runtime.

```
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its contributors
   may be used to endorse or promote products derived from this software
   without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES ... (disclaimer như BSD-2 ở trên).
```

## A.3 — Apache License 2.0

Áp dụng cho: Ktor, Kotlin, kotlinx.coroutines, kotlinx.serialization, Exposed,
sqlite-jdbc, SnakeYAML, bcrypt (favre.lib), Netty, hls.js, AndroidX, Jetpack
Compose, OkHttp.

Toàn văn: https://www.apache.org/licenses/LICENSE-2.0.txt — tóm tắt điều khoản
bắt buộc (Section 4): khi redistribute phải (a) kèm bản copy License này;
(b) giữ mọi copyright/patent/trademark/attribution notice; (c) nếu có file
`NOTICE` của upstream thì giữ kèm; (d) ghi rõ các file bạn đã sửa. Apache-2.0
cấp quyền patent và cho phép dùng thương mại không hạn chế.

> Khi đóng gói release, đặt một bản đầy đủ `LICENSE-2.0.txt` cạnh file này
> (script ở mục cuối có thể tự tải về).

## A.4 — Eclipse Public License (EPL-1.0 và EPL-2.0)

Áp dụng cho: Mosquitto (EPL-2.0/EDL-1.0), Paho MQTT (EPL-1.0/EDL-1.0),
Logback (EPL-1.0/LGPL-2.1), Jakarta Mail (EPL-2.0/…).

- EPL-1.0 toàn văn: https://www.eclipse.org/legal/epl-v10.html
- EPL-2.0 toàn văn: https://www.eclipse.org/legal/epl-2.0/

Tóm tắt: weak copyleft mức **"Program"** (thường là từng module/file của chính
thành phần đó). Phân phối nguyên trạng các binary này **không** ràng buộc code
độc lập của bạn. Nghĩa vụ: kèm thông báo license này và, khi được yêu cầu,
cung cấp source của *chính thành phần EPL đó* (xem Written Offer).

## A.5 — GPL-2.0 WITH Classpath Exception

Áp dụng cho: OpenJDK / Eclipse Temurin 21, nhánh GPL của Jakarta Mail.

- GPL-2.0 toàn văn: https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt
- Classpath Exception: https://openjdk.org/legal/gplv2+ce.html

Tóm tắt: Classpath Exception cho phép **liên kết/chạy** ứng dụng của bạn trên
runtime mà không khiến ứng dụng trở thành tác phẩm phái sinh GPL. Ship JRE/jar
nguyên trạng → chỉ attribution + Written Offer cho source của *chính các thành
phần này*.

---

# Appendix B — Written Offer (source code)

Đối với các thành phần phân phối theo EPL-1.0/EPL-2.0 và GPL-2.0-with-Classpath-Exception
(Mosquitto, Paho MQTT, Logback, Jakarta Mail, OpenJDK/Temurin), mã nguồn tương ứng
**phiên bản nguyên trạng** chúng tôi phân phối có sẵn tại các kho upstream được liệt
kê ở bảng trên. Theo yêu cầu bằng văn bản gửi tới `[EMAIL_HỖ_TRỢ — CHỜ CHỐT]`, chúng tôi sẽ cung
cấp bản sao mã nguồn của các thành phần đó trong thời hạn luật định. Chúng tôi **không**
sửa đổi mã nguồn của các thành phần này.

> TODO trước khi phát hành: thay `<EMAIL_HỖ_TRỢ>` và điền chủ sở hữu bản quyền (`© ...`)
> ở các dòng "code nội bộ" mục D.

---

# Appendix C — Trích xuất license text chính xác lúc release

NOTICES này dùng SPDX + bản quyền cấp dự án. Để tuân thủ chặt nhất, lúc đóng gói
release nên vendor đúng file `LICENSE`/`NOTICE` của từng package từ artifact đã build:

```bash
# JVM jars (fat-dist): liệt kê jar và rút license trong META-INF
find backend/lib -name '*.jar' -exec sh -c \
  'unzip -l "$1" | grep -iE "LICENSE|NOTICE"' _ {} \;

# npm (license của deps thực sự bundle):
cd frontend && npx license-checker --production --out ../THIRD_PARTY_npm.txt

# Tải các license text chuẩn chưa nhúng đầy đủ ở trên:
curl -sL https://www.apache.org/licenses/LICENSE-2.0.txt   -o licenses/Apache-2.0.txt
curl -sL https://www.eclipse.org/legal/epl-2.0/            -o licenses/EPL-2.0.html
curl -sL https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt -o licenses/GPL-2.0.txt
```

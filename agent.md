# Wisper Workspace Instructions (NVNMC Hosting)

Tài liệu này là **Quy chuẩn không gian làm việc cấp cao nhất** dành cho AI Agents và Lập trình viên khi làm việc trên dự án **Wisper** (`furimeo/wisper`), vận hành trên hạ tầng máy chủ của hệ sinh thái **NVNMC Hosting**.

---

## 0. TỔNG QUAN HỆ THỐNG & ĐỊA CHỈ TRUY CẬP

- **Mục tiêu dự án:** Nền tảng Web & Containerized App Hosting (Apps, Static Sites, Managed Databases, Web Terminal, Web File Manager với cơ chế cô lập gVisor).
- **Mã nguồn:** `https://github.com/furimeo/wisper` (checkout tại `D:\wisper`).
- **Máy chủ Production:** VM `192.168.1.149` (hostname: `panel`, VM 101 trên máy chủ Proxmox VE `100.88.64.59` / `pve-prod`).
- **Tên miền công khai:** `https://wisper.nvnmc.cloud/`.
- **Hệ sinh thái:** Nằm trong cụm máy chủ **NVNMC Hosting** cùng với Pterodactyl Game Panel (`panel.nvnmc.cloud`) và NVN Dashboard (`dash.nvnmc.cloud`).
- **Hai phần mềm cấu thành:**
  1. **wisper (Panel):** Java 21 LTS + Spring Boot 4.1.x MVC + Spring Data JDBC + PostgreSQL 17 + React 19/Inertia/Vite đóng gói thành 1 file jar thực thi duy nhất (`/opt/wisper/wisper.jar`). Quản lý trạng thái mong muốn (desired state).
  2. **sasayaki (Node daemon):** Go 1.27 + binary tĩnh tích hợp Caddy làm edge proxy/TLS tự động + Docker Engine API + gVisor (`runsc`) + SQLite cục bộ. Chạy trên từng máy chủ node và **luôn chủ động quay số về Panel qua gRPC port 9090 (dial-out)**.
- **Tài liệu gốc & Hợp đồng kỹ thuật:** Đọc thêm [`AGENTS.md`](AGENTS.md) và các hợp đồng ràng buộc trong [`docs/contracts/`](docs/contracts/README.md). Khi có sự bất đồng về chi tiết contract kỹ thuật nội bộ của Wisper, contract trong `docs/contracts/` là thẩm quyền cao nhất.

---

## 1. NGUYÊN TẮC TỐI THƯỢNG: ĐÁNH GIÁ ẢNH HƯỞNG & DỪNG LẠI KHI CÓ RỦI RO (IMPACT & RISK-FIRST STOP GATE)

Mục đích: Thận trọng tối đa, kiểm soát mọi tác dụng phụ (side-effects), bảo vệ an toàn cho cả Wisper và các dịch vụ khác đang chạy chung máy chủ.

### Phân cấp rủi ro (Risk Tiers)
- **Tier R0 (Read-only):** Đọc code, `git status/diff`, tra cứu logs (`journalctl`), kiểm tra service status, `SELECT` DB an toàn -> Tự do thực hiện.
- **Tier R1 (Source-only Reversible):** Sửa code trên branch/worktree, chạy unit/integration test cục bộ -> Được phép thực hiện trong phạm vi yêu cầu của task.
- **Tier R2 (Controlled Live Reversible):** Build JAR, copy file JAR lên VM, reload service `wisper-panel.service`, sửa route proxy -> Cần pre-flight check, backup bản jar cũ và phương án rollback tức thì.
- **Tier R3 (High-risk / Irreversible):** Chạy migration PostgreSQL production, thao tác `DELETE/DROP` dữ liệu sống, sửa cấu hình mạng/firewall máy chủ, thay đổi key mã hóa `WISPER_CRYPTO_KEY_1` -> BẮT BUỘC Hard Stop, lập phương án và chờ phê duyệt.

### Cổng dừng thao tác ghi (Mutation Stop Gate)
Khi nhận bất kỳ yêu cầu thay đổi nào (sửa code, migration, lệnh hạ tầng, cấu hình systemd):
1. **Phân tích phạm vi ảnh hưởng trước (Impact Analysis First):** Xác định rõ thành phần bị ảnh hưởng trực tiếp và gián tiếp. Kiểm tra nguy cơ xung đột với các dịch vụ anh em trên cùng máy chủ.
2. **Quy tắc dừng an toàn:**
   - Nếu phát hiện dấu hiệu nguy hiểm (Tier R2/R3), nguy cơ tràn đĩa, lỗi mã hóa crypto, sập service web chung hoặc mất dữ liệu:
     - **DỪNG NGAY BƯỚC GHI/THỰC THI RỦI RO.** Tuyệt đối không tự ý chạy lệnh phá hủy lên máy chủ production.
     - **VẪN TIẾP TỤC các khảo sát read-only/phân tích cần thiết** để xác định nguyên nhân, thu thập bằng chứng và chuẩn bị sẵn phương án rollback/thay thế an toàn.
     - Báo cáo rõ: (1) Rủi ro phát hiện, (2) Tại sao nguy hiểm, (3) Phương án đề xuất -> Chờ phê duyệt trước khi thực thi.

---

## 2. BẢN ĐỒ THẨM QUYỀN HỆ THỐNG & RANH GIỚI AN TOÀN (NVNMC HOSTING ECOSYSTEM)

Máy chủ `192.168.1.149` (VM 101 `panel`) là máy chủ dùng chung cho nhiều dịch vụ cốt lõi của NVNMC Hosting. Bắt buộc tuân thủ nghiêm ngặt ranh giới sau:

| Dịch vụ | Tên miền | Công nghệ | Dữ liệu quản lý | Service Systemd | Ranh giới cho phép |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Wisper Panel** | `wisper.nvnmc.cloud` | Java 21, Spring Boot, React | DB PostgreSQL `wisper` | `wisper-panel.service` | **TOÀN QUYỀN TRONG SCOPE TASK** |
| **Pterodactyl Core** | `panel.nvnmc.cloud` | PHP 8.3, Laravel | MariaDB `pterodactyl` | `nginx`, `pteroq.service` | **CẤM CHẠM / CẤM SỬA** |
| **Legacy CtrlPanel** | `dash.nvnmc.cloud` (cũ) | PHP 8.3, Laravel | MariaDB `ctrlpanel` | `ctrlpanel-queue.service` | **CẤM CHẠM / CẤM SỬA** |
| **New Dashboard** | `dash.nvnmc.cloud` (mới) | AdonisJS 7, TypeScript | PostgreSQL `nvn_dashboard` | `nvn-dashboard-preview.service` | **CẤM CHẠM / CẤM SỬA** |
| **Edge / Proxy** | `*.nvnmc.cloud` | Nginx, Cloudflare Tunnel | File config Nginx | `nginx.service`, `cloudflared` | **CHỈ ĐỌC / SỬA KHI CÓ DUYỆT RÕ** |

### Ràng buộc bất biến vùng dữ liệu & Dịch vụ:
1. **Ranh giới cơ sở dữ liệu (Database Isolation):**
   - Wisper chỉ sở hữu và thao tác trên database PostgreSQL `wisper`.
   - Tuyệt đối không đọc, ghi, truy vấn hoặc sửa đổi các bảng của `pterodactyl`, `ctrlpanel`, hay database của dashboard TypeScript.
2. **Ranh giới dịch vụ (Service Isolation):**
   - Tuyệt đối **KHÔNG restart** các service: `nginx`, `php8.3-fpm`, `ctrlpanel-queue.service`, `pteroq.service`, `nvn-dashboard-preview.service`, hoặc daemon `wings`.
   - Mọi thao tác restart chỉ được áp dụng duy nhất lên `wisper-panel.service`.
3. **Cổng chống nhầm máy chủ (Host Gate):**
   - SSH target production luôn là `ssh panel-prod` (hoặc `ssh root@192.168.1.149` qua proxy jump PVE). Trước khi thực thi lệnh thay đổi, phải xác nhận `hostname` trả về đúng là `panel`.
   - Tuyệt đối không chạy lệnh trên VPS cũ `panel-old` (`103.216.127.119`).

---

## 3. NGUYÊN TẮC TƯ DUY & VIẾT CODE: TÁI SỬ DỤNG CÓ KIỂM CHỨNG (DISCOVERY-FIRST & REUSE LADDER)

Mục đích: Chống hiện tượng "tự chế lại bánh xe", giữ vững thiết kế tinh gọn của Wisper.

Mọi yêu cầu thêm tính năng mới hoặc sửa đổi code tuân theo nấc thang:
1. **Khảo sát có điểm dừng (Bounded Discovery First):**
   - Trước khi viết code mới, tra cứu các Record, Use-case class, Repository, Controller hoặc Component đã tồn tại trong domain tương ứng (`auth`, `service`, `deploy`, `files`, `node`, `database`, `backup`...).
   - Dừng tìm kiếm khi đã xác định được architecture owner hoặc file chịu trách nhiệm; không search lan man vô hạn.
2. **Nấc thang xử lý:** `REUSE` (tái sử dụng nếu khớp contract) -> `EXTEND` (mở rộng nếu chỉ thiếu capability nhỏ) -> `CREATE NEW` (chỉ tạo file mới khi chứng minh được component cũ không đáp ứng).
3. **Tối giản & Đúng tầng (Minimum Viable Delta):**
   - Dòng code tốt nhất là dòng code không phải viết. Chỉ tạo delta thực sự cần thiết theo đúng quy ước cấu trúc của Wisper.
4. **Cam kết phạm vi (Task Scope Contract):**
   - Xác định danh sách tối thiểu các file cần tác động. Không format lan man hoặc sửa các file ngoài phạm vi task.

---

## 4. ĐẶC TẢ CÔNG NGHỆ & KIẾN TRÚC BẤT BIẾN CỦA WISPER (WISPER DOCTRINE)

Tất cả quy tắc sau đây là bất biến, đã được định hình trong kiến trúc cốt lõi của Wisper:

### 4.1 Stack kỹ thuật (Khóa cứng - Tuyệt đối không thay thế)
- **Panel Backend:** Java 21 LTS (Virtual Threads ON), Spring Boot 4.1.x MVC.
  - **Dữ liệu:** Spring Data JDBC + PostgreSQL 17. Viết SQL tường minh. Khóa dòng dùng `SKIP LOCKED` và lắng nghe qua `LISTEN/NOTIFY`. **TUYỆT ĐỐI KHÔNG DÙNG JPA/Hibernate.**
  - **Web:** Spring MVC truyền thống trên Virtual Threads. **TUYỆT ĐỐI KHÔNG DÙNG WebFlux.**
  - **Job Queue:** `db-scheduler` chạy trên cùng Postgres (job và dữ liệu commit trong cùng 1 transaction). **KHÔNG DÙNG Redis.**
  - **Thư viện cấm:** Lombok, MapStruct, Guava, Apache Commons, REST API riêng biệt cho UI (Inertia xử lý cầu nối).
- **Panel Frontend:** React 19 + TypeScript + Inertia + Vite (được build gộp trực tiếp vào bên trong file jar).
  - Định hướng giao diện: **Mobile-first**.
- **Realtime:** SSE (Server-Sent Events). Không vận hành thêm WebSocket phức tạp cho notification/stats.
- **Node Daemon (`sasayaki`):** Go 1.27, binary tĩnh, nhúng thư viện Caddy, Docker Engine API + gVisor (`runsc`), SQLite cục bộ.
- **Giao thức:** gRPC + Protobuf (`proto/wisper/v1/`). Node luôn chủ động quay số về Panel.

### 4.2 Nguyên tắc "No stubs. Ever" (Không code giả lập)
> **If it is reachable, it works (Nếu người dùng bấm được tới, nó phải hoạt động thật).**

Nghiêm cấm commit các đoạn code sau:
- `TODO`, `FIXME`, `not implemented`, `UnsupportedOperationException`, `panic("todo")`.
- Hàm trả về `null`/`nil`/mảng rỗng chỉ để thỏa mãn signature của interface.
- Route trên menu điều hướng nhưng render trang trống hoặc placeholder.
- RPC khai báo trong `proto/` nhưng chỉ implement 1 phía (Panel hoặc Node).

### 4.3 Luật cấu trúc file: 1 File = 1 Feature, 1 Folder = 1 Feature Cluster
- **Không có Service class khổng lồ chứa 10 hàm:** Không tạo `DeploymentService`. Mỗi use-case là một file riêng mang tên **động từ** (Ví dụ: `StartDeployment.java`, `BuildArtifact.java`, `PublishRelease.java`, `RollbackRelease.java`).
- **Tổ chức theo Domain, cấm tổ chức theo Layer:**
  - Panel package theo domain nghiệp vụ: `auth`, `org`, `project`, `service`, `deploy`, `domain`, `node`, `placement`, `database`, `backup`, `files`, `stats`, `grpc`, `web`, `migration`.
  - CẤM tạo các folder: `controller/`, `service/`, `dto/`, `model/`, `config/`. Controller nằm ngay cạnh Record và Repository mà nó phục vụ.
- **Các tên thư mục bị CẤM TUYỆT ĐỐI:**
  - Folder: `util/`, `utils/`, `common/`, `shared/`, `helpers/`, `misc/`, `core/`, `base/`.
  - Class/Type: `*Manager`, `*Helper`, `*Util`, `Abstract*`, `Base*` (khi chỉ có 1 implementation duy nhất).
- **Giới hạn độ dài file:**
  - File trên 300 dòng: Phải tách file, hoặc ghi rõ comment ở đầu file giải trình lý do.
  - File trên 500 dòng: Bị từ chối (Rejection). Ngoại lệ: code sinh tự động (Protobuf, Inertia bundle) và SQL migration.

### 4.4 Quy tắc Desired State & Kiến trúc Seam
- **Desired state, không dùng RPC mệnh lệnh:** Panel chỉ công bố trạng thái mong muốn ("Node N phải chạy bộ workload X ở thế hệ 47"). `sasayaki` chạy vòng lặp diff và hội tụ mỗi 15 giây.
- **Panel sở hữu ý định (intent), Node sở hữu thực tế (fact):** Không bao giờ ghi cùng một trường dữ liệu từ cả hai hướng.

---

## 5. CHUẨN MỰC ĐA NGÔN NGỮ 100% (LOCALIZATION PARITY: EN & VI)

Giao diện web của Wisper phải được **bản địa hóa 100% bằng cả tiếng Anh (`en`) và tiếng Việt (`vi`)**:
1. **Cơ chế truy xuất:** Mọi chuỗi ký tự hiển thị trên giao diện bắt buộc dùng hàm `t('feature.screen.item', params)` từ `@/i18n`. Nghiêm cấm hardcode văn bản thô trong component hoặc view.
2. **Cấu trúc Flat JSON:** Từ điển ngôn ngữ nằm tại `panel/frontend/src/i18n/<locale>/<domain>.json`. Toàn bộ file JSON phải là **dạng phẳng (key-value)** với key phân tách bằng dấu chấm (Ví dụ: `"service.list.col_service": "..."`). **CẤM lồng ghép nested JSON objects.**
3. **Parity 100% giữa 2 ngôn ngữ:** Bất kỳ key nào xuất hiện trong `en/<domain>.json` đều bắt buộc phải có key tương ứng chính xác trong `vi/<domain>.json` và ngược lại.
4. **Chuẩn hóa thuật ngữ kỹ thuật:** Giữ nguyên các danh từ kỹ thuật quốc tế trong cả bản tiếng Anh và tiếng Việt: `node`, `sasayaki`, `backup`, `runsc`, `gVisor`, `cgroups v2`, `Docker`, `API token`, `cron`, `database`, `port`.

---

## 6. QUY TRÌNH HẠ TẦNG & TRIỂN KHAI TRÊN VM 192.168.1.149

### 6.1 Thông số môi trường Production
- **Host Target:** `panel-prod` (`192.168.1.149`).
- **Thư mục ứng dụng:** `/opt/wisper`.
- **File thực thi:** `/opt/wisper/wisper.jar`.
- **File biến môi trường:** `/etc/wisper/panel.env` (sở hữu bởi `wisper:wisper`, quyền `0600`).
- **Systemd Service:** `wisper-panel.service`.
- **Cổng lắng nghe:**
  - `127.0.0.1:8080` (HTTP Web UI).
  - `0.0.0.0:9090` (gRPC dành cho remote node daemons kết nối tới).
- **Định tuyến domain `https://wisper.nvnmc.cloud/`:** Định tuyến qua proxy/tunnel vào port nội bộ `8080`.

### 6.2 Quy trình Build & Deploy Production (Chuẩn Tier R2)
1. **Build JAR cục bộ:**
   ```bash
   cd panel
   ./gradlew bootJar
   ```
   Xác nhận build thành công và file JAR được tạo tại `panel/build/libs/wisper.jar`.
2. **Pre-flight Check trên VM:**
   - Kiểm tra dung lượng đĩa: `ssh panel-prod "df -h /"` (đảm bảo còn trống > 2 GB).
   - Kiểm tra trạng thái service hiện tại: `ssh panel-prod "systemctl status wisper-panel.service"`.
3. **Backup bản JAR hiện tại:**
   ```bash
   ssh panel-prod "cp /opt/wisper/wisper.jar /opt/wisper/wisper.jar.bak-\$(date +%Y%m%d%H%M%S)"
   ```
4. **Copy bản JAR mới lên máy chủ:**
   ```bash
   scp panel/build/libs/wisper.jar panel-prod:/opt/wisper/wisper.jar.new
   ssh panel-prod "chown wisper:wisper /opt/wisper/wisper.jar.new && mv /opt/wisper/wisper.jar.new /opt/wisper/wisper.jar"
   ```
5. **Restart & Kiểm tra dịch vụ:**
   ```bash
   ssh panel-prod "systemctl restart wisper-panel.service"
   ssh panel-prod "sleep 5 && systemctl is-active wisper-panel.service"
   ssh panel-prod "curl -I -s http://127.0.0.1:8080/ | head -n 5"
   ```
6. **Phương án Rollback (nếu gặp lỗi khởi động):**
   ```bash
   ssh panel-prod "mv /opt/wisper/wisper.jar.bak-<TIMESTAMP> /opt/wisper/wisper.jar && systemctl restart wisper-panel.service"
   ```

---

## 7. BẢO MẬT & AN TOÀN DỮ LIỆU SỐNG

1. **Khóa mật mã `WISPER_CRYPTO_KEY_1`:**
   - Đây là khóa AES-256 mã hóa toàn bộ dữ liệu nhạy cảm (mật khẩu database của khách hàng, token API) trong PostgreSQL.
   - Tuyệt đối không thay đổi, ghi đè hoặc làm lộ khóa này. Mất khóa đồng nghĩa với việc không thể giải mã dữ liệu của khách hàng.
   - Tuyệt đối không commit file cấu hình chứa khóa thật lên git.
2. **Tính bất biến của Migration PostgreSQL:**
   - Các file migration nằm tại `panel/src/main/resources/wisper/migrations/V{n}__*.sql`.
   - File migration đã chạy trên production là **bất biến (immutable)**. Nghiêm cấm sửa nội dung các file đã chạy. Mọi thay đổi schema bắt buộc tạo migration mới dạng số tăng dần `V{n+1}__ten_thay_doi.sql`.
3. **An toàn Container & Môi trường người dùng:**
   - Mọi container của khách hàng bắt buộc chạy qua gVisor `runsc` với user namespace riêng biệt, drop toàn bộ capabilities, bật `no-new-privileges`, giới hạn PID và ulimit.
   - **Tuyệt đối không bao giờ mount `docker.sock` vào container của khách hàng.**
   - Egress từ container tới các dải IP private nội bộ (`192.168.0.0/16`, `10.0.0.0/8`, `172.16.0.0/12`) và endpoint cloud metadata (`169.254.169.254`) mặc định bị chặn hoàn toàn.
4. **Token kích hoạt Node:**
   - Single-use token có TTL 15 phút, chỉ truyền qua `--token-file` hoặc stdin, tuyệt đối không truyền qua argv command-line (tránh lộ qua `ps`).

---

## 8. QUY TRÌNH PHẢN BIỆN ĐỐI KHÁNG (CHATGPT WEB ADVERSARIAL REVIEW) & MODULE CONTRACT

- **Cổng phản biện chéo 2 vòng (Two-Phase Adversarial Review Gate):**
  Tuân thủ nghiêm ngặt skill `chatgpt-web-adversarial-review`:
  1. **Vòng 1 (Trước khi viết code):** Gửi tài liệu thiết kế, sơ đồ logic, schema migration và các invariants sang ChatGPT trên giao diện web để mổ xẻ rủi ro, phát hiện điểm mù kiến trúc trước khi bắt đầu lập trình.
  2. **Vòng 2 (Sau khi viết code xong):** Gửi toàn bộ git diff và mã nguồn thực tế sang ChatGPT để nghiệm thu đối kháng. Nếu phát hiện lỗ hổng hoặc sai sót contract, tiến hành sửa chữa $\to$ kiểm thử $\to$ gửi phản biện lại cho đến khi không còn blocker hợp lệ.
- **Nguyên tắc dứt điểm từng Module (Module-by-Module Contract):**
  - Triển khai tuần tự: Hoàn thành trọn vẹn từ Backend (Record $\to$ Repository $\to$ Use-case $\to$ Controller), Frontend (Inertia React Component $\to$ i18n parity), đến Build & Test verify thành công trước khi chuyển sang module tiếp theo.

---

## 9. BỘ LỆNH KIỂM CHỨNG & BUILD (VERIFICATION COMMANDS)

Trước khi commit bất kỳ thay đổi nào, bắt buộc chạy các lệnh kiểm tra tương ứng:

```bash
# 1. Kiểm tra & Build Frontend (TypeScript, React, Vite)
cd panel/frontend
npm run typecheck
npm run build

# 2. Kiểm tra & Build Panel Backend (Java 21, Spring Boot, Tests)
cd ../../panel
./gradlew test
./gradlew bootJar

# 3. Kiểm tra Node Daemon (Go 1.27)
cd ../sasayaki
go vet ./...
go test ./...
```

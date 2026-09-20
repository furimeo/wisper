---
name: chatgpt-web-adversarial-review
description: >-
  Quy trình tự động phản biện chéo kế hoạch triển khai và nghiệm thu mã nguồn với ChatGPT trên giao diện web
  qua công cụ tự động hóa trình duyệt (Browser Automation Tool / MCP), ưu tiên tính linh hoạt theo runtime hiện có,
  giảm hard-code công cụ và chỉ giữ các ràng buộc an toàn cốt lõi.
---

# Kỹ Năng Phối Hợp Phản Biện Kế Hoạch & Nghiệm Thu Mã Nguồn Với ChatGPT (Web)

Tài liệu này chuẩn hóa quy trình làm việc giữa agent hiện tại và ChatGPT trên giao diện web nhằm mục đích
**phản biện đối kháng 2 vòng (Two-phase adversarial review)**:

1. **Vòng 1 (Trước khi code):** "Vạch lá tìm sâu", phát hiện mọi lỗ hổng bảo mật, xung đột dữ liệu,
   race conditions và thiếu sót nghiệp vụ trong kế hoạch trước khi bắt đầu lập trình.
2. **Vòng 2 (Sau khi code xong):** Kiểm tra nghiệm thu mã nguồn thực tế (Code Audit), phát hiện sai lệch
   so với thiết kế hoặc cạm bẫy mới sinh ra trong quá trình implement; lặp lại việc sửa lỗi cho đến khi
   không còn blocker hợp lệ chưa xử lý.

> [!IMPORTANT]
> Skill này phải **thích nghi theo runtime hiện tại**. Không giả định trước tên model, client, tool hay provider.
> Model có thể chạy qua Claude Code, Cowork, 9Router, Gemini/Antigravity hoặc runtime khác.
> Hãy quan sát tool thực sự có trong phiên và chọn cách thực hiện phù hợp nhất.

---

## 1. Nguyên Tắc Cốt Lõi Của Quy Trình Phản Biện

1. **Không tự mãn:** Mọi kế hoạch và đoạn code mới dù chi tiết và pass test vẫn có điểm mù.
   Việc đưa sang ChatGPT phản biện cả kế hoạch lẫn code thực tế là bắt buộc để có góc nhìn độc lập thứ hai
   (Second Opinion).

2. **Cung cấp ngữ cảnh kỹ thuật sâu (Deep Context Ingestion):**
   Khi gửi sang ChatGPT, không mô tả chung chung mà phải đưa ra các thông tin thực sự cần cho việc review, ví dụ:
   - Schema bảng (PostgreSQL data types, indexes, unique/check constraints).
   - State machine và mã HTTP status.
   - Cơ chế khóa (Redis distributed lock, PostgreSQL `FOR UPDATE`, `SKIP LOCKED`, advisory lock nếu có).
   - Công thức toán học, đặc biệt với tiền tệ / billing.
   - Ràng buộc môi trường (Pterodactyl Wings API, Node.js, framework, queue, DB).
   - Failure modes và retry semantics nếu có external side effects.

3. **Phân loại phản biện (Triage):**
   Mọi góp ý từ ChatGPT phải được phân loại:
   - **Nhóm 1 — Critical Blocker:** Có thể gây mất tiền, mất server, lộ dữ liệu, race condition nghiêm trọng,
     corruption hoặc treo hệ thống → bắt buộc xử lý.
   - **Nhóm 2 — Operational Improvement:** Hiệu năng, UX, observability, logging, audit trail,
     maintainability → cân nhắc bổ sung.
   - **Nhóm 3 — False Positive / Out of Scope:** Không phù hợp hợp đồng hệ thống hoặc dựa trên giả định sai
     → giải trình rõ lý do từ chối.

4. **Không coi ChatGPT là oracle:** Một câu “approved” không thay thế test, invariant, logs và code thực tế.
   Review bên ngoài là second opinion, không phải nguồn chân lý duy nhất.

---

## 2. Nguyên Tắc Thích Nghi Theo Runtime

### 2.1. Dùng tool hiện có, không hard-code tool không tồn tại

Agent phải:
- Kiểm tra toolset hiện có trong phiên.
- Ưu tiên tool native của runtime cho file/code/shell khi phù hợp.
- Ưu tiên browser automation tool cho thao tác web.
- Không giả định tên tool cụ thể nếu runtime không đảm bảo.
- Không tuyên bố đã click, upload, đọc file hay chạy lệnh nếu thực tế chưa làm được.

### 2.2. Không phụ thuộc đường dẫn nội bộ của provider

Các đường dẫn nội bộ hoặc artifact tạm của provider/model backend không được xem là workspace bền vững.

Ví dụ các path dạng:
- `.gemini/antigravity-ide/brain/...`
- `.system_generated/tasks/...`
- task log nội bộ
- artifact path do backend sinh tự động

chỉ được dùng khi runtime thực sự cần và file tồn tại tại thời điểm sử dụng.

**Không được trả các path tạm đó như output bền vững cho người dùng.**

Khi cần file tạm:
- Ưu tiên đặt trong working directory hoặc project hiện tại.
- Có thể dùng `tmp/`, `.tmp/`, `.cache/` hoặc thư mục tạm phù hợp runtime.
- Không bắt buộc một path cố định nếu runtime có lựa chọn tốt hơn.

### 2.3. Ưu tiên tự chủ

Skill chỉ quy định mục tiêu, guardrail và điều kiện hoàn thành.
Agent được quyền chọn:
- Tool.
- Thứ tự thao tác.
- Cách tạo file tạm.
- Cách truyền payload.
- Cách polling.
- Cách retry.
- Cách fallback.

miễn là không vi phạm các invariant an toàn trong skill này.

---

## 3. Quy Trình Tối Ưu Chống Đơ & Cơ Chế Fallback (Anti-Freeze Workflow)

> [!IMPORTANT]
> Khi dán trực tiếp văn bản dung lượng lớn vào ô chat web, trình duyệt có thể lag, timeout hoặc mất kết nối.
> Vì vậy không nên ép toàn bộ payload lớn qua thao tác typing nếu có cách tốt hơn.

### Nguyên tắc bảo vệ

1. **Ưu tiên transport hiệu quả nhất có sẵn**
   - Nếu runtime/browser tool hỗ trợ upload file an toàn → có thể dùng file attachment.
   - Nếu payload nhỏ → có thể nhập trực tiếp.
   - Nếu payload lớn và không có upload tool → dùng clipboard/manual attach/chunking tùy tình huống.

2. **Circuit Breaker**
   Nếu browser automation bị:
   - timeout kéo dài,
   - mất kết nối,
   - CAPTCHA / Turnstile,
   - DOM không ổn định,
   - hoặc retry nhiều lần không tiến triển,

   thì dừng retry mù quáng và chuyển sang fallback phù hợp.

3. **Không giả định Browser MCP có thể điều khiển Windows desktop**
   Browser automation chỉ nên được xem là có quyền trên những gì tool thực tế expose.
   Nếu không có khả năng điều khiển native file picker / desktop UI thì không được giả vờ là có.

---

## 4. Khung Phản Biện 6 Chiều (6-Axis Adversarial Framework)

### Trục 1: Tranh chấp đồng thời & Khóa dữ liệu (Race Conditions & Concurrency)

- Nếu người dùng mở nhiều tab / gửi concurrent requests, request có lọt qua check-before-write không?
- Lock có TTL hợp lý không?
- Worker chết giữa chừng thì lock có được giải phóng / lease có expire không?
- Có nguy cơ deadlock do lock ordering không?
- Idempotency có được enforce ở DB hay chỉ ở application?

### Trục 2: Bảo mật & Chữ ký mật mã (Security & Cryptography)

- Callback signature có chống Replay Attack / Timing Attack không?
- Endpoint có rate limit phù hợp không?
- Có IDOR / authz bypass không?
- Có injection / unsafe deserialization / SSRF / path traversal không?
- Secret có thể rò qua logs, diff, clipboard hoặc browser payload không?

### Trục 3: Toàn vẹn tiền tệ & Sổ cái (Ledger & Math Integrity)

- Tiền tệ có dùng integer/bigint thay vì floating point không?
- Idempotency key có `UNIQUE` ở DB không?
- Amount có lấy từ source of truth thực tế không?
- Transaction boundary đã bao trọn accounting + state update chưa?
- Retry/replay có thể cộng/trừ tiền nhiều lần không?

### Trục 4: Trạng thái mạng mơ hồ & Chịu lỗi (Fault Tolerance & Ambiguous States)

- Timeout sau remote side-effect có tạo trạng thái không biết đã áp dụng hay chưa?
- Retry có gây duplicate side-effect không?
- Có reconcile job không?
- Có backoff / lease / batch size / deadline / poison message handling không?

### Trục 5: Ràng buộc Cơ sở dữ liệu (Database Schema Invariants)

- FK / UNIQUE / CHECK / partial index có phù hợp không?
- Query quan trọng có index đúng không?
- Nullability có phù hợp state machine không?
- Có invariant chỉ nằm trong code nhưng lẽ ra nên enforce ở DB không?

### Trục 6: Nghiệp vụ thực tế & Cạm bẫy hạ tầng (Domain Invariants)

- External ID có thể reuse không?
- Có edge case billing / restore / archive / suspend / adoption không?
- Có assumption sai về Pterodactyl/Wings/API/network/proxy/cache/queue không?
- Có trạng thái retroactive billing, stale cache hoặc eventual consistency không?

---

## 5. Vòng 1: Phản Biện Kế Hoạch (Pre-Implementation)

### Bước 1: Chuẩn bị nội dung

Tạo payload chứa các phần cần thiết, ví dụ:
- Mục tiêu module.
- Stack.
- Schema.
- Invariants.
- State machine.
- Locking.
- External APIs.
- Failure modes.
- Kế hoạch triển khai.

Có thể lưu vào file tạm nếu thuận tiện.

### Bước 2: Gửi sang ChatGPT Web

Prompt khuyến nghị:

```text
Hãy đóng vai Lead Software Architect & Security Auditor cực kỳ khó tính.
Hãy phản biện tài liệu kỹ thuật theo 6 trục:
Concurrency, Security/Cryptography, Ledger/Math Integrity,
Fault Tolerance, Database Constraints, Domain/Infrastructure Pitfalls.

Với mỗi finding, hãy nêu:
- mức độ,
- cơ chế lỗi,
- điều kiện kích hoạt,
- ảnh hưởng,
- phương án vá cụ thể,
- test hoặc invariant nên bổ sung.

Không kết luận an toàn nếu chưa đủ bằng chứng.
```

### Bước 3: Triage

Mỗi finding phải được agent tự kiểm tra lại với codebase và hợp đồng hệ thống trước khi chấp nhận.

---

## 6. Vòng 2: Nghiệm Thu Mã Nguồn Thực Tế (Post-Implementation Code Audit)

Sau khi code và local checks pass:

1. Thu thập targeted diff / source code đúng phạm vi.
2. Loại secret và dữ liệu không cần thiết.
3. Gửi sang ChatGPT Web để review.
4. Yêu cầu kiểm tra:
   - Sai lệch so với Vòng 1.
   - Race condition.
   - Transaction / connection leak.
   - Retry/idempotency.
   - Error handling.
   - State transition.
   - Authz/security boundary.
   - Regression.
5. Với finding hợp lệ:
   - sửa code,
   - chạy lại test/check,
   - tạo diff mới,
   - review lại nếu cần.

Prompt khuyến nghị:

```text
Hãy đóng vai Lead Code Reviewer & Security Auditor.
Kiểm tra code thực tế so với invariants và các finding của Vòng 1.

Nếu có lỗi, hãy chỉ rõ:
- file / vùng code,
- cơ chế lỗi,
- điều kiện kích hoạt,
- mức độ,
- cách khắc phục,
- test nên bổ sung.

Nếu chưa đủ bằng chứng, không được tuyên bố production-safe.
```

---

## 7. Secret-Scrubbing Gate

Trước khi đưa payload sang web, clipboard hoặc file tạm:

1. Không gửi `.env`, private key, token, password, cookie, credential dump nếu không thật sự cần.
2. Redact các chuỗi nhạy cảm thành `<REDACTED_SECRET>`.
3. Chỉ gửi đúng scope cần review.
4. Khi nghi ngờ một chuỗi có thể là secret, ưu tiên redact.
5. Nếu payload đã đi qua clipboard, có thể xóa clipboard sau khi hoàn tất nếu phù hợp.

---

## 8. Fallback Strategy

Nếu browser automation không hoạt động ổn định, agent tự chọn fallback phù hợp nhất từ runtime hiện có.

Ví dụ:
- Clipboard.
- Manual paste.
- Manual attach file.
- Chia payload thành phần nhỏ.
- Lưu response vào file.
- Yêu cầu user thực hiện một thao tác thủ công tối thiểu.

Fallback phải:
- Không làm mất dữ liệu quan trọng.
- Không làm lộ secret.
- Không giả vờ là automation đã thành công.
- Báo rõ bước nào cần user can thiệp.

---

## 9. Điều Kiện Hoàn Thành

Chỉ báo hoàn thành khi:

1. Không còn Critical Blocker hợp lệ chưa xử lý.
2. Local checks/test cần thiết pass.
3. Các invariant quan trọng có test hoặc bằng chứng tương ứng.
4. Không còn secret chưa scrub trong payload/artifact.
5. Không còn discrepancy quan trọng giữa kế hoạch và implementation.
6. Nếu browser/tool gặp hạn chế, báo rõ phần nào đã tự động và phần nào cần thao tác thủ công.

> Không dùng tiêu chí “ChatGPT nói CODE ĐẠT CHUẨN” làm điều kiện duy nhất.
> ChatGPT là second opinion, không phải oracle.

---

### Browser tool selection

- Use managed Preview tools only for pages associated with a valid preview serverId.
- If preview_list is empty or no valid serverId exists, do not attempt preview_snapshot,
  preview_eval, preview_click, preview_fill, or related Preview actions.
- For arbitrary external websites or existing authenticated browser sessions,
  prefer the available general-purpose browser automation tool.
- Do not repeatedly probe Preview tools after a missing-serverId validation error.
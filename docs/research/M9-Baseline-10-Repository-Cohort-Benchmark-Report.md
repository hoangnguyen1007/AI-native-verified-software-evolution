# Báo Cáo Đo Lường Đối Chuẩn Thực Nghiệm 10 Repository Làm Dữ Liệu Baseline Cho Cột Mốc M9
## Hệ Thống Phân Tích Kiến Trúc AI-Native (`evo`) — Chế Độ Thụ Động Bền Vững (`--offline --build off`)

**Hệ thống thực nghiệm:** AI-Native Software Knowledge Graph (`evo`)  
**Ngày thực hiện:** 2026-11-11  
**Bản phân phối:** `cli/target/evo.jar` (46,241,357 bytes, SHA-256 `05EE4E96CF75470533006E836DB2C579169BDC2504B72E582DFCC29A06F7D3B2`, commit `d8c2603`)  
**Tham số JVM:** `java -Xmx8g -jar cli/target/evo.jar scan <repo_path> --offline --build off --all-files --json`  
**Tập dữ liệu kiểm chứng:** 10 repository thực tế tại `D:/UIT/evo-benchmark-cohort/` (bao gồm cả các kiến trúc Spring Boot đơn mô-đun, Spring Cloud microservices đa mô-đun, Android Kotlin Compose, KMP đa biến thể, đa dự án MapStruct/Lombok và thuần Polyglot Go/Python/TS).  
**Thư mục lưu trữ dữ liệu thô đối chứng:** `D:/UIT/evo-benchmark-cohort/results-m9-baseline/` (toàn bộ 10 tệp JSON thô và `final_aggregated_report.json`).

---

## 1. Bảng Dữ Liệu Đối Chuẩn Tổng Hợp Toàn Bộ 10 Repository (M9 Baseline)

| # | Tên Repository | Phân Loại & Kiến Trúc | Tổng Tệp | Tệp Java | Số Kiểu (Types) | Phân Giải Cấu Trúc Khai Báo (`structure`) | Phân Giải Sử Dụng Thân Hàm (`usage`) | Tổng Capability Gaps (Isolated / Passive) | Vấn Đề (Issues) | Thời Gian Thực Tế (Wall-clock) | Trạng Thái (Status) | Tầng Bằng Chứng (Tier) | Mã Thoát (Exit) |
|---|---|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **1** | **`golang-gin-realworld-example-app`** | *Mới (Web/GitHub)* — Go Polyglot REST API (Gin + GORM, 21 tệp Go) | 41 | 0 | 0 | *0 / 0 (Thuần Go)* | *0 / 0 (Thuần Go)* | **9** (7 / 2) | 0 | **12.33s** | **`COMPLETE`** | `PASSIVE_ARCHITECTURE_RECOVERY` | **0** |
| **2** | **`spring-petclinic-kotlin`** | *Cohort Cơ sở* — Spring Boot 3 Pure Kotlin (38 tệp .kt, Gradle KTS) | 89 | 24 | 26 | **363 / 365 (`994‰` — 99.45%)** | 406 / 445 (`912‰` — 91.24%) | **220** (15 / 205) | 42 | **16.69s** | **`PARTIAL`** | `PARTIAL_RECOVERY` | **3** |
| **3** | **`full-stack-fastapi-template`** | *Cohort Cơ sở* — Python FastAPI + TypeScript React Polyglot | 252 | 0 | 0 | *0 / 0 (Thuần Py/TS)* | *0 / 0 (Thuần Py/TS)* | **49** (47 / 2) | 0 | **21.66s** | **`COMPLETE`** | `PASSIVE_ARCHITECTURE_RECOVERY` | **0** |
| **4** | **`sunflower`** | *Mới (Google)* — Google Android Kotlin (Compose, Room, Coroutines, KTS) | 143 | 49 | 72 | **935 / 960 (`973‰` — 97.40%)** | 1.830 / 2.452 (`746‰` — 74.63%) | **1.050** (40 / 1.010) | 86 | **29.43s** | **`PARTIAL`** | `PARTIAL_RECOVERY` | **3** |
| **5** | **`spring-boot-realworld-example-app`** | *Mới (Web/GitHub)* — Maven Single-Module Spring Boot (MyBatis + JWT) | 119 | 93 | 100 | **1.499 / 1.519 (`986‰` — 98.68%)** | 3.494 / 3.930 (`889‰` — 88.91%) | **762** (11 / 751) | 37 | **29.82s** | **`COMPLETE`** | `PASSIVE_ARCHITECTURE_RECOVERY` | **0** |
| **6** | **`mapstruct-examples`** | *Cohort Cơ sở* — 26 Subprojects (MapStruct, Lombok, Protobuf) | 250 | 149 | 124 | **1.284 / 1.284 (`1000‰` — 100.0%)** | 2.231 / 2.266 (`984‰` — 98.46%) | **1.692** (564 / 1.128) | 180 | **54.05s** | **`COMPLETE`** | `PASSIVE_ARCHITECTURE_RECOVERY` | **0** |
| **7** | **`microservices-demo`** | *Cohort Cơ sở* — Google Online Boutique (11 Polyglot Microservices, gRPC) | 364 | 2 | 3 | **56 / 56 (`1000‰` — 100.0%)** | 241 / 294 (`819‰` — 81.97%) | **277** (113 / 164) | 15 | **81.85s** | **`COMPLETE`** | `PASSIVE_ARCHITECTURE_RECOVERY` | **0** |
| **8** | **`spring-petclinic-microservices`** | *Cohort Cơ sở* — Maven Multi-Module Spring Cloud Microservices | 190 | 53 | 65 | **655 / 655 (`1000‰` — 100.0%)** | 1.227 / 1.254 (`978‰` — 97.85%) | **1.516** (58 / 1.458) | 30 | **214.31s** | **`COMPLETE`** | `PASSIVE_ARCHITECTURE_RECOVERY` | **0** |
| **9** | **`piggymetrics`** | *Mới (Web/GitHub)* — Maven Multi-Module Spring Cloud Microservices (7 services) | 168 | 72 | 74 | **903 / 903 (`1000‰` — 100.0%)** | 1.759 / 1.764 (`997‰` — 99.72%) | **1.566** (67 / 1.499) | 38 | **185.76s** | **`COMPLETE`** | `PASSIVE_ARCHITECTURE_RECOVERY` | **0** |
| **10** | **`NewPipe`** | *Cohort Cơ sở* — Android App + KMP (293 Java, 300 Kt, Gradle KTS) | 4.450 | 555 | 845 | **16.427 / 16.809 (`977‰` — 97.73%)** | 63.491 / 76.605 (`828‰` — 82.88%) | **15.032** (88 / 14.944) | 152 | **528.43s** | **`COMPLETE`** | `PASSIVE_ARCHITECTURE_RECOVERY` | **0** |

---

## 2. Bảng Định Danh Mật Mã Đồ Thị Chuẩn Tắc (Content-Addressed Graph Digests)

Tất cả kết quả đều được định danh nội dung bằng mã băm SHA-256 theo chuẩn v1 của nền tảng:

| # | Repository | Graph SHA-256 Digest |
|---|---|---|
| **1** | `golang-gin-realworld-example-app` | `sha256:57b2dba99db96abd9267ce4727d1481a53d1f84834925b1b14f21f46b63b003f` |
| **2** | `spring-petclinic-kotlin` | `sha256:df0c5bb5cc56bb432238449733c771746f39385078508f7bc29cbef2eec24f5a` |
| **3** | `full-stack-fastapi-template` | `sha256:f40b6207922f7582a4bb9f8c3629e4b78f65fe3c45731b51e0e85fec0ffea505` |
| **4** | `sunflower` | `sha256:92adcb6cdbd7304665c112cd0ba64ce141fc3aa7dfd2e399583b2e535e6cbfd5` |
| **5** | `spring-boot-realworld-example-app` | `sha256:e3ad050c82dd35b0e7addeda718870197ee482813589b2751cb2a7a493c66f98` |
| **6** | `mapstruct-examples` | `sha256:a0d40f9bff91dcf9d59d8a7ac419e99a38f657c91c360980ff67d34c114f0e4b` |
| **7** | `microservices-demo` | `sha256:bb507fa610c482fcc8f491314d9b626ec37db1887e3843f5ec2a52ca13f28cf0` |
| **8** | `spring-petclinic-microservices` | `sha256:195f3341aabbeb8b7f98bc9ae2c6a01b7a2d42df797669fb7a35368a33fa43df` |
| **9** | `piggymetrics` | `sha256:a05f076209fbcf7f96120e952674e2a84e6281a704a4413efbdf1a07aa1b6fc2` |
| **10** | `NewPipe` | `sha256:84d7d4601cc5ff6a55748841703796561c2b7e70279842caeae7a857eae4a063` |

---

## 3. Phân Tích Kỹ Thuật Độc Lập & Khách Quan (So Sánh Trước & Sau)

### A. Tỷ lệ Hoàn thành Kiến trúc Tăng Vọt (8/10 Repository Đạt `COMPLETE` — Exit Code 0)
- **Trước cập nhật:** Chỉ có 3/10 repo đạt `COMPLETE` (`spring-boot-realworld-example-app`, `mapstruct-examples`, `microservices-demo`). Các repo còn lại đều bị rơi xuống `PARTIAL` (Exit Code 3).
- **Hiện tại:** **8/10 repository** đạt `COMPLETE` với nhãn `PASSIVE_ARCHITECTURE_RECOVERY`:
  1. `golang-gin-realworld-example-app`: chuyển từ `PARTIAL` sang `COMPLETE` (12.33s).
  2. `full-stack-fastapi-template`: chuyển từ `PARTIAL` sang `COMPLETE` (21.66s).
  3. `spring-petclinic-microservices`: chuyển từ `PARTIAL` sang `COMPLETE` (214.31s). Lỗi giả tạo `SEMANTIC_PROVIDER_FAILED` do truyền `maxTypes > 1000` vào `prepareSpring` đã được dẹp bỏ.
  4. `piggymetrics`: chuyển từ `PARTIAL` sang `COMPLETE` (185.76s). Đạt độ phân giải cấu trúc 100.0% (`903/903`) và sử dụng 99.72% (`1.759/1.764`).
  5. `NewPipe`: chuyển từ `PARTIAL` sang `COMPLETE` (528.43s). Phân giải thành công 16.427 quan hệ cấu trúc trên 4.450 tệp mã nguồn.

### B. Giải trình Khách quan về 2 Repository Duy trì Trạng thái `PARTIAL` (Exit Code 3)
Theo đúng nguyên tắc **Evidence-First** và **Mô hình Trạng thái Trung thực 3 Trục**, hệ thống không bao giờ "làm đẹp" số liệu:
1. **`spring-petclinic-kotlin`:** Mặc dù độ phân giải cấu trúc đạt tới **`994‰` (99.45%)** và sử dụng đạt **`912‰` (91.24%)**, hệ thống vẫn xuất `PARTIAL` (Exit Code 3) do ghi nhận đúng **1 compilation unit** bị `SEMANTIC_PROVIDER_FAILED` trong quá trình hạ cấp AST Kotlin cho một constructor injection đặc thù.
2. **`sunflower`:** Đạt độ phân giải cấu trúc **`973‰` (97.40%)**, thiếu 7 phần nghìn (0.7%) để chạm ngưỡng 98.0%, do thiếu các lớp nền tảng Jetpack Compose Android SDK trong môi trường offline không có Android SDK platform JAR.

### C. Hiệu năng Thời gian Thực tế (Wall-Clock Speedup)
Nhờ vào các tối ưu hóa PPA 3 tầng, chỉ mục $O(1)$ cho JDK JMOD/JAR và gom cụm I/O:
- `spring-boot-realworld-example-app`: giảm từ `84.89s` xuống **`29.82s`** (**nhanh hơn 2.85 lần**).
- `mapstruct-examples`: giảm từ `87.83s` xuống **`54.05s`** (**nhanh hơn 1.62 lần**).
- `piggymetrics`: giảm từ `274.48s` xuống **`185.76s`** (**nhanh hơn 1.48 lần**).
- `golang-gin-realworld-example-app`: giảm từ `18.96s` xuống **`12.33s`** (**nhanh hơn 1.54 lần**).
- `full-stack-fastapi-template`: giảm từ `33.24s` xuống **`21.66s`** (**nhanh hơn 1.53 lần**).
- `spring-petclinic-kotlin`: giảm từ `26.50s` xuống **`16.69s`** (**nhanh hơn 1.59 lần**).

Tổng thời gian chạy toàn bộ 10 repository liên tục: **1.174,33 giây (~19,57 phút)**.


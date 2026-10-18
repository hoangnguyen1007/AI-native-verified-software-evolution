# Comprehensive Repository Coverage — Problem Statements and Human Mandate

## 1. Human Mandate (Tôn chỉ chỉ đạo của Human)

- **Mọi dự án thực tế, bất kể quy mô hay kiến trúc (từ đơn khối tới monorepo đa dịch vụ microservices), đều phải được phân tích và đo lường đầy đủ.**
- **Không bỏ sót bất kỳ thành phần, kiểu dữ liệu, hay ngóc ngách kiến trúc nào.**
- **Hạn chế tối đa các kết quả `FAILED` và `PARTIAL`, hướng tới việc kiểm tra và bao quát toàn vẹn mọi dự án.**
- **Giải quyết triệt để các rào cản kỹ thuật khiến các dự án lớn, phức tạp hoặc đa module bị gián đoạn, từ chối hoặc rơi vào trạng thái thiếu hụt dữ liệu.**

### Bổ sung chỉ đạo đã xác nhận — 2026-10-17

Human yêu cầu lập kế hoạch M8.4 tối đa 4–6 bước, và đã chọn **semantic engine cho từng ngôn ngữ ngay trong M8.4**. Quy định cũ chỉ inventory ngoài Java được thay thế. Build cần được dùng để thu generated sources, bytecode và effective build context: **cấm chạy trực tiếp trên host; cho phép chạy trong sandbox đã kiểm chứng**, kết hợp phân tích tĩnh trước build. Không giữ các trần cứng tổng repo, số file hay thời gian tải làm điều kiện loại repo.

[ADR-007](../decisions/ADR-007-isolated-build-and-polyglot-readiness.md), [hợp đồng M8.4](m8-repository-readiness.md) và [kế hoạch sáu bước](../tasks/m8/04-comprehensive-repository-readiness.md) cụ thể hóa chỉ đạo. “Toàn bộ” yêu cầu inventory/byte accounting không bỏ sót, mọi module/source set/language có disposition và semantic obligation, generated outputs có lineage, mọi context đã chọn có coverage rõ. Không lấy hash/build xanh/không có diagnostic làm chứng minh hiểu mọi hành vi. Giảm PARTIAL bằng đóng obligation có evidence, giữ lịch sử gap nhưng không đếm gap đã giải quyết như thiếu hụt hiện tại.

Bỏ trần sản phẩm bằng streaming, lưu trên đĩa, checkpoint, backpressure và worker phù hợp; vẫn giữ cô lập CPU/RAM/disk/network, bounded API và watchdog. Khi thiếu tài nguyên/quyền/toolchain, giữ kết quả và điểm tiếp tục. Catalog ngôn ngữ đầu tiên không thành whitelist vĩnh viễn; claim nghiệm thu chỉ áp dụng cho khả năng/context/cohort được kiểm chứng, không giả mọi ngôn ngữ tương lai hay tài nguyên vô hạn đã được giải quyết.

---

## 2. Danh sách các vấn đề cốt lõi cần giải quyết (Core Problem Statements)

### Vấn đề 1: Rào cản giới hạn thời gian tải về qua mạng (Network Acquisition Timeouts)
- **Hiện tượng:** Quá trình tiếp nhận các repository qua đường truyền mạng Git HTTPS bị giới hạn thời gian cứng (ví dụ 120 giây). Đối với các dự án lớn, nhiều module hoặc khi mạng quốc tế có độ trễ, tiến trình clone bị ngắt đột ngột (`REMOTE_TIMEOUT`) và dẫn tới kết quả thất bại (`FAILED`) ngay ở bước thu nhận đầu vào (`ACQUISITION`).
- **Vấn đề đặt ra:** Cơ chế tiếp nhận mạng hiện tại chưa đáp ứng được việc tải về hoàn chỉnh các repository có khối lượng lớn, cấu trúc phức tạp hoặc phụ thuộc vào điều kiện mạng thực tế.

### Vấn đề 2: Các ngưỡng trần dung lượng và số lượng tập tin (Size and File Count Ceilings)
- **Hiện tượng:** Hệ thống thiết lập các chốt chặn cứng về dung lượng tối đa (ví dụ trần 128 MB) hoặc số lượng tập tin tối đa (ví dụ 20.000 files/entries). Khi gặp các monorepo thực tế, các kho mã nguồn microservices tích hợp hoặc repository chứa nhiều thư mục cấu hình/tài nguyên, hệ thống kích hoạt cờ vượt ngưỡng kích thước (`REMOTE_SIZE_LIMIT`) và chấm dứt tiến trình với trạng thái `FAILED`.
- **Vấn đề đặt ra:** Các ngưỡng trần cứng hiện tại ngăn cản hệ thống tiếp nhận và phân tích các dự án quy mô lớn trong môi trường doanh nghiệp.

### Vấn đề 3: Sự vắng mặt của mã nguồn sinh tự động (Unmodeled Generated Code & Stubs — gRPC, Protobuf, Code Generators)
- **Hiện tượng:** Các dự án thực tế sử dụng các công nghệ sinh mã như gRPC, Protocol Buffers (`protobuf-maven-plugin`), OpenAPI Generator, ANTLR, hoặc các annotation processors. Các lớp Java tương ứng (ví dụ `*Grpc.java`, `*OuterClass.java`) không tồn tại dưới dạng mã nguồn tĩnh trên đĩa trước khi biên dịch. Khi các module khác trong hệ thống tham chiếu tới các lớp này, bộ phân tích tĩnh gặp bế tắc trong việc giải quyết kiểu dữ liệu, dẫn tới lỗi phân tích phụ thuộc chéo, cạn bộ nhớ hoặc chấm dứt bất thường (`TERMINAL_FAILURE`).
- **Vấn đề đặt ra:** Hệ thống thiếu khả năng nhận diện, nắm bắt và xử lý các kiểu dữ liệu phụ thuộc vào mã nguồn sinh tự động, làm gãy vỡ chuỗi phân tích của các dự án tích hợp đa công nghệ.

### Vấn đề 4: Tần suất xuất hiện trạng thái PARTIAL và FAILED trên các dự án phức tạp
- **Hiện tượng:** Khi phân tích các dự án đa module thực tế, kết quả thường bị dừng ở trạng thái `FAILED` (do lỗi phân tích, thiếu phụ thuộc ngoài, hoặc xung đột resolve) hoặc bị hạ cấp xuống `PARTIAL` do hàng ngàn khoảng trống năng lực (Capability Gaps) chưa được giải quyết.
- **Vấn đề đặt ra:** Mức độ hoàn thiện và độ bao quát phân tích chưa đạt tới trạng thái toàn vẹn cho các dự án đa tầng; chưa thu hẹp được khoảng cách giữa hiện trạng mã nguồn thực tế và mô hình tri thức kiến trúc cần trích xuất.

### Vấn đề 5: Thiếu cơ chế thích ứng đối với các điều kiện tiên quyết của repository (Pre-analysis Readiness and Safety)
- **Hiện tượng:** Nhiều repository đòi hỏi các bước tiền xử lý hoặc chuẩn bị môi trường (như giải quyết artifact, chuẩn bị mã nguồn phụ thuộc, hoặc xác định cấu hình module) mới có thể bộc lộ toàn bộ thông tin kiến trúc. Tiến trình phân tích hiện tại đang bị động và tách biệt trước các điều kiện này.
- **Vấn đề đặt ra:** Hệ thống cần đáp ứng yêu cầu tiếp nhận và chuẩn bị đầy đủ dữ kiện kiến trúc cho mọi dự án, đảm bảo bao quát toàn bộ mã nguồn mà vẫn duy trì tính xác thực và an toàn.

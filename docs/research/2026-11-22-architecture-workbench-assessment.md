# Đánh giá tính ứng dụng và độ mới của Architecture Workbench

*22/11/2026*

## Kết luận

**CONFIRMED qua hồ sơ repo.** Workbench có một luồng demo đầy đủ trên fixture Java/Spring đã ghim. Luồng nối phân tích, so sánh snapshot, finding có điều kiện, chẩn đoán, bản vá và xác minh cô lập. Trong demo, Spring giữ dependency ở baseline và đối chứng âm khi bật `dev`, rồi bỏ dependency ở candidate. Khi tắt `dev`, component liên quan không hoạt động ở cả ba phiên bản. Đây là bằng chứng cho các cấu hình đã đăng ký. [Hợp đồng và kết quả demo](../architecture/demo-repair-verification.md)

**PROVISIONAL.** Sản phẩm đủ nền tảng cho một pilot cục bộ với đội Java/Spring cần truy vết dependency giữa module, điều tra finding theo profile hoặc review một thay đổi kiến trúc. Giá trị thực tế đến từ việc mở được bằng chứng, thấy phạm vi ảnh hưởng và biết bước kiểm tra tiếp theo. Chưa có dữ liệu đo thời gian xử lý công việc của người dùng, tỷ lệ họ chấp nhận patch, độ chính xác của chat với nhà cung cấp thật hoặc hiệu quả triển khai trên nhiều đội.

## Đối chiếu

| Năng lực | Tiền lệ xác nhận được | Hàm ý cho Workbench |
|---|---|---|
| So kiến trúc dự kiến với mã thực tế | Reflexion Models nghiên cứu từ thập niên 1990. Structure101 cho phép ánh xạ thành phần, đặt rule phân tầng và hiển thị dependency vi phạm. [Reflexion Models](https://www.cs.ubc.ca/sites/default/files/tr/1997/TR-97-15_0.pdf) · [Structure101](https://www.sonarsource.com/structure101/docs/cpa/studio7/content/perspectives/architecture-diagrams) | Đồ thị và rule là năng lực nền tảng đã có tiền lệ. |
| Kiểm tra kiến trúc Java/Spring | ArchUnit kiểm tra lớp, tầng và chu trình. Spring Modulith kiểm tra chu trình ở cấp module, giới hạn truy cập package nội bộ và danh sách dependency cho phép. [ArchUnit](https://www.archunit.org/userguide/html/000_Index.html) · [Spring Modulith](https://docs.spring.io/spring-modulith/reference/verification.html) | Đây là các đường cơ sở phù hợp với rule có cùng ngữ nghĩa. Profile và thứ tự cấu hình Spring cần phép kiểm riêng. |
| Hỏi đáp về repo có nguồn | Sourcegraph Deep Search hỗ trợ hỏi tiếp, giới hạn phạm vi bằng tham chiếu và xem nguồn đã dùng. Nghiên cứu năm 2026 về Clean Architecture kết hợp bằng chứng tĩnh, rubric và LLM với đánh giá chuyên gia trên 36 module. [Sourcegraph](https://sourcegraph.com/docs/getting-started#deep-search) · [Nghiên cứu Clean Architecture](https://www.mdpi.com/2073-431x/15/10/657) | Chat có trích dẫn đã có tiền lệ. Cần đo liệu snapshot, phân vùng phân tích và tình trạng bằng chứng có làm câu trả lời chính xác hơn trên câu hỏi kiến trúc cụ thể hay không. |
| Đề xuất và kiểm tra refactor | ArchFix nghiên cứu 32 hướng sửa vi phạm kiến trúc trên hai hệ thống công nghiệp. OpenRewrite có cơ chế recipe và tạo patch từ dry run. [ArchFix](https://homepages.dcc.ufmg.br/~mtov/pub/2013_spe.pdf) · [OpenRewrite](https://docs.openrewrite.org/reference/rewrite-maven-plugin) | Khả năng tạo patch đã có tiền lệ. Cần kiểm nghiệm việc nối patch với finding có điều kiện, baseline/candidate và đối chứng âm. |

**HYPOTHESIS.** Điểm khác biệt đáng nghiên cứu là cùng một finding Spring giữ được phân loại `MUST`/`MAY`/`UNKNOWN`, vùng cấu hình và nguồn khi chuyển từ khám phá sang đề xuất sửa, rồi đối chiếu với kết quả build, test và verdict trong môi trường cô lập. Khảo sát này xác định các tiền lệ liên quan trong nhóm nguồn trên. Tính mới học thuật và lợi thế so với từng công cụ vẫn cần đối chứng có cùng đầu vào.

## Giới hạn hiện tại

- Bản ghi cohort cho thấy độ phân giải usage theo tỷ lệ mục đã thử dao động từ 791‰ ở Java service của `microservices-demo` đến 978‰ ở `spring-petclinic-microservices`. Đây là tỷ lệ phân giải, với mẫu số khác nhau giữa các repo. Cần nhãn đối chiếu độc lập để đo đúng/sai. Nhiều capture vẫn có gap. [Các kết quả cohort](../current-state.md)
- Điểm repository dựa vào sự hiện diện của README, test, cấu hình CI và build/package manifest. Chất lượng kiến trúc cần xem M6, rule và bằng chứng. Trạng thái `PARTIAL` và nguồn phát sinh gap cần xuất hiện ngay lúc người dùng ra quyết định. [Hợp đồng chấm điểm](../architecture/workbench-repository-scoring.md)
- G7 đã đóng theo quyết định của người phụ trách. Hồ sơ độc lập vẫn giữ verdict `CHANGES REQUIRED` và ba assertion chưa giải quyết tại lần review cuối. Báo cáo xác minh native và trạng thái của từng capture chỉ áp dụng cho đầu vào đã được ghi nhận. [Hồ sơ M11.6](../tasks/m11/06-execution.md)
- Chat có thể xác thực ID của bằng chứng được trích, còn mối liên hệ giữa câu văn của model và nội dung bằng chứng cần được đánh giá. Khi có nhà cung cấp, tính hữu ích phụ thuộc vào chất lượng capture, câu hỏi, mô hình và giới hạn truy xuất. [Hợp đồng chat](../architecture/workbench-architecture-chat.md)

## Thử nghiệm kế tiếp

**CANDIDATE IDEA.** Chọn các repository Java/Spring ngoài fixture với cặp snapshot và một tập câu hỏi có kết quả do người review xác nhận, gồm cả trường hợp profile đối lập, dữ liệu thiếu và candidate phá hành vi. Chạy ba điều kiện trên cùng đầu vào được phép dùng: Workbench dùng evidence, cùng model với repo context thông thường, và chat dùng evidence của Workbench. Ghi riêng thời gian tìm nguyên nhân, nhận định đúng có nguồn, nhận định thiếu căn cứ, phát hiện bị bỏ sót, patch được chấp nhận và transition được xác minh. Dùng cùng rule cho các so sánh với ArchUnit hoặc Spring Modulith khi hai bên diễn đạt được điều kiện tương đương. Cho người review chấm mà không biết câu trả lời đến từ điều kiện nào. Mọi build của repo chạy trong worker cô lập đã được kiểm chứng.

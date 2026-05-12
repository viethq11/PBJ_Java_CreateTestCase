# Tổng Quan Hoạt Động Của Dự Án PBJ Online Judge

Dự án **PBJ Online Judge** là một hệ thống chấm bài lập trình trực tuyến (tương tự LeetCode, Codeforces) được tích hợp Trí tuệ nhân tạo (AI) để tự động hóa quá trình ra đề và tạo test case.

Dưới đây là tài liệu tổng hợp về cách hệ thống hoạt động từ trong ra ngoài.

---

## 1. Công Nghệ Sử Dụng (Tech Stack)
- **Backend:** Java 21, Spring Boot 3.2.4 (Spring Web, Spring Data JPA, Spring AOP).
- **Frontend:** HTML5, Tailwind CSS (qua CDN), Thymeleaf (Server-side rendering).
- **Database:** MySQL 8.0 (thiết lập Master-Slave).
- **AI Integration:** Ollama local phân tích đề ngắn, sau đó Google Gemini sinh test plan, generator, golden solution và validator.
- **Infrastructure:** Docker & Docker Compose để container hóa và quản lý các service.

---

## 2. Kiến Trúc Cơ Sở Dữ Liệu (Master - Slave)
Hệ thống sử dụng cơ chế **Cân bằng tải cơ sở dữ liệu** thông qua Spring AOP:
- **`mysql-master` (Port 3307):** Chịu trách nhiệm ghi dữ liệu (INSERT, UPDATE, DELETE).
- **`mysql-slave` (Port 3308):** Chịu trách nhiệm đọc dữ liệu (SELECT).
- **Luồng hoạt động:** Spring Boot sử dụng `DataSourceAspect` để chặn các phương thức được đánh dấu `@Transactional`. Nếu là `readOnly = true`, kết nối sẽ tự động trỏ đến cơ sở dữ liệu Slave. Nếu không, trỏ đến Master.

*(Lưu ý: Hiện tại trong môi trường dev, Slave đang trỏ về cùng một nguồn với Master để tránh lỗi thiếu bảng dữ liệu do chưa thiết lập đồng bộ Replication)*.

---

## 3. Quy Trình Hoạt Động (Workflow)

### Luồng 1: Tạo Bài Toán Tự Động Bằng AI (AI Problem Generation)
Đây là tính năng cốt lõi của hệ thống.
1. **Input từ người dùng:** Tại giao diện trang chủ, người dùng nhập "Tiêu đề bài toán", "Mô tả", và có thể đính kèm **Ảnh minh họa**.
2. **Gửi Request:** `ProblemController` tiếp nhận request và chuyển cho `ProblemService`.
3. **Gọi AI Model:** Spring Boot gọi **Ollama local** để sinh `analysis_json` ngắn, rồi gửi đề gốc + `analysis_json` lên **Google Gemini**.
4. **Phân tích và Sinh dữ liệu:** Gemini trả về một cấu trúc dữ liệu JSON chứa:
   - Tên bài toán, nội dung mô tả chi tiết.
   - Giới hạn thời gian (Time Limit), Giới hạn bộ nhớ (Memory Limit).
   - Mã nguồn chuẩn (Golden Solution - thường là C++ hoặc Java).
   - Danh sách các Test Case (Input/Output).
5. **Kiểm duyệt (Validation):**
   - Hệ thống không tin tưởng hoàn toàn vào AI. Nó sẽ sử dụng **CodeExecutionService** (Sandbox) để biên dịch và chạy thử "Golden Solution" với các Test Case mà AI vừa sinh ra.
   - Nếu output của code khớp với output dự kiến, Test Case hợp lệ.
6. **Lưu trữ:** Bài toán và các Test case hợp lệ được lưu vào database (`mysql-master`).
7. **Hiển thị:** Chuyển hướng người dùng về trang chủ để hiển thị bài toán mới trong "Ngân Hàng Bài Tập".

### Luồng 2: Xem và Nộp Bài (Submission)
1. **Xem Đề:** Người dùng nhấn vào bài toán ở trang chủ. `ProblemController` lấy dữ liệu từ `mysql-slave` và render ra giao diện chi tiết bằng Thymeleaf.
2. **Nộp code:** Người dùng viết code giải bài toán và nộp lên hệ thống.
3. **Chấm bài (Sandbox):**
   - Hệ thống tạo một tiến trình độc lập (thông qua `ProcessBuilder`).
   - Ghi code của người dùng ra file, tiến hành biên dịch (vd: `javac`, `g++`).
   - Chạy file thực thi, giới hạn thời gian (Timeout) và bộ nhớ, bơm các `Input` của Test Case vào chương trình.
   - So sánh `Output` thực tế của người dùng với `Expected Output` đã lưu trong database.
4. **Trả kết quả:** Trả về trạng thái: `Accepted (AC)`, `Wrong Answer (WA)`, `Time Limit Exceeded (TLE)`, hoặc `Compile Error (CE)`.

---

## 4. Cấu Trúc Mã Nguồn (Thư mục chính)
- **`com.pbj.config`**: Chứa các cấu hình về Database Routing (Master/Slave), cấu hình CORS, hoặc cấu hình AI Bean.
- **`com.pbj.controller`**: Các REST API và Web Controller (như `ProblemController`, `CustomErrorController`).
- **`com.pbj.service`**: Nơi chứa business logic:
  - `ProblemService`: Xử lý luồng tạo bài, gọi AI.
  - `CodeExecutionService`: Môi trường Sandbox để chạy code an toàn.
- **`com.pbj.entity` / `repository`**: Các Entity JPA (`Problem`, `TestCase`, `Submission`) và interface thao tác với DB.
- **`src/main/resources/templates/`**: Chứa các file HTML giao diện (vd: `index.html`).
- **`docker-compose.yml` & `Dockerfile`**: File cấu hình đóng gói và triển khai ứng dụng.

---

## 5. Các Lỗi Thường Gặp & Cách Khắc Phục
- **Lỗi 404 khi gọi AI:** Thường do khai báo sai tên model trong `application.yml` (vd `gemini-1.5-flash`). Giải pháp là đổi lại thành các model được hỗ trợ như `gemini-pro`.
- **Lỗi Vòng lặp chuyển hướng (Infinite Redirect Loop):** Do cơ chế đọc DB từ Slave thất bại (DB Slave trống) kết hợp với `CustomErrorController` chuyển hướng ngược về trang chủ. Giải pháp là trỏ Slave url về Master hoặc cung cấp một trang lỗi riêng rẽ.
- **Lỗi không kết nối được Database:** Thường do container MySQL chưa khởi động xong nhưng App đã chạy. Đã khắc phục bằng cấu hình `depends_on: service_healthy` trong docker-compose.

Dự án này là một minh chứng xuất sắc cho việc kết hợp AI vào các nền tảng giáo dục, giúp giảm thiểu 90% thời gian ra đề và chuẩn bị test case của giảng viên/người quản trị.

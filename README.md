PBJ Judge
Hệ thống hỗ trợ sinh test case tự động bằng AI cho bài toán lập trình thi đấu, hỗ trợ kiểm tra lời giải với các trạng thái AC / WA / TLE.

Công nghệ sử dụng
Java 21
Spring Boot 3.2
MySQL 8.4
Docker Compose
Ollama
Gemini API
Tổng quan hệ thống

PBJ Judge hỗ trợ:

Nhập đề bài bằng text hoặc ảnh
OCR đề bằng Gemini
Phân tích bài toán bằng AI
Sinh test case tự động
Kiểm tra code với sandbox
Hỗ trợ:
AC
WA
TLE
RE
CE
Kiến trúc AI
AI hai tầng
Ollama local

Dùng để:

Phân tích nhanh đề bài
Xác định loại bài
Sinh schema cơ bản
Gemini

Dùng để:

OCR ảnh đề
Chuẩn hóa metadata
Sinh test plan
Sinh golden solution
Sinh validator
Yêu cầu môi trường
Công cụ	Phiên bản
Git	2.40+
JDK	21
Maven	3.9+
Docker Desktop	4.x+
Ollama	Latest
Kiểm tra môi trường
java -version
mvn -version
git --version
docker --version
docker compose version
ollama --version
Cấu hình môi trường

Tạo file .env:

GEMINI_API_KEY=your_key
GEMINI_API_KEYS=
GEMINI_MODEL=gemini-2.5-flash
GEMINI_PRO_MODEL=gemini-2.5-flash
GEMINI_TIMEOUT_SECONDS=240

OLLAMA_BASE_URL=http://host.docker.internal:11434
OLLAMA_MODEL=deepseek-coder:6.7b

AI_QUEUE_MAX_CONCURRENCY=1
Cài đặt Ollama
ollama pull deepseek-coder:6.7b
ollama list
ollama serve
Chạy bằng Docker
Clone project
git clone https://github.com/your-username/pbj-judge.git
cd pbj-judge
Build và chạy
docker compose up -d --build
Kiểm tra container
docker ps
docker compose logs -f app
Port hệ thống
Service	Port
app	8080
mysql-master	3307
mysql-slave	3308
Các lệnh thường dùng
Xem log
docker logs pbj-judge-app --tail 100 -f
Restart app
docker compose restart app
Rebuild app
docker compose up -d --build app
Dừng hệ thống
docker compose down
Xóa volume
docker compose down -v
Chạy local
Main class
com.pbj.PbjApplication
Environment Variables
GEMINI_API_KEY=...
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_MODEL=deepseek-coder:6.7b
Hướng dẫn sử dụng
Tạo bài mới

Mở:

http://localhost:8080
Chọn:
Tạo bài mới
Nhập:
Tiêu đề
Mô tả
Constraints
Input/Output
Ảnh đề (optional)
Nhấn:
Phân Tích & Tạo Đề

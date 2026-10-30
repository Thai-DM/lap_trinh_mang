# Kiểm tra README và source trước khi đưa lên main

Ngày kiểm tra: **30/10/2026** (Asia/Saigon). Source đối chiếu: `9214e91cf616015edd5fa4efb929c20cd4b5e924`, đầu nhánh `dev` đã tích hợp vào nhánh web. Các thay đổi chuẩn bị main chỉ sửa tài liệu, trigger CI và loại sản phẩm build khỏi Git; không thay đổi Java production hoặc luật Ranking.

## Tiêu chuẩn đã đọc

- `final-project/Instruction.md`, đặc biệt mục 13 (14 nội dung tối thiểu của README), mục 12 (cấu trúc dễ đọc/dễ chạy), mục 14 và 18 (source và kiểm tra cuối).
- `final-project/Topics.md`: game mạng phải thể hiện giao tiếp Client–Server, đồng bộ trạng thái và xử lý nhiều client; phân biệt baseline và phần phát triển mới.
- `final-project/Submission.md`: tên source/report, nội dung bàn giao `Source/` và `Report/`, hạn 23:59 ngày 31/10/2026.
- Bốn tài liệu trong `New folder`: quy định trình bày KLTN QĐ-612 (21 trang), mẫu bìa chính/phụ, một báo cáo mẫu về giảng dạy tiếng Anh, phiếu nhận xét sau bìa. PDF quy định là bản scan, đã render và đọc; các DOCX được trích văn bản. Đây là tài liệu **trình bày báo cáo**, không phải mẫu README phần mềm. Không sao chép nội dung/tên thành viên của báo cáo mẫu vào project; không đưa các file mẫu đó lên GitHub.

## Kết quả đối chiếu 14 yêu cầu README

| Yêu cầu của Instruction, mục 13 | Vị trí trong README gốc repository |
|---|---|
| Project description | Đoạn mở đầu, phân biệt game local và hệ thống mạng |
| Architecture | Sơ đồ toàn hệ thống, phân biệt standalone/web/cluster |
| Technology stack | Bảng công nghệ, phiên bản và module |
| Requirements | JDK 17, Internet lần build đầu, OpenGL, trình duyệt/Python, RAM web |
| Installation | Clone, chọn JDK, build với Gradle Wrapper |
| Configuration | File properties, TCP/admin port, LAN, các cách triển khai |
| Database setup | SQLite tự tạo schema, đường dẫn DB, fallback RAM, DB gateway riêng |
| Run server | ClusterMain standalone và dòng báo sẵn sàng |
| Run client | JAR desktop, hai terminal, phòng và Sẵn sàng |
| Test | Lệnh nhóm test, toàn bộ test, web test, vị trí báo cáo HTML |
| Experimental results | Phép đo ngày 15/10 có liên kết JSON, giới hạn phạm vi |
| Novelty | Các thay đổi so với baseline local, không nhận toàn bộ game là mới |
| Limitations | TCP, WAN, benchmark A/B, worker failure, ranking cluster, tài khoản |
| References | Nguồn game gốc, ghi công tài nguyên, tài liệu công nghệ và yêu cầu thầy |

README cũ được giữ nguyên tại `final-project/README_UPSTREAM.md`. README mới ở `final-project/` dẫn về hướng dẫn chính, nên người mở thư mục module vẫn tìm được cách chạy đúng. Đã kiểm tra các liên kết file local trong hai README mới; không có liên kết hỏng. Không xác nhận mọi URL Internet hoặc mọi liên kết lịch sử trong tài liệu cũ.

## Kiểm tra từ checkout sạch

Môi trường: Windows, JDK **17.0.12**, Gradle Wrapper **7.3.3**, Python **3.9**; dependency có sẵn trong cache. Checkout riêng chứa đầy đủ source và script đã lưu trong Git; không sử dụng các JAR cũ hoặc output compile của working tree đang biên tập. Không chạy benchmark tải.

| Kiểm tra | Kết quả |
|---|---|
| `:common:test` | 58 test / 3 suite, không failure/error/skipped |
| Nhóm `:server:test` trong README | 80 test / 7 suite, không failure/error/skipped |
| Nhóm `:tests:test` trong README | 23 test / 3 suite, không failure/error/skipped |
| `:server:installCluster`, `:desktop:dist` | Build thành công; JAR/dependency được tạo từ source |
| Web `test buildWeb prepareGateway` | 10 test / 1 suite đạt; TeaVM hoàn tất, JavaScript và gateway được tạo |
| `python -m unittest discover -s scripts/web/tests -v` | 4 test đạt, gồm HTTPS/WSS và đối chiếu CONNECT_ACK |
| Standalone chạy thực tế | Khởi động ở cổng loopback tạm, nhận CONNECT và trả CONNECT_ACK đúng requestId |
| SQLite standalone | Tạo được bốn bảng: processed_matches, players, guest_profiles, ranking_sessions; không fallback RAM |
| GameGateway chạy thực tế | WebSocket upgrade 101 với Origin loopback hợp lệ; qua TCP nhận CONNECT_ACK đúng requestId |
| Kiểm tra token/private key trong file tracked dưới 2 MB | Không có khớp các mẫu private key/AWS access key/GitHub token đã kiểm tra; không phải chứng nhận không có mọi loại secret |

Tổng **171 test Java + 4 test Python** đạt trong đợt này. Biên dịch server và desktop khoảng 45 giây; web khoảng 42 giây, là thời gian chạy build tại máy kiểm tra, **không phải số đo hiệu năng game**. Có cảnh báo API/Gradle cũ và SLF4J không có binding; không làm thất bại các bước trên. Không chạy lại chơi tay/âm thanh/OpenGL/browser E2E đầy đủ trong đợt kiểm tra này. JSON tổng hợp kiểm chứng đi kèm tại [verification-20261030.json](verification-20261030.json).

`scripts/web/tests/test-deploy.sh` đã thử bằng Git Bash/Windows nhưng không hoàn thành. Kịch bản cần ngữ nghĩa symlink/POSIX của Linux; không ghi nhận PASS từ lần thử Windows. Workflow Ubuntu vẫn giữ bài kiểm tra này. Đây là kiểm tra switch/rollback giả lập, không triển khai server thật. Kết quả CI trên GitHub cần được xem riêng sau khi push.

## Phạm vi tích hợp và bảo toàn dữ liệu

- Bản main bao gồm toàn bộ code **đã tích hợp** của nhóm trong đầu nhánh dev/web nêu trên, cùng script/config/assets/test cần thiết. Không trộn lại các nhánh tính năng cũ độc lập: phần Ranking hiện tại đã có bổ sung hồ sơ khách/Boss mà nhánh Ranking cũ chưa có; chép đè từ đó sẽ làm mất các sửa đổi mới.
- Loại sáu file build/JAR/cache đã từng được track và một debug log ở gốc khỏi bản source; Gradle Wrapper JAR, toàn bộ source, test, config, assets và bằng chứng thực nghiệm lịch sử vẫn được giữ.
- Những script đang bị xóa local ở working tree của người dùng không được ghi nhận thành thao tác xóa trên main; dùng bản script đã lưu trong commit. Mọi chỉnh sửa local, báo cáo đang biên tập và file chưa track được giữ ở working tree ban đầu.
- Không sửa production Java, công thức Ranking, dữ liệu đóng góp thành viên hoặc báo cáo Word trong đợt này. Workflow thêm trigger build cho main; điều kiện deploy Azure vẫn chỉ áp dụng nhánh web.

## Những việc nhóm còn phải xác nhận khi nộp bài

README/source đã được chuẩn bị để tải về và build; điều này **không đồng nghĩa toàn bộ thủ tục nộp bài đã hoàn thành**. Nhóm vẫn cần chốt số nhóm và thông tin thành viên, chọn bản báo cáo cuối, đối chiếu báo cáo với các thay đổi dev gần nhất, kiểm tra Compilatio thực tế, xác minh giấy phép đầy đủ của game/tài nguyên kế thừa và bàn giao Source/Report theo Submission. Không tự điền thông tin tác giả hoặc cam kết tỷ lệ Compilatio.

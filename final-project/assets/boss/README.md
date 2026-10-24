# Tài nguyên hình ảnh cho chế độ boss

Ngày tải: 22/10/2026. Chế độ Boss đã sử dụng PNG nhân vật, particle `fire_01.png` của Kenney và sprite sheet `test.png` của More Explosions. Các tệp còn lại được giữ để tham khảo/chỉnh sửa.

## Boss: Demon Slime — đã có hình boss

- Thư mục: `demon-slime/`.
- Tác giả: **chierit**.
- Trang nguồn: https://chierit.itch.io/boss-demon-slime
- Giấy phép ghi trên trang tác giả: **CC BY 4.0** — https://creativecommons.org/licenses/by/4.0/
- File ZIP do người dùng cung cấp: `boss_demon_slime_FREE_v1.0.zip` tại thư mục gốc repository; giữ nguyên ZIP gốc.
- Đã giải nén nguyên vẹn 67 tệp: 60 PNG từng khung, một sprite sheet, năm GIF xem trước và một tệp Aseprite.
- Animation: đứng 6 khung, đi 12 khung, chém 15 khung, trúng đòn 5 khung, chết 22 khung. Canvas mỗi khung PNG là 288×160; GIF tham chiếu dùng 100 ms/khung.
- `demon-slime/animations.json` liệt kê các khung theo thứ tự số để tránh đọc nhầm khung 10 trước khung 2. `OnlineBossAssets` nạp các khung trên render thread và đồng bộ động tác với trạng thái server; hitbox và sát thương được quyết định riêng trong `BossEngine`.
- Nội dung ghi công dùng khi tích hợp: **Boss: Demon Slime by chierit — https://chierit.itch.io/boss-demon-slime — CC BY 4.0.** Các ảnh hiện giữ nguyên; khi chỉnh sửa cần ghi rõ thay đổi.
- Bản miễn phí không có animation đập đất hay phun lửa riêng. Có thể bắt đầu triển khai bằng đòn chém có sẵn rồi ghép hiệu ứng cho chiêu bổ sung.

## Kenney Particle Pack

- Thư mục: `effects/kenney-particle-pack/`.
- Tác giả: **Kenney Vleugels (Kenney.nl)**; bản trong archive: **1.1**.
- Trang nguồn: https://kenney.nl/assets/particle-pack
- Giấy phép: **CC0**; giữ nguyên tệp `License.txt` trong gói.
- Có hai bộ PNG: `PNG (Transparent)` và `PNG (Black background)`, cùng ảnh xem trước `Preview.png`.
- Khi tích hợp, ưu tiên bộ nền trong suốt. Đây là các ảnh particle rời để ghép thành khói, tia sáng hoặc hiệu ứng phát tán; không phải một animation chiêu boss hoàn chỉnh.

## More Explosions

- Thư mục: `effects/more-explosions/`.
- Tác giả: **StumpyStrust**.
- Trang nguồn: https://opengameart.org/content/more-explosions
- Tệp gốc: https://opengameart.org/sites/default/files/exports_0.zip
- Giấy phép trang tác giả: **CC0** — https://creativecommons.org/publicdomain/zero/1.0/
- Đã giữ nguyên 6 ảnh PNG từ gói tải: `test.png`, `test3.png`, `test4.png`, `test5.png`, `test6.png`, `tests.png`.
- Các ảnh kích thước 1024×1024, chứa các khung chuyển động hiệu ứng nổ. Cần xác định lưới và số khung có hình trước khi tạo animation.
- Đã kiểm tra cả 6 ảnh có kênh alpha và nền trong suốt. Chưa chuyển đổi hoặc chỉnh sửa ảnh gốc.

Đã kiểm tra tính toàn vẹn ZIP và các ảnh PNG của cả ba gói. `manifest.json` ghi nguồn, giấy phép, SHA-256 archive, danh sách tệp và kích thước ảnh.

## Các bộ chưa tải được

Trang itch.io trả HTTP 403 khi tải tự động. Bộ sau vẫn chưa có:

- Fire Column Pixel Art Effect — https://sanctumpixel.itch.io/fire-column-pixel-art-effect

Demon Slime đã được nhập từ ZIP người dùng cung cấp và tích hợp với AI/chiêu thức server trong chế độ Boss. Fire Column không phải dependency của chế độ hiện tại.

# 05 — Concurrency và room scheduling

**DONE:** đo lock/scheduling boundary và giữ ownership. **PARTIAL:** chưa chứng minh fairness khi phòng nặng trộn phòng idle.

Worker hiện dùng một simulation owner, tick các room tuần tự. Box2D World không chạy đồng thời ở hai thread; network reader chỉ submit input qua runtime/queue monitor. Scheduler không giữ registry lock trong tick. Unregister đánh dấu entry inactive rồi đợi tick đang chạy qua registration monitor; close không dispose World đang bị step đồng thời. Các test scheduler/lifecycle kiểm tra lỗi một runtime được cô lập và unregister/stop an toàn.

Per connection có hai platform threads cho blocking reader/writer. Không thêm một thread cho mỗi room hoặc mỗi profiler stage. Shared frame encoding dùng monitor riêng cho frame, chỉ writer tranh chấp; toàn bộ cache chỉ khóa ngắn khi chọn/đặt entry. Socket I/O không chạy dưới cache/frame lock. JSON encode chậm có thể trì hoãn writer khác cùng frame nhưng không trực tiếp giữ simulation owner.

Các lock waits được instrument: input/runtime monitor, tick/runtime monitor, send lock. Chưa instrument mọi registry/session/JVM lock; không gọi đây là tổng thời gian chờ lock của process. Các histogram stage cũng có monitor riêng, do đó cần benchmark overhead của chính phép đo.

Fixed-rate executor vẫn có thể catch-up nhiều cycle liên tiếp khi trễ. Không bỏ physics step hoặc dùng variable timestep để làm đẹp frequency. Chưa có bounded catch-up hay deadline scheduler. Một room có combat/projectile cost lớn vẫn có thể trì hoãn các room sau trên cùng owner. `ROOM_SCHEDULING_DELAY` đo delay tới lượt room trong cycle; `roomTicks` cho count/mean/max riêng từng room, chưa có histogram p99 riêng cho mỗi room. Không khẳng định đã loại starvation.

| Mô hình | Đánh giá hiện tại |
|---|---|
| Thread/room | Đơn giản nhưng nhiều stack, timer và context switch; chưa có bằng chứng đủ để chuyển. |
| Một owner/worker hiện tại | Ownership rõ, ít simulation threads; head-of-line blocking giữa room là hạn chế. |
| Bounded pool với owner cố định theo shard | **PROPOSED** nếu IMBALANCED chứng minh cần; mỗi World phải có đúng owner và migration chỉ ở boundary an toàn. |
| Work budget/deadline/round-robin | **PROPOSED**; cần định nghĩa điều gì xảy ra khi một tick quá budget, không ngắt JNI `World.step` giữa chừng. |

Input queue giữ capacity 32/player và kiểm tra sequence. Drain lấy trạng thái cuối nhưng OR các edge quan trọng; không chuyển sang chỉ giữ input mới nhất làm mất jump/attack. Primitive timestamp array song hành queue đo residence time mà không tạo wrapper cho mỗi input. Rejections hiện gộp tổng; breakdown stale/duplicate/saturation và p95 queue-depth theo thời gian còn thiếu.

Phép đo tiếp theo khi RAM đủ: cùng tổng số room, cùng bot seed, chạy IDLE rồi COMBAT/IMBALANCED; so sánh per-room duration/delay, deadline violations, actual 60 Hz, CPU/core và thread count qua ít nhất hai lần lặp. Không chọn scheduler mới chỉ dựa vào throughput snapshot.

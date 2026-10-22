# Room sharding và failure policy

ControlPlane cấp `r_` + UUID cho room. Trong một critical section: kiểm tra session idle → chọn worker eligible → reserve room và session slots → phát ticket gắn worker/room. Worker không tự tạo room tùy ý từ JOIN của client cluster: SessionAdmission xác minh owner trước khi tạo local room và cho phép v1 JOIN. Legacy get-or-create vẫn tồn tại trên standalone.

Mỗi worker có incarnation UUID ngẫu nhiên. Heartbeat cùng workerId nhưng khác incarnation hoặc tới sau khi worker bị LOST bị fence; process mới phải có workerId mới. RoomId không tái sử dụng. Không chuyển ownership của room cũ sau timeout, kể cả lobby chưa bắt đầu. Player có thể xin room mới sau khi room cũ ABORTED/cleanup, với RoomId và ticket mới.

Authority ở local prototype là một CP process. JVM synchronized bảo đảm select+reserve nguyên tử trong process đó; đây không phải distributed lock triển khai HA. CP loss khiến mọi worker tự fence; restart CP không phục hồi registry cũ. Không chạy song song hai CP độc lập dùng chung một authority namespace trong production.

Lifecycle runtime giữ nguyên: WAITING → READY → COUNTDOWN → PLAYING → FINISHED → explicit remove. PLAYING tương ứng RUNNING trong thiết kế. GameRoom giữ 2–4 player slots; GameRoom/RoomManager quản lý World, scheduler registration và disposal. Worker maintenance chỉ remove room, không tick Box2D.

Matchmaking reserve group2–4 và thiết lập minimumPlayers trước JOIN đầu tiên; group4 không countdown khi mới có2client ready. Room matchmaking khóa admission đối với session ngoài roster. Room manual giữ minimum2 như cũ. Đây là cấu hình lobby gate, không đổi simulation/gameplay physics.

Lease 5s tại CP; worker dùng deadline monotonic 4s từ lúc bắt đầu heartbeat request, dự phòng 1s. Request chậm không kéo dài lease từ lúc response về. Khi worker mất CP, maintenance thread đóng GameServer, không phát result giả. CP chuyển room sang ABORTED sau lease. JVM pause/network partition có thể trì hoãn thực thi close; vì không reassign RoomId cũ nên không tạo hai simulation owner.

Room chưa có client nào được worker materialize hết hạn ở CP sau 120s. Worker dọn room không còn connection sau 25s; lobby bị bỏ dở có TTL; FINISHED giữ 2s để drain terminal frames rồi đóng sessions và remove World. Heartbeat tiếp theo xác nhận CLEANUP. Ticket TTL 20s, session TTL 15 phút, queue TTL 30s. Không reuse session slot trước khi room terminal; disconnect/reconnect đang hoạt động chưa hỗ trợ.

Graceful worker removal: CP drain → ngừng cấp room mới → đợi room count/reservation bằng 0 → gọi worker `/shutdown` → close TCP/scheduler/persistence. Shutdown toàn cluster có grace cấu hình 0–180s; hết grace thì abort phòng còn chạy và kết thúc process. Đây là chính sách vận hành tường minh, không phải phục hồi trận.

Prototype dùng một host/loopback; region là key lọc placement. Triển khai multi-region thực tế cần region discovery, địa chỉ public/private phù hợp, auth channel bảo mật và registry phân cell có replication.

# 02 — Luật gameplay-v1

**Phạm vi:** tài liệu này mô tả chế độ HP tương thích. Online UI hiện mặc định dùng damage % và blast-zone K.O; xem [Platform Fighter](12-PLATFORM-FIGHTER.md) cho luật mới.

**Luật hiện tại:** chạm vùng dung nham trừ 10 HP và bật lên 1,4 m/s với trọng lực bình thường; chỉ mất một mạng nếu HP về 0. Giới hạn trừ HP một lần/60 tick, không trừ khi bất tử nhưng vẫn bật lên. Tiếp xúc solid đỡ chân loại trừ lava. Xem [FIX1](11-FIX1.md).

Host là creator của room có cấu hình; nếu host rời lobby, quyền host chuyển cho slot còn lại nhỏ nhất. Chỉ host đang thuộc phòng được update. Max players 2/3/4; time 60/90/120/180/300 giây hoặc 0=INFINITE; lives 1/2/3/5; drop NONE/LOW=1800/MEDIUM=900/HIGH=480 ticks. Mặc định gameplay: 4 player, 120 giây, 3 lives, MEDIUM. Đổi luật hủy ready của mọi player. Luật khóa từ COUNTDOWN; setup giữ bản sao bất biến. Không cho hạ capacity dưới roster/minimum matchmaking hiện có.

ACTIVE → HP về 0 → trừ đúng một stock. Còn stock: RESPAWNING 90 tick, body ngừng tham gia physics; sau đó chọn ngẫu nhiên một mặt bệ an toàn, ưu tiên xa đối thủ và tránh lặp điểm vừa dùng, HP=100. Bất tử 120 tick từ tick hồi sinh, hết đúng tại `respawnTick+120`. Hồi sinh không reset F cooldown. Mạng cuối: ELIMINATED, không nhận input gameplay, không gây damage mới, vẫn nhận state/events/result. Disconnect là forfeit toàn bộ stock còn lại, được xử lý ở simulation owner.

Respawning vẫn được tính là người còn stock khi xét last-player-standing. Hết stock đồng thời tất cả: draw. INFINITE không hết hạn theo wall clock. Trận có giới hạn dùng `matchSeconds*60` simulation ticks. Khi hết giờ, giữ policy hiện có: HP cao nhất trong các player còn stock; đồng hạng HP cao nhất là draw, không ưu tiên slot hay tự đổi sang so lives. Champion là winner authoritative đó. Room legacy vẫn dùng luật thời gian/fall cũ.

Damage central gate kiểm tra attacker/target active, block, respawn invulnerability và shield. Shield tiêu đúng một hit hợp lệ, không đổi HP/impulse cho hit đó. Damage statistics cộng phần HP thực mất, không cộng overkill hoặc chi phí tự kích hoạt stomp. Cơ chế stomp cũ và HP cost vẫn tồn tại; không thêm shockwave mở rộng. Lava không bị vô hiệu bởi block/shield; bảo vệ hồi sinh ngăn trừ HP.

Knockback: `1 + 3*(1-clamp(hp,0,100)/100)^2`, tính theo HP sau hit; multiplier mặc định tối đa 4, vector impulse dài tối đa 8. Config record validate finite/positive. Hướng giữ nguyên, dùng Box2D impulse và gravity cũ. Trong gameplay-v1, giữ external impulse/wall-jump trong cửa sổ 12 tick để walking clamp không hủy hiệu ứng ngay tick sau.

JVM properties `smashprofs.gameplay.maxKnockbackMultiplier` (mặc định 4) và `smashprofs.gameplay.maxKnockbackImpulse` (mặc định 8) cấu hình hai giới hạn này; giá trị không hữu hạn/không hợp lệ bị từ chối khi tạo gameplay runtime.

Bám tường cần contact normal ngang với collider Tile tĩnh; có thể bám lúc đang đi lên, giữ hướng vào tường để áp sát. Khi rơi, tốc độ được giới hạn -0,55 m/s. W bật khỏi mặt tường với vận tốc ngang 0,6 m/s, dọc 2,4 m/s; cửa sổ rời contact 6 tick và không reuse cùng latch. Double jump giới hạn cũ vẫn áp dụng sau khi rời tường. Knockback ngăn slide clamp trong 12 tick, để đòn đánh không bị triệt tiêu ngay.

E nhặt vật phẩm gần nhất trong 0,35 m; G thả vũ khí lên bệ và giữ nguyên đạn. E đổi súng thả súng cũ với đạn còn lại. Không tự nhặt khi đi qua. Túi gồm một súng và một khiên; bình máu hồi ngay khi bấm nhặt.

Attribution: direct valid hit lưu attacker/weapon/tick. Ring-out chỉ ghi killer nếu có bằng chứng hit trong 300 tick gần nhất; hết cửa sổ hoặc tự rơi thì killer=null. Mỗi stock loss tính một death và tối đa một K.O. Weapon Hoarder đếm súng/lựu đạn, không đếm Health/Shield; `itemsCollected` vẫn đếm mọi pickup. Danh hiệu DAMAGE/WEAPONS/KO đồng hạng được chia sẻ; nếu tất cả bằng 0 thì không trao danh hiệu đó.

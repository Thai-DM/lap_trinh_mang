# 02 — Luật gameplay-v1

**Luật dung nham đã được người dùng đổi sau bản đầu:** chạm vùng dung nham trừ10HP và bật lên; chỉ mất một mạng nếu HP về0. Giới hạn trừ HP một lần/60tick, vẫn bật lên trong thời gian bảo vệ hồi sinh. Những mô tả dung nham mất ngay một mạng bên dưới thuộc bản trước; xem [bản sửa hiện tại](10-COMBAT-UX-FIXES.md).

Host là creator của room có cấu hình; nếu host rời lobby, quyền host chuyển cho slot còn lại nhỏ nhất. Chỉ host đang thuộc phòng được update. Max players 2/3/4; time 60/90/120/180/300 giây hoặc 0=INFINITE; lives 1/2/3/5; drop NONE/LOW=1800/MEDIUM=900/HIGH=480 ticks. Mặc định gameplay: 4 player, 120 giây, 3 lives, MEDIUM. Đổi luật hủy ready của mọi player. Luật khóa từ COUNTDOWN; setup giữ bản sao bất biến. Không cho hạ capacity dưới roster/minimum matchmaking hiện có.

ACTIVE → mất HP hoặc rơi xuống y<0 → trừ đúng một stock. Còn stock: RESPAWNING 90 tick, body ngừng tham gia physics; sau đó spawn ở phần giữa platform rộng đã được TMX/SpawnProvider kiểm tra, phân tách vị trí theo slot, HP=100. Bất tử 120 tick từ tick hồi sinh, hết đúng tại `respawnTick+120`. Hồi sinh không reset F cooldown. Mạng cuối: ELIMINATED, không nhận input gameplay, không gây damage mới, vẫn nhận state/events/result. Disconnect là forfeit toàn bộ stock còn lại, được xử lý ở simulation owner.

Respawning vẫn được tính là người còn stock khi xét last-player-standing. Hết stock đồng thời tất cả: draw. INFINITE không hết hạn theo wall clock. Trận có giới hạn dùng `matchSeconds*60` simulation ticks. Khi hết giờ, giữ policy hiện có: HP cao nhất trong các player còn stock; đồng hạng HP cao nhất là draw, không ưu tiên slot hay tự đổi sang so lives. Champion là winner authoritative đó. Room legacy vẫn dùng luật thời gian/fall cũ.

Damage central gate kiểm tra attacker/target active, block, respawn invulnerability và shield. Shield tiêu đúng một hit hợp lệ, không đổi HP/impulse cho hit đó. Damage statistics cộng phần HP thực mất, không cộng overkill hoặc chi phí tự kích hoạt stomp. Cơ chế stomp cũ và HP cost vẫn tồn tại; không thêm shockwave mở rộng. Lava là stock-loss hazard kể cả khi vừa hồi sinh; vị trí respawn đặt trên platform an toàn.

Knockback: `1 + 3*(1-clamp(hp,0,100)/100)^2`, tính theo HP sau hit; multiplier mặc định tối đa 4, vector impulse dài tối đa 8. Config record validate finite/positive. Hướng giữ nguyên, dùng Box2D impulse và gravity cũ. Trong gameplay-v1, giữ external impulse/wall-jump trong cửa sổ 12 tick để walking clamp không hủy hiệu ứng ngay tick sau.

JVM properties `smashprofs.gameplay.maxKnockbackMultiplier` (mặc định 4) và `smashprofs.gameplay.maxKnockbackImpulse` (mặc định 8) cấu hình hai giới hạn này; giá trị không hữu hạn/không hợp lệ bị từ chối khi tạo gameplay runtime.

Wall slide chỉ khi contact normal ngang với collider Tile tĩnh và đang rơi; tốc độ rơi được giới hạn -0,55 m/s. W bật ra khỏi mặt tường đang chạm, hướng theo contact normal; cần contact thực và không reuse cùng wall latch. Double jump giới hạn cũ vẫn áp dụng sau khi rời tường. Knockback ngăn slide clamp trong 12 tick, để đòn đánh không bị triệt tiêu ngay.

Attribution: direct valid hit lưu attacker/weapon/tick. Ring-out chỉ ghi killer nếu có bằng chứng hit trong 300 tick gần nhất; hết cửa sổ hoặc tự rơi thì killer=null. Mỗi stock loss tính một death và tối đa một K.O. Weapon Hoarder đếm súng/lựu đạn, không đếm Health/Shield; `itemsCollected` vẫn đếm mọi pickup. Danh hiệu DAMAGE/WEAPONS/KO đồng hạng được chia sẻ; nếu tất cả bằng 0 thì không trao danh hiệu đó.

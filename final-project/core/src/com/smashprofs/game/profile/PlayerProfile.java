package com.smashprofs.game.profile;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;

import java.util.Random;

/**
 * Quản lý Hồ sơ người chơi (Player Profile) cục bộ.
 *
 * <p>Lưu trữ và đồng bộ:
 * <ul>
 *   <li>{@code profileId}: Mã định danh tài khoản duy nhất (dùng để lưu và tích lũy điểm ELO vào SQLite).</li>
 *   <li>{@code displayName}: Tên hiển thị của người chơi trong phòng đấu và trên Bảng xếp hạng.</li>
 *   <li>{@code avatar}: Chiến binh đại diện ("Alex", "Leo", "Luca").</li>
 * </ul>
 *
 * Dữ liệu được lưu bền vững qua LibGDX {@link Preferences} ("smashprofs_player_profile").
 */
public class PlayerProfile {

    private static final String PREF_NAME = "smashprofs_player_profile";
    private static final String KEY_PROFILE_ID = "profile_id";
    private static final String KEY_DISPLAY_NAME = "display_name";
    private static final String KEY_AVATAR = "avatar";

    private static PlayerProfile instance;

    private String profileId;
    private String displayName;
    private String avatar;

    public PlayerProfile() {
        load();
    }

    /**
     * Lấy thể hiện Singleton của Hồ sơ người chơi.
     */
    public static synchronized PlayerProfile get() {
        if (instance == null) {
            instance = new PlayerProfile();
        }
        return instance;
    }

    public static synchronized boolean hasInstance() {
        return instance != null;
    }

    /**
     * Nạp dữ liệu từ Preferences hoặc khởi tạo mặc định nếu là lần đầu chạy.
     */
    public synchronized void load() {
        if (Gdx.app != null) {
            Preferences prefs = Gdx.app.getPreferences(PREF_NAME);
            this.profileId = prefs.getString(KEY_PROFILE_ID, "");
            this.displayName = prefs.getString(KEY_DISPLAY_NAME, "");
            this.avatar = prefs.getString(KEY_AVATAR, "Alex");

            if (this.profileId == null || this.profileId.isBlank()) {
                this.profileId = generateDefaultProfileId();
            }
            if (this.displayName == null || this.displayName.isBlank()) {
                this.displayName = "Chiến Binh " + (10 + new Random().nextInt(90));
            }
            if (this.avatar == null || this.avatar.isBlank()) {
                this.avatar = "Alex";
            }
            save();
        } else {
            // Môi trường test headless
            this.profileId = "test_player_01";
            this.displayName = "Chiến Binh Test";
            this.avatar = "Alex";
        }
    }

    /**
     * Lưu hồ sơ người chơi vào Preferences.
     */
    public synchronized void save() {
        if (Gdx.app != null) {
            Preferences prefs = Gdx.app.getPreferences(PREF_NAME);
            prefs.putString(KEY_PROFILE_ID, this.profileId);
            prefs.putString(KEY_DISPLAY_NAME, this.displayName);
            prefs.putString(KEY_AVATAR, this.avatar);
            prefs.flush();
        }
    }

    /**
     * Đăng xuất / Tạo mới hồ sơ: Đổi sang ID ngẫu nhiên mới và đặt tên mới.
     */
    public synchronized void resetToNew() {
        this.profileId = generateDefaultProfileId();
        this.displayName = "Chiến Binh " + (10 + new Random().nextInt(90));
        this.avatar = "Alex";
        save();
    }

    private String generateDefaultProfileId() {
        return "player_" + (1000 + new Random().nextInt(9000));
    }

    public synchronized String getProfileId() {
        return profileId;
    }

    public synchronized void setProfileId(String profileId) {
        if (profileId != null && !profileId.isBlank()) {
            this.profileId = profileId.trim();
        }
    }

    public synchronized String getDisplayName() {
        return displayName;
    }

    public synchronized void setDisplayName(String displayName) {
        if (displayName != null && !displayName.isBlank()) {
            this.displayName = displayName.trim();
        }
    }

    public synchronized String getAvatar() {
        return avatar != null ? avatar : "Alex";
    }

    public synchronized void setAvatar(String avatar) {
        if (avatar != null && !avatar.isBlank()) {
            this.avatar = avatar.trim();
        }
    }
}

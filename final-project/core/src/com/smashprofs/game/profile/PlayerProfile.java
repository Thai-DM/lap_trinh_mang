package com.smashprofs.game.profile;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;

import java.util.Random;

/**
 * Quản lý Hồ sơ người chơi (Player Profile) cục bộ.
 *
 * <p>Lưu trữ và đồng bộ:
 * <ul>
 *   <li>{@code profileId}: Mã cục bộ trước khi kết nối, không dùng để xác thực quyền sở hữu điểm.</li>
 *   <li>Khóa hồ sơ khách do server cấp được lưu riêng theo endpoint để tiếp tục tích lũy điểm.</li>
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
    private static final String KEY_ELO = "elo";

    private static PlayerProfile instance;

    private String profileId;
    private String displayName;
    private String avatar;
    private int elo = 1000;

    /** Guest credentials are scoped to the server, and never included in public profile labels. */
    private String serverKey(String host,int port) { return "server:"+host.trim()+":"+(host.startsWith("ws://") || host.startsWith("wss://")?0:port)+":"; }
    public synchronized String getCurrentServerProfileId() {
        if(Gdx.app==null) { return profileId; }
        var prefs=Gdx.app.getPreferences("online-server");
        String host=System.getProperty("smashprofs.server.host",prefs.getString("host","127.0.0.1"));
        int port;
        try { port=Integer.parseInt(System.getProperty("smashprofs.server.port",prefs.getString("port","54555"))); }
        catch(NumberFormatException invalid) { port=54555; }
        return getServerProfileId(host,port);
    }
    public synchronized String getShortServerProfileId() {
        String id=getCurrentServerProfileId();return id.length()<=18?id:id.substring(0,10)+"…"+id.substring(id.length()-4);
    }
    public synchronized void prepareConnection(com.fasterxml.jackson.databind.node.ObjectNode payload,String host,int port) {
        if(Gdx.app==null) { return; }
        var prefs=Gdx.app.getPreferences(PREF_NAME);String key=serverKey(host,port);
        payload.put("guestProfile",true);
        String token=prefs.getString(key+"token","");
        if(!token.isBlank()) { payload.put("profileToken",token); }
    }
    public synchronized void acceptConnection(com.fasterxml.jackson.databind.JsonNode payload,String host,int port) {
        if(Gdx.app==null) { return; }
        if(!payload.path("profileId").isTextual() || !payload.path("profileToken").isTextual()) { throw new IllegalArgumentException("Invalid guest profile response"); }
        String id=payload.path("profileId").asText(),token=payload.path("profileToken").asText();
        if(!id.matches("guest_[0-9a-f-]{36}") || !token.matches("[0-9a-f]{64}")) { throw new IllegalArgumentException("Invalid guest profile response"); }
        var prefs=Gdx.app.getPreferences(PREF_NAME);String key=serverKey(host,port);
        prefs.putString(key+"id",id).putString(key+"token",token).flush();
    }
    public synchronized String getServerProfileId(String host,int port) {
        return Gdx.app==null?profileId:Gdx.app.getPreferences(PREF_NAME).getString(serverKey(host,port)+"id",profileId);
    }

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
            this.displayName = com.smashprofs.common.model.DisplayName.sanitize(prefs.getString(KEY_DISPLAY_NAME, ""), null);
            this.avatar = prefs.getString(KEY_AVATAR, "Alex");
            String savedElo = prefs.getString(KEY_ELO, "1000");
            try { this.elo = Integer.parseInt(savedElo); } catch (NumberFormatException e) { this.elo = 1000; }

            if (this.profileId == null || this.profileId.isBlank()) {
                this.profileId = generateDefaultProfileId();
            }
            if (this.displayName == null || this.displayName.isBlank()) {
                this.displayName = randomDisplayName();
            }
            if (this.avatar == null || this.avatar.isBlank()) {
                this.avatar = "Alex";
            }
            save();
        } else {
            // Môi trường test headless
            this.profileId = "test_player_01";
            this.displayName = "Tester";
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
            prefs.putString(KEY_ELO, String.valueOf(this.elo));
            prefs.flush();
        }
    }

    /**
     * Đăng xuất / Tạo mới hồ sơ: Đổi sang ID ngẫu nhiên mới và đặt tên mới.
     */
    public synchronized void resetToNew() {
        if(Gdx.app!=null) { Gdx.app.getPreferences(PREF_NAME).clear(); }
        this.profileId = generateDefaultProfileId();
        this.displayName = randomDisplayName();
        this.avatar = "Alex";
        this.elo = 1000;
        save();
    }

    private String generateDefaultProfileId() {
        return "player_" + (1000 + new Random().nextInt(9000));
    }

    private static String randomDisplayName() {
        return "Chi\u1ebfn S\u0129 " + (10 + new Random().nextInt(90));
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
        String clean = com.smashprofs.common.model.DisplayName.sanitize(displayName, null);
        if (clean != null) {
            this.displayName = clean;
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

    public synchronized int getElo() {
        return elo;
    }

    public synchronized void setElo(int elo) {
        this.elo = Math.max(0, elo);
        save();
    }
}

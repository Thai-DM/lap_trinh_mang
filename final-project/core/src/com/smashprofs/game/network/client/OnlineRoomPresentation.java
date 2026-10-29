package com.smashprofs.game.network.client;

import com.smashprofs.common.model.MatchRules;
import java.security.SecureRandom;

/** User-facing room codes and rule descriptions, independent of rendering and authority. */
public final class OnlineRoomPresentation {
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final char[] ALPHABET="ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private OnlineRoomPresentation() { }
    public static String newRoomCode() {
        var code=new StringBuilder(6);for(int i=0;i<6;i++) { code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]); }return code.toString();
    }
    public static String winRule(MatchRules rules) {
        if(rules.boss()) { return "HỢP TÁC ĐÁNH BOSS · Hạ boss để cả đội thắng. Hết giờ hoặc cả đội hết mạng: thua."; }
        if(rules.fighter()) { return rules.matchSeconds()==0?"Người cuối cùng còn mạng sẽ thắng.":"Hết giờ: nhiều mạng hơn thắng; cùng mạng xét HP cao hơn; bằng nhau thì hòa."; }
        return rules.matchSeconds()==0?"Người cuối cùng còn mạng sẽ thắng.":"Còn một người có mạng: thắng ngay. Hết giờ: xét HP cao nhất; bằng HP thì hòa.";
    }
    public static String stockRule() { return "Dung nham: -10 HP và bật lên. HP về 0: mất 1 mạng, hồi sinh sau 1,5 giây với 100 HP."; }
    public static String stockRule(MatchRules rules) { return rules.boss()?"Đồng đội không gây sát thương nhau. Có sẵn Laser, hết đạn tự nạp sau 1,5 giây. Dung nham -10 HP; hết HP mất mạng.":rules.fighter()?"Hết HP hoặc vượt biên mất 1 mạng; hồi sinh 100 HP sau 1,5 giây. Có nhặt và dùng vũ khí.":stockRule(); }
    public static String dropRate(MatchRules.DropRate rate) { return switch(rate) {
        case NONE->"Tắt vật phẩm";case LOW->"Thấp · 30 giây/lần";case MEDIUM->"Vừa · 15 giây/lần";case HIGH->"Cao · 8 giây/lần";case VERY_HIGH->"Rất cao · 4 giây/lần";
    }; }
    public static String error(String code,String fallback) {
        if(code==null) { return fallback; }
        return switch(code) {
            case "MATCHMAKING_FULL"->"Máy chủ đang đủ trận. Hãy thử tìm lại sau một lát.";
            case "MATCHMAKING_UNAVAILABLE"->"Máy chủ này chưa hỗ trợ ghép nhanh. Hãy dùng phòng riêng hoặc đổi máy chủ.";
            case "ROOM_NOT_FOUND"->"Không tìm thấy phòng. Kiểm tra mã và địa chỉ máy chủ.";
            case "ROOM_FULL"->"Phòng đã đủ người. Hãy tạo phòng khác hoặc nhờ chủ phòng tăng chỗ.";
            case "CONNECTION_FAILED"->"Không kết nối được máy chủ. Hãy mở server và kiểm tra địa chỉ.";
            case "INVALID_ROOM_STATE"->"Phòng đã tồn tại hoặc đang bắt đầu trận. Hãy dùng mã khác để tạo phòng.";
            case "GAMEPLAY_CAPABILITY_REQUIRED","FIGHTER_CAPABILITY_REQUIRED","BOSS_CAPABILITY_REQUIRED"->"Phòng này cần phiên bản game mới.";
            default->fallback;
        };
    }
}

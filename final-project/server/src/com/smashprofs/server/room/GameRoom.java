package com.smashprofs.server.room;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.game.MatchRuntime;
import com.smashprofs.server.game.MatchSetup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Quản lý membership của phòng với tối đa 4 player.
 * Synchronized tạm thời bảo vệ membership, lựa chọn nhân vật, ready, phase, countdown và runtime.
 * Mutable room state về sau sẽ do simulation thread hoặc serialized executor sở hữu.
 */
public final class GameRoom {
    private static final Logger LOGGER = Logger.getLogger(GameRoom.class.getName());
    // Khớp với PlayerTypes và CharacterSelectScreen của baseline, không phụ thuộc core.
    private static final Set<String> VALID_CHARACTER_IDS = Set.of("Alex", "Luca", "Maurice", "Leo", "Viktor");
    private static final long NANOS_PER_MILLISECOND = 1_000_000L;
    private static final long COUNTDOWN_DURATION_NANOS = 3000L * NANOS_PER_MILLISECOND;
    private final String roomId;
    private final LongSupplier nanoClock;
    private final Consumer<MatchRuntime> onMatchStarted;
    private final Function<List<MatchSetup.Participant>, MatchSetup> setupFactory;
    private final PlayerSlot[] playerSlots = new PlayerSlot[4];
    private final Map<PlayerId, String> selectedCharacters = new HashMap<>();
    private final Set<PlayerId> readyPlayers = new HashSet<>();
    private final Set<PlayerId> loadedPlayers=new HashSet<>(),rematchVotes=new HashSet<>();
    public synchronized void markLoaded(PlayerId id) {
        if(!rules.fighter() || !containsPlayer(id) || !readyPlayers.contains(id) || closed) { throw new IllegalStateException("Not a ready fighter"); }
        boolean added=loadedPlayers.add(id);
        if(added) { settingsVersion++; }
        if(added && loadedPlayers.containsAll(readyPlayers) && phase==RoomPhase.COUNTDOWN) { countdownStartedAtNanos=nanoClock.getAsLong(); }
    }
    public synchronized int getLoadedPlayerCount() { return loadedPlayers.size(); }
    public record Rematch(int votes,int required,boolean started) { }
    public synchronized Rematch requestRematch(PlayerId id,String matchId,Set<PlayerId> connectedPlayers) {
        refreshMatchCompletion();
        if(!rules.fighter() || closed || phase!=RoomPhase.FINISHED || !containsPlayer(id) || matchRuntime==null || !matchRuntime.getMatchId().equals(matchId) || getPlayerCount()<2) { throw new IllegalStateException("Rematch requires a finished match and its roster"); }
        var available=getPlayerSlots().stream().map(PlayerSlot::playerId).filter(connectedPlayers::contains).collect(java.util.stream.Collectors.toSet());
        if(available.size()<2 || !available.contains(id)) { throw new IllegalStateException("At least two connected players required"); }
        rematchVotes.retainAll(available);rematchVotes.add(id);int votes=rematchVotes.size(),required=available.size();
        boolean start=votes==required;
        if(start) {
            // Finished gameplay already released its native world. Leave the old runtime readable
            // until its scheduler registration and pending reliable terminal deliveries retire.
            matchRuntime=null;preparedMatch=null;countdownComplete=false;countdownRemainingNanos=0;countdownStartedAtNanos=0;
            for(int i=0;i<playerSlots.length;i++) {
                if(playerSlots[i]!=null && !available.contains(playerSlots[i].playerId())) {
                    var departed=playerSlots[i].playerId();playerSlots[i]=null;selectedCharacters.remove(departed);readyPlayers.remove(departed);
                }
            }
            if(!available.contains(host)) { host=getPlayerSlots().get(0).playerId(); }
            minimumPlayers=Math.min(minimumPlayers,available.size());
            loadedPlayers.clear();rematchVotes.clear();phase=RoomPhase.WAITING;settingsVersion++;updateLobbyPhase();
        }
        return new Rematch(votes,required,start);
    }
    private RoomPhase phase = RoomPhase.WAITING;
    private int minimumPlayers = 2;
    private com.smashprofs.common.model.MatchRules rules = com.smashprofs.common.model.MatchRules.LEGACY;
    private PlayerId host;
    private boolean matchmaking;
    public synchronized boolean isMatchmaking() { return matchmaking; }
    /** Casual quick match uses fixed rules and exactly two participants. */
    public synchronized void configureMatchmaking(PlayerId creator, boolean boss) {
        if(boss) { configureBoss(creator); } else { configureGameplay(creator,true); }
        rules=new com.smashprofs.common.model.MatchRules(2,rules.matchSeconds(),rules.lives(),rules.dropRate(),true,true,rules.keepWeaponOnThrow(),boss);
        matchmaking=true;
    }
    private long settingsVersion;
    private long rulesVersion;
    public synchronized long getRulesVersion() { return rulesVersion; }
    public synchronized PlayerId getHost() { return host; }
    public synchronized long getSettingsVersion() { return settingsVersion; }
    public synchronized com.smashprofs.common.model.MatchRules getRules() { return rules; }
    public synchronized void configureGameplay(PlayerId creator) {
        configureGameplay(creator,false);
    }
    public synchronized void configureGameplay(PlayerId creator,boolean fighter) {
        if(closed || getPlayerCount()!=0 || phase!=RoomPhase.WAITING) { throw new IllegalStateException("Configure before joining"); }
        host=Objects.requireNonNull(creator); rules=fighter?com.smashprofs.common.model.MatchRules.FIGHTER:com.smashprofs.common.model.MatchRules.DEFAULT; settingsVersion++;rulesVersion++;
    }
    public synchronized void configureBoss(PlayerId creator) {
        configureGameplay(creator,true);rules=com.smashprofs.common.model.MatchRules.BOSS;
    }
    public synchronized void updateSettings(PlayerId actor,com.smashprofs.common.model.MatchRules next) {
        if(matchmaking) { throw new SecurityException("Quick match rules are fixed"); }
        if(!rules.gameplay() || !Objects.equals(host,actor) || getPlayerSlot(actor)==null) { throw new SecurityException("Only room host may update settings"); }
        if(closed || phase==RoomPhase.COUNTDOWN || isMatchLocked()) { throw new IllegalStateException("Rules locked"); }
        if(!next.gameplay() || next.fighter()!=rules.fighter() || next.boss()!=rules.boss() || next.maxPlayers()<getPlayerCount() || next.maxPlayers()<minimumPlayers) { throw new IllegalArgumentException("Invalid mode/capacity"); }
        if(next.equals(rules)) { return; }
        rules=next; readyPlayers.clear(); cancelCountdown(); updateLobbyPhase(); settingsVersion++;rulesVersion++;
    }

    /** Cluster matchmaking can reserve an exact group before any client joins. Legacy minimum stays 2. */
    public synchronized void setMinimumPlayers(int count) {
        if (count < 2 || count > 4) { throw new IllegalArgumentException("minimum players 2..4"); }
        if (count == minimumPlayers) { return; }
        if (closed || getPlayerCount() != 0 || phase != RoomPhase.WAITING) { throw new IllegalStateException("Configure before joining"); }
        minimumPlayers = count;
    }
    private long countdownStartedAtNanos;
    private long countdownRemainingNanos;
    private boolean countdownComplete;
    private MatchSetup preparedMatch;
    private MatchRuntime matchRuntime;
    private boolean closed;
    private String lifecycleFailure;

    public GameRoom(String roomId) {
        this(roomId, System::nanoTime);
    }

    /** Clock phải trả thời gian monotonic theo nanosecond; có thể inject clock giả khi test. */
    public GameRoom(String roomId, LongSupplier nanoClock) {
        this(roomId, nanoClock, runtime -> { });
    }

    /** RoomManager gắn boundary registration; phòng độc lập vẫn giữ API tick thủ công cũ. */
    GameRoom(String roomId, LongSupplier nanoClock, Consumer<MatchRuntime> onMatchStarted) {
        this(roomId, nanoClock, onMatchStarted,
                participants -> new MatchSetup(UUID.randomUUID().toString(), roomId, participants));
    }

    /** Điểm inject nội bộ để test lỗi chuẩn bị dữ liệu, không thêm model/protocol mới. */
    GameRoom(String roomId, LongSupplier nanoClock, Consumer<MatchRuntime> onMatchStarted,
             Function<List<MatchSetup.Participant>, MatchSetup> setupFactory) {
        this.roomId = validateRoomId(roomId);
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock không được null");
        this.onMatchStarted = Objects.requireNonNull(onMatchStarted, "onMatchStarted không được null");
        this.setupFactory = Objects.requireNonNull(setupFactory, "setupFactory không được null");
    }

    public String getRoomId() {
        return roomId;
    }

    /** Đọc trạng thái phòng dưới cùng khóa với các thao tác cập nhật lobby. */
    public synchronized RoomPhase getPhase() {
        refreshMatchCompletion();
        return phase;
    }

    /** Chỉ đọc result volatile; không lấy khóa runtime hoặc giữ room lock qua Box2D simulation. */
    private boolean refreshMatchCompletion() {
        if (phase == RoomPhase.PLAYING && matchRuntime != null && matchRuntime.isFinished()) {
            phase = RoomPhase.FINISHED;
            return true;
        }
        return false;
    }

    private boolean isMatchLocked() {
        RoomPhase current = getPhase();
        return current == RoomPhase.PLAYING || current == RoomPhase.FINISHED;
    }

    /** Gán slot trống nhỏ nhất; player đã có trong phòng không bị thêm lại. */
    public synchronized JoinResult joinPlayer(PlayerId playerId) {
        if (closed) {
            validatePlayerId(playerId);
            return JoinResult.ROOM_CLOSED;
        }
        if (getPlayerSlot(playerId) != null) {
            return JoinResult.ALREADY_IN_ROOM;
        }
        if (isMatchLocked()) {
            return JoinResult.MATCH_ALREADY_STARTED;
        }
        // Giữ roster ổn định cả khi countdown hoàn tất nhưng chưa khởi tạo trận.
        if (phase == RoomPhase.COUNTDOWN) {
            return JoinResult.COUNTDOWN_IN_PROGRESS;
        }
        if(getPlayerCount()>=rules.maxPlayers()) { return JoinResult.ROOM_FULL; }
        for (int index = 0; index < rules.maxPlayers(); index++) {
            if (playerSlots[index] == null) {
                playerSlots[index] = new PlayerSlot(index, playerId);
                settingsVersion++;
                if(host==null) { host=playerId; settingsVersion++; }
                readyPlayers.remove(playerId);
                updateLobbyPhase();
                return JoinResult.JOINED;
            }
        }
        return JoinResult.ROOM_FULL;
    }

    /** Giải phóng slot ngoài PLAYING; không đổi slot của các player còn lại. */
    public synchronized boolean leavePlayer(PlayerId playerId) {
        PlayerSlot slot = getPlayerSlot(playerId);
        if (slot == null || closed) {
            return false;
        }
        if (isMatchLocked()) {
            // TODO: Xử lý rời trận qua disconnect/forfeit lifecycle, không sửa roster đang chơi.
            return false;
        }
        playerSlots[slot.slotIndex()] = null;
        settingsVersion++;
        if(playerId.equals(host)) { host=getPlayerSlots().stream().map(PlayerSlot::playerId).findFirst().orElse(null); settingsVersion++; }
        selectedCharacters.remove(playerId);
        readyPlayers.remove(playerId);
        cancelCountdown();
        updateLobbyPhase();
        return true;
    }

    /**
     * Chọn hoặc thay đổi nhân vật cho player đã join room.
     * ID nhân vật phải khớp chính xác tên trong baseline, không trim hay đổi hoa/thường.
     * Đổi sang nhân vật khác hủy ready; chọn lại cùng nhân vật giữ nguyên ready.
     *
     * @return true nếu thành công, false nếu player không ở phòng, phòng đã đóng hoặc đang PLAYING
     * @throws NullPointerException nếu characterId null
     * @throws IllegalArgumentException nếu characterId không hợp lệ
     */
    public synchronized boolean selectCharacter(PlayerId playerId, String characterId) {
        PlayerSlot slot = getPlayerSlot(playerId);
        Objects.requireNonNull(characterId, "characterId không được null");
        if (!VALID_CHARACTER_IDS.contains(characterId)) {
            throw new IllegalArgumentException("characterId không hợp lệ: " + characterId);
        }
        if (slot == null || closed || isMatchLocked()) {
            return false;
        }
        String previousCharacter = selectedCharacters.put(playerId, characterId);
        if (!characterId.equals(previousCharacter)) {
            settingsVersion++;
            readyPlayers.remove(playerId);
            cancelCountdown();
        }
        updateLobbyPhase();
        return true;
    }

    /** Trả nhân vật đã chọn, hoặc null nếu chưa chọn hay player không ở trong phòng. */
    public synchronized String getSelectedCharacter(PlayerId playerId) {
        if (getPlayerSlot(playerId) == null) {
            return null;
        }
        return selectedCharacters.get(playerId);
    }

    /**
     * Đặt hoặc hủy ready cho player trong phòng; ready=true cần đã chọn nhân vật.
     * Gọi lại cùng trạng thái vẫn thành công nếu đủ điều kiện và chưa PLAYING.
     *
     * @return true nếu hợp lệ, false nếu phòng đóng/PLAYING/FINISHED, player không ở phòng hoặc chưa chọn nhân vật
     */
    public synchronized boolean setReady(PlayerId playerId, boolean ready) {
        if (getPlayerSlot(playerId) == null || closed || isMatchLocked()) {
            return false;
        }
        if (ready) {
            if (!selectedCharacters.containsKey(playerId)) {
                return false;
            }
            if(readyPlayers.add(playerId)) { settingsVersion++; }
        } else {
            if(readyPlayers.remove(playerId)) { settingsVersion++; }
            cancelCountdown();
        }
        updateLobbyPhase();
        return true;
    }

    /**
     * Cập nhật lựa chọn tùy chọn và ready trong cùng khóa, dành cho một request PLAYER_READY.
     * characterId null nghĩa là giữ lựa chọn hiện tại, không phải xóa nhân vật.
     * Kiểm tra toàn bộ điều kiện trước khi gọi selectCharacter/setReady để tránh cập nhật dở dang.
     * Không khởi động countdown hay trận đấu; lifecycle hiện có tiếp tục phụ trách.
     *
     * @return false nếu player không thuộc phòng, phòng đóng hoặc phase không cho phép
     * @throws IllegalArgumentException nếu nhân vật yêu cầu không hợp lệ hoặc ready thiếu nhân vật
     */
    public synchronized boolean updatePlayerReady(PlayerId playerId, String characterId, boolean ready) {
        if (getPlayerSlot(playerId) == null || closed
                || phase == RoomPhase.PLAYING || phase == RoomPhase.FINISHED) {
            return false;
        }
        String effectiveCharacter = characterId != null ? characterId : selectedCharacters.get(playerId);
        if ((characterId != null || ready)
                && (effectiveCharacter == null || !VALID_CHARACTER_IDS.contains(effectiveCharacter))) {
            throw new IllegalArgumentException("Cần nhân vật hợp lệ để chọn hoặc ready");
        }
        // Sau validation, không có thread khác đổi membership/phase giữa hai thao tác này.
        if (characterId != null) {
            selectCharacter(playerId, characterId);
        }
        return setReady(playerId, ready);
    }

    /** Player chưa join được xem là không ready; ID không hợp lệ bị từ chối. */
    public synchronized boolean isPlayerReady(PlayerId playerId) {
        return getPlayerSlot(playerId) != null && readyPlayers.contains(playerId);
    }

    /** Chỉ kiểm tra điều kiện 2–4 player đã chọn nhân vật hợp lệ và đều ready, không bắt đầu trận. */
    public synchronized boolean canStartMatch() {
        if (closed || getPhase() == RoomPhase.FINISHED) {
            return false;
        }
        int playerCount = getPlayerCount();
        if (playerCount < minimumPlayers || playerCount > 4) {
            return false;
        }
        for (PlayerSlot slot : playerSlots) {
            if (slot != null) {
                String characterId = selectedCharacters.get(slot.playerId());
                if (characterId == null || !VALID_CHARACTER_IDS.contains(characterId)
                        || !readyPlayers.contains(slot.playerId())) {
                    return false;
                }
            }
        }
        return true;
    }

    public synchronized int getPlayerCount() {
        int count = 0;
        for (PlayerSlot slot : playerSlots) {
            if (slot != null) {
                count++;
            }
        }
        return count;
    }

    public synchronized boolean containsPlayer(PlayerId playerId) {
        return getPlayerSlot(playerId) != null;
    }

    /** Trả slot của player, hoặc null nếu player không ở trong phòng. */
    public synchronized PlayerSlot getPlayerSlot(PlayerId playerId) {
        validatePlayerId(playerId);
        for (PlayerSlot slot : playerSlots) {
            if (slot != null && slot.playerId().equals(playerId)) {
                return slot;
            }
        }
        return null;
    }

    /** Snapshot bất biến theo thứ tự slot, không chứa slot trống. */
    public synchronized List<PlayerSlot> getPlayerSlots() {
        List<PlayerSlot> snapshot = new ArrayList<>();
        for (PlayerSlot slot : playerSlots) {
            if (slot != null) {
                snapshot.add(slot);
            }
        }
        return List.copyOf(snapshot);
    }

    public synchronized boolean isFull() {
        return getPlayerCount() == playerSlots.length;
    }

    /**
     * Một bước state machine dưới cùng khóa room: READY bắt đầu countdown;
     * COUNTDOWN cập nhật clock, chuẩn bị và khởi tạo trận khi đủ điều kiện.
     * PLAYING đọc completion để chuyển FINISHED; không tự bắt đầu lại trận đã kết thúc.
     * Lỗi hủy countdown và khóa tự động retry cho phòng này; xóa/tạo lại phòng để phục hồi tự động.
     * API tường minh cũ vẫn còn, nhưng caller không được tick runtime do server quản lý.
     *
     * @return true nếu chuyển phase thành COUNTDOWN/PLAYING/FINISHED, false nếu không đổi/bị lỗi
     */
    public synchronized boolean updateLifecycle() {
        if (closed || lifecycleFailure != null) {
            return false;
        }
        String operation = "startCountdown";
        try {
            switch (phase) {
                case READY:
                    return canStartMatch() && startCountdown();
                case COUNTDOWN:
                    operation = "updateCountdown";
                    updateCountdown();
                    if (!countdownComplete) {
                        return false;
                    }
                    operation = "prepareMatch";
                    if (!canStartMatch() || prepareMatch() == null) {
                        throw new IllegalStateException("Điều kiện chuẩn bị trận không còn hợp lệ");
                    }
                    operation = "startMatch";
                    if (startMatch() == null) {
                        throw new IllegalStateException("Không thể khởi động trận đã chuẩn bị");
                    }
                    return true;
                case PLAYING:
                    return refreshMatchCompletion();
                case WAITING:
                case FINISHED:
                default:
                    return false;
            }
        } catch (RuntimeException failure) {
            lifecycleFailure = "Phòng " + roomId + " lỗi ở " + operation + ": " + failure;
            cancelCountdown();
            LOGGER.log(Level.SEVERE, lifecycleFailure, failure);
            return false;
        }
    }

    /** Null nếu chưa có lỗi; giữ nguyên nguyên nhân lỗi để không báo/khởi tạo lại vô hạn mỗi tick. */
    public synchronized String getLifecycleFailure() {
        return lifecycleFailure;
    }

    /** Chỉ đọc runtime đã publish, không khởi tạo trận khi caller chỉ cần truy vấn. */
    public synchronized MatchRuntime getMatchRuntime() {
        return matchRuntime;
    }

    /** Bắt đầu tường minh; trả false nếu không đủ điều kiện hoặc đã ở phase COUNTDOWN. */
    public synchronized boolean startCountdown() {
        if (closed || phase != RoomPhase.READY || !canStartMatch()) {
            return false;
        }
        countdownStartedAtNanos = nanoClock.getAsLong();
        countdownRemainingNanos = COUNTDOWN_DURATION_NANOS;
        countdownComplete = false;
        phase = RoomPhase.COUNTDOWN;
        if(rules.fighter()) { settingsVersion++; }
        return true;
    }

    /**
     * updateLifecycle gọi định kỳ; riêng method này không tự tạo thread hay bắt đầu trận.
     * Dùng hiệu nanoTime để không phụ thuộc đồng hồ lịch hoặc mốc nanoTime âm.
     *
     * @return true duy nhất ở lần cập nhật vừa hoàn tất countdown; các lần khác trả false
     */
    public synchronized boolean updateCountdown() {
        if (closed || phase != RoomPhase.COUNTDOWN || countdownComplete) {
            return false;
        }
        if(rules.fighter() && !loadedPlayers.containsAll(readyPlayers)) { countdownStartedAtNanos=nanoClock.getAsLong();countdownRemainingNanos=COUNTDOWN_DURATION_NANOS;return false; }
        long elapsedNanos = Math.max(0L, nanoClock.getAsLong() - countdownStartedAtNanos);
        countdownRemainingNanos = elapsedNanos >= COUNTDOWN_DURATION_NANOS
                ? 0L : COUNTDOWN_DURATION_NANOS - elapsedNanos;
        if (countdownRemainingNanos == 0L) {
            countdownComplete = true;
            return true;
        }
        return false;
    }

    /**
     * Trả thời gian tại lần updateCountdown gần nhất, làm tròn lên để không báo 0 quá sớm.
     * Trước khi bắt đầu, sau khi hủy hoặc sau khi hoàn tất đều trả 0.
     */
    public synchronized long getCountdownRemainingMillis() {
        return (countdownRemainingNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND;
    }

    public synchronized boolean isCountdownComplete() {
        return countdownComplete;
    }

    /** Countdown hoàn tất vẫn giữ phase COUNTDOWN, nhưng không còn đang đếm ngược. */
    public synchronized boolean isCountingDown() {
        return !closed && phase == RoomPhase.COUNTDOWN && !countdownComplete;
    }

    /**
     * Chuẩn bị một snapshot bất biến sau khi updateCountdown đã ghi nhận hoàn tất.
     * UUID chỉ được sinh ở lần chuẩn bị thành công đầu tiên của lượt countdown hiện tại.
     * Không bắt đầu runtime và không chuyển phase sang PLAYING.
     *
     * @return setup đã cache, hoặc null nếu countdown/roster chưa đủ điều kiện
     */
    public synchronized MatchSetup prepareMatch() {
        if (closed || phase != RoomPhase.COUNTDOWN || !countdownComplete || !canStartMatch()) {
            return null;
        }
        if (preparedMatch == null) {
            List<MatchSetup.Participant> participants = new ArrayList<>();
            for (PlayerSlot slot : getPlayerSlots()) {
                participants.add(new MatchSetup.Participant(
                        slot.playerId(), slot.slotIndex(), selectedCharacters.get(slot.playerId())));
            }
            preparedMatch = Objects.requireNonNull(setupFactory.apply(List.copyOf(participants)),
                    "setupFactory không được trả null");
            if(rules.gameplay()) { preparedMatch=new MatchSetup(preparedMatch.matchId(),preparedMatch.roomId(),preparedMatch.participants(),rules); }
        }
        return preparedMatch;
    }

    /**
     * Khởi tạo từ setup đã chuẩn bị tường minh, chỉ khi countdown hoàn tất và roster còn khớp.
     * Kiểm tra, lưu runtime và chuyển phase nằm trong cùng khóa của room.
     *
     * @return runtime đang chơi nếu đã start, runtime mới nếu thành công, hoặc null nếu chưa đủ điều kiện
     */
    public synchronized MatchRuntime startMatch() {
        refreshMatchCompletion();
        if (closed) {
            return null;
        }
        if (phase == RoomPhase.PLAYING) {
            return matchRuntime;
        }
        if (phase != RoomPhase.COUNTDOWN || !countdownComplete
                || preparedMatch == null || !canStartMatch()) {
            return null;
        }
        if (!roomId.equals(preparedMatch.roomId())
                || preparedMatch.participants().size() != getPlayerCount()) {
            return null;
        }
        for (MatchSetup.Participant participant : preparedMatch.participants()) {
            PlayerSlot slot = getPlayerSlot(participant.playerId());
            if (slot == null || slot.slotIndex() != participant.slotIndex()
                    || !participant.characterId().equals(selectedCharacters.get(participant.playerId()))) {
                return null;
            }
        }
        // Nếu constructor, start hoặc boundary registration ném lỗi, chưa lưu runtime hay chuyển phase.
        MatchRuntime candidate = new MatchRuntime(preparedMatch, nanoClock);
        try {
            candidate.start();
            // Boundary chỉ nhận runtime đã start. Nếu register bị từ chối, đóng World chưa publish.
            // Không gọi tick thủ công với runtime của phòng do server quản lý.
            onMatchStarted.accept(candidate);
        } catch (RuntimeException | Error failure) {
            try {
                candidate.close();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        matchRuntime = candidate;
        phase = RoomPhase.PLAYING;
        return matchRuntime;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    /**
     * Cleanup quản trị: chặn lệnh mới và trả runtime để RoomManager unregister ngoài khóa room.
     * Không phải GAME_END: giữ phase/roster/state, không tạo kết quả hay chuyển sang FINISHED.
     */
    synchronized MatchRuntime closeForServerShutdown() {
        closed = true;
        return matchRuntime;
    }

    /** Hủy cả countdown đang chạy hoặc đã hoàn tất; tính lại lobby, không tự restart. */
    public synchronized boolean cancelCountdown() {
        loadedPlayers.clear();
        if (closed || phase != RoomPhase.COUNTDOWN) {
            return false;
        }
        countdownStartedAtNanos = 0L;
        countdownRemainingNanos = 0L;
        countdownComplete = false;
        // Setup của lượt bị hủy không được dùng cho roster hoặc lượt countdown mới.
        preparedMatch = null;
        phase = canStartMatch() ? RoomPhase.READY : RoomPhase.WAITING;
        if(rules.fighter()) { settingsVersion++; }
        return true;
    }

    /** Chỉ gọi khi đang giữ khóa của room; dùng chung điều kiện ready, không bắt đầu trận. */
    private void updateLobbyPhase() {
        if (phase == RoomPhase.WAITING || phase == RoomPhase.READY) {
            phase = canStartMatch() ? RoomPhase.READY : RoomPhase.WAITING;
        }
    }

    public enum JoinResult {
        JOINED,
        ALREADY_IN_ROOM,
        ROOM_FULL,
        COUNTDOWN_IN_PROGRESS,
        MATCH_ALREADY_STARTED,
        ROOM_CLOSED
    }

    /** Giữ nguyên giá trị ID hợp lệ, không tự trim hay tạo ID mới. */
    private static void validatePlayerId(PlayerId playerId) {
        Objects.requireNonNull(playerId, "playerId không được null");
        String value = Objects.requireNonNull(playerId.value(), "playerId.value không được null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("playerId.value không được blank");
        }
    }

    /** Dùng chung quy tắc ID trong package; giữ nguyên chuỗi hợp lệ, không trim. */
    static String validateRoomId(String roomId) {
        Objects.requireNonNull(roomId, "roomId không được null");
        if (roomId.isBlank()) {
            throw new IllegalArgumentException("roomId không được blank");
        }
        return roomId;
    }
}

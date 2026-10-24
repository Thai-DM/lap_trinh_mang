package com.smashprofs.server.game;

import com.badlogic.gdx.physics.box2d.World;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.server.game.combat.MeleeCombatController;
import com.smashprofs.server.game.combat.StompCombatController;
import com.smashprofs.server.game.projectile.ProjectileManager;
import com.smashprofs.server.game.projectile.ServerProjectile;
import com.smashprofs.server.game.physics.HeadlessPhysicsWorld;
import com.smashprofs.server.game.physics.PhysicsWorldFactory;
import com.smashprofs.server.game.physics.MapCollisionBuilder;
import com.smashprofs.server.game.physics.TmxCollisionLoader;
import com.smashprofs.server.game.physics.PlayerPhysicsFactory;
import com.smashprofs.server.game.physics.PlayerMovementController;
import com.smashprofs.server.game.physics.PlayerJumpController;
import com.smashprofs.server.game.physics.ArenaBoundaryController;

import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.concurrent.TimeUnit;

/** Sở hữu state/queue/World riêng; simulation owner áp dụng movement, jump/gravity, boundary, melee rồi step. */
public final class MatchRuntime implements AutoCloseable {
    private volatile com.smashprofs.server.metrics.PerformanceMetrics metrics = com.smashprofs.server.metrics.PerformanceMetrics.DISABLED;
    private long measuredTicks, measuredTickNanos, maxTickNanos;
    public synchronized void setPerformanceMetrics(com.smashprofs.server.metrics.PerformanceMetrics metrics) {
        this.metrics=Objects.requireNonNull(metrics); inputQueue.setPerformanceMetrics(metrics); jumpController.setPerformanceMetrics(metrics);
    }
    public synchronized Map<String,Object> tickMetrics() {
        return Map.of("count",measuredTicks,"meanMs",measuredTicks==0?0:measuredTickNanos/(double)measuredTicks/1e6,"maxMs",maxTickNanos/1e6);
    }
    public synchronized void resetTickMetrics() { measuredTicks=0;measuredTickNanos=0;maxTickNanos=0; }
    public synchronized int getPendingInputCount() { return inputQueue.getPendingCount(); }
    public static final int TICKS_PER_SECOND = 60;
    /** Timeout held input (movement và block) ban đầu 250 ms ở 60 Hz, không phải hằng số gameplay gốc. */
    public static final int DEFAULT_MOVEMENT_TIMEOUT_TICKS = TICKS_PER_SECOND / 4;
    static final float PHYSICS_TIME_STEP = 1.0f / TICKS_PER_SECOND;
    static final int VELOCITY_ITERATIONS = 6;
    static final int POSITION_ITERATIONS = 2;
    static final long MATCH_DURATION_NANOS = TimeUnit.SECONDS.toNanos(120);
    private final MatchSetup setup;
    private final boolean stableRosters=Boolean.parseBoolean(System.getProperty("smashprofs.performance.stableRosters","true"));
    private List<MatchSetup.Participant> orderedParticipants;
    private List<MatchSetup.Participant> participantsInSlotOrder() {
        if(orderedParticipants==null) { orderedParticipants=setup.participants().stream().sorted(Comparator.comparingInt(MatchSetup.Participant::slotIndex)).toList(); }
        return stableRosters?orderedParticipants:setup.participants().stream().sorted(Comparator.comparingInt(MatchSetup.Participant::slotIndex)).toList();
    }
    private final Supplier<HeadlessPhysicsWorld> physicsFactory;
    private final PhysicsStepper physicsStepper;
    private final Consumer<HeadlessPhysicsWorld> initializationCheck;
    private final int movementInputTimeoutTicks;
    private final int meleeCooldownTicks;
    private final LongSupplier nanoClock;
    private long matchStartedAtNanos;
    private final PlayerMovementController movementController = new PlayerMovementController();
    // Chỉ simulation owner cập nhật; close dùng cùng khóa runtime để xóa, không lưu action edges.
    private final Map<PlayerId, HeldInput> heldInputs = new HashMap<>();
    private volatile MatchState matchState;
    // Chỉ simulation owner publish sau tick hoàn tất; reader không truy cập JNI hoặc khóa runtime.
    private volatile AuthoritativeGameSnapshot latestSnapshot;
    // Non-null là nguồn FINISHED duy nhất; publish sau final snapshot, không thêm finished/alive flags.
    private volatile AuthoritativeMatchResult matchResult;
    private InputQueue inputQueue;
    private HeadlessPhysicsWorld physicsWorld;
    private MapCollisionBuilder.MapCollisionBodies arenaBodies;
    private PlayerPhysicsFactory.PlayerPhysicsBodies playerBodies;
    private PlayerJumpController jumpController;
    private ArenaBoundaryController boundaryController;
    private MeleeCombatController meleeController;
    private StompCombatController stompController;
    private ProjectileManager projectileManager;
    private GameplayMatch gameplay;
    GameplayMatch gameplay() { return gameplay; }
    public synchronized List<com.smashprofs.common.model.MatchEvent> getEventsAfter(long sequence) { return gameplay==null?List.of():gameplay.eventsAfter(sequence); }
    public synchronized void requestDisconnect(PlayerId id) { if(gameplay!=null && !closed && !isFinished()) { gameplay.disconnect(id); } }
    private volatile boolean closed;
    private Thread simulationOwner;

    public MatchRuntime(MatchSetup setup) {
        this(setup, new PhysicsWorldFactory()::create, World::step);
    }

    /** Clock server monotonic; GameRoom truyền clock hiện có, test dùng manual clock, không client time. */
    public MatchRuntime(MatchSetup setup, LongSupplier nanoClock) {
        this(setup, new PhysicsWorldFactory()::create, World::step, physics -> { },
                DEFAULT_MOVEMENT_TIMEOUT_TICKS, MeleeCombatController.DEFAULT_COOLDOWN_TICKS, nanoClock);
    }

    /** Cấu hình timeout bằng simulation tick; không dùng clientTick hoặc đồng hồ client. */
    public MatchRuntime(MatchSetup setup, int movementInputTimeoutTicks) {
        this(setup, new PhysicsWorldFactory()::create, World::step, physics -> { }, movementInputTimeoutTicks);
    }

    /** Cooldown anti-spam server cấu hình theo tick; source gốc không có cooldown melee riêng. */
    public MatchRuntime(MatchSetup setup, int movementInputTimeoutTicks, int meleeCooldownTicks) {
        this(setup, new PhysicsWorldFactory()::create, World::step, physics -> { },
                movementInputTimeoutTicks, meleeCooldownTicks);
    }

    /** Seam nội bộ để kiểm thử native thật, lỗi khởi tạo và số lần step; không public World mutable. */
    MatchRuntime(MatchSetup setup, Supplier<HeadlessPhysicsWorld> physicsFactory, PhysicsStepper physicsStepper) {
        this(setup, physicsFactory, physicsStepper, physics -> { });
    }

    /** Kiểm tra trước publish để test lỗi cuối initialization/race; không public World cho caller. */
    MatchRuntime(MatchSetup setup, Supplier<HeadlessPhysicsWorld> physicsFactory, PhysicsStepper physicsStepper,
                 Consumer<HeadlessPhysicsWorld> initializationCheck) {
        this(setup, physicsFactory, physicsStepper, initializationCheck, DEFAULT_MOVEMENT_TIMEOUT_TICKS);
    }

    /** Seam test giữ World native thật và timeout cấu hình được, không thêm public physics getter. */
    MatchRuntime(MatchSetup setup, Supplier<HeadlessPhysicsWorld> physicsFactory, PhysicsStepper physicsStepper,
                 Consumer<HeadlessPhysicsWorld> initializationCheck, int movementInputTimeoutTicks) {
        this(setup, physicsFactory, physicsStepper, initializationCheck, movementInputTimeoutTicks,
                MeleeCombatController.DEFAULT_COOLDOWN_TICKS);
    }

    MatchRuntime(MatchSetup setup, Supplier<HeadlessPhysicsWorld> physicsFactory, PhysicsStepper physicsStepper,
                 Consumer<HeadlessPhysicsWorld> initializationCheck, int movementInputTimeoutTicks,
                 int meleeCooldownTicks) {
        this(setup, physicsFactory, physicsStepper, initializationCheck, movementInputTimeoutTicks,
                meleeCooldownTicks, System::nanoTime);
    }

    MatchRuntime(MatchSetup setup, Supplier<HeadlessPhysicsWorld> physicsFactory, PhysicsStepper physicsStepper,
                 Consumer<HeadlessPhysicsWorld> initializationCheck, int movementInputTimeoutTicks,
                 int meleeCooldownTicks, LongSupplier nanoClock) {
        this.setup = Objects.requireNonNull(setup, "setup không được null");
        this.physicsFactory = Objects.requireNonNull(physicsFactory, "physicsFactory không được null");
        this.physicsStepper = Objects.requireNonNull(physicsStepper, "physicsStepper không được null");
        this.initializationCheck = Objects.requireNonNull(initializationCheck, "initializationCheck không được null");
        if (movementInputTimeoutTicks <= 0) {
            throw new IllegalArgumentException("movementInputTimeoutTicks phải lớn hơn 0");
        }
        this.movementInputTimeoutTicks = movementInputTimeoutTicks;
        if (meleeCooldownTicks <= 0) {
            throw new IllegalArgumentException("meleeCooldownTicks phải lớn hơn 0");
        }
        this.meleeCooldownTicks = meleeCooldownTicks;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock không được null");
    }

    /** Khởi tạo state/queue/World đúng một lần, chỉ publish khi hoàn tất; close là terminal. */
    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("MatchRuntime đã đóng, không thể restart");
        }
        if (matchState == null) {
            MatchState initialState = new MatchState(setup);
            InputQueue initialQueue = new InputQueue(setup);
            HeadlessPhysicsWorld candidate = null;
            MapCollisionBuilder.MapCollisionBodies candidateArena = null;
            PlayerPhysicsFactory.PlayerPhysicsBodies candidatePlayers = null;
            PlayerJumpController candidateJump = null;
            ArenaBoundaryController candidateBoundary = null;
            MeleeCombatController candidateMelee = null;
            StompCombatController candidateStomp = null;
            ProjectileManager candidateProjectiles = null;
            GameplayMatch candidateGameplay = null;
            long candidateStartedAtNanos;
            try {
                candidate = Objects.requireNonNull(physicsFactory.get(), "physicsFactory không được trả null");
                candidate.getWorld(); // Không nhận wrapper đã dispose.
                var geometry = new TmxCollisionLoader().loadOriginalMap();
                candidateArena = new MapCollisionBuilder().build(candidate, geometry,setup.rules().fighter());
                candidatePlayers = new PlayerPhysicsFactory().build(candidate, setup, geometry);
                candidateProjectiles = new ProjectileManager(candidate, initialState, candidatePlayers);
                candidateJump = new PlayerJumpController(candidatePlayers, candidateProjectiles);
                candidateBoundary = new ArenaBoundaryController(candidatePlayers, candidateJump);
                candidateMelee = new MeleeCombatController(initialState, candidatePlayers, meleeCooldownTicks);
                candidateStomp = new StompCombatController(initialState, candidatePlayers, candidateJump);
                if(setup.rules().gameplay()) { candidateGameplay=new GameplayMatch(setup,initialState,candidatePlayers,candidateJump,geometry);candidateProjectiles.enableGameplayLimits(); }
                if(setup.rules().fighter()) { candidateGameplay.enableFighter(candidate.getWorld(),geometry); }
                if(candidateGameplay!=null && candidateGameplay.boss!=null) {
                    var boss=candidateGameplay.boss;
                    candidateProjectiles.setBossTarget(new ProjectileManager.BossTarget() {
                        public float x() { return boss.x; }
                        public float y() { return boss.y+.35f; }
                        public boolean hit(PlayerId owner,float x,float y,float radius,float damage) {
                            return boss.overlaps(x-radius,y-radius,x+radius,y+radius) && boss.damage(owner,damage);
                        }
                    });
                }
                initializationCheck.accept(candidate);
                candidateStartedAtNanos = nanoClock.getAsLong();
            } catch (RuntimeException | Error failure) {
                // World giải phóng toàn bộ native bodies; handles sau đó chỉ bỏ references, không destroy lại.
                for (AutoCloseable resource : new AutoCloseable[]{candidate, candidateGameplay, candidateProjectiles, candidateStomp, candidateMelee, candidateBoundary, candidateJump,
                        candidatePlayers, candidateArena}) {
                    if (resource != null) {
                        try {
                            resource.close();
                        } catch (Exception | Error cleanupFailure) {
                            failure.addSuppressed(cleanupFailure);
                        }
                    }
                }
                if (failure instanceof LinkageError) {
                    // Native loading có thể ném Error; chuyển thành lỗi lifecycle rõ ràng cho GameRoom.
                    throw new IllegalStateException("Không khởi tạo được Box2D native cho trận " + getMatchId(), failure);
                }
                throw failure;
            }
            inputQueue = initialQueue;
            physicsWorld = candidate;
            arenaBodies = candidateArena;
            playerBodies = candidatePlayers;
            jumpController = candidateJump;
            boundaryController = candidateBoundary;
            meleeController = candidateMelee;
            stompController = candidateStomp;
            projectileManager = candidateProjectiles;
            gameplay = candidateGameplay;
            matchStartedAtNanos = candidateStartedAtNanos;
            matchState = initialState;
        }
    }

    /**
     * Network thread chỉ đưa input vào queue, không thay đổi state hay tick.
     * Tái sử dụng toàn bộ validation, sequence và giới hạn của InputQueue.
     *
     * @throws IllegalStateException nếu runtime chưa start thành công
     */
    public InputQueue.OfferResult submitInput(PlayerInput input) {
        long entered=System.nanoTime();
        synchronized(this) {
        metrics.record(com.smashprofs.server.metrics.PerformanceMetrics.Stage.INPUT_LOCK_WAIT,System.nanoTime()-entered);
        requireStarted();
        if (isFinished()) { return InputQueue.OfferResult.MATCH_FINISHED; }
        if(gameplay!=null && input!=null && matchState.containsPlayer(input.playerId()) && !gameplay.active(input.playerId())) { return InputQueue.OfferResult.PLAYER_INACTIVE; }
        return inputQueue.offer(input);
        }
    }

    /**
     * Drain một batch, cập nhật held axis, áp dụng movement ngang rồi step World đúng một lần.
     * Jump chỉ từ edge input mới, gravity mỗi tick; chỉ tăng serverTick khi step thành công.
     * Melee chỉ từ edge input mới, sau boundary/fall và trước step như CombatManager gốc.
     * Block lấy held input còn hạn; damage/knockback chỉ áp dụng một lần cho mỗi hit không bị block.
     * Stomp chỉ kích hoạt từ edge airborne mới; landing event resolve trước step kế tiếp, không lặp flag hit.
     * Ability hỗ trợ IBM/Viktor, Star/Maurice, Mine/Leo, Rocket/Alex; manager resolve character phía server.
     * Projectile update trước spawn, resolve contact tick trước rồi step; không có loop riêng từng type.
     * Thread gọi tick hợp lệ đầu tiên là simulation owner; start không tự gán owner.
     * Các tick tiếp theo phải do cùng thread gọi, chưa hỗ trợ chuyển owner.
     *
     * @throws IllegalStateException nếu chưa start hoặc caller không phải simulation owner
     */
    public List<PlayerInput> tick() {
        long entered=System.nanoTime();
        synchronized(this) {
            long started=System.nanoTime(); metrics.record(com.smashprofs.server.metrics.PerformanceMetrics.Stage.TICK_LOCK_WAIT,started-entered);
            try { return tickOwned(); }
            finally {
                long duration=System.nanoTime()-started; measuredTicks++; measuredTickNanos+=duration; maxTickNanos=Math.max(maxTickNanos,duration);
                metrics.record(com.smashprofs.server.metrics.PerformanceMetrics.Stage.ROOM_TICK,duration);
            }
        }
    }
    private List<PlayerInput> tickOwned() {
        requireStarted();
        if (isFinished()) { throw new IllegalStateException("Trận đã FINISHED, không tick tiếp"); }
        Thread caller = Thread.currentThread();
        if (simulationOwner == null) {
            simulationOwner = caller;
        } else if (simulationOwner != caller) {
            throw new IllegalStateException("tick phải chạy trên simulation owner của trận");
        }
        long upcomingTick=matchState.getServerTick()+1;
        if(gameplay!=null) { gameplay.beforeTick(upcomingTick); }
        List<PlayerInput> effectiveInputs = inputQueue.drainForTick();
        if(setup.rules().fighter()) { return tickFighter(upcomingTick,effectiveInputs); }
        long phaseStarted=System.nanoTime();
        // Queue chấp nhận transport hợp lệ; simulation không áp dụng điều khiển cho player đã chết.
        // Vẫn trả batch đã drain và giữ Body chạy physics thụ động (Rocket có thể đang bám Body này).
        List<PlayerInput> actionableInputs = effectiveInputs.stream()
                .filter(input -> !matchState.getPlayer(input.playerId()).isDead()).toList();
        long serverTick = matchState.getServerTick();
        // Player gốc không đổi isGrounded ngay khi jump: không tạo combo jump+stomp từ grounded cùng tick.
        List<PlayerId> airborneStompRequests = actionableInputs.stream().filter(PlayerInput::stompPressed)
                .filter(input -> !jumpController.getJumpState(input.playerId()).grounded())
                .map(PlayerInput::playerId).toList();
        for (PlayerInput input : actionableInputs) {
            heldInputs.put(input.playerId(), new HeldInput(input.moveAxis(), input.blockHeld(), serverTick));
        }
        // drainForTick đã nhả khóa queue; không giữ khóa InputQueue khi mutate/step World.
        for (var participant : setup.participants()) {
            PlayerId playerId = participant.playerId();
            if (matchState.getPlayer(playerId).isDead()) {
                heldInputs.remove(playerId); // Không chờ timeout khi chết dù không có packet mới.
                if(gameplay!=null) { continue; }
            }
            HeldInput held = heldInputs.get(playerId);
            float moveAxis = 0.0f;
            boolean blockHeld = false;
            if (held != null) {
                if (serverTick - held.receivedAtTick() < movementInputTimeoutTicks) {
                    moveAxis = held.moveAxis();
                    blockHeld = held.blockHeld();
                } else {
                    heldInputs.remove(playerId);
                }
            }
            matchState.getPlayer(playerId).setBlocking(blockHeld);
            if(gameplay!=null) { jumpController.setMovementIntent(playerId,moveAxis); }
            // Player gốc tính facing từ vx hiện tại TRƯỚC walking/jump; vx=0 giữ hướng lần trước.
            matchState.getPlayer(playerId).updateFacing(playerBodies.getVelocity(playerId).xMetersPerSecond());
            if(gameplay==null || !jumpController.preserveExternalImpulse(playerId)) { movementController.applyHorizontalMovement(playerBodies, playerId, moveAxis); }
        }
        for (PlayerInput input : actionableInputs) {
            if (input.jumpPressed()) {
                jumpController.jump(input.playerId());
            }
        }
        for (PlayerId playerId : airborneStompRequests) {
            if (stompController.activate(playerId)) {
                matchState.getPlayer(playerId).applyDamage(StompCombatController.ACTIVATION_HP_COST);
            }
        }
        jumpController.applyGravity();
        // Player.update gốc: gravity -> edge pushback -> fall recovery, trước PlayScreen.world.step.
        if(gameplay==null) {
            for (PlayerId playerId : boundaryController.applyBoundaries()) { matchState.getPlayer(playerId).applyFallPenalty(); }
        } else {
            gameplay.applyLavaContacts();
        }
        stompController.cancelDeadOrRecoveredPlayers();
        // Thứ tự slot ổn định, không phụ thuộc thứ tự packet, roster hoặc HashMap.
        metrics.record(com.smashprofs.server.metrics.PerformanceMetrics.Stage.MOVEMENT,System.nanoTime()-phaseStarted); phaseStarted=System.nanoTime();
        // HP/dead có hiệu lực ngay: slot bị đánh chết trước lượt sẽ không còn được tấn công trong tick này.
        Map<PlayerId, PlayerInput> inputByPlayer = new HashMap<>();
        for (PlayerInput input : actionableInputs) { inputByPlayer.put(input.playerId(), input); }
        for (var participant : participantsInSlotOrder()) {
            PlayerId attackerId = participant.playerId();
            PlayerInput input = inputByPlayer.get(attackerId);
            if(gameplay!=null && input!=null) { gameplay.items.interact(input,upcomingTick); }
            // Cùng slot: melee trước stomp; giữa các player giữ policy slot-order/HP có hiệu lực ngay.
            if (input != null && input.meleePressed() && (gameplay==null || !gameplay.items.attack(attackerId,upcomingTick))) {
                var hit = meleeController.attack(attackerId, serverTick);
                if (hit != null) {
                    // Player.applyForces gốc cộng gravity hiện tại của target vào impulse knockback.
                    if(gameplay!=null) { gameplay.damage(attackerId,hit.targetId(),"MELEE",hit.damage(),hit.knockbackX(),hit.knockbackY()); }
                    else { jumpController.applyMeleeKnockback(hit.targetId(), hit.knockbackX(), hit.knockbackY());matchState.getPlayer(hit.targetId()).applyDamage(hit.damage()); }
                }
            }
            var stompHit = stompController.consumeLanding(attackerId);
            if (stompHit != null) {
                if(gameplay!=null) { gameplay.damage(attackerId,stompHit.targetId(),"STOMP",StompCombatController.STOMP_DAMAGE,stompHit.knockbackX(),StompCombatController.VERTICAL_KNOCKBACK); }
                else { jumpController.applyStompKnockback(stompHit.targetId(), stompHit.knockbackX(),
                        StompCombatController.VERTICAL_KNOCKBACK);
                matchState.getPlayer(stompHit.targetId()).applyDamage(StompCombatController.STOMP_DAMAGE); }
            }
        }
        stompController.cancelDeadOrRecoveredPlayers();
        // CombatManager: update projectile có sẵn trước shooting, sau đó resolve contact từ tick trước.
        metrics.record(com.smashprofs.server.metrics.PerformanceMetrics.Stage.COMBAT,System.nanoTime()-phaseStarted); phaseStarted=System.nanoTime();
        projectileManager.updateProjectiles();
        if(gameplay!=null) { projectileManager.expireGameplayProjectiles(upcomingTick);gameplay.items.update(upcomingTick); }
        for (var participant : participantsInSlotOrder()) {
            PlayerInput input = inputByPlayer.get(participant.playerId());
            if (input != null && input.abilityPressed()) {
                float aimX = input.moveAxis();
                float aimY = (input.fighter().upHeld() ? 1f : 0f) - (input.fighter().downHeld() ? 1f : 0f);
                if(gameplay==null) { projectileManager.spawnProjectile(participant.playerId(), serverTick); }
                else if(gameplay.ultimateReady(participant.playerId())) {
                    boolean activated=participant.characterId().equals("Luca")?gameplay.items.orbit(participant.playerId(),upcomingTick):projectileManager.spawnProjectile(participant.playerId(),aimX,aimY,upcomingTick);
                    if(activated) { gameplay.ultimateUsed(participant.playerId()); }
                }
            }
        }
        for (var hit : projectileManager.resolveHits()) {
            ServerPlayer target = matchState.getPlayer(hit.targetId());
            if (!target.isDead()) {
                if(gameplay!=null) { gameplay.damage(hit.ownerId(),hit.targetId(),"ULTIMATE",hit.damage(),hit.knockbackX(),hit.knockbackY()); }
                else { jumpController.applyProjectileKnockback(hit.targetId(), hit.knockbackX(), hit.knockbackY());target.applyDamage(hit.damage()); }
            }
        }
        stompController.cancelDeadOrRecoveredPlayers();
        metrics.record(com.smashprofs.server.metrics.PerformanceMetrics.Stage.PROJECTILES,System.nanoTime()-phaseStarted); phaseStarted=System.nanoTime();
        try {
            physicsStepper.step(physicsWorld.getWorld(), PHYSICS_TIME_STEP, VELOCITY_ITERATIONS, POSITION_ITERATIONS);
        } catch (RuntimeException | LinkageError failure) {
            // Không tăng tick hoặc giả lập rollback; scheduler hiện có sẽ cô lập runtime lỗi.
            throw new IllegalStateException("Box2D step thất bại cho trận " + getMatchId(), failure);
        } finally {
            metrics.record(com.smashprofs.server.metrics.PerformanceMetrics.Stage.PHYSICS_STEP,System.nanoTime()-phaseStarted);
        }
        phaseStarted=System.nanoTime();
        jumpController.synchronizeAfterStep();
        boundaryController.synchronizeAfterStep();
        stompController.cancelDeadOrRecoveredPlayers();
        stompController.captureLandingsAfterStep();
        projectileManager.collectHitsAfterStep();
        projectileManager.cleanupDestroyed();
        matchState.advanceTick();
        if(gameplay!=null) { gameplay.applyLavaContacts();gameplay.afterTick(); }
        metrics.record(com.smashprofs.server.metrics.PerformanceMetrics.Stage.POST_PHYSICS,System.nanoTime()-phaseStarted); phaseStarted=System.nanoTime();
        var completedSnapshot = GameSnapshotBuilder.build(matchState, playerBodies, jumpController, projectileManager);
        if(gameplay!=null) { completedSnapshot=new AuthoritativeGameSnapshot(getMatchId(),getRoomId(),matchState.getServerTick(),completedSnapshot.players(),completedSnapshot.projectiles(),gameplay.snapshot()); }
        metrics.record(com.smashprofs.server.metrics.PerformanceMetrics.Stage.SNAPSHOT_BUILD,System.nanoTime()-phaseStarted);
        long elapsedNanos = Math.max(0L, nanoClock.getAsLong() - matchStartedAtNanos);
        boolean expired=gameplay==null?elapsedNanos>=MATCH_DURATION_NANOS:setup.rules().matchSeconds()>0 && matchState.getServerTick()>=setup.rules().matchSeconds()*60L;
        var result = MatchEndEvaluator.evaluate(completedSnapshot, expired);
        latestSnapshot = completedSnapshot;
        if (result != null) {
            if(gameplay!=null) { gameplay.finished(); }
            heldInputs.clear();
            inputQueue.clear();
            // Ghi sau snapshot: reader thấy result cũng thấy final snapshot đã được publish đầy đủ.
            matchResult = result;
            if(gameplay!=null) { releaseNativeResources(); }
        }
        return effectiveInputs;
    }

    private List<PlayerInput> tickFighter(long tick,List<PlayerInput> inputs) {
        projectileManager.updateProjectiles();
        if(gameplay!=null) { projectileManager.expireGameplayProjectiles(tick); }
        if(gameplay.boss!=null) { gameplay.boss.update(tick); }
        gameplay.fighter.update(tick,inputs);
        gameplay.items.update(tick);
        Map<PlayerId, PlayerInput> inputByPlayer = new HashMap<>();
        for (PlayerInput in : inputs) { inputByPlayer.put(in.playerId(), in); }
        for (var participant : participantsInSlotOrder()) {
            PlayerInput input = inputByPlayer.get(participant.playerId());
            if (input != null && input.abilityPressed()) {
                if (gameplay.ultimateReady(participant.playerId())) {
                    float aimX = input.moveAxis();
                    float aimY = (input.fighter().upHeld() ? 1f : 0f) - (input.fighter().downHeld() ? 1f : 0f);
                    boolean activated = participant.characterId().equals("Luca")
                            ? gameplay.items.orbit(participant.playerId(), tick)
                            : projectileManager.spawnProjectile(participant.playerId(), aimX, aimY, tick);
                    if (activated) { gameplay.ultimateUsed(participant.playerId()); }
                }
            }
        }
        for (var hit : projectileManager.resolveHits()) {
            ServerPlayer target = matchState.getPlayer(hit.targetId());
            if (!target.isDead()) {
                gameplay.damage(hit.ownerId(), hit.targetId(), "ULTIMATE", hit.damage(), hit.knockbackX(), hit.knockbackY());
            }
        }
        try { physicsStepper.step(physicsWorld.getWorld(),PHYSICS_TIME_STEP,VELOCITY_ITERATIONS,POSITION_ITERATIONS); }
        catch(RuntimeException | LinkageError error) { throw new IllegalStateException("Fighter physics step failed",error); }
        projectileManager.collectHitsAfterStep();
        projectileManager.cleanupDestroyed();
        gameplay.fighter.applyLavaContacts();jumpController.synchronizeAfterStep();
        matchState.advanceTick();gameplay.fighter.checkBlastZones();gameplay.afterTick();
        var base=GameSnapshotBuilder.build(matchState,playerBodies,jumpController,projectileManager);
        var snapshot=new AuthoritativeGameSnapshot(getMatchId(),getRoomId(),tick,base.players(),base.projectiles(),gameplay.snapshot());
        var result=MatchEndEvaluator.evaluate(snapshot,setup.rules().matchSeconds()>0 && tick>=setup.rules().matchSeconds()*60L);
        latestSnapshot=snapshot;
        if(result!=null) { gameplay.finished();heldInputs.clear();inputQueue.clear();matchResult=result;releaseNativeResources(); }
        return inputs;
    }

    /** Chỉ gọi dưới khóa runtime, không để lifecycle chưa sẵn sàng nhận input hoặc tick. */
    private void requireStarted() {
        if (closed) {
            throw new IllegalStateException("MatchRuntime đã đóng");
        }
        if (matchState == null) {
            throw new IllegalStateException("MatchRuntime chưa được start");
        }
    }

    public boolean isStarted() {
        return matchState != null;
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean isFinished() { return matchResult != null; }

    /** Null khi chưa kết thúc; historical result vẫn đọc được sau close, không JNI. */
    public AuthoritativeMatchResult getMatchResult() { return matchResult; }

    /** Quan sát cleanup nội bộ, không trả World mutable cho network hoặc test. */
    synchronized boolean isPhysicsDisposed() {
        return physicsWorld != null && physicsWorld.isDisposed();
    }

    /**
     * Caller phải unregister/dừng scheduling trước close. Cùng khóa tick đảm bảo chờ step đang chạy.
     * Closed chặn cả tick và input mới; state lịch sử vẫn được giữ, không tạo GAME_END.
     * isStarted vẫn biểu thị đã khởi tạo thành công; dùng isClosed để kiểm tra khả năng tiếp tục tick.
     */
    @Override
    public synchronized void close() {
        closed = true;
        latestSnapshot = null; // Không công bố trận đã đóng; snapshot caller giữ trước đó vẫn bất biến.
        heldInputs.clear();
        if (inputQueue != null) { inputQueue.clear(); }
        if(gameplay!=null) { gameplay.close();gameplay=null; }
        if (matchState != null) {
            for (PlayerId playerId : matchState.getPlayerIds()) {
                matchState.getPlayer(playerId).setBlocking(false);
            }
        }
        releaseNativeResources();
    }
    /** Final immutable state/events remain readable for reliable terminal delivery after native disposal. */
    private void releaseNativeResources() {
        if (physicsWorld != null) {
            physicsWorld.close();
            if (projectileManager != null) {
                projectileManager.close(); // World đã dispose: chỉ bỏ references, không destroy lại.
                projectileManager = null;
            }
            if (stompController != null) {
                stompController.close();
                stompController = null;
            }
            if (meleeController != null) {
                meleeController.close();
                meleeController = null;
            }
            if (boundaryController != null) {
                boundaryController.close();
                boundaryController = null;
            }
            if (jumpController != null) {
                jumpController.close();
                jumpController = null;
            }
            if (playerBodies != null) {
                playerBodies.close();
                playerBodies = null;
            }
            if (arenaBodies != null) {
                arenaBodies.close();
                arenaBodies = null;
            }
        }
    }

    @FunctionalInterface
    interface PhysicsStepper {
        void step(World world, float timeStep, int velocityIterations, int positionIterations);
    }

    /** Request transport mới nhất và tick nhận; không phải nguồn blocking chính thức thứ hai, không giữ edge. */
    private record HeldInput(float moveAxis, boolean blockHeld, long receivedAtTick) { }

    /** Đọc snapshot dưới khóa lifecycle/simulation, không thêm mutable Body hoặc protocol field. */
    public synchronized PlayerJumpController.JumpState getJumpState(PlayerId playerId) {
        requireStarted();
        if(jumpController==null) { throw new IllegalStateException("Finished simulation resources released"); }
        return jumpController.getJumpState(playerId);
    }

    /** View stomp lifecycle chỉ đọc; không thêm mutable physics getter hoặc thay đổi protocol. */
    public synchronized StompCombatController.StompState getStompState(PlayerId playerId) {
        requireStarted();
        if(stompController==null) { throw new IllegalStateException("Finished simulation resources released"); }
        return stompController.getState(playerId);
    }

    /** List bất biến chỉ đọc dưới khóa runtime; không cung cấp Body hoặc manager cho network. */
    public synchronized List<ServerProjectile.View> getProjectiles() {
        requireStarted();
        return projectileManager==null?List.of():projectileManager.getProjectiles();
    }

    public synchronized int getActiveProjectileCount() {
        requireStarted();
        return projectileManager==null?0:projectileManager.getActiveCount();
    }

    /** State chỉ có API đọc; trả null trước khi runtime được khởi tạo thành công. */
    public MatchState getMatchState() {
        return matchState;
    }

    /**
     * Chỉ đọc reference bất biến, dùng được từ thread khác mà không đọc Body/World.
     * Null trước tick thành công đầu tiên hoặc sau close. Trong tick đang chạy/step lỗi, giữ bản hoàn tất trước đó.
     * serverTick của bản đầu tiên là 1; không build lại theo từng lần đọc.
     */
    public AuthoritativeGameSnapshot getLatestSnapshot() {
        return closed ? null : latestSnapshot;
    }

    /** Chỉ quan sát clock authoritative/mốc start hiện có; không thay tick, gameplay hoặc JNI. */
    public synchronized long getRemainingTimeMillis() {
        requireStarted();
        if(gameplay!=null) { return setup.rules().matchSeconds()==0?0:Math.max(0,setup.rules().matchSeconds()*1000L-matchState.getServerTick()*1000L/60); }
        long elapsed = Math.max(0L, nanoClock.getAsLong() - matchStartedAtNanos);
        long remaining = elapsed >= MATCH_DURATION_NANOS ? 0 : MATCH_DURATION_NANOS - elapsed;
        return (remaining + 999_999L) / 1_000_000L;
    }

    public String getMatchId() {
        return setup.matchId();
    }

    /** Setup authoritative đã bất biến, getter không khởi tạo/tick/read World hoặc tạo model khác. */
    public MatchSetup getMatchSetup() { return setup; }

    public String getRoomId() {
        return setup.roomId();
    }

    /** Danh sách đã bất biến từ MatchSetup, không để caller sửa roster của trận. */
    public List<MatchSetup.Participant> getParticipants() {
        return setup.participants();
    }
}

package com.smashprofs.server.game.physics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Cấu hình spawn ban đầu bất biến theo slot, không phải bản sao vị trí gameplay đang chạy. */
public final class SpawnProvider {
    private static final float CLEARANCE_METERS = 1.0f / MapCollisionBuilder.PIXELS_PER_METER;
    private final List<SpawnPosition> positions;
    private final List<SpawnPosition> respawnCandidates;

    public SpawnProvider(MapCollisionDefinition geometry, float polygonSkinMeters) {
        Objects.requireNonNull(geometry, "geometry không được null");
        if (!Float.isFinite(polygonSkinMeters) || polygonSkinMeters < 0) {
            throw new IllegalArgumentException("polygonSkinMeters phải hợp lệ");
        }
        // PlayerFactory gốc: (450,85) và (725,50) pixel, không lấy vị trí từ client.
        List<SpawnPosition> candidates = new ArrayList<>();
        candidates.add(clearAbove(geometry, new SpawnPosition(4.5f, 0.85f), polygonSkinMeters));
        candidates.add(clearAbove(geometry, new SpawnPosition(7.25f, 0.5f), polygonSkinMeters));
        var platform = geometry.rectangles().stream()
                .filter(rectangle -> rectangle.heightPixels() > 0
                        && rectangle.widthPixels() / MapCollisionBuilder.PIXELS_PER_METER
                        > 8 * PlayerPhysicsFactory.PLAYER_RADIUS_METERS)
                .max(Comparator.comparingDouble(MapCollisionDefinition.CollisionRectangle::widthPixels)
                        .thenComparingInt(MapCollisionDefinition.CollisionRectangle::objectId))
                .orElseThrow(() -> new IllegalArgumentException("Map không có platform đủ rộng cho spawn bổ sung"));
        for (float fraction : new float[]{0.25f, 0.75f}) {
            float x = (platform.xPixels() + fraction * platform.widthPixels()) / MapCollisionBuilder.PIXELS_PER_METER;
            float y = (platform.yPixels() + platform.heightPixels()) / MapCollisionBuilder.PIXELS_PER_METER
                    + PlayerPhysicsFactory.PLAYER_RADIUS_METERS + polygonSkinMeters + CLEARANCE_METERS;
            candidates.add(clearAbove(geometry, new SpawnPosition(x, y), polygonSkinMeters));
        }
        for (int index = 0; index < candidates.size(); index++) {
            SpawnPosition position = candidates.get(index);
            float radius = PlayerPhysicsFactory.PLAYER_RADIUS_METERS;
            float width = geometry.mapWidthPixels() / MapCollisionBuilder.PIXELS_PER_METER;
            float height = geometry.mapHeightPixels() / MapCollisionBuilder.PIXELS_PER_METER;
            if (position.xMeters() - radius < 0 || position.yMeters() - radius < 0
                    || position.xMeters() + radius > width || position.yMeters() + radius > height) {
                throw new IllegalArgumentException("Spawn nằm ngoài arena, slot=" + index);
            }
            for (int previous = 0; previous < index; previous++) {
                SpawnPosition other = candidates.get(previous);
                float dx = position.xMeters() - other.xMeters();
                float dy = position.yMeters() - other.yMeters();
                float separation = 2 * radius + CLEARANCE_METERS;
                if (dx * dx + dy * dy < separation * separation) {
                    throw new IllegalArgumentException("Spawn chồng nhau, slot=" + index);
                }
            }
        }
        positions = List.copyOf(candidates);
        respawnCandidates = safePlatformCandidates(geometry, polygonSkinMeters);
    }

    public SpawnPosition getSpawn(int slotIndex) {
        if (slotIndex < 0 || slotIndex > 3) {
            throw new IllegalArgumentException("slotIndex phải nằm trong khoảng 0 đến 3");
        }
        return positions.get(slotIndex);
    }

    /** Precomputed once per match: supported platform tops, clear of every solid and lava. */
    public List<SpawnPosition> getRespawnCandidates() { return respawnCandidates; }
    private static List<SpawnPosition> safePlatformCandidates(MapCollisionDefinition geometry,float skin) {
        var result=new ArrayList<SpawnPosition>();float radius=PlayerPhysicsFactory.PLAYER_RADIUS_METERS+skin;
        float width=geometry.mapWidthPixels()/100,height=geometry.mapHeightPixels()/100;
        for(var platform:geometry.rectangles()) {
            if(platform.widthPixels()<30 || platform.heightPixels()<=0) { continue; }
            for(float fraction:new float[]{.2f,.5f,.8f}) {
                float x=(platform.xPixels()+platform.widthPixels()*fraction)/100;
                float y=(platform.yPixels()+platform.heightPixels())/100+radius+.02f;
                if(x-radius<.1f || x+radius>width-.1f || y-radius<0 || y+radius>height-.02f) { continue; }
                boolean safe=true;
                for(var block:geometry.rectangles()) {
                    float dx=x-Math.max(block.xPixels()/100,Math.min(x,(block.xPixels()+block.widthPixels())/100));
                    float dy=y-Math.max(block.yPixels()/100,Math.min(y,(block.yPixels()+block.heightPixels())/100));
                    if(dx*dx+dy*dy<radius*radius) { safe=false;break; }
                }
                for(var lava:geometry.lavaZones()) {
                    if(x+radius>=lava.xPixels()/100 && x-radius<=(lava.xPixels()+lava.widthPixels())/100
                            && y-radius<=(lava.yPixels()+lava.heightPixels())/100+.002f) { safe=false;break; }
                }
                if(safe) { result.add(new SpawnPosition(x,y)); }
            }
        }
        if(result.isEmpty()) { throw new IllegalArgumentException("No safe respawn platform"); }
        return List.copyOf(result);
    }

    /** Chỉ nâng Y nếu vòng tròn thực sự chồng collider; không di chuyển X hoặc sửa geometry. */
    private static SpawnPosition clearAbove(MapCollisionDefinition geometry, SpawnPosition original, float skin) {
        float y = original.yMeters();
        float effectiveRadius = PlayerPhysicsFactory.PLAYER_RADIUS_METERS + skin;
        for (int pass = 0; pass <= geometry.rectangles().size(); pass++) {
            boolean changed = false;
            for (var rectangle : geometry.rectangles()) {
                float left = rectangle.xPixels() / MapCollisionBuilder.PIXELS_PER_METER;
                float bottom = rectangle.yPixels() / MapCollisionBuilder.PIXELS_PER_METER;
                float right = (rectangle.xPixels() + rectangle.widthPixels()) / MapCollisionBuilder.PIXELS_PER_METER;
                float top = (rectangle.yPixels() + rectangle.heightPixels()) / MapCollisionBuilder.PIXELS_PER_METER;
                float nearestX = Math.max(left, Math.min(original.xMeters(), right));
                float nearestY = Math.max(bottom, Math.min(y, top));
                float dx = original.xMeters() - nearestX;
                float dy = y - nearestY;
                if (dx * dx + dy * dy < effectiveRadius * effectiveRadius) {
                    y = top + effectiveRadius + CLEARANCE_METERS;
                    changed = true;
                }
            }
            if (!changed) {
                return new SpawnPosition(original.xMeters(), y);
            }
        }
        throw new IllegalArgumentException("Không tìm được spawn không chồng collider");
    }

    public record SpawnPosition(float xMeters, float yMeters) {
        public SpawnPosition {
            if (!Float.isFinite(xMeters) || !Float.isFinite(yMeters)) {
                throw new IllegalArgumentException("Spawn phải có tọa độ hữu hạn");
            }
        }
    }
}

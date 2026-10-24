package com.smashprofs.game.Screens;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.*;
import com.smashprofs.game.network.client.*;
import java.util.*;

/** Screen-owned GL assets. Construct/update/dispose exclusively on the libGDX render thread. */
final class OnlineSpriteAssets implements AutoCloseable {
    private record Animations(Animation<TextureRegion> stand, Animation<TextureRegion> run, Animation<TextureRegion> air) { }
    private final Map<String, Texture> textures = new LinkedHashMap<>();
    private final Map<String, Animations> animations = new HashMap<>();
    private final Map<String,TextureRegion[]> punches=new HashMap<>();
    Texture texture(String path) { return textures.computeIfAbsent(path, Texture::new); }
    void preload() {
        for(String name:GameStartRoster.CHARACTERS) {
            animations(name);String folder=name.equals("Maurice")?"Momo":name,file=name.equals("Maurice")?"momo":name.toLowerCase(java.util.Locale.ROOT);
            texture("Sprites/"+folder+"/"+file+"_punch.png");
        }
    }
    private Animations animations(String character) {
        return animations.computeIfAbsent(character, id -> {
            var assets = OnlineVisualCatalog.character(id);
            var standing = TextureRegion.split(texture(assets.stand()), 100, 100)[0];
            var running = TextureRegion.split(texture(assets.run()), 100, 100)[0];
            return new Animations(new Animation<>(0.25f, standing), new Animation<>(0.15f, running),
                    new Animation<>(1f, running[0], running[3])); // same air frames as original Player
        });
    }
    Sprite player(String character) {
        var sprite = new Sprite(animations(character).stand().getKeyFrame(0));
        sprite.setSize(0.25f, 0.25f); // Original 25 / PPM; world meters.
        sprite.setOriginCenter();
        return sprite;
    }
    TextureRegion frame(WireGameSnapshot.Player player, float time) {
        var a = animations(player.characterId());
        return (!player.grounded() ? a.air() : Math.abs(player.velocityX()) > 0.001f ? a.run() : a.stand()).getKeyFrame(time, true);
    }
    TextureRegion frame(WireGameSnapshot.Player player,float time,com.smashprofs.common.model.FighterSnapshot.Player fighter) {
        if(fighter!=null && (fighter.action().equals("ATTACK") || fighter.action().equals("CHARGE"))) {
            var frames=punches.computeIfAbsent(player.characterId(),name->{
                String folder=name.equals("Maurice")?"Momo":name,file=name.equals("Maurice")?"momo":name.toLowerCase(java.util.Locale.ROOT);
                return TextureRegion.split(texture("Sprites/"+folder+"/"+file+"_punch.png"),100,100)[0];
            });
            return frames[Math.min(frames.length-1,fighter.attackFrame()/3)];
        }
        return frame(player,time);
    }
    Sprite projectile(String type) {
        var asset = OnlineVisualCatalog.projectile(type);
        var texture = texture(asset.path());
        var sprite = new Sprite(texture);
        sprite.setSize(texture.getWidth() / 100f * asset.scale(), texture.getHeight() / 100f * asset.scale());
        sprite.setOriginCenter();
        sprite.setFlip(true, false); // Original rocket/IBM/star sprites face left before flip.
        return sprite;
    }
    @Override public void close() { textures.values().forEach(Texture::dispose); textures.clear(); animations.clear(); }
}

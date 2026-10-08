package dev.emergence.mod.client.model;

import dev.emergence.mod.EmergenceMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.inventory.InventoryMenu;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Client cache of part meshes ({@code assets/emergence/models/part/*.obj}) and of the part textures
 * ({@code textures/block/part/*.png}, stitched into the block atlas). Cleared on resource reload.
 */
public final class PartModels {
    private PartModels() {}

    private static final Map<String, Optional<ObjModel>> MODELS = new HashMap<>();
    private static final Map<String, TextureAtlasSprite> SPRITES = new HashMap<>();

    public static final ResourceManagerReloadListener RELOAD = rm -> clear();

    public static synchronized void clear() {
        MODELS.clear();
        SPRITES.clear();
    }

    /** The named model, or null if it is missing or unreadable (the error is logged once). */
    public static synchronized ObjModel model(String name) {
        return MODELS.computeIfAbsent(name, n -> {
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(EmergenceMod.MODID, "models/part/" + n + ".obj");
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(loc);
            if (res.isEmpty()) {
                EmergenceMod.LOG.warn("Missing part model {}", loc);
                return Optional.empty();
            }
            try (var in = new InputStreamReader(res.get().open(), StandardCharsets.UTF_8)) {
                return Optional.of(ObjModel.read(in));
            } catch (Exception e) {
                EmergenceMod.LOG.error("Failed to read part model {}", loc, e);
                return Optional.empty();
            }
        }).orElse(null);
    }

    public static synchronized TextureAtlasSprite sprite(String texture) {
        return SPRITES.computeIfAbsent(texture, t -> Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(ResourceLocation.fromNamespaceAndPath(EmergenceMod.MODID, "block/part/" + t)));
    }
}

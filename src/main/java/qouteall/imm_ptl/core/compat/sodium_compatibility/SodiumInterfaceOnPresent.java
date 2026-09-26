package qouteall.imm_ptl.core.compat.sodium_compatibility;

import com.mojang.logging.LogUtils;
import net.caffeinemc.mods.sodium.api.texture.SpriteUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkStatus;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.util.GameRendererStorage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Only loaded when Sodium is present. Uses Sodium's public classes only (no mixins into Sodium).
 */
@Environment(EnvType.CLIENT)
public class SodiumInterfaceOnPresent extends SodiumInterface.Invoker {
    private static final Logger LOGGER = LogUtils.getLogger();

    private boolean projectionWarned = false;

    @Override
    public boolean isSodiumPresent() {
        return true;
    }

    @Override
    public void markSpriteActive(TextureAtlasSprite sprite) {
        SpriteUtil.INSTANCE.markSpriteActive(sprite);
    }

    @Override
    public void onClientChunkLoaded(ClientLevel world, int chunkX, int chunkZ) {
        ChunkTrackerHolder.get(world).onChunkStatusAdded(chunkX, chunkZ, ChunkStatus.FLAG_HAS_BLOCK_DATA);
    }

    @Override
    public void onClientChunkUnloaded(ClientLevel world, int chunkX, int chunkZ) {
        // like Sodium's ClientChunkCache.drop hook
        ChunkTrackerHolder.get(world).onChunkStatusRemoved(chunkX, chunkZ, ChunkStatus.FLAG_HAS_BLOCK_DATA);
    }

    @Override
    public boolean supportsSeveralCamerasPerRenderer() {
        // render lists (RenderSectionManager, one ChunkRenderList per RenderRegion), cached draw batches and the
        // chunk uniforms (written once per frame) all belong to the last camera that set up the terrain
        return false;
    }

    @Override
    public @Nullable Matrix4f swapTerrainProjection(Matrix4fc projection) {
        // GameRendererStorage.sodium$getProjectionMatrix returns the matrix that Sodium copies the projection
        // into in GameRenderer.renderLevel; it is read when LevelRenderer.render prepares the chunk draws
        Matrix4fc stored = ((GameRendererStorage) Minecraft.getInstance().gameRenderer).sodium$getProjectionMatrix();
        if (!(stored instanceof Matrix4f mutable)) {
            if (!projectionWarned) {
                projectionWarned = true;
                LOGGER.error("Cannot set Sodium's terrain projection ({}); portal views may be drawn unclipped", stored.getClass());
            }
            return null;
        }
        Matrix4f previous = new Matrix4f(mutable);
        mutable.set(projection);
        return previous;
    }
}

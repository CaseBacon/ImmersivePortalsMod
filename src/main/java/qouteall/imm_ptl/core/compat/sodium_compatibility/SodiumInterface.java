package qouteall.imm_ptl.core.compat.sodium_compatibility;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * Everything Immersive Portals needs from Sodium. The default invoker is used when Sodium is absent;
 * {@link SodiumInterfaceOnPresent} (the only class that references Sodium) is installed when it is loaded.
 * <p>
 * Sodium gives every {@code LevelRenderer} its own {@code SodiumWorldRenderer}, so every client world has its
 * own chunk meshes and render lists. A Sodium renderer keeps one camera's culling results and draw batches per
 * frame, so the portal view renderer only lets one camera use it per frame ({@link #supportsSeveralCamerasPerRenderer}).
 */
@Environment(EnvType.CLIENT)
public class SodiumInterface {

    public static class Invoker {
        public boolean isSodiumPresent() {
            return false;
        }

        public void markSpriteActive(TextureAtlasSprite sprite) {

        }

        /**
         * Our client chunk map replaces the {@code ClientChunkCache} methods that Sodium hooks to learn about
         * loaded chunks, so it reports them itself.
         */
        public void onClientChunkLoaded(ClientLevel world, int chunkX, int chunkZ) {

        }

        public void onClientChunkUnloaded(ClientLevel world, int chunkX, int chunkZ) {

        }

        /**
         * Whether a level renderer can draw the level for more than one camera in the same frame.
         */
        public boolean supportsSeveralCamerasPerRenderer() {
            return true;
        }

        /**
         * Sodium draws terrain with the projection matrix it captured in {@code GameRenderer.renderLevel},
         * not with the projection of the level render state. Sets the projection it uses and returns the
         * previous one (to restore), or null when there is nothing to set.
         */
        public @Nullable Matrix4f swapTerrainProjection(Matrix4fc projection) {
            return null;
        }
    }

    public static Invoker invoker = new Invoker();
}

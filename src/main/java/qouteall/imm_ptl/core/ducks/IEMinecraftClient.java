package qouteall.imm_ptl.core.ducks;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;

public interface IEMinecraftClient {
    void ip_setWorldRenderer(LevelRenderer r);
    
    // since 26.2 the level is extracted by a LevelExtractor that owns the ClientLevel reference
    void ip_setLevelExtractor(LevelExtractor extractor);
    
    Thread ip_getRunningThread();
}

package qouteall.imm_ptl.peripheral.mixin.client;

import net.minecraft.client.resources.SplashManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(SplashManager.class)
public class MixinSplashManager_CVB {
    // since 26.x the splashes are an immutable list of components
    @Shadow
    private List<Component> splashes;
    
    @Inject(method = "apply(Ljava/util/List;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("RETURN"))
    private void onApply(
        List<Component> list, ResourceManager resourceManager, ProfilerFiller profiler, CallbackInfo ci
    ) {
        List<Component> result = new ArrayList<>(splashes.size() + 2);
        for (Component splash : splashes) {
            switch (splash.getString()) {
                case "Euclidian!" -> result.add(Component.literal("Non-Euclidian!"));
                case "Slow acting portals!" -> {
                    result.add(Component.literal("Fast acting portals!"));
                    result.add(Component.literal("Immersive Portals!"));
                }
                default -> result.add(splash);
            }
        }
        splashes = List.copyOf(result);
    }
}

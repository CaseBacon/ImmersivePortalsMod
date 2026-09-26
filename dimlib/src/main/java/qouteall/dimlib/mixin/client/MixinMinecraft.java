package qouteall.dimlib.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.dimlib.ClientDimensionInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {
    @Inject(
        method = "updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V",
        at = @At("HEAD")
    )
    private void onClientReset(ClientLevel level, boolean stopSound, CallbackInfo ci) {
        if (level == null) {
            ClientDimensionInfo.cleanup();
        }
    }
}

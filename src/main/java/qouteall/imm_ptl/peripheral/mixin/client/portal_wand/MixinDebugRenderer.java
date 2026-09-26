package qouteall.imm_ptl.peripheral.mixin.client.portal_wand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.mc_utils.WireBuffers;
import qouteall.imm_ptl.peripheral.wand.PortalWandItem;

@Mixin(DebugRenderer.class)
public class MixinDebugRenderer {
    // the portal wand markings are emitted as gizmos together with the vanilla debug renderers
    @Inject(
        method = "emitGizmos(Lnet/minecraft/client/renderer/culling/Frustum;DDDF)V",
        at = @At("RETURN")
    )
    private void onEmitGizmos(
        Frustum frustum,
        double camX, double camY, double camZ,
        float partialTicks,
        CallbackInfo ci
    ) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        ItemStack itemStack = player.getMainHandItem();

        if (itemStack.getItem() == PortalWandItem.instance) {
            WireBuffers buffers = new WireBuffers(new Vec3(camX, camY, camZ));
            PortalWandItem.clientRender(player, itemStack, buffers, camX, camY, camZ);
            buffers.flush();
        }
    }

}

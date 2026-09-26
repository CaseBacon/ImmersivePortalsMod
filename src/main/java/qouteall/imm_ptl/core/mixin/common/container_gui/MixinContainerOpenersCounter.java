package qouteall.imm_ptl.core.mixin.common.container_gui;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

@Mixin(ContainerOpenersCounter.class)
public abstract class MixinContainerOpenersCounter {
    // The container could be opened via portal: the player could be anywhere in any dimension.
    // Vanilla only searches entities around the container, so also check all players.
    @ModifyReturnValue(
        method = "getEntitiesWithContainerOpen(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Ljava/util/List;",
        at = @At("RETURN")
    )
    private List<ContainerUser> addPlayersOpeningThroughPortal(
        List<ContainerUser> original, Level level, BlockPos pos
    ) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return original;
        }
        ContainerOpenersCounter this_ = (ContainerOpenersCounter) (Object) this;
        List<ContainerUser> result = new ArrayList<>(original);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isSpectator() && !result.contains(player) && player.hasContainerOpen(this_, pos)) {
                result.add(player);
            }
        }
        return result;
    }
}

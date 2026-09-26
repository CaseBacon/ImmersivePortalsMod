package qouteall.imm_ptl.core.network;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.mixin.client.sync.MixinMinecraft_RedirectedPacket;
import qouteall.q_misc_util.dimension.DimensionIntId;
import qouteall.q_misc_util.my_util.LimitedLogger;

@Environment(EnvType.CLIENT)
public class PacketRedirectionClient {
    
    public static final Minecraft client = Minecraft.getInstance();
    private static final LimitedLogger limitedLogger = new LimitedLogger(100);
    
    /**
     * This ensures that when it calls client.execute(...)
     * the task will be executed with redirected dimension.
     * {@link MixinMinecraft_RedirectedPacket}
     * This is also used in networking threads.
     */
    public static final ThreadLocal<ResourceKey<Level>> clientTaskRedirection =
        ThreadLocal.withInitial(() -> null);
    
    public static boolean getIsProcessingRedirectedMessage() {
        return clientTaskRedirection.get() != null;
    }
    
    /**
     * This is intended to be called in networking thread.
     * The dimension id is passed as integer,
     * because the dimension id map is only stable in client thread
     * (reading dimension id map in networking thread is not guaranteed to work).
     * <p>
     * Since 26.x vanilla packets are queued in {@link Minecraft#packetProcessor()} instead of
     * the client task queue. The redirected packet is queued there too (as its wrapping payload packet),
     * so that it is handled in order with the non-redirected packets.
     */
    public static void handleRedirectedPacket(
        int dimensionIntId,
        Packet<ClientGamePacketListener> packet,
        ClientGamePacketListener handler,
        ClientboundCustomPayloadPacket wrapper
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.packetProcessor().isSameThread()) {
            ResourceKey<Level> dimension = DimensionIntId.getClientMap()
                .fromIntegerId(dimensionIntId);
            
            ResourceKey<Level> oldTaskRedirection = clientTaskRedirection.get();
            clientTaskRedirection.set(dimension);
            
            try {
                ClientWorldLoader.withSwitchedWorldFailSoft(
                    dimension,
                    () -> {
                        packet.handle(handler);
                    }
                );
            }
            finally {
                clientTaskRedirection.set(oldTaskRedirection);
            }
        }
        else {
            minecraft.packetProcessor().scheduleIfPossible(handler, wrapper);
        }
    }
    
    /**
     * For vanilla packets, in {@code PacketUtils#ensureRunningOnSameThread}
     * it will resubmit the task,
     * and the task will be redirected in {@link MixinMinecraft_RedirectedPacket},
     * except for the bundle packet {@link net.minecraft.client.multiplayer.ClientPacketListener#handleBundlePacket(ClientboundBundlePacket)}.
     * <p>
     * For mod packets ({@link ClientboundCustomPayloadPacket}),
     * the mod will also handle the packet using {@link Minecraft#execute(Runnable)} (If not, that mod has the bug)
     */
    @Deprecated
    public static void old_handleRedirectedPacket(
        ResourceKey<Level> dimension,
        Packet<ClientGamePacketListener> packet,
        ClientGamePacketListener handler
    ) {
        ResourceKey<Level> oldTaskRedirection = clientTaskRedirection.get();
        clientTaskRedirection.set(dimension);
        
        try {
            if (Minecraft.getInstance().isSameThread()) {
                // typically for the invocation inside bundle packet handling
                ClientWorldLoader.withSwitchedWorldFailSoft(
                    dimension,
                    () -> {
                        packet.handle(handler);
                    }
                );
            }
            else {
                // normal packet handling
                packet.handle(handler);
            }
        }
        finally {
            clientTaskRedirection.set(oldTaskRedirection);
        }
    }
}

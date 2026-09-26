package qouteall.imm_ptl.core.platform_specific;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.world.entity.EntityType;
import qouteall.imm_ptl.core.IPModMainClient;
import qouteall.imm_ptl.core.compat.IPModInfoChecking;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.portal.BreakableMirror;
import qouteall.imm_ptl.core.portal.EndPortalEntity;
import qouteall.imm_ptl.core.portal.LoadingIndicatorEntity;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalTrackedPortal;
import qouteall.imm_ptl.core.portal.global_portals.VerticalConnectingPortal;
import qouteall.imm_ptl.core.portal.global_portals.WorldWrappingPortal;
import qouteall.imm_ptl.core.portal.nether_portal.GeneralBreakablePortal;
import qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity;
import qouteall.q_misc_util.Helper;

import java.util.Arrays;

public class IPModEntryClient implements ClientModInitializer {
    
    
    
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void initPortalRenderers() {
        
        Arrays.stream(new EntityType<?>[]{
            Portal.ENTITY_TYPE,
            NetherPortalEntity.ENTITY_TYPE,
            EndPortalEntity.ENTITY_TYPE,
            Mirror.ENTITY_TYPE,
            BreakableMirror.ENTITY_TYPE,
            GlobalTrackedPortal.ENTITY_TYPE,
            WorldWrappingPortal.ENTITY_TYPE,
            VerticalConnectingPortal.ENTITY_TYPE,
            GeneralBreakablePortal.ENTITY_TYPE
        }).forEach(
            // PORT(26.3): PortalEntityRenderer (portal content and overlay rendering) is
            // quarantined with the renderer. A no-op renderer keeps portal entities valid for
            // the client entity render dispatcher.
            entityType -> EntityRendererRegistry.register(
                entityType,
                (EntityRendererProvider) NoopRenderer::new
            )
        );
        
        // PORT(26.3): LoadingIndicatorRenderer (text rendering) is quarantined with the renderer.
        EntityRendererRegistry.register(
            LoadingIndicatorEntity.entityType,
            NoopRenderer::new
        );
        
    }
    
    @Override
    public void onInitializeClient() {
        IPModMainClient.init();
        
        initPortalRenderers();
        
        // PORT(26.3): the Sodium and Iris integrations are quarantined and fabric.mod.json
        // declares both as incompatible, so the no-op SodiumInterface/IrisInterface invokers
        // stay installed. The OnSodiumPresent/OnIrisPresent implementations are kept in
        // SodiumInterfaceOnPresent/IrisInterfaceOnPresent for the compatibility phase.
        if (FabricLoader.getInstance().isModLoaded("sodium")) {
            Helper.err("Sodium is present, but Sodium compatibility is not ported to 26.3 yet");
        }
        if (FabricLoader.getInstance().isModLoaded("iris")) {
            Helper.err("Iris is present, but Iris compatibility is not ported to 26.3 yet");
        }
        
        IPModInfoChecking.initClient();
    }
    
}

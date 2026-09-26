package qouteall.q_misc_util;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;

public class MiscUtilModEntryClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ImplRemoteProcedureCall.initClient();
        
        MiscNetworking.initClient();
        
        // replaces the former Gui#render mixin; HUD elements are extracted into the GUI render state
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("q_misc_util", "custom_text_overlay"),
            CustomTextOverlay::render
        );
    }
}

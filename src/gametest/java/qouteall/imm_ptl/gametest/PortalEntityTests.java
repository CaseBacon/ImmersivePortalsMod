package qouteall.imm_ptl.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.Portal;

public class PortalEntityTests {
    /**
     * Entity data is saved through ValueOutput/ValueInput since 26.x; the portal keeps its
     * own compound tag format inside.
     */
    @GameTest
    public void portalSurvivesSaveAndLoad(GameTestHelper helper) {
        Vec3 origin = helper.absoluteVec(new Vec3(2, 2, 2));
        Vec3 destination = helper.absoluteVec(new Vec3(2, 2, 6));
        Portal portal = TestUtil.spawnPortal(helper.getLevel(), origin, Level.NETHER, destination);

        CompoundTag tag = McHelper.saveEntityToTag(portal);

        Portal loaded = Portal.ENTITY_TYPE.create(helper.getLevel(), EntitySpawnReason.LOAD);
        helper.assertTrue(loaded != null, "could not create portal");
        McHelper.loadEntityFromTag(loaded, tag);

        helper.assertTrue(TestUtil.near(loaded.getOriginPos(), origin), "origin " + loaded.getOriginPos());
        helper.assertTrue(TestUtil.near(loaded.getDestPos(), destination), "destination " + loaded.getDestPos());
        helper.assertTrue(loaded.getDestDim() == Level.NETHER, "destination dimension " + loaded.getDestDim());
        helper.assertTrue(Math.abs(loaded.getWidth() - 2) < 1e-6, "width " + loaded.getWidth());
        helper.assertTrue(Math.abs(loaded.getHeight() - 3) < 1e-6, "height " + loaded.getHeight());
        helper.assertTrue(loaded.getUUID().equals(portal.getUUID()), "uuid changed");

        portal.discard();
        helper.succeed();
    }
}

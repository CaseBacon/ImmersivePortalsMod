package qouteall.q_misc_util;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ImplRemoteProcedureCallTest {

    public static class Fixture {
        public static void allowed(ServerPlayer player, int number, String text) {}

        // public static, but never registered
        public static void notRegistered(ServerPlayer player, int number) {}

        public static void noPlayerParameter(int number) {}
    }

    private static final String ALLOWED =
        ImplRemoteProcedureCall.methodId(Fixture.class, "allowed");
    private static final String NOT_REGISTERED =
        ImplRemoteProcedureCall.methodId(Fixture.class, "notRegistered");

    @BeforeAll
    public static void setUp() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (!ImplRemoteProcedureCall.isServerboundRegistered(ALLOWED)) {
            ImplRemoteProcedureCall.registerServerbound(Fixture.class, "allowed");
        }
    }

    private static boolean isValid(Consumer<RegistryFriendlyByteBuf> writer) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        writer.accept(buf);
        boolean valid = ImplRemoteProcedureCall.isValidServerboundPayload(buf);
        // a rejected payload must still be fully consumed
        assertTrue(buf.readableBytes() == 0);
        return valid;
    }

    @Test
    public void acceptsRegisteredMethod() {
        assertTrue(isValid(buf -> {
            buf.writeUtf(ALLOWED);
            buf.writeInt(3);
            buf.writeUtf("abc");
        }));
    }

    @Test
    public void rejectsUnregisteredPublicStaticMethod() {
        assertFalse(ImplRemoteProcedureCall.isServerboundRegistered(NOT_REGISTERED));
        assertFalse(isValid(buf -> {
            buf.writeUtf(NOT_REGISTERED);
            buf.writeInt(3);
        }));
    }

    @Test
    public void rejectsArbitraryClassName() {
        assertFalse(isValid(buf -> {
            buf.writeUtf("java.lang.System.exit");
            buf.writeInt(0);
        }));
    }

    @Test
    public void rejectsTrailingBytes() {
        assertFalse(isValid(buf -> {
            buf.writeUtf(ALLOWED);
            buf.writeInt(3);
            buf.writeUtf("abc");
            buf.writeByte(0);
        }));
    }

    @Test
    public void rejectsTruncatedArguments() {
        assertFalse(isValid(buf -> {
            buf.writeUtf(ALLOWED);
            buf.writeInt(3);
        }));
    }

    @Test
    public void rejectsOversizedString() {
        assertFalse(isValid(buf -> {
            buf.writeUtf(ALLOWED);
            buf.writeInt(3);
            buf.writeUtf("x".repeat(ImplRemoteProcedureCall.MAX_STRING_LENGTH + 1), Integer.MAX_VALUE);
        }));
    }

    @Test
    public void rejectsEmptyPayload() {
        assertFalse(isValid(buf -> {}));
    }

    @Test
    public void serverboundMethodsMustTakePlayer() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ImplRemoteProcedureCall.registerServerbound(Fixture.class, "noPlayerParameter")
        );
    }
}

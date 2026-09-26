package qouteall.q_misc_util;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.q_misc_util.my_util.CountDownInt;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remote procedure calls with an explicit allowlist.
 * <p>
 * Every remotely invocable method must be registered in code with
 * {@link #registerServerbound(Class, String)} (client to server) or
 * {@link #registerClientbound(Class, String)} (server to client).
 * The method id on the wire is only a key into these registries: a received id
 * never causes class loading or reflective lookup. Every parameter type must have a
 * codec registered in this class, otherwise registration fails.
 * <p>
 * (Before the 26.3 port, a client could invoke any public static method of any class whose
 * name contains "RemoteCallable", and unknown parameter types were deserialized with Gson.)
 */
public class ImplRemoteProcedureCall {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final CountDownInt LOGGING_LIMIT = new CountDownInt(100);

    private static final CountDownInt ERROR_MESSAGE_LIMIT = new CountDownInt(10);

    public static final int MAX_METHOD_ID_LENGTH = 256;

    public static final int MAX_STRING_LENGTH = 32767;

    public static final Gson gson = MiscHelper.gson;

    public interface ArgCodec<T> {
        void encode(RegistryFriendlyByteBuf buf, T value);

        T decode(RegistryFriendlyByteBuf buf);
    }

    private record CodecEntry(Class<?> rawClass, ArgCodec<Object> codec) {}

    // keyed by the declared parameter type name, e.g. "java.util.List<java.lang.String>"
    private static final Map<String, CodecEntry> CODECS_BY_TYPE_NAME = new ConcurrentHashMap<>();

    // for encoding by runtime class (the sender does not always know the remote method signature)
    private static final Map<Class<?>, ArgCodec<Object>> CODECS_BY_RUNTIME_CLASS = new ConcurrentHashMap<>();

    public record RegisteredMethod(
        String id, Method method, List<ArgCodec<Object>> argCodecs
    ) {}

    private static final Map<String, RegisteredMethod> SERVERBOUND_METHODS = new ConcurrentHashMap<>();
    private static final Map<String, RegisteredMethod> CLIENTBOUND_METHODS = new ConcurrentHashMap<>();

    static {
        registerBuiltinCodecs();
    }

    // ----------------------------------------------------------------- codecs

    @SuppressWarnings("unchecked")
    public static <T> void registerCodec(Type declaredType, Class<? super T> runtimeClass, ArgCodec<T> codec) {
        CodecEntry entry = new CodecEntry(runtimeClass, (ArgCodec<Object>) codec);
        if (CODECS_BY_TYPE_NAME.putIfAbsent(declaredType.getTypeName(), entry) != null) {
            throw new IllegalStateException("Duplicate RPC codec for " + declaredType.getTypeName());
        }
        CODECS_BY_RUNTIME_CLASS.putIfAbsent(runtimeClass, (ArgCodec<Object>) codec);
    }

    public static <T> void registerCodec(Class<T> clazz, ArgCodec<T> codec) {
        registerCodec(clazz, clazz, codec);
    }

    public static <T> void registerStreamCodec(Class<T> clazz, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        registerCodec(clazz, new ArgCodec<T>() {
            @Override
            public void encode(RegistryFriendlyByteBuf buf, T value) {
                codec.encode(buf, value);
            }

            @Override
            public T decode(RegistryFriendlyByteBuf buf) {
                return codec.decode(buf);
            }
        });
    }

    /**
     * Registers a codec that transfers the object as JSON and decodes only into the given class.
     */
    public static <T> void registerJsonCodec(Class<T> clazz) {
        registerCodec(clazz, new ArgCodec<T>() {
            @Override
            public void encode(RegistryFriendlyByteBuf buf, T value) {
                buf.writeUtf(gson.toJson(value, clazz), MAX_STRING_LENGTH);
            }

            @Override
            public T decode(RegistryFriendlyByteBuf buf) {
                T result = gson.fromJson(buf.readUtf(MAX_STRING_LENGTH), clazz);
                if (result == null) {
                    throw new IllegalArgumentException("null " + clazz.getSimpleName());
                }
                return result;
            }
        });
    }

    public static <E extends Enum<E>> void registerEnumCodec(Class<E> clazz) {
        E[] constants = clazz.getEnumConstants();
        registerCodec(clazz, new ArgCodec<E>() {
            @Override
            public void encode(RegistryFriendlyByteBuf buf, E value) {
                buf.writeVarInt(value.ordinal());
            }

            @Override
            public E decode(RegistryFriendlyByteBuf buf) {
                int ordinal = buf.readVarInt();
                if (ordinal < 0 || ordinal >= constants.length) {
                    throw new IllegalArgumentException("invalid " + clazz.getSimpleName() + " " + ordinal);
                }
                return constants[ordinal];
            }
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerBuiltinCodecs() {
        ArgCodec<Integer> intCodec = new ArgCodec<>() {
            public void encode(RegistryFriendlyByteBuf buf, Integer v) {buf.writeInt(v);}

            public Integer decode(RegistryFriendlyByteBuf buf) {return buf.readInt();}
        };
        registerCodec(int.class, Integer.class, intCodec);
        registerCodec(Integer.class, intCodec);

        ArgCodec<Long> longCodec = new ArgCodec<>() {
            public void encode(RegistryFriendlyByteBuf buf, Long v) {buf.writeLong(v);}

            public Long decode(RegistryFriendlyByteBuf buf) {return buf.readLong();}
        };
        registerCodec(long.class, Long.class, longCodec);
        registerCodec(Long.class, longCodec);

        ArgCodec<Double> doubleCodec = new ArgCodec<>() {
            public void encode(RegistryFriendlyByteBuf buf, Double v) {buf.writeDouble(v);}

            public Double decode(RegistryFriendlyByteBuf buf) {return buf.readDouble();}
        };
        registerCodec(double.class, Double.class, doubleCodec);
        registerCodec(Double.class, doubleCodec);

        ArgCodec<Float> floatCodec = new ArgCodec<>() {
            public void encode(RegistryFriendlyByteBuf buf, Float v) {buf.writeFloat(v);}

            public Float decode(RegistryFriendlyByteBuf buf) {return buf.readFloat();}
        };
        registerCodec(float.class, Float.class, floatCodec);
        registerCodec(Float.class, floatCodec);

        ArgCodec<Boolean> booleanCodec = new ArgCodec<>() {
            public void encode(RegistryFriendlyByteBuf buf, Boolean v) {buf.writeBoolean(v);}

            public Boolean decode(RegistryFriendlyByteBuf buf) {return buf.readBoolean();}
        };
        registerCodec(boolean.class, Boolean.class, booleanCodec);
        registerCodec(Boolean.class, booleanCodec);

        registerCodec(String.class, new ArgCodec<>() {
            public void encode(RegistryFriendlyByteBuf buf, String v) {buf.writeUtf(v, MAX_STRING_LENGTH);}

            public String decode(RegistryFriendlyByteBuf buf) {return buf.readUtf(MAX_STRING_LENGTH);}
        });

        registerCodec(UUID.class, new ArgCodec<>() {
            public void encode(RegistryFriendlyByteBuf buf, UUID v) {buf.writeUUID(v);}

            public UUID decode(RegistryFriendlyByteBuf buf) {return buf.readUUID();}
        });

        registerCodec(Identifier.class, new ArgCodec<>() {
            public void encode(RegistryFriendlyByteBuf buf, Identifier v) {buf.writeIdentifier(v);}

            public Identifier decode(RegistryFriendlyByteBuf buf) {return buf.readIdentifier();}
        });

        registerCodec(BlockPos.class, new ArgCodec<>() {
            public void encode(RegistryFriendlyByteBuf buf, BlockPos v) {buf.writeBlockPos(v);}

            public BlockPos decode(RegistryFriendlyByteBuf buf) {return buf.readBlockPos();}
        });

        registerCodec(Vec3.class, new ArgCodec<>() {
            public void encode(RegistryFriendlyByteBuf buf, Vec3 v) {
                buf.writeDouble(v.x);
                buf.writeDouble(v.y);
                buf.writeDouble(v.z);
            }

            public Vec3 decode(RegistryFriendlyByteBuf buf) {
                return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
            }
        });

        registerCodec(DQuaternion.class, new ArgCodec<>() {
            public void encode(RegistryFriendlyByteBuf buf, DQuaternion q) {
                buf.writeDouble(q.x);
                buf.writeDouble(q.y);
                buf.writeDouble(q.z);
                buf.writeDouble(q.w);
            }

            public DQuaternion decode(RegistryFriendlyByteBuf buf) {
                return new DQuaternion(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble());
            }
        });

        registerCodec(byte[].class, new ArgCodec<>() {
            public void encode(RegistryFriendlyByteBuf buf, byte[] v) {buf.writeByteArray(v);}

            // bounded by the maximum custom payload size
            public byte[] decode(RegistryFriendlyByteBuf buf) {return buf.readByteArray(1 << 20);}
        });

        registerCodec(int[].class, new ArgCodec<>() {
            public void encode(RegistryFriendlyByteBuf buf, int[] v) {buf.writeVarIntArray(v);}

            public int[] decode(RegistryFriendlyByteBuf buf) {return buf.readVarIntArray(1 << 16);}
        });

        // ResourceKey: the registry is implied by the declared parameter type
        registerResourceKeyCodec(
            "net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>", Registries.DIMENSION
        );
        registerResourceKeyCodec(
            "net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome>", Registries.BIOME
        );

        registerStreamCodec(Block.class, ByteBufCodecs.registry(Registries.BLOCK));
        registerStreamCodec(Item.class, ByteBufCodecs.registry(Registries.ITEM));
        registerStreamCodec(BlockState.class, ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY));
        registerStreamCodec(ItemStack.class, ItemStack.OPTIONAL_STREAM_CODEC);
        registerStreamCodec(CompoundTag.class, ByteBufCodecs.COMPOUND_TAG);
        // not the trusted codec: components may come from clients
        registerStreamCodec(Component.class, ComponentSerialization.STREAM_CODEC);

        registerCodec(
            new TypeName("java.util.List<java.lang.String>"), List.class,
            new ArgCodec<List>() {
                public void encode(RegistryFriendlyByteBuf buf, List v) {
                    buf.writeVarInt(v.size());
                    for (Object o : v) {
                        buf.writeUtf((String) o, MAX_STRING_LENGTH);
                    }
                }

                public List decode(RegistryFriendlyByteBuf buf) {
                    int size = readLimitedSize(buf, 65536);
                    List<String> result = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        result.add(buf.readUtf(MAX_STRING_LENGTH));
                    }
                    return result;
                }
            }
        );

        registerCodec(
            new TypeName("java.util.Map<java.lang.String, java.lang.Integer>"), Map.class,
            new ArgCodec<Map>() {
                public void encode(RegistryFriendlyByteBuf buf, Map v) {
                    buf.writeVarInt(v.size());
                    for (Object o : v.entrySet()) {
                        Map.Entry e = (Map.Entry) o;
                        buf.writeUtf((String) e.getKey(), MAX_STRING_LENGTH);
                        buf.writeInt((Integer) e.getValue());
                    }
                }

                public Map decode(RegistryFriendlyByteBuf buf) {
                    int size = readLimitedSize(buf, 65536);
                    Map<String, Integer> result = new HashMap<>();
                    for (int i = 0; i < size; i++) {
                        result.put(buf.readUtf(MAX_STRING_LENGTH), buf.readInt());
                    }
                    return result;
                }
            }
        );
    }

    private static int readLimitedSize(RegistryFriendlyByteBuf buf, int limit) {
        int size = buf.readVarInt();
        if (size < 0 || size > limit) {
            throw new IllegalArgumentException("invalid collection size " + size);
        }
        return size;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> void registerResourceKeyCodec(
        String typeName, ResourceKey<? extends Registry<T>> registry
    ) {
        registerCodec(new TypeName(typeName), ResourceKey.class, new ArgCodec<ResourceKey>() {
            public void encode(RegistryFriendlyByteBuf buf, ResourceKey v) {
                buf.writeIdentifier(v.identifier());
            }

            public ResourceKey decode(RegistryFriendlyByteBuf buf) {
                return ResourceKey.create(registry, buf.readIdentifier());
            }
        });
    }

    // a Type that only carries a type name (for generic parameter types)
    private record TypeName(String name) implements Type {
        @Override
        public String getTypeName() {
            return name;
        }
    }

    // ----------------------------------------------------------------- registration

    public static String methodId(Class<?> owner, String methodName) {
        return owner.getName().replace('$', '.') + "." + methodName;
    }

    /**
     * Allows clients to invoke {@code owner.methodName(ServerPlayer player, ...)} on the server.
     */
    public static void registerServerbound(Class<?> owner, String methodName) {
        RegisteredMethod m = createRegisteredMethod(owner, methodName, true);
        if (SERVERBOUND_METHODS.putIfAbsent(m.id(), m) != null) {
            throw new IllegalStateException("Duplicate serverbound RPC " + m.id());
        }
    }

    /**
     * Allows the server to invoke {@code owner.methodName(...)} on the client.
     */
    public static void registerClientbound(Class<?> owner, String methodName) {
        RegisteredMethod m = createRegisteredMethod(owner, methodName, false);
        if (CLIENTBOUND_METHODS.putIfAbsent(m.id(), m) != null) {
            throw new IllegalStateException("Duplicate clientbound RPC " + m.id());
        }
    }

    public static boolean isServerboundRegistered(String methodId) {
        return SERVERBOUND_METHODS.containsKey(methodId);
    }

    public static boolean isClientboundRegistered(String methodId) {
        return CLIENTBOUND_METHODS.containsKey(methodId);
    }

    private static RegisteredMethod createRegisteredMethod(
        Class<?> owner, String methodName, boolean serverbound
    ) {
        List<Method> candidates = new ArrayList<>();
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(methodName) && Modifier.isStatic(method.getModifiers())) {
                candidates.add(method);
            }
        }
        if (candidates.size() != 1) {
            throw new IllegalArgumentException(
                "RPC method %s must be a unique public static method, found %d"
                    .formatted(methodId(owner, methodName), candidates.size())
            );
        }
        Method method = candidates.get(0);
        Type[] paramTypes = method.getGenericParameterTypes();
        int start = 0;
        if (serverbound) {
            if (paramTypes.length == 0 || paramTypes[0] != ServerPlayer.class) {
                throw new IllegalArgumentException(
                    "Serverbound RPC method %s must take ServerPlayer as the first parameter"
                        .formatted(methodId(owner, methodName))
                );
            }
            start = 1;
        }
        List<ArgCodec<Object>> codecs = new ArrayList<>();
        for (int i = start; i < paramTypes.length; i++) {
            CodecEntry entry = CODECS_BY_TYPE_NAME.get(paramTypes[i].getTypeName());
            if (entry == null) {
                throw new IllegalArgumentException(
                    "No RPC codec for parameter type %s of %s"
                        .formatted(paramTypes[i].getTypeName(), methodId(owner, methodName))
                );
            }
            codecs.add(entry.codec());
        }
        return new RegisteredMethod(methodId(owner, methodName), method, List.copyOf(codecs));
    }

    // ----------------------------------------------------------------- encoding

    private static void encodeArguments(RegistryFriendlyByteBuf buf, String methodId, List<Object> args) {
        buf.writeUtf(methodId, MAX_METHOD_ID_LENGTH);
        for (Object arg : args) {
            if (arg == null) {
                throw new IllegalArgumentException("RPC argument must not be null " + methodId);
            }
            ArgCodec<Object> codec = findCodecForRuntimeClass(arg.getClass());
            if (codec == null) {
                throw new IllegalArgumentException(
                    "No RPC codec for argument type %s of %s".formatted(arg.getClass(), methodId)
                );
            }
            codec.encode(buf, arg);
        }
    }

    private static @Nullable ArgCodec<Object> findCodecForRuntimeClass(Class<?> clazz) {
        ArgCodec<Object> exact = CODECS_BY_RUNTIME_CLASS.get(clazz);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<Class<?>, ArgCodec<Object>> e : CODECS_BY_RUNTIME_CLASS.entrySet()) {
            if (e.getKey().isAssignableFrom(clazz)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Never throws. The result is invalid for an unregistered method, malformed arguments or trailing bytes.
     */
    private static Decoded decode(
        RegistryFriendlyByteBuf buf, Map<String, RegisteredMethod> registry
    ) {
        String methodId = null;
        try {
            methodId = buf.readUtf(MAX_METHOD_ID_LENGTH);
            RegisteredMethod method = registry.get(methodId);
            if (method == null) {
                if (LOGGING_LIMIT.tryDecrement()) {
                    LOGGER.warn("Rejected remote procedure call to unregistered method {}", methodId);
                }
                buf.skipBytes(buf.readableBytes());
                return new Decoded(methodId, null, null);
            }
            List<Object> args = new ArrayList<>(method.argCodecs().size());
            for (ArgCodec<Object> codec : method.argCodecs()) {
                args.add(codec.decode(buf));
            }
            if (buf.readableBytes() != 0) {
                throw new IllegalArgumentException("trailing bytes: " + buf.readableBytes());
            }
            return new Decoded(methodId, method, args);
        }
        catch (Exception e) {
            if (LOGGING_LIMIT.tryDecrement()) {
                LOGGER.error("Failed to parse remote procedure call {}", methodId, e);
            }
            buf.skipBytes(buf.readableBytes());
            return new Decoded(methodId, null, null);
        }
    }

    private record Decoded(
        @Nullable String methodId, @Nullable RegisteredMethod method, @Nullable List<Object> args
    ) {
        boolean isValid() {
            return method != null && args != null;
        }
    }

    /**
     * Decodes a serverbound RPC payload body. Exposed for tests.
     */
    public static boolean isValidServerboundPayload(RegistryFriendlyByteBuf buf) {
        return decode(buf, SERVERBOUND_METHODS).isValid();
    }

    // ----------------------------------------------------------------- payloads

    public static final class C2SRPCPayload implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<C2SRPCPayload> TYPE =
            new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath("iportal", "remote_c2s")
            );

        public static final StreamCodec<RegistryFriendlyByteBuf, C2SRPCPayload> CODEC = StreamCodec.of(
            (b, p) -> encodeArguments(b, p.methodId, p.args),
            b -> new C2SRPCPayload(decode(b, SERVERBOUND_METHODS))
        );

        private final String methodId;
        private final List<Object> args;
        private final @Nullable Decoded decoded;

        // sender side
        C2SRPCPayload(String methodId, List<Object> args) {
            this.methodId = methodId;
            this.args = args;
            this.decoded = null;
        }

        // receiver side
        private C2SRPCPayload(Decoded decoded) {
            this.methodId = String.valueOf(decoded.methodId());
            this.args = List.of();
            this.decoded = decoded;
        }

        public void handle(ServerPlayNetworking.Context c) {
            ServerPlayer player = c.player();
            if (decoded == null || !decoded.isValid()) {
                if (ERROR_MESSAGE_LIMIT.tryDecrement()) {
                    serverTellFailure(player);
                }
                return;
            }

            try {
                Object[] argArray = new Object[decoded.args().size() + 1];
                argArray[0] = player;
                for (int i = 0; i < decoded.args().size(); i++) {
                    argArray[i + 1] = decoded.args().get(i);
                }
                decoded.method().method().invoke(null, argArray);
            }
            catch (Exception e) {
                if (LOGGING_LIMIT.tryDecrement()) {
                    LOGGER.error(
                        "Failed to invoke remote procedure call {} {}", methodId, player, e
                    );
                    serverTellFailure(player);
                }
            }
        }

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static final class S2CRPCPayload implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<S2CRPCPayload> TYPE =
            new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath("iportal", "remote_s2c")
            );

        public static final StreamCodec<RegistryFriendlyByteBuf, S2CRPCPayload> CODEC = StreamCodec.of(
            (b, p) -> encodeArguments(b, p.methodId, p.args),
            b -> new S2CRPCPayload(decode(b, CLIENTBOUND_METHODS))
        );

        private final String methodId;
        private final List<Object> args;
        private final @Nullable Decoded decoded;

        S2CRPCPayload(String methodId, List<Object> args) {
            this.methodId = methodId;
            this.args = args;
            this.decoded = null;
        }

        private S2CRPCPayload(Decoded decoded) {
            this.methodId = String.valueOf(decoded.methodId());
            this.args = List.of();
            this.decoded = decoded;
        }

        @Environment(EnvType.CLIENT)
        public void handle(ClientPlayNetworking.Context c) {
            if (decoded == null || !decoded.isValid()) {
                if (ERROR_MESSAGE_LIMIT.tryDecrement()) {
                    clientTellFailure();
                }
                return;
            }

            try {
                decoded.method().method().invoke(null, decoded.args().toArray(new Object[0]));
            }
            catch (Exception e) {
                if (LOGGING_LIMIT.tryDecrement()) {
                    LOGGER.error("Failed to invoke remote procedure call {}", methodId, e);
                    clientTellFailure();
                }
            }
        }

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(
            C2SRPCPayload.TYPE, C2SRPCPayload.CODEC
        );

        PayloadTypeRegistry.clientboundPlay().register(
            S2CRPCPayload.TYPE, S2CRPCPayload.CODEC
        );

        ServerPlayNetworking.registerGlobalReceiver(
            C2SRPCPayload.TYPE, C2SRPCPayload::handle
        );
    }

    @Environment(EnvType.CLIENT)
    public static void initClient() {
        ClientPlayNetworking.registerGlobalReceiver(
            S2CRPCPayload.TYPE, S2CRPCPayload::handle
        );
    }

    @Environment(EnvType.CLIENT)
    public static Packet<ServerCommonPacketListener> createServerboundPacket(
        String methodPath,
        Object... arguments
    ) {
        return ClientPlayNetworking.createServerboundPacket(
            new C2SRPCPayload(methodPath, List.of(arguments))
        );
    }

    public static Packet<ClientCommonPacketListener> createClientboundPacket(
        String methodPath,
        Object... arguments
    ) {
        return ServerPlayNetworking.createClientboundPacket(
            new S2CRPCPayload(methodPath, List.of(arguments))
        );
    }

    @Environment(EnvType.CLIENT)
    private static void clientTellFailure() {
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal(
            "The client failed to process a packet from server. See the log for details."
        ).withStyle(ChatFormatting.RED));
    }

    private static void serverTellFailure(ServerPlayer player) {
        player.sendSystemMessage(Component.literal(
            "The server failed to process a packet sent from client."
        ).withStyle(ChatFormatting.RED));
    }
}

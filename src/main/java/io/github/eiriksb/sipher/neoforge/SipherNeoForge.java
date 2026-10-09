package io.github.eiriksb.sipher.neoforge;

//? if neoforge {
import io.github.eiriksb.sipher.Sipher;
import io.github.eiriksb.sipher.config.SipherServerConfig;
import io.github.eiriksb.sipher.net.CaptionPayload;
import io.github.eiriksb.sipher.net.CaptionUpdatePayload;
import io.github.eiriksb.sipher.net.SipherNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
//? if >=1.21.9 {
/*import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ConfigurationTask;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;

import java.util.function.Consumer;
*///?}

/** NeoForge entry point. */
@Mod(Sipher.MOD_ID)
public final class SipherNeoForge {
    public SipherNeoForge(IEventBus modBus, ModContainer container) {
        Sipher.init(new NeoForgePlatform());
        modBus.addListener(SipherNeoForge::registerPayloads);
        //? if >=1.21.9 {
        /*modBus.addListener((RegisterConfigurationTasksEvent event) -> event.register(new ServerThreadStep()));
        *///?}
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> SipherServerConfig.load(
                event.getServer().getWorldPath(LevelResource.ROOT), Sipher.platform().gameDirectory()));

        //? if >=1.21.9 {
        /*if (FMLEnvironment.getDist().isClient()) {
        *///?} else
        if (FMLEnvironment.dist.isClient()) {
            SipherNeoForgeClient.init(modBus, container);
        }
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(SipherNetwork.PROTOCOL).optional();
        registrar.playToServer(CaptionUpdatePayload.TYPE, CaptionUpdatePayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                SipherNetwork.receivedOnServer(player, payload);
            }
        });
        registrar.playToClient(CaptionPayload.TYPE, CaptionPayload.STREAM_CODEC,
                (payload, context) -> SipherNetwork.receivedOnClient(payload));
    }

    //? if >=1.21.9 {
    /*// Works around a NeoForge bug that can crash the server when a Fabric player joins. NeoForge finishes its
    // cross-loader login steps (c:version, c:register, which Fabric API answers) on the network thread, so the login
    // steps after them start there too, including Minecraft's own step that finds the player's spawn and loads chunks.
    // That races the server thread's chunk updates ("Exception ticking world"). This step, after NeoForge's, finishes
    // on the next server tick, so everything after it starts on the server thread.
    private record ServerThreadStep() implements ConfigurationTask {
        private static final Type TYPE = new Type(Sipher.id("server_thread").toString());

        @Override
        public void start(Consumer<Packet<?>> sender) {
        }

        @Override
        public boolean tick() {
            return true;
        }

        @Override
        public Type type() {
            return TYPE;
        }
    }
    *///?}
}
//?}

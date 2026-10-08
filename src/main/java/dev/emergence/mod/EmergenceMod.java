package dev.emergence.mod;

import com.mojang.brigadier.Command;
import dev.emergence.core.materials.MaterialDatabase;
import dev.emergence.mod.block.AssemblyBlock;
import dev.emergence.mod.net.IslandSyncPayload;
import dev.emergence.mod.registry.ModRegistries;
import dev.emergence.mod.sim.Profiler;
import dev.emergence.mod.sim.SimulationManager;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(EmergenceMod.MODID)
public final class EmergenceMod {
    public static final String MODID = "emergence";
    public static final Logger LOG = LoggerFactory.getLogger("Emergence");

    public EmergenceMod(IEventBus modBus, ModContainer container) {
        LOG.info("Emergence: Engineering Universe initialising ({} materials)", MaterialDatabase.standard().all().size());
        ModRegistries.register(modBus);
        modBus.addListener(EmergenceMod::registerPayloads);
        if (FMLEnvironment.dist.isClient()) dev.emergence.mod.client.ClientSetup.init(modBus);

        NeoForge.EVENT_BUS.addListener(EmergenceMod::onLevelTick);
        NeoForge.EVENT_BUS.addListener(EmergenceMod::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(EmergenceMod::onLevelSave);
        NeoForge.EVENT_BUS.addListener(EmergenceMod::onLeftClickBlock);
        NeoForge.EVENT_BUS.addListener(EmergenceMod::onRegisterCommands);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(IslandSyncPayload.TYPE, IslandSyncPayload.STREAM_CODEC, EmergenceMod::handleSync);
    }

    private static void handleSync(IslandSyncPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> dev.emergence.mod.client.ClientSimState.apply(payload));
    }

    private static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel sl) SimulationManager.get(sl).tick();
    }

    private static void onLevelSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel sl) SimulationManager.get(sl).saveAll();
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel sl) {
            SimulationManager.get(sl).saveAll();
            SimulationManager.clear(sl);
        }
    }

    /** Left-clicking an assembly removes the single part under the crosshair instead of breaking the block. */
    private static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof AssemblyBlock)) return;
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) {
            event.setCanceled(true);
            return;
        }
        event.setCanceled(true);
        if (!event.getLevel().isClientSide) {
            AssemblyBlock.removeTargetedPart(event.getLevel(), event.getPos(), event.getEntity());
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("emergence")
                .then(Commands.literal("perf").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(Profiler.report()), false);
                    return Command.SINGLE_SUCCESS;
                })));
    }
}

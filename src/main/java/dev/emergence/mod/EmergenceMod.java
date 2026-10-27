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
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStartedEvent e) -> {
            if (Boolean.getBoolean("emergence.buildDemo")) dev.emergence.mod.demo.DemoBuilder.generateWorld(e.getServer());
            // -Demergence.forceLoad=cx0,cz0,cx1,cz1: those chunks kept loaded with no player near (a headless server loading a
            // saved machine to see it restart: ./gradlew runStationServer)
            String fl = System.getProperty("emergence.forceLoad", "");
            if (!fl.isBlank()) {
                String[] c = fl.split(",");
                for (int x = Integer.parseInt(c[0].trim()); x <= Integer.parseInt(c[2].trim()); x++)
                    for (int z = Integer.parseInt(c[1].trim()); z <= Integer.parseInt(c[3].trim()); z++) e.getServer().overworld().setChunkForced(x, z, true);
            }
        });
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var reg = event.registrar("1");
        reg.playToClient(IslandSyncPayload.TYPE, IslandSyncPayload.STREAM_CODEC, EmergenceMod::handleSync);
        reg.playToClient(dev.emergence.mod.net.SoundPayload.TYPE, dev.emergence.mod.net.SoundPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> dev.emergence.mod.client.ClientSounds.apply(payload)));
        reg.playToClient(dev.emergence.mod.net.DisplaySyncPayload.TYPE, dev.emergence.mod.net.DisplaySyncPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> dev.emergence.mod.client.ClientDisplays.apply(payload)));
        reg.playToClient(dev.emergence.mod.net.LoggerSyncPayload.TYPE, dev.emergence.mod.net.LoggerSyncPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> dev.emergence.mod.client.LoggerHud.apply(payload)));
        registerChipPayloads(reg);
        reg.playToServer(dev.emergence.mod.net.PartSpecEditPayload.TYPE, dev.emergence.mod.net.PartSpecEditPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
                    String why = dev.emergence.mod.part.PartSpecEdits.apply(sp, payload.offhand()
                            ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND, payload.spec());
                    if (why != null) sp.displayClientMessage(net.minecraft.network.chat.Component.literal(why), true);
                }));
    }

    private static void registerChipPayloads(net.neoforged.neoforge.network.registration.PayloadRegistrar reg) {
        reg.playToServer(dev.emergence.mod.net.ChipTextPayload.TYPE, dev.emergence.mod.net.ChipTextPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
                    String why = dev.emergence.mod.part.ChipEdits.writeText(sp, payload.pos(), payload.partId(), payload.text());
                    sp.displayClientMessage(net.minecraft.network.chat.Component.literal(why != null ? why : "Written to the chip."), true);
                }));
        reg.playToServer(dev.emergence.mod.net.ChipProbePayload.Request.TYPE, dev.emergence.mod.net.ChipProbePayload.Request.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) {
                        ctx.reply(dev.emergence.mod.part.ChipEdits.probe(sp, payload.pos(), payload.partId()));
                    }
                }));
        reg.playToClient(dev.emergence.mod.net.ChipProbePayload.TYPE, dev.emergence.mod.net.ChipProbePayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> dev.emergence.mod.client.ClientHooks.chipProbe(payload)));
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
                }))
                .then(Commands.literal("log").then(Commands.literal("export").executes(ctx -> {
                    var src = ctx.getSource();
                    var player = src.getPlayerOrException();
                    var s = SimulationManager.get(src.getLevel()).logger(player);
                    if (s == null || s.log().size() == 0) {
                        src.sendFailure(Component.literal("Your data logger has recorded nothing yet: use it on a part."));
                        return 0;
                    }
                    try {
                        java.nio.file.Path dir = src.getServer().getServerDirectory().resolve("emergence-logs");
                        java.nio.file.Files.createDirectories(dir);
                        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                        String name = stamp + "-" + s.title().replaceAll("[^A-Za-z0-9]+", "_").replaceAll("_+$", "") + ".csv";
                        java.nio.file.Path file = dir.resolve(name);
                        java.nio.file.Files.writeString(file, s.log().csv());
                        int n = s.log().size();
                        src.sendSuccess(() -> Component.literal("Saved " + n + " samples to " + file.toAbsolutePath()), false);
                    } catch (java.io.IOException e) {
                        src.sendFailure(Component.literal("Could not write the log: " + e.getMessage()));
                        return 0;
                    }
                    return Command.SINGLE_SUCCESS;
                })))
                .then(Commands.literal("demo").requires(s -> s.hasPermission(2)).executes(ctx -> {
                    var src = ctx.getSource();
                    net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(src.getPosition()).offset(3, 0, -6);
                    dev.emergence.mod.demo.DemoBuilder.build(src.getLevel(), at);
                    src.sendSuccess(() -> Component.literal("Built the Emergence exhibit gallery in front of you. Read the signs; use the Multimeter."), true);
                    return Command.SINGLE_SUCCESS;
                })));
    }
}

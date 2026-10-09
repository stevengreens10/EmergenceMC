package dev.emergence.mod.registry;

import dev.emergence.core.assembly.PartKind;
import dev.emergence.mod.EmergenceMod;
import dev.emergence.mod.block.AssemblyBlock;
import dev.emergence.mod.block.AssemblyBlockEntity;
import dev.emergence.mod.item.MultimeterItem;
import dev.emergence.mod.part.PartItem;
import dev.emergence.mod.part.PartPresets;
import dev.emergence.mod.part.PartSpec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

public final class ModRegistries {
    private ModRegistries() {}

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(EmergenceMod.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(EmergenceMod.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, EmergenceMod.MODID);
    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, EmergenceMod.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, EmergenceMod.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PartSpec>> PART_SPEC =
            COMPONENTS.registerComponentType("part_spec", b -> b.persistent(PartSpec.CODEC).networkSynchronized(PartSpec.STREAM_CODEC));

    public static final DeferredBlock<AssemblyBlock> ASSEMBLY = BLOCKS.register("assembly", () -> new AssemblyBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(0.3f).sound(SoundType.METAL)
                    .noOcclusion().dynamicShape().pushReaction(PushReaction.BLOCK)
                    .lightLevel(s -> s.getValue(AssemblyBlock.LIGHT))));

    public static final Supplier<BlockEntityType<AssemblyBlockEntity>> ASSEMBLY_BE = BLOCK_ENTITIES.register("assembly",
            () -> BlockEntityType.Builder.of(AssemblyBlockEntity::new, ASSEMBLY.get()).build(null));

    public static final Map<PartKind, DeferredItem<PartItem>> PART_ITEMS = new EnumMap<>(PartKind.class);

    static {
        for (PartKind k : PartKind.values()) {
            PART_ITEMS.put(k, ITEMS.register(k.name().toLowerCase(), () -> new PartItem(k, new Item.Properties())));
        }
    }

    public static final DeferredItem<MultimeterItem> MULTIMETER = ITEMS.register("multimeter",
            () -> new MultimeterItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<dev.emergence.mod.item.DataLoggerItem> DATA_LOGGER = ITEMS.register("data_logger",
            () -> new dev.emergence.mod.item.DataLoggerItem(new Item.Properties().stacksTo(1)));

    /** Held in the off-hand, part items place miniature (1/16-block, true-scale) parts. */
    public static final DeferredItem<Item> TWEEZERS = ITEMS.register("precision_tweezers",
            () -> new Item(new Item.Properties().stacksTo(1)) {
                @Override
                public void appendHoverText(ItemStack stack, TooltipContext ctx, java.util.List<Component> tip, net.minecraft.world.item.TooltipFlag flag) {
                    tip.add(Component.translatable("tooltip.emergence.precision_tweezers").withStyle(net.minecraft.ChatFormatting.GRAY));
                }
            });

    public static final Supplier<CreativeModeTab> TAB = TABS.register("parts", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.emergence.parts"))
            .icon(() -> new ItemStack(PART_ITEMS.get(PartKind.COIL).get()))
            .displayItems((params, out) -> {
                out.accept(MULTIMETER.get());
                out.accept(DATA_LOGGER.get());
                out.accept(TWEEZERS.get());
                PartPresets.all().forEach(out::accept);
            })
            .build());

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        COMPONENTS.register(bus);
        TABS.register(bus);
    }
}

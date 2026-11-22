package net.mcreator.wifi;

import dan200.computercraft.api.peripheral.PeripheralCapability;
import dan200.computercraft.api.ComputerCraftAPI;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.Registries;

import java.util.function.Supplier;

@Mod(WifiMod.MOD_ID)
public class WifiMod {
	public static final String MOD_ID = "wifi";

	public static final ModConfigSpec.IntValue WIRELESS_RANGE;
	public static final ModConfigSpec.IntValue MAX_CLIENTS;
	public static final ModConfigSpec.IntValue MAX_INBOX_SIZE;
	public static final ModConfigSpec.IntValue ANNOUNCE_INTERVAL_SECONDS;
	public static final ModConfigSpec.IntValue PEER_TIMEOUT_SECONDS;
	private static final ModConfigSpec SERVER_CONFIG;

	static {
		ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
		builder.push("network");
		WIRELESS_RANGE = builder
				.comment("Scan radius in blocks for attached-peripheral nearby-router discovery.")
				.defineInRange("wirelessRange", 60, 1, 1024);
		MAX_CLIENTS = builder
				.comment("Maximum number of LAN clients connected to one router.")
				.defineInRange("maxClients", 155, 1, 254);
		MAX_INBOX_SIZE = builder
				.comment("Maximum queued messages per LAN client.")
				.defineInRange("maxInboxSize", 64, 1, 4096);
		ANNOUNCE_INTERVAL_SECONDS = builder
				.comment("Seconds between wireless router bridge discovery beacons.")
				.defineInRange("announceIntervalSeconds", 3, 1, 60);
		PEER_TIMEOUT_SECONDS = builder
				.comment("Legacy setting retained for compatibility; server-wide routing does not use it.")
				.defineInRange("peerTimeoutSeconds", 12, 3, 300);
		builder.pop();
		SERVER_CONFIG = builder.build();
	}

	private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
	private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
	private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
			DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);

	public static final Supplier<WifiRouterBlock> WIFI_ROUTER = BLOCKS.register("wifi_router", WifiRouterBlock::new);
	public static final Supplier<BlockItem> WIFI_ROUTER_ITEM = ITEMS.registerSimpleBlockItem("wifi_router", WIFI_ROUTER);
	public static final Supplier<BlockEntityType<WifiRouterBlockEntity>> WIFI_ROUTER_ENTITY = BLOCK_ENTITY_TYPES.register(
			"wifi_router", () -> BlockEntityType.Builder.of(WifiRouterBlockEntity::new, WIFI_ROUTER.get()).build(null));

	public WifiMod(IEventBus modEventBus, ModContainer modContainer) {
		ComputerCraftAPI.registerAPIFactory(WifiAirSessions.Api::new);
		modContainer.registerConfig(ModConfig.Type.SERVER, SERVER_CONFIG);
		BLOCKS.register(modEventBus);
		ITEMS.register(modEventBus);
		BLOCK_ENTITY_TYPES.register(modEventBus);
		modEventBus.addListener(this::registerCapabilities);
		modEventBus.addListener(this::addToCreativeTab);
		NeoForge.EVENT_BUS.addListener(this::onServerStopping);
	}

	private void onServerStopping(ServerStoppingEvent event) {
		WifiAirSessions.stopServer(event.getServer());
		WifiRouterNetwork.stopServer(event.getServer());
	}

	private void registerCapabilities(RegisterCapabilitiesEvent event) {
		event.registerBlockEntity(PeripheralCapability.get(), WIFI_ROUTER_ENTITY.get(),
				(blockEntity, side) -> blockEntity.peripheral());
	}

	private void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
		if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
			event.accept(WIFI_ROUTER_ITEM.get());
		}
	}
}

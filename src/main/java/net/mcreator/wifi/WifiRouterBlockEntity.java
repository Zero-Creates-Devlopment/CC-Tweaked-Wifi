package net.mcreator.wifi;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class WifiRouterBlockEntity extends BlockEntity {
	private final WifiRouterPeripheral peripheral = new WifiRouterPeripheral(this);

	public WifiRouterBlockEntity(BlockPos pos, BlockState state) {
		super(WifiMod.WIFI_ROUTER_ENTITY.get(), pos, state);
	}

	public WifiRouterPeripheral peripheral() {
		return peripheral;
	}

	@Override
	public void onLoad() {
		super.onLoad();
		MinecraftServer server = level == null ? null : level.getServer();
		if (server != null) {
			WifiRouterNetwork.forServer(server).register(peripheral);
		}
	}

	@Override
	public void onChunkUnloaded() {
		unregister();
		super.onChunkUnloaded();
	}

	@Override
	public void setRemoved() {
		unregister();
		super.setRemoved();
	}

	private void unregister() {
		MinecraftServer server = level == null ? null : level.getServer();
		if (server != null) {
			WifiRouterNetwork.forServer(server).unregister(peripheral);
		}
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		peripheral.save(tag);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		peripheral.load(tag);
	}
}

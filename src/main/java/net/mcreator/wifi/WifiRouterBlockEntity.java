package net.mcreator.wifi;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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

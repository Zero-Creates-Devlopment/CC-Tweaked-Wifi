package net.mcreator.wifi;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public class WifiRouterBlockEntity extends BlockEntity {
	private final WifiRouterPeripheral peripheral = new WifiRouterPeripheral(this);

	public WifiRouterBlockEntity(BlockPos pos, BlockState state) {
		super(WifiMod.WIFI_ROUTER_ENTITY.get(), pos, state);
	}

	public WifiRouterPeripheral peripheral() {
		return peripheral;
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		peripheral.save(output);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		peripheral.load(input);
	}
}

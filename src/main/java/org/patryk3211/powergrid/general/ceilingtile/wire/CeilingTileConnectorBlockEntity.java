package org.patryk3211.powergrid.general.ceilingtile.wire;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;

public class CeilingTileConnectorBlockEntity extends ElectricBlockEntity {
    public CeilingTileConnectorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // Wire anchor: lines through unloaded chunks must stay connected, so keep this in the grid without ticking.
    @Override
    public boolean usePersistentSimulation() {
        return true;
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        builder.setTerminalCount(1);
    }
}

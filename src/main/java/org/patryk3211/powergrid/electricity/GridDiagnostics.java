package org.patryk3211.powergrid.electricity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * Builds the text of diagnostic messages about devices which trip, blow or overheat. These describe the state of
 * the device and the network it is in, which is what is needed to tell a real overload from a simulation problem.
 */
public final class GridDiagnostics {
    private GridDiagnostics() {
    }

    public static String blockName(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    public static String position(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String voltage(@Nullable IElectricNode node) {
        return node == null ? "ground" : String.format("%.3fV", node.getVoltage());
    }

    /**
     * Describes the electrical state of a wire together with the network it belongs to.
     */
    public static String describe(@Nullable AbstractElectricWire wire) {
        if(wire == null)
            return "no wire";
        var network = wire.getNetwork();
        if(network == null)
            return "wire isn't part of any network (stale)";
        return String.format("current=%.4fA, voltage drop=%.4fV (%s -> %s), power=%.3fW, converged=%b, network: %s",
                wire.current(), wire.potentialDifference(), voltage(wire.getNode1()), voltage(wire.getNode2()),
                wire.power(), wire.isConverged(), network.describe());
    }
}

package org.patryk3211.powergrid.electricity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.GraphedElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.NetworkGraph;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;

/**
 * Builds the text of diagnostic messages about devices which trip, blow or overheat. These describe the state of
 * the device and the network it is in, which is what is needed to tell a real overload from a simulation problem.
 */
public final class GridDiagnostics {
    private static final int MAX_LISTED = 8;
    private static final int MAX_SEARCH = 400;

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
        return describe(wire, null);
    }

    /**
     * Describes the electrical state of a wire, the network it belongs to, where the current goes on both of its
     * sides and what the voltage sources of the network are doing. The last two answer "who is drawing this current".
     */
    public static String describe(@Nullable AbstractElectricWire wire, @Nullable Level level) {
        if(wire == null)
            return "no wire";
        var network = wire.getNetwork();
        if(network == null)
            return "wire isn't part of any network (stale)";
        var text = new StringBuilder(String.format(
                "current=%.4fA, voltage drop=%.4fV (%s -> %s), power=%.3fW, converged=%b, network: %s",
                wire.current(), wire.potentialDifference(), voltage(wire.getNode1()), voltage(wire.getNode2()),
                wire.power(), wire.isConverged(), network.describe()));
        try {
            text.append("; side 1: ").append(side(network, wire, wire.getNode1(), level));
            text.append("; side 2: ").append(side(network, wire, wire.getNode2(), level));
            text.append("; sources: ").append(sources(network));
        } catch(RuntimeException e) {
            // Diagnostics must never break the simulation.
            text.append("; (diagnostics failed: ").append(e).append(")");
        }
        return text.toString();
    }

    /**
     * Lists what is connected to a node of the wire, with the current in each connection.
     */
    private static String side(ElectricalNetwork network, AbstractElectricWire wire, @Nullable IElectricNode node, @Nullable Level level) {
        if(node == null)
            return "ground";
        var text = new StringBuilder();
        if(network.isLeaf(node))
            text.append("(eliminated leaf node) ");
        if(!(network instanceof GraphedElectricalNetwork graphed)) {
            return text.append("no connection graph").toString();
        }
        var graph = graphed.getGraph();
        var wires = graph.getWires(node);
        int listed = 0;
        for(var other : wires) {
            if(other == wire)
                continue;
            if(listed == MAX_LISTED) {
                text.append(", ... ");
                break;
            }
            if(listed++ > 0)
                text.append(", ");
            text.append(String.format("%s %.2fA", other, other.current()));
            var closest = closestOwned(graph, other, node);
            if(closest != null)
                text.append(" towards ").append(owner(closest, level));
        }
        var couplings = graph.getCouplings(node);
        for(var coupling : couplings) {
            if(listed++ > 0)
                text.append(", ");
            text.append(coupling);
        }
        if(listed == 0)
            text.append("nothing else is connected");
        return text.toString();
    }

    @Nullable
    private static OwnedFloatingNode closestOwned(NetworkGraph graph, AbstractElectricWire via, IElectricNode from) {
        IElectricNode start = via.getNode1() == from ? via.getNode2() : via.getNode1();
        if(start == null)
            return null;
        var visited = new HashSet<IElectricNode>();
        visited.add(from);
        var queue = new ArrayDeque<IElectricNode>();
        queue.add(start);
        while(!queue.isEmpty() && visited.size() < MAX_SEARCH) {
            var node = queue.poll();
            if(!visited.add(node))
                continue;
            if(node instanceof OwnedFloatingNode owned)
                return owned;
            queue.addAll(graph.getConnectedNodes(node));
        }
        return null;
    }

    /**
     * Describes where in the world a node is: the device it belongs to, or if it is a plain junction,
     * the closest device connected to it.
     */
    public static String locate(ElectricalNetwork network, @Nullable IElectricNode node, @Nullable Level level) {
        if(node == null)
            return "ground";
        if(node instanceof OwnedFloatingNode owned)
            return owner(owned, level);
        if(!(network instanceof GraphedElectricalNetwork graphed))
            return node.toString();
        var visited = new HashSet<IElectricNode>();
        var queue = new ArrayDeque<IElectricNode>();
        queue.add(node);
        var graph = graphed.getGraph();
        while(!queue.isEmpty() && visited.size() < MAX_SEARCH) {
            var current = queue.poll();
            if(!visited.add(current))
                continue;
            if(current instanceof OwnedFloatingNode owned)
                return "near " + owner(owned, level);
            queue.addAll(graph.getConnectedNodes(current));
        }
        return "an internal node";
    }

    private static String owner(OwnedFloatingNode node, @Nullable Level level) {
        if(node.endpoint instanceof BlockWireEndpoint block) {
            var pos = block.getPos();
            if(level != null && level.isLoaded(pos))
                return blockName(level.getBlockState(pos).getBlock()) + " at " + position(pos) + " terminal " + block.getTerminal();
            return "block at " + position(pos) + " terminal " + block.getTerminal() + " (not loaded)";
        }
        return node.endpoint.toString();
    }

    /**
     * Lists the voltage sources of a network: their voltage and the current they deliver (negative means the
     * source is being charged or driven, a source absorbing a lot of current is usually where an overload goes).
     */
    private static String sources(ElectricalNetwork network) {
        var text = new StringBuilder();
        int listed = 0, total = 0;
        for(var node : List.copyOf(network.getNodes())) {
            if(!(node instanceof VoltageSourceCoupling source))
                continue;
            ++total;
            if(listed >= MAX_LISTED * 2)
                continue;
            if(listed++ > 0)
                text.append(", ");
            if(source instanceof org.patryk3211.powergrid.electricity.sim.special.TransmissionLinePort port) {
                text.append(port.describeEnds());
                continue;
            }
            text.append(String.format("%s(emf=%.2fV, R=%.3f, I=%.2fA)", source.getClass().getSimpleName(),
                    source.getVoltage(), source.getResistance(), source.getCurrent()));
        }
        if(total > listed)
            text.append(", ... (").append(total).append(" in total)");
        return listed == 0 ? "none" : text.toString();
    }
}

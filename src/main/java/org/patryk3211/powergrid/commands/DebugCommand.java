package org.patryk3211.powergrid.commands;

import org.patryk3211.powergrid.electricity.ShortCircuitDetector;
import org.patryk3211.powergrid.collections.ModdedConfigs;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import net.createmod.catnip.data.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.LevelAccessor;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.GraphedElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.NetworkGraph;
import org.patryk3211.powergrid.electricity.sim.PerformanceCounter;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import static net.minecraft.commands.Commands.literal;


public class DebugCommand {
    private record ProblematicWire(AbstractElectricWire wire, OwnedFloatingNode closestOwnedNode, double residual) { }
    private record ProblemEntry(Date timestamp, double residual, List<ProblematicWire> wires) { }
    private static final List<ProblemEntry> savedProblematicExecutions = new ArrayList<>();

    private static OwnedFloatingNode findClosestNode(NetworkGraph graph, AbstractElectricWire wire) {
        var checkNodes = new ArrayList<IElectricNode>();
        checkNodes.add(wire.getNode1());
        checkNodes.add(wire.getNode2());

        while(!checkNodes.isEmpty()) {
            var node = checkNodes.remove(0);
            var connected = graph.getConnectedNodes(node);
            for(var other : connected) {
                if(other instanceof OwnedFloatingNode owned)
                    return owned;
            }
            checkNodes.addAll(connected);
            if(checkNodes.size() > 10000)
                break;
        }
        return null;
    }

    public static void pushProblems(GraphedElectricalNetwork network, double residual, Collection<Pair<AbstractElectricWire, Double>> wires) {
        var frame = wires.stream().map(entry -> new ProblematicWire(entry.getFirst(), findClosestNode(network.getGraph(), entry.getFirst()), entry.getSecond())).toList();
        savedProblematicExecutions.add(new ProblemEntry(new Date(), residual, frame));
        if(savedProblematicExecutions.size() > 5)
            savedProblematicExecutions.remove(0);
    }

    public static ArgumentBuilder<CommandSourceStack, ?> register() {
        return literal("debug")
                .then(literal("problems")
                        .then(literal("clear").executes(ctx -> {
                            savedProblematicExecutions.clear();
                            ctx.getSource().sendSystemMessage(Component.literal("Cleared problem frame storage"));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .executes(ctx -> {
                    var source = ctx.getSource();
                    source.sendSystemMessage(Component.literal("Showing problematic wires of " + savedProblematicExecutions.size() +
                            " latest non-converged networks:").withStyle(ChatFormatting.GRAY));
                    for(int i = 0; i < savedProblematicExecutions.size(); ++i) {
                        var frame = savedProblematicExecutions.get(i);
                        source.sendSystemMessage(Component.literal("[" + i + "] " + PerformanceCounter.FORMAT.format(frame.timestamp) + " final residual = " + frame.residual)
                                .withStyle(ChatFormatting.GRAY));
                        for(var entry : frame.wires) {
                            source.sendSystemMessage(Component.literal("  " + entry.wire()).withStyle(ChatFormatting.GOLD));
                            source.sendSystemMessage(Component.literal(String.format("  Wire residual = %g", entry.residual)).withStyle(ChatFormatting.GRAY));
                            source.sendSystemMessage(Component.literal("  Closest owned node:").withStyle(ChatFormatting.DARK_GRAY));
                            source.sendSystemMessage(Component.literal("  " + entry.closestOwnedNode()).withStyle(ChatFormatting.DARK_GRAY));
                        }
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .then(literal("shorts").executes(ctx -> {
                    var source = ctx.getSource();
                    var global = GlobalElectricNetworks.getWorldNetworks((LevelAccessor) source.getLevel());
                    var limit = ModdedConfigs.server().electricity.solver.shortCircuitCurrent.get();
                    if(global == null || limit <= 0) {
                        source.sendSystemMessage(Component.literal(limit <= 0 ? "Short circuit detection is disabled (shortCircuitCurrent = 0)" : "Current level doesn't have any electrical networks"));
                        return Command.SINGLE_SUCCESS;
                    }
                    var findings = ShortCircuitDetector.scan(global, limit);
                    if(findings.isEmpty()) {
                        source.sendSystemMessage(Component.literal("No short circuits found in " + global.subnetworks.size() + " networks").withStyle(ChatFormatting.GREEN));
                    } else {
                        source.sendSystemMessage(Component.literal("Found " + findings.size() + " networks with a possible short circuit:").withStyle(ChatFormatting.GOLD));
                        for(var finding : findings.subList(0, Math.min(5, findings.size())))
                            source.sendSystemMessage(Component.literal(finding).withStyle(ChatFormatting.GRAY));
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .then(literal("audit").executes(ctx -> {
                    var source = ctx.getSource();
                    var global = GlobalElectricNetworks.getWorldNetworks((LevelAccessor) source.getLevel());
                    if(global == null) {
                        source.sendSystemMessage(Component.literal("Current level doesn't have any electrical networks"));
                    } else {
                        int repairs = global.audit();
                        source.sendSystemMessage(Component.literal(repairs == 0
                                ? "No stale state found in " + global.subnetworks.size() + " networks"
                                : "Repaired " + repairs + " stale entries (details are in the server log)")
                                .withStyle(repairs == 0 ? ChatFormatting.GREEN : ChatFormatting.GOLD));
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .then(literal("status").executes(ctx -> {
                    var source = ctx.getSource();
                    var player = source.getPlayerOrException();
                    var global = GlobalElectricNetworks.getWorldNetworks((LevelAccessor) player.level());
                    if(global == null) {
                        source.sendSystemMessage(Component.literal("Current level doesn't have any electrical networks"));
                    } else {
                        var nets = global.subnetworks;
                        int notConverged = 0;
                        for(var net : nets) {
                            if(!net.isConverged())
                                ++notConverged;
                        }
                        source.sendSystemMessage(Component.literal("Current level has " + global.subnetworks.size() + " networks, " + notConverged + " are not converged."));
                    }
                    return Command.SINGLE_SUCCESS;
                }));
    }
}

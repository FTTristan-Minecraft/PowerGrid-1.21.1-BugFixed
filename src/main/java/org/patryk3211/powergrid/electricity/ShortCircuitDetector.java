package org.patryk3211.powergrid.electricity;

import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.PowerGrid;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;
import org.patryk3211.powergrid.utility.RateLimitedLog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Detects short circuits: parts of a network which carry far more current than anything is meant to, or voltage
 * sources which are being loaded so hard that their output collapses. Detection never changes the simulation,
 * it reports where the current is going so the cause can be found (and fixed) by a player.
 */
public final class ShortCircuitDetector {
    private static final int LISTED_WIRES = 5;
    // A voltage source delivering more than this share of its short circuit current is considered collapsed.
    private static final double COLLAPSE_RATIO = 0.5;
    // Consecutive positive checks before the report, a single spike while the network settles is not a short.
    private static final int CONFIRMATIONS = 2;
    // Remind about a persisting short every this many checks.
    private static final int REMINDER_CHECKS = 12;

    private ShortCircuitDetector() {
    }

    private record Hot(AbstractElectricWire wire, double current) { }

    /**
     * Analyses a solved network.
     * @param limit Current (in amps) above which a wire is considered to be shorted.
     * @return A description of the problem, or null if the network is healthy (or can't be judged yet).
     */
    @Nullable
    public static String analyze(ElectricalNetwork network, @Nullable Level level, double limit) {
        if(!network.isConverged() || network.isDirty())
            return null;

        var hot = new ArrayList<Hot>();
        for(var wire : network.getWires()) {
            // Eliminated leaf nodes aren't solved, their wires have no meaningful current.
            if((wire.getNode1() != null && network.isLeaf(wire.getNode1())) || (wire.getNode2() != null && network.isLeaf(wire.getNode2())))
                continue;
            double current;
            try {
                current = Math.abs(wire.current());
            } catch(RuntimeException e) {
                continue;
            }
            if(Double.isFinite(current) && current > limit)
                hot.add(new Hot(wire, current));
        }

        var collapsed = new ArrayList<String>();
        for(var node : network.getNodes()) {
            if(!(node instanceof VoltageSourceCoupling source))
                continue;
            double emf = Math.abs(source.getVoltage());
            double current = Math.abs(source.getCurrent());
            if(emf > 1 && source.getResistance() > 0 && current * source.getResistance() > emf * COLLAPSE_RATIO) {
                collapsed.add(String.format("%s(emf=%.1fV, R=%.3f, delivering %.1fA of a possible %.1fA)", source.getClass().getSimpleName(),
                        emf, source.getResistance(), current, emf / source.getResistance()));
            }
        }

        if(hot.isEmpty() && collapsed.isEmpty())
            return null;

        hot.sort(Comparator.comparingDouble(Hot::current).reversed());
        var text = new StringBuilder();
        if(!hot.isEmpty()) {
            text.append(hot.size()).append(" wires carry more than ").append(String.format("%.0f", limit)).append(" A: ");
            for(int i = 0; i < Math.min(LISTED_WIRES, hot.size()); ++i) {
                if(i > 0)
                    text.append("; ");
                var entry = hot.get(i);
                text.append(String.format("%s %.1fA between [%s] and [%s]", entry.wire(), entry.current(),
                        GridDiagnostics.locate(network, entry.wire().getNode1(), level),
                        GridDiagnostics.locate(network, entry.wire().getNode2(), level)));
            }
        }
        if(!collapsed.isEmpty()) {
            if(!text.isEmpty())
                text.append(". ");
            text.append("Voltage sources being driven into a short: ").append(String.join(", ", collapsed));
        }
        text.append(". Network: ").append(network.describe());
        return text.toString();
    }

    /**
     * Called regularly for every network of a level, reports shorts which persist for a few checks.
     */
    public static void check(ElectricalNetwork network, @Nullable Level level, double limit) {
        var finding = analyze(network, level, limit);
        if(finding == null) {
            // Not converged networks can't be judged, they keep their count, healthy ones start over.
            if(network.isConverged() && !network.isDirty())
                network.setShortStreak(0);
            return;
        }
        int streak = network.getShortStreak() + 1;
        network.setShortStreak(streak);
        if(streak == CONFIRMATIONS || (streak > CONFIRMATIONS && (streak - CONFIRMATIONS) % REMINDER_CHECKS == 0)) {
            RateLimitedLog.warn(PowerGrid.LOGGER, "short-circuit-" + System.identityHashCode(network),
                    "Possible SHORT CIRCUIT in {}: {}", level == null ? "a level" : level.dimension().location(), finding);
        }
    }

    /**
     * Analyses every network of a level right now, used by the debug command.
     */
    public static List<String> scan(WorldNetworks networks, double limit) {
        var findings = new ArrayList<String>();
        for(var network : networks.subnetworks) {
            var finding = analyze(network, networks.world, limit);
            if(finding != null)
                findings.add(finding);
        }
        return findings;
    }
}

package org.patryk3211.powergrid.electricity;

import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;

/**
 * Lets overload protection (breakers, fuses) ride out the surge which follows a change in the network, for
 * instance when a long transmission line is switched in and charges up. Such a surge is several times the normal
 * current but it is over within a few ticks and harms nothing, tripping on it would make the line impossible to
 * energise. A real overload outlasts the settling time and is then handled as usual.
 */
public final class InrushGuard {
    // Ticks after a structure change of the network during which the readings are not representative.
    public static final int SETTLING_TICKS = 8;
    // A network which keeps changing must not be able to hide an overload for ever.
    public static final int MAX_IGNORED_TICKS = 20;

    private int ignored;

    /**
     * @param recentlySwitched True if the protected device was switched on just now.
     * @return True if an overcurrent reading taken now must not count.
     */
    public boolean ignores(@Nullable ElectricalNetwork network, boolean recentlySwitched) {
        boolean settling = recentlySwitched || (network != null && network.ticksSinceStructureChange() < SETTLING_TICKS);
        if(settling && ignored < MAX_IGNORED_TICKS) {
            ++ignored;
            return true;
        }
        if(!settling)
            ignored = 0;
        return false;
    }

    public void reset() {
        ignored = 0;
    }
}

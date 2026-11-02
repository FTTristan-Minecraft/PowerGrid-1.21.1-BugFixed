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
    // Ticks after the device was switched on which are ridden out as well.
    public static final int SWITCHING_TICKS = 10;
    // A network which keeps changing must not be able to hide an overload for ever. This has to stay below the
    // time a protected device survives a short, or it would be destroyed before the protection reacts.
    public static final int MAX_IGNORED_TICKS = 12;
    // Game time of "never", far enough in the past to not count as recent (Long.MIN_VALUE would overflow).
    public static final long NEVER = -1_000_000L;

    private int ignored;

    /**
     * @param now Current game time.
     * @param switchedOnAt Game time the protected device was switched on, {@link #NEVER} if it wasn't.
     * @return True if an overcurrent reading taken now must not count.
     */
    public boolean ignores(@Nullable ElectricalNetwork network, long now, long switchedOnAt) {
        boolean recentlySwitched = now >= switchedOnAt && now - switchedOnAt < SWITCHING_TICKS;
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

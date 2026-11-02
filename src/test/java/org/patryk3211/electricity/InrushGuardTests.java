package org.patryk3211.electricity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.InrushGuard;

public class InrushGuardTests extends TestHelper {
    private Network settledNetwork() {
        var Net = new Network();
        var V1 = Net.V(5);
        var N1 = Net.N();
        Net.W(1f, V1, N1);
        for(int i = 0; i < 20; ++i)
            Net.calculate();
        return Net;
    }

    @Test
    void settledNetworkIsNotIgnored() {
        var Net = settledNetwork();
        Assertions.assertFalse(new InrushGuard().ignores(Net.network, false));
    }

    @Test
    void readingsRightAfterAChangeAreIgnoredUntilItSettles() {
        var Net = settledNetwork();
        var guard = new InrushGuard();
        Net.network.setDirty();
        Assertions.assertTrue(guard.ignores(Net.network, false), "A fresh change must be ridden out");
        for(int i = 0; i < InrushGuard.SETTLING_TICKS + 1; ++i)
            Net.calculate();
        Assertions.assertFalse(guard.ignores(Net.network, false), "Once settled an overload counts again");
    }

    @Test
    void aNetworkWhichKeepsChangingCannotHideAnOverload() {
        var Net = settledNetwork();
        var guard = new InrushGuard();
        int ignored = 0;
        for(int i = 0; i < 100; ++i) {
            Net.network.setDirty();
            if(guard.ignores(Net.network, false))
                ++ignored;
        }
        Assertions.assertEquals(InrushGuard.MAX_IGNORED_TICKS, ignored);
    }

    @Test
    void switchingOnIsRiddenOutEvenInASettledNetwork() {
        var Net = settledNetwork();
        Assertions.assertTrue(new InrushGuard().ignores(Net.network, true));
    }
}

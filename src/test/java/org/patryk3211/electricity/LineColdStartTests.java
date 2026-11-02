package org.patryk3211.electricity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.sim.special.TransmissionLinePort;

public class LineColdStartTests extends TestHelper {
    private double peakCurrent(boolean farLoad) {
        final float Z = 0.2265f;
        var Net1 = new Network(true);
        var Net2 = new Network(true);
        var V1 = Net1.V(234f, 0.5f);
        var N1 = Net1.N();
        var N2 = Net2.N();
        var T1 = new TransmissionLinePort(N1, Z, null);
        var T2 = new TransmissionLinePort(N2, Z, null);
        T1.other = T2;
        T2.other = T1;
        Net1.W(0.1f, V1, N1);
        Net1.network.addNode(T1);
        Net2.network.addNode(T2);
        if(farLoad)
            Net2.W(100f, N2, null);

        double peak = 0;
        for(int i = 0; i < 60; ++i) {
            Net1.calculate();
            Net2.calculate();
            peak = Math.max(peak, Math.abs(V1.getCurrent()));
        }
        Assertions.assertEquals(farLoad ? 234 * 100 / (100 + 0.1 + 0.5 + 2 * Z) : 234, N2.getVoltage(), 2.0, "Far end must reach the line voltage");
        return peak;
    }

    @Test
    void openLineDoesNotDrawASurgeWhenEnergised() {
        // Without the fix this was a few hundred amps for a couple of ticks.
        Assertions.assertTrue(peakCurrent(false) < 15, "Line surge was too high");
    }

    @Test
    void loadedLineStillCarriesItsLoad() {
        var peak = peakCurrent(true);
        Assertions.assertTrue(peak > 1.0 && peak < 15, "Load current out of range: " + peak);
    }
}

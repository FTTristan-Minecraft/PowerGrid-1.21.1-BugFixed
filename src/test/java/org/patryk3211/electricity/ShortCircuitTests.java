package org.patryk3211.electricity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.ShortCircuitDetector;

public class ShortCircuitTests extends TestHelper {
    @Test
    void normalLoadIsNotAShort() {
        var Net = new Network();
        var V1 = Net.V(230, 0.1f);
        var N1 = Net.N();
        Net.W(0.5f, V1, N1);
        Net.W(10f, N1, null);
        for(int i = 0; i < 3; ++i)
            Net.calculate();

        Assertions.assertNull(ShortCircuitDetector.analyze(Net.network, null, 100), "A 20 A load must not be reported");
    }

    @Test
    void sourceDrivenIntoAShortIsReported() {
        var Net = new Network();
        var V1 = Net.V(230, 0.1f);
        var N1 = Net.N();
        Net.W(0.001f, V1, N1);
        Net.W(0.001f, N1, null);
        for(int i = 0; i < 3; ++i)
            Net.calculate();

        var finding = ShortCircuitDetector.analyze(Net.network, null, 100);
        Assertions.assertNotNull(finding, "A direct short must be reported");
        Assertions.assertTrue(finding.contains("short"), finding);
    }

    @Test
    void networkWhichIsStillSettlingIsNotJudged() {
        var Net = new Network();
        var V1 = Net.V(230, 0.1f);
        var N1 = Net.N();
        Net.W(0.001f, V1, N1);
        Net.W(0.001f, N1, null);
        // Nothing has been solved yet, there is no state to judge.
        Assertions.assertNull(ShortCircuitDetector.analyze(Net.network, null, 100));
    }
}

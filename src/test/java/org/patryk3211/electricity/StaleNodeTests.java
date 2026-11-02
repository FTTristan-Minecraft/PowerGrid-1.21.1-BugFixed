package org.patryk3211.electricity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.utility.RateLimitedLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

public class StaleNodeTests extends TestHelper {
    @Test
    void foreignNodesAreDroppedInsteadOfReportedEveryTick() {
        var Net = new Network();
        var V1 = Net.V(5);
        var N1 = Net.N();
        var stale = Net.N();
        Net.W(1f, V1, N1);
        Net.W(1f, N1, null);

        var before = Net.network.size();
        // The node is replaced somewhere else while the network still lists it.
        stale.setNetwork(null);

        for(int i = 0; i < 5; ++i)
            Net.calculate();

        Assertions.assertEquals(before - 1, Net.network.size(), "Stale node wasn't dropped");
        Assertions.assertEquals(2.5, N1.getVoltage(), 1e-6, "Remaining network doesn't solve correctly");
    }

    @Test
    void healthyNetworkNeedsNoRepairs() {
        var Net = new Network();
        var V1 = Net.V(5);
        var N1 = Net.N();
        Net.W(1f, V1, N1);
        Net.W(1f, N1, null);
        Net.calculate();

        Assertions.assertEquals(0, Net.network.audit(), "A consistent network must not be touched");
        Net.calculate();
        Assertions.assertEquals(2.5, N1.getVoltage(), 1e-6);
    }

    @Test
    void wireOwnedByAnotherNetworkIsForgotten() {
        var Net = new Network();
        var V1 = Net.V(5);
        var N1 = Net.N();
        Net.W(1f, V1, N1);
        var load = Net.W(1f, N1, null);
        Net.calculate();

        // Somebody else took the wire, this network still lists it.
        load.setNetwork(null);
        Assertions.assertTrue(Net.network.audit() > 0, "Stale wire wasn't detected");
        Assertions.assertEquals(0, Net.network.audit(), "Repairs must be complete");
        Net.calculate();
    }

    @Test
    void clearedNetworkDoesNotKeepOldSourceCounts() {
        var Net = new Network();
        var V1 = Net.V(5);
        var N1 = Net.N();
        Net.W(1f, V1, N1);
        Net.calculate();

        Net.network.clear();
        // The new content has no source in it.
        var N2 = Net.N();
        Net.W(1f, N2, null);
        Assertions.assertEquals(0, Net.network.audit(), "Counters of the old content must not survive clear()");
        Net.calculate();
        Assertions.assertEquals(0, N2.getVoltage(), 1e-9, "A network without sources must stay at zero");
    }

    @Test
    void repeatedMessagesAreRateLimited() {
        var logger = LoggerFactory.getLogger(StaleNodeTests.class);
        var count = new AtomicInteger();
        Logger counting = new CountingLogger(logger, count);
        for(int i = 0; i < 10_000; ++i)
            RateLimitedLog.warn(counting, "test-flood", "flood {}", i);
        Assertions.assertTrue(count.get() <= 5, "Flood wasn't limited, " + count.get() + " lines written");
    }

    private static class CountingLogger extends org.slf4j.helpers.AbstractLogger {
        private final Logger delegate;
        private final AtomicInteger count;

        CountingLogger(Logger delegate, AtomicInteger count) {
            this.delegate = delegate;
            this.count = count;
        }

        @Override protected String getFullyQualifiedCallerName() { return null; }
        @Override protected void handleNormalizedLoggingCall(org.slf4j.event.Level level, org.slf4j.Marker marker, String messagePattern, Object[] arguments, Throwable throwable) { count.incrementAndGet(); }
        @Override public boolean isTraceEnabled() { return false; }
        @Override public boolean isTraceEnabled(org.slf4j.Marker marker) { return false; }
        @Override public boolean isDebugEnabled() { return false; }
        @Override public boolean isDebugEnabled(org.slf4j.Marker marker) { return false; }
        @Override public boolean isInfoEnabled() { return true; }
        @Override public boolean isInfoEnabled(org.slf4j.Marker marker) { return true; }
        @Override public boolean isWarnEnabled() { return true; }
        @Override public boolean isWarnEnabled(org.slf4j.Marker marker) { return true; }
        @Override public boolean isErrorEnabled() { return true; }
        @Override public boolean isErrorEnabled(org.slf4j.Marker marker) { return true; }
        @Override public String getName() { return delegate.getName(); }
    }
}

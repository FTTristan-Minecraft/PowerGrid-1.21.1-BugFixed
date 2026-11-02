/*
 * Copyright 2025 patryk3211
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.patryk3211.powergrid.electricity.sim.special;

import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.solver.IOuterHook;
import org.patryk3211.powergrid.electricity.sim.solver.IResidualAdder;
import org.patryk3211.powergrid.electricity.sim.solver.ISolverHook;

public class TransmissionLinePort extends VoltageSourceCoupling implements IOuterHook, ISolverHook {
    private final TransmissionLine line;
    public TransmissionLinePort other;
    public boolean solved = false;

    // While the two ends of a line don't know each other's voltage yet, the port is practically disconnected.
    // Otherwise a line which starts uncharged looks like a dead short to everything feeding it.
    private static final float OPEN_RESISTANCE = 1.0e6f;
    // Exchanges to wait for an energised end before the line is connected anyway.
    private static final int MAX_UNSYNCED_EXCHANGES = 100;

    double I, V;
    private double Ieq;
    private float lineResistance;
    private boolean synced = false;
    private int unsyncedExchanges = 0;
    // Own network stamp at the last exchange of voltages with the other end.
    private int lastExchangeStamp;

    public TransmissionLinePort(IElectricNode node, float resistance, TransmissionLine line) {
        this(node, null, resistance, line);
    }

    public TransmissionLinePort(IElectricNode node, IElectricNode tie, float resistance, TransmissionLine line) {
        super(node, tie, resistance);
        this.line = line;
        this.lineResistance = resistance;
        super.setResistance(OPEN_RESISTANCE);
        V = node.getVoltage();
    }

    /**
     * Connects the port with the resistance of the line. Used when both ends are known to be energised already.
     */
    public void markSynced() {
        if(synced)
            return;
        synced = true;
        super.setResistance(lineResistance);
    }

    public boolean isSynced() {
        return synced;
    }

    @Override
    public void setResistance(float resistance) {
        lineResistance = resistance;
        if(synced)
            super.setResistance(resistance);
    }

    /**
     * Brings an unconnected pair of ports into operation without a surge. The end which is not charged
     * is set to the voltage of the one which is, then the pair is connected with no current flowing in it.
     */
    private void synchronizeEnds() {
        var thisVoltage = positive.getVoltage();
        var otherVoltage = other.positive.getVoltage();
        var high = Math.max(Math.abs(thisVoltage), Math.abs(otherVoltage));
        ++unsyncedExchanges;
        ++other.unsyncedExchanges;
        // Nothing is energised yet, there is nothing to connect.
        if(high < 1.0 && unsyncedExchanges < MAX_UNSYNCED_EXCHANGES)
            return;

        var thisEmf = thisVoltage;
        var otherEmf = otherVoltage;
        if(Math.abs(thisVoltage - otherVoltage) > Math.max(5.0, 0.1 * high)) {
            // One end is not charged (cold start, a line to a fresh island). Both ports take the voltage of the
            // charged end as their source voltage, the line then charges without drawing a surge from its feed.
            var target = Math.abs(thisVoltage) >= Math.abs(otherVoltage) ? thisVoltage : otherVoltage;
            thisEmf = otherEmf = target;
        }
        V = thisEmf;
        other.V = otherEmf;
        I = 0;
        other.I = 0;
        markSynced();
        other.markSynced();
        lastExchangeStamp = getStamp();
        other.lastExchangeStamp = other.getStamp();
    }

    @Override
    public void preSolve() {
        setVoltage(V);
    }

    @Override
    public void postUpperSolve() {
        solved = true;
        if(other.solved) {
            solved = false;
            other.solved = false;

            if(!synced || !other.synced) {
                synchronizeEnds();
                return;
            }

            I = getCurrent();
            other.I = other.getCurrent();
            var I = this.I + other.I;

            V = other.positive.getVoltage() + I * lineResistance;
            other.V = positive.getVoltage() + I * lineResistance;
            lastExchangeStamp = getStamp();
            other.lastExchangeStamp = other.getStamp();
        }
    }

    @Override
    public void startIteration(int iteration) {
        if(!synced) {
            Ieq = 0;
            return;
        }
        var correction = -(getCurrent() - this.I) * lineResistance;
        Ieq = correction;
    }

    @Override
    public void addResidual(IResidualAdder residual) {
        residual.add(index, Ieq);
    }

    @Override
    public String toString() {
        return String.format("TransmissionLinePort(Z=%g%s, line=%s)", lineResistance, synced ? "" : ", not connected yet", line == null ? "none" : line.getId());
    }

    /**
     * State of this end of a line for diagnostics, includes what it knows about the far end.
     */
    public String describeEnds() {
        var far = other == null ? null : other.getNetwork();
        return String.format("%s[emf=%.2fV, I=%.2fA, far node=%.2fV, far I=%.2fA, last exchange %d ticks ago, far network: %s]",
                this, V, I, other == null ? Double.NaN : other.positive.getVoltage(), other == null ? Double.NaN : other.I,
                getStamp() - lastExchangeStamp, far == null ? "NONE" : far.describe());
    }

    public TransmissionLinePort getOther() {
        return other;
    }

    public TransmissionLine getLine() {
        return line;
    }
}

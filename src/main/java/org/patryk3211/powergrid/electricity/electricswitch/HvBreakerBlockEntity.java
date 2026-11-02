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
package org.patryk3211.powergrid.electricity.electricswitch;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.lang.LangBuilder;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.collections.ModdedSoundEvents;
import org.patryk3211.powergrid.config.ThermalValues;
import org.patryk3211.powergrid.PowerGrid;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.GridDiagnostics;
import org.patryk3211.powergrid.electricity.InrushGuard;
import org.patryk3211.powergrid.utility.RateLimitedLog;
import org.patryk3211.powergrid.electricity.base.ThermalBehaviour;
import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.kinetics.base.ElectricKineticBlockEntity;
import org.patryk3211.powergrid.utility.Lang;

import java.util.List;

public class HvBreakerBlockEntity extends ElectricKineticBlockEntity {
    @Nullable
    private SwitchedWire wire;
    private ScrollValueBehaviour setting;

    protected LerpedFloat charge;
    protected boolean prevState, state, wasCharged;
    private boolean redstoneState;
    private int splitCooldown;
    private int overcurrentTicks;
    // Game time which is long enough ago to never count as recent, Long.MIN_VALUE would overflow when subtracted from.
    private static final long NEVER = InrushGuard.NEVER;
    private long lastOvercurrentTime = NEVER;
    // Highest current seen during the running overcurrent streak, reported when the breaker trips.
    private double overcurrentPeak;
    // Sum of ((I / limit)^2 - 1) over the streak, a measure of how much heat the overload has put into the breaker.
    private double overcurrentHeat;
    // A heavy overload must open the breaker long before the 5 tick delay, which it wouldn't survive (it has
    // very little thermal mass). 4 means: 2.2 times the trip current opens it at once, 1.5 times after 4 ticks.
    private static final double TRIP_HEAT = 4.0;
    // Switching a line in makes it draw a surge for a few ticks, the trip must not react to that (the breaker
    // has the thermal capacity to ride it out). After this many ticks of settling a real overload trips.
    private long closedAt = NEVER;
    private final InrushGuard inrushGuard = new InrushGuard();

    public HvBreakerBlockEntity(BlockEntityType<?> typeIn, BlockPos pos, BlockState state) {
        super(typeIn, pos, state);
        charge = LerpedFloat.linear().startWithValue(0).chase(0, 0, LerpedFloat.Chaser.LINEAR);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        setting = new HvBreakerScrollBehaviour(Lang.translateDirect("gui.hv_breaker.setting"), this, new Box());
        setting.setValue(0);
        behaviours.add(setting);
    }

    // The trip current can be set up to this value, the breaker has to be able to carry it.
    private static final int MAX_TRIP_CURRENT = 100;
    // Closing onto a transmission line (or any other capacitive load) draws a few ticks of several times the
    // normal current. A breaker which can't absorb that would be destroyed every time it is switched in.
    private static final float MIN_THERMAL_MASS = 20.0f;

    @Override
    public @Nullable ThermalBehaviour specifyThermalBehaviour() {
        var block = getBlockState().getBlock();
        // The configured values are a floor, below it the breaker couldn't carry the current it is meant to trip at.
        float ratedPower = MAX_TRIP_CURRENT * MAX_TRIP_CURRENT * resistance();
        float mass = Math.max(ThermalValues.getMass(block), MIN_THERMAL_MASS);
        float power = Math.max(ThermalValues.getPower(block), ratedPower);
        return ThermalBehaviour.forMaxPower(this, mass, power);
    }

    @Override
    public void onSpeedChanged(float previousSpeed) {
        super.onSpeedChanged(previousSpeed);
        charge.chase(1, getChaseSpeed(), LerpedFloat.Chaser.LINEAR);
        if(state)
            return;
        sendData();
    }

    private float getChaseSpeed() {
        return Mth.clamp(Math.abs(getSpeed()) / 80 / 20, 0, 1);
    }

    @Override
    public void initialize() {
        assert level != null;
        super.initialize();
        redstoneState = level.getBestNeighborSignal(worldPosition) > 0;
        // A breaker which is loaded closed energises its lines right now, that is a switching surge as well.
        closedAt = level.getGameTime();
    }

    /**
     * Opens or closes the breaker, closing requires the breaker to be charged just like a redstone trigger does.
     * @return true if the breaker is in the requested state afterwards.
     */
    public boolean setClosed(boolean closed) {
        if (state == closed) {
            return true;
        }
        if (level == null || level.isClientSide || (closed && !isCharged())) {
            return false;
        }
        state = closed;
        resetOvercurrent();
        if(closed)
            closedAt = level.getGameTime();
        if(wire != null) {
            wire.setState(state);
        } else {
            electricBehaviour.rebuildCircuit(false);
        }
        charge.setValueNoUpdate(state ? 1 : 0);
        (state ? ModdedSoundEvents.BREAKER_ON : ModdedSoundEvents.BREAKER_OFF)
                .playOnServer(level, worldPosition);
        notifyUpdate();
        return true;
    }

    public boolean toggle() {
        return setClosed(!state);
    }

    public boolean isClosed() {
        return state;
    }

    public boolean isCharged() {
        return charge.getValue() == 1;
    }

    public int getTripCurrent() {
        return setting.getValue();
    }

    public void setTripCurrent(int current) {
        setting.setValue(current);
    }

    public void trigger() {
        assert level != null;
        var redstone = level.getBestNeighborSignal(worldPosition) > 0;
        if(redstone && !redstoneState && (charge.getValue() == 1 || state)) {
            toggle();
        }
        redstoneState = redstone;
    }

    @Override
    public boolean usePersistentSimulation() {
        return true;
    }

    private void resetOvercurrent() {
        overcurrentTicks = 0;
        overcurrentPeak = 0;
        overcurrentHeat = 0;
        lastOvercurrentTime = NEVER;
    }

    private void simulationProtectionTick(String origin) {
        if(level == null || level.isClientSide)
            return;
        if(wire == null || !state || setting.getValue() == 0) {
            // Nothing to protect (or nothing to protect against), a streak must not survive that.
            resetOvercurrent();
            return;
        }
        if(!wire.isConverged())
            return;

        var now = level.getGameTime();
        var current = Math.abs(wire.current());
        var limit = setting.getValue();
        if(current <= limit) {
            resetOvercurrent();
            inrushGuard.reset();
            return;
        }
        if(inrushGuard.ignores(wire.getNetwork(), now, closedAt)) {
            RateLimitedLog.warn(PowerGrid.LOGGER, "hv-breaker-ridethrough-" + worldPosition.asLong(),
                    "HV breaker at {} ({}) carries {} A, above its trip current of {} A, but the network is still settling "
                            + "(last change {} ticks ago), riding it out: {}",
                    GridDiagnostics.position(worldPosition), level.dimension().location(), String.format("%.1f", current), limit,
                    wire.getNetwork() == null ? -1 : wire.getNetwork().ticksSinceStructureChange(), GridDiagnostics.describe(wire, level));
            return;
        }
        // Readings which are far apart are not one streak, this keeps old counts from tripping the breaker later.
        if(now - lastOvercurrentTime > 20)
            resetOvercurrent();
        lastOvercurrentTime = now;
        overcurrentPeak = Math.max(overcurrentPeak, current);
        overcurrentHeat += (current / limit) * (current / limit) - 1;
        ++overcurrentTicks;

        if(overcurrentTicks == 1) {
            RateLimitedLog.warn(PowerGrid.LOGGER, "hv-breaker-overcurrent-" + worldPosition.asLong(),
                    "HV breaker at {} ({}) sees {} A, above its trip current of {} A (checked by {}): {}",
                    GridDiagnostics.position(worldPosition), level.dimension().location(),
                    String.format("%.3f", current), limit, origin, GridDiagnostics.describe(wire, level));
        }
        if(overcurrentTicks >= 5 || overcurrentHeat >= TRIP_HEAT) {
            RateLimitedLog.warn(PowerGrid.LOGGER, "hv-breaker-trip-" + worldPosition.asLong(),
                    "HV breaker at {} ({}) TRIPPED: current stayed above {} A for {} ticks, peak {} A, overload heat {} (checked by {}): {}",
                    GridDiagnostics.position(worldPosition), level.dimension().location(), limit, overcurrentTicks,
                    String.format("%.3f", overcurrentPeak), String.format("%.2f", overcurrentHeat), origin, GridDiagnostics.describe(wire, level));
            state = false;
            wire.setState(false);
            resetOvercurrent();
            charge.setValueNoUpdate(0);
            ModdedSoundEvents.BREAKER_OFF.playOnServer(level, worldPosition);
            notifyUpdate();
        }
    }

    @Override
    public void simulationTick() {
        simulationProtectionTick("world network (chunk not ticking)");
    }

    @Override
    public void tick() {
        assert level != null;
        applyPower(wire);

        super.tick();
        prevState = state;
        charge.tickChaser();

        if(getChaseSpeed() != 0 && !charge.settled()) {
            int soundRate = (int) (1 / (getChaseSpeed() * 10)) + 1;
            float volume = .25f;
            float pitch = charge.getValue() + 0.5f;
            if (((int) level.getGameTime()) % soundRate == 0)
                level.playSound(null, worldPosition, SoundEvents.WOODEN_BUTTON_CLICK_OFF, SoundSource.BLOCKS,
                        volume, pitch);
            wasCharged = false;
        } else if(charge.getValue() == 1 && !wasCharged) {
            level.playSound(null, worldPosition, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS,
                    .25f, 1.5f);
            level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
            wasCharged = true;
        }

        simulationProtectionTick("block entity tick");
        if(wire != null) {
            if (!state && splitCooldown++ >= 100) {
                GlobalElectricNetworks.getWorldNetworks(level).scheduleIslandDiscovery(wire.getNetwork());
                electricBehaviour.rebuildCircuit(false);
            }
        }
    }

    public int getSignal() {
        if(state)
            return 15;
        if(charge.getValue() == 1)
            return 1;
        return 0;
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.put("Charge", charge.writeNBT());
        compound.putBoolean("State", state);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        // Always force the value to be set
        charge.readNBT(compound.getCompound("Charge"), clientPacket);
        if(compound.contains("Charge"))
            charge.setValueNoUpdate(compound.getCompound("Charge").getFloat("Value"));
        state = compound.getBoolean("State");
        if(wire != null) {
            wire.setState(state);
        } else {
            electricBehaviour.rebuildCircuit(false);
        }
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        builder.setTerminalCount(2);
        if(state) {
            splitCooldown = 0;
            wire = builder.connectSwitch(resistance(), builder.terminalNode(0), builder.terminalNode(1), state);
        } else {
            wire = null;
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        Lang.translate("gui.hv_breaker.status")
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);

        LangBuilder line;
        if(state) {
            line = Lang.translate("gui.hv_breaker.closed")
                    .style(ChatFormatting.RED);
        } else {
            line = Lang.translate("gui.hv_breaker.open")
                    .style(ChatFormatting.GREEN);
        }
        if(charge.getValue() == 1) {
            line.add(Lang.text(" - ").style(ChatFormatting.DARK_GRAY))
                    .add(Lang.translate("gui.hv_breaker.charged").style(ChatFormatting.YELLOW));
        }
        line.forGoggles(tooltip, 1);
        return true;
    }

    public static class Box extends CenteredSideValueBoxTransform {
        public Box() {
            super((state, dir) -> dir == state.getValue(HvBreakerBlock.HORIZONTAL_FACING));
        }

        @Override
        protected Vec3 getSouthLocation() {
            return VecHelper.voxelSpace(8.0f, 7.0f, 15.5f);
        }
    }
}

package org.patryk3211.powergrid.compat.cc.breaker;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.electricity.electricswitch.HvBreakerBlockEntity;

public class HvBreakerPeripheral implements IPeripheral {
    private final HvBreakerBlockEntity breaker;

    public HvBreakerPeripheral(HvBreakerBlockEntity breaker) {
        this.breaker = breaker;
    }

    @Override
    public @NotNull String getType() {
        return "powergrid_hv_breaker";
    }

    @LuaFunction
    public boolean isClosed() {
        return breaker.isClosed();
    }

    @LuaFunction
    public boolean isOpen() {
        return !breaker.isClosed();
    }

    @LuaFunction
    public boolean isCharged() {
        return breaker.isCharged();
    }

    @LuaFunction
    public int tripCurrent() {
        return breaker.getTripCurrent();
    }

    @LuaFunction(mainThread = true)
    public void setTripCurrent(int current) throws LuaException {
        if (current < 0) {
            throw new LuaException("Trip current must not be negative");
        }
        breaker.setTripCurrent(current);
    }

    @LuaFunction(mainThread = true)
    public boolean open() {
        return breaker.setClosed(false);
    }

    @LuaFunction(mainThread = true)
    public boolean close() {
        return breaker.setClosed(true);
    }

    @LuaFunction(mainThread = true)
    public boolean toggle() {
        return breaker.toggle();
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        if (other instanceof HvBreakerPeripheral that) {
            return this.breaker == that.breaker;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return breaker.hashCode();
    }
}

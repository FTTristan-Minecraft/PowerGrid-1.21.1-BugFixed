package org.patryk3211.powergrid.electricity;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.PowerGrid;
import org.patryk3211.powergrid.collections.ModdedConfigs;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;

/**
 * Keeps chunks which contain devices that transmit or generate power (generators, transformers, breakers, fuses...)
 * loaded without ticking them. Such a device keeps its place in the grid and is simulated by the world networks,
 * while ordinary consumers in the same area stay paused (an open circuit) and put no load on the grid.
 * <p>
 * The tickets are persistent, so the loaded area survives server restarts. There is a single ticket per chunk,
 * owned by the chunk's origin, and it is reconciled with the devices actually in the chunk whenever a device
 * is added or removed and whenever the chunk is loaded.
 */
public final class GridChunkAnchors {
    public static final TicketController CONTROLLER = new TicketController(PowerGrid.asResource("grid_anchor"));

    private GridChunkAnchors() {
    }

    private static BlockPos owner(ServerLevel level, ChunkPos pos) {
        return new BlockPos(pos.getMinBlockX(), level.getMinBuildHeight(), pos.getMinBlockZ());
    }

    /**
     * @return The config value or null if it cannot be read yet (tickets are left untouched then).
     */
    @Nullable
    private static Boolean enabled() {
        try {
            var server = ModdedConfigs.server();
            return server == null ? null : server.electricity.keepTransmissionChunksLoaded.get();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static boolean isAnchor(BlockEntity entity, @Nullable BlockEntity excluded) {
        if(entity == excluded || entity.isRemoved())
            return false;
        var behaviour = BlockEntityBehaviour.get(entity, ElectricBehaviour.TYPE);
        return behaviour != null && behaviour.usesPersistentSimulation();
    }

    private static boolean hasAnchors(LevelChunk chunk, @Nullable BlockEntity excluded) {
        for(var entity : chunk.getBlockEntities().values()) {
            if(isAnchor(entity, excluded))
                return true;
        }
        return false;
    }

    /**
     * Makes sure the chunk of the given position is kept loaded if, and only if, it contains an anchoring device.
     * @param excluded Block entity to ignore (one which is being removed).
     */
    public static void refresh(@Nullable Level level, BlockPos position, @Nullable BlockEntity excluded) {
        if(!(level instanceof ServerLevel server))
            return;
        var chunkPos = new ChunkPos(position);
        var chunk = server.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
        if(chunk == null)
            return;
        refresh(server, chunk, excluded);
    }

    public static void refresh(ServerLevel level, LevelChunk chunk, @Nullable BlockEntity excluded) {
        var enabled = enabled();
        if(enabled == null)
            return;
        var anchored = enabled && hasAnchors(chunk, excluded);
        var pos = chunk.getPos();
        CONTROLLER.forceChunk(level, owner(level, pos), pos.x, pos.z, anchored, false);
    }

    /**
     * Called when a chunk finishes loading. Anchoring devices in chunks which don't tick would never be
     * initialized by regular ticking, so they join the grid here.
     */
    public static void chunkLoaded(ServerLevel level, LevelChunk chunk) {
        for(var entity : chunk.getBlockEntities().values()) {
            if(entity.isRemoved())
                continue;
            var behaviour = BlockEntityBehaviour.get(entity, ElectricBehaviour.TYPE);
            // If the chunk does tick, the first block entity tick takes over from the world networks.
            if(behaviour != null)
                behaviour.initializeWithoutTicking();
        }
        refresh(level, chunk, null);
    }
}

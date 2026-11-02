package org.patryk3211.powergrid.utility;

import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logging for conditions which can repeat every tick. A flood of log lines (or stack traces) on the server
 * thread can starve the whole server, so every message key is limited to a few lines per time window and the
 * number of suppressed lines is reported afterwards.
 */
public final class RateLimitedLog {
    private static final int LINES_PER_WINDOW = 3;
    private static final long WINDOW_NANOS = 30_000_000_000L;
    private static final int MAX_KEYS = 512;

    private static final class State {
        long windowStart;
        int lines;
        long suppressed;
    }

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();

    private RateLimitedLog() {
    }

    /**
     * @return How many lines were suppressed since the last allowed one, or -1 if this message must not be logged now.
     */
    private static long acquire(String key) {
        if(STATES.size() > MAX_KEYS)
            STATES.clear();
        var state = STATES.computeIfAbsent(key, $ -> new State());
        synchronized(state) {
            var now = System.nanoTime();
            if(state.lines == 0 || now - state.windowStart > WINDOW_NANOS) {
                state.windowStart = now;
                state.lines = 0;
            }
            if(state.lines >= LINES_PER_WINDOW) {
                ++state.suppressed;
                return -1;
            }
            ++state.lines;
            var suppressed = state.suppressed;
            state.suppressed = 0;
            return suppressed;
        }
    }

    public static void warn(Logger logger, String key, String message, Object... args) {
        if(logger == null) {
            System.err.println(key + ": " + message);
            return;
        }
        var suppressed = acquire(key);
        if(suppressed < 0)
            return;
        logger.warn(message, args);
        if(suppressed > 0)
            logger.warn("({} similar messages suppressed)", suppressed);
    }

    public static void error(Logger logger, String key, String message, Object... args) {
        if(logger == null) {
            System.err.println(key + ": " + message);
            return;
        }
        var suppressed = acquire(key);
        if(suppressed < 0)
            return;
        logger.error(message, args);
        if(suppressed > 0)
            logger.error("({} similar messages suppressed)", suppressed);
    }
}

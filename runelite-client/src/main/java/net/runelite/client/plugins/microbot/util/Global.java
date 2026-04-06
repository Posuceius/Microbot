package net.runelite.client.plugins.microbot.util;

import lombok.SneakyThrows;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.tick.TickDispatcher;
import net.runelite.client.plugins.microbot.util.tick.TickWaiter;

import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

public class Global {
    static ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(10);
    static ScheduledFuture<?> scheduledFuture;

    public static ScheduledFuture<?> awaitExecutionUntil(Runnable callback, BooleanSupplier awaitedCondition, int time) {
        scheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (awaitedCondition.getAsBoolean()) {
                scheduledFuture.cancel(true);
                scheduledFuture = null;
                callback.run();
            }
        }, 0, time, TimeUnit.MILLISECONDS);
        return scheduledFuture;
    }

    public static void sleep(int start) {
        if (Microbot.getClient().isClientThread()) return;
        try {
            Thread.sleep(start);
        } catch (InterruptedException ignored) {
            // ignore interrupted
        }
    }

    public static void sleep(int start, int end) {
        int randomSleep = Rs2Random.between(start, end);
        sleep(randomSleep);
    }

    public static void sleepGaussian(int mean, int stddev) {
        int randomSleep = Rs2Random.randomGaussian(mean, stddev);
        sleep(randomSleep);
    }

    @SneakyThrows
    public static <T> T sleepUntilNotNull(Callable<T> method, int timeoutMillis, int sleepMillis) {
        if (Microbot.getClient().isClientThread()) return null;
        boolean done;
        T methodResponse;
        final long endTime = System.currentTimeMillis()+timeoutMillis;
        do {
            methodResponse = method.call();
            done = methodResponse != null;
            sleep(sleepMillis);
        } while (!done && System.currentTimeMillis() < endTime);
        return methodResponse;
    }

    public static <T> T sleepUntilNotNull(Callable<T> method, int timeoutMillis) {
        return sleepUntilNotNull(method, timeoutMillis, 100);
    }

    /**
     * Polls until the supplied condition becomes true or timeout elapses.
     * Must not be invoked on the client thread; callers should run on script/executor threads.
     */
    public static boolean sleepUntil(BooleanSupplier awaitedCondition) {
        return sleepUntil(awaitedCondition, 5000);
    }

    /**
     * Polls until the supplied condition becomes true or timeout elapses.
     * Wakes on game tick boundaries for precise detection instead of polling every 100ms.
     * Must not be invoked on the client thread; callers should run on script/executor threads.
     */
    public static boolean sleepUntil(BooleanSupplier awaitedCondition, int time) {
        if (Microbot.getClient().isClientThread()) return false;
        long deadline = System.currentTimeMillis() + time;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (awaitedCondition.getAsBoolean()) return true;
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                if (!sleepUntilNextTick(Math.min(remaining, 600))) {
                    // Tick-based wake not available (dispatcher null or no tick fired within timeout).
                    // Fall back to a short sleep to prevent busy-spinning.
                    sleep(100);
                }
            }
            return awaitedCondition.getAsBoolean();
        } catch (Exception e) {
            Microbot.logStackTrace("Global Sleep: ", e);
        }
        return false;
    }

    public static boolean sleepUntil(BooleanSupplier awaitedCondition, Runnable action, long timeoutMillis, int sleepMillis) {
        if (Microbot.getClient().isClientThread()) return false;
        long startTime = System.nanoTime();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            while (System.nanoTime() - startTime < timeoutNanos) {
                if (awaitedCondition.getAsBoolean()) {
                    return true;
                }
				action.run();
                sleep(sleepMillis);
            }
        } catch (Exception e) {
            Microbot.logStackTrace("Global Sleep: ", e);
        }
        return false;
    }

    public static boolean sleepUntilTrue(BooleanSupplier awaitedCondition) {
        if (Microbot.getClient().isClientThread()) return false;
        long startTime = System.currentTimeMillis();
        try {
            do {
                if (awaitedCondition.getAsBoolean()) {
                    return true;
                }
                sleep(100);
            } while (System.currentTimeMillis() - startTime < 5000);
        } catch (Exception e) {
            Microbot.logStackTrace("Global Sleep: ", e);
        }
        return false;
    }

    public static boolean sleepUntilTrue(BooleanSupplier awaitedCondition, int time, int timeout) {
        if (Microbot.getClient().isClientThread()) return false;
        long startTime = System.currentTimeMillis();
        try {
            do {
                if (awaitedCondition.getAsBoolean()) {
                    return true;
                }
                sleep(time);
            } while (System.currentTimeMillis() - startTime < timeout);
        } catch (Exception e) {
            Microbot.logStackTrace("Global Sleep: ", e);
        }
        return false;
    }

    public static boolean sleepUntilTrue(BooleanSupplier awaitedCondition, BooleanSupplier resetCondition, int time, int timeout) {
        if (Microbot.getClient().isClientThread()) return false;
        long startTime = System.currentTimeMillis();
        try {
            do {
                if (resetCondition.getAsBoolean()) {
                    startTime = System.currentTimeMillis();
                }
                if (awaitedCondition.getAsBoolean()) {
                    return true;
                }
                sleep(time);
            } while (System.currentTimeMillis() - startTime < timeout);
        } catch (Exception e) {
            Microbot.logStackTrace("Global Sleep: ", e);
        }
        return false;
    }

    /**
     * Polls a condition on the client thread until true or timeout, without blocking the client thread itself.
     */
    public static void sleepUntilOnClientThread(BooleanSupplier awaitedCondition) {
        sleepUntilOnClientThread(awaitedCondition, Rs2Random.between(2500, 5000));
    }

    public static void sleepUntilOnClientThread(BooleanSupplier awaitedCondition, int time) {
        if (Microbot.getClient().isClientThread()) return;
        boolean done;
        long startTime = System.currentTimeMillis();
        try {
            do {
                done = Microbot.getClientThread().runOnClientThreadOptional(awaitedCondition::getAsBoolean).orElse(false);
            } while (!done && System.currentTimeMillis() - startTime < time);
        } catch (Exception e) {
            Microbot.logStackTrace("Global Sleep: ", e);
        }
    }

    public boolean sleepUntilTick(int ticksToWait) {
        int startTick = Microbot.getClient().getTickCount();
        return Global.sleepUntil(() -> Microbot.getClient().getTickCount() >= startTick + ticksToWait, ticksToWait * 600 + 2000);
    }

	/**
	 * Blocks the calling thread until the next game tick fires.
	 * No-op if called on the client thread.
	 */
	public static void sleepUntilNextTick() {
		sleepUntilNextTick(2000);
	}

	/**
	 * Blocks the calling thread until the next game tick fires, or the wall-clock timeout expires.
	 * No-op if called on the client thread.
	 *
	 * @param wallClockTimeoutMs maximum wall-clock time to wait in milliseconds
	 * @return true if a tick was received, false if timed out
	 */
	public static boolean sleepUntilNextTick(long wallClockTimeoutMs) {
		if (Microbot.getClient().isClientThread()) return false;
		TickDispatcher dispatcher = Microbot.getTickDispatcher();
		if (dispatcher == null) return false;
		TickWaiter waiter = dispatcher.registerWait(null, 1, wallClockTimeoutMs);
		return waiter.await();
	}

	/**
	 * Blocks the calling thread for exactly the given number of game ticks.
	 * No-op if called on the client thread.
	 *
	 * @param ticks number of game ticks to wait
	 * @return true if all ticks elapsed, false if timed out
	 */
	public static boolean sleepForTicks(int ticks) {
		return sleepForTicks(ticks, ticks * 800L + 2000);
	}

	/**
	 * Blocks the calling thread for exactly the given number of game ticks,
	 * or until the wall-clock timeout expires.
	 * No-op if called on the client thread.
	 *
	 * @param ticks number of game ticks to wait
	 * @param wallClockTimeoutMs maximum wall-clock time to wait in milliseconds
	 * @return true if all ticks elapsed, false if timed out
	 */
	public static boolean sleepForTicks(int ticks, long wallClockTimeoutMs) {
		if (Microbot.getClient().isClientThread()) return false;
		TickDispatcher dispatcher = Microbot.getTickDispatcher();
		if (dispatcher == null) return false;
		TickWaiter waiter = dispatcher.registerWait(null, ticks, wallClockTimeoutMs);
		return waiter.await();
	}

}

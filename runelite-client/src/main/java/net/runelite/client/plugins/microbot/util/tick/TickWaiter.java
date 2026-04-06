package net.runelite.client.plugins.microbot.util.tick;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * A thread synchronization primitive that allows a script thread to block until
 * a condition becomes true on a game tick, or until a timeout expires.
 *
 * <h3>Threading contract</h3>
 * <ul>
 *   <li>Each {@code TickWaiter} owns an independent {@link ReentrantLock} - there is no
 *       shared lock, so deadlock between waiters is impossible.</li>
 *   <li>The client thread calls {@link #evaluateAndSignal(int)} at most briefly: it
 *       acquires the lock only long enough to set state and call
 *       {@link Condition#signalAll()}, then releases immediately. It never blocks.</li>
 *   <li>The script thread calls {@link #await()}, which blocks on the waiter's own
 *       {@link Condition} until signaled or the wall-clock deadline passes.</li>
 * </ul>
 */
@Slf4j
public class TickWaiter
{
	/** Condition to evaluate each tick. {@code null} for unconditional waits. */
	private final BooleanSupplier condition;

	/** Maximum number of ticks to wait before timing out. 0 means no tick limit. */
	private final int maxTicks;

	/** Absolute wall-clock deadline in nanoseconds ({@link System#nanoTime} based). */
	private final long deadlineNanos;

	/** Tick count when this waiter was registered. */
	private final int startTick;

	/** Per-waiter lock. Never held while blocking - client thread acquires it only to signal. */
	private final ReentrantLock lock;

	/** Condition variable derived from {@link #lock}. Script thread waits on this. */
	private final Condition signal;

	/** Set to {@code true} once this waiter has been resolved (met, timed out, or errored). */
	private volatile boolean completed;

	/** {@code true} if the condition was actually evaluated and returned {@code true}. */
	private volatile boolean conditionMet;

	/**
	 * Creates a new {@code TickWaiter}.
	 *
	 * @param condition the condition to evaluate on each tick, or {@code null} for unconditional waits
	 * @param maxTicks maximum number of ticks to wait; 0 means rely solely on the wall-clock timeout
	 * @param wallClockTimeoutMs wall-clock timeout in milliseconds
	 * @param startTick the current tick count at registration time
	 */
	TickWaiter(BooleanSupplier condition, int maxTicks, long wallClockTimeoutMs, int startTick)
	{
		this.condition = condition;
		this.maxTicks = maxTicks;
		this.deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(wallClockTimeoutMs);
		this.startTick = startTick;
		this.lock = new ReentrantLock();
		this.signal = lock.newCondition();
		this.completed = false;
		this.conditionMet = false;
	}

	/**
	 * Evaluates the condition for the current tick and signals the waiting script thread if done.
	 * <p>
	 * Called on the client thread by {@code TickDispatcher}. This method acquires the lock only
	 * briefly to signal and then releases it immediately - it never blocks.
	 *
	 * @param currentTick the current global tick count
	 * @return {@code true} if this waiter is now complete and should be removed from the dispatcher,
	 *         {@code false} if the condition has not yet been met and the waiter should remain
	 */
	boolean evaluateAndSignal(int currentTick)
	{
		// Check condition first (even on the boundary tick)
		if (condition == null)
		{
			// For unconditional waits (sleepForTicks), DON'T resolve immediately.
			// Only the tick timeout check should resolve these.
			// Fall through to the timeout check below.
		}
		else
		{
			boolean result;
			try
			{
				result = condition.getAsBoolean();
			}
			catch (Exception conditionException)
			{
				log.error("TickWaiter condition threw an exception on tick {} - resolving as timed out", currentTick, conditionException);
				signalCompletion(false);
				return true;
			}

			if (result)
			{
				log.debug("TickWaiter condition met on tick {}", currentTick);
				signalCompletion(true);
				return true;
			}
		}

		// Tick timeout check - runs AFTER condition evaluation
		if (maxTicks > 0 && (currentTick - startTick) >= maxTicks)
		{
			log.debug("TickWaiter tick timeout reached after {} ticks (max={})", currentTick - startTick, maxTicks);
			signalCompletion(condition == null); // unconditional waits "succeed" on timeout
			return true;
		}

		return false;
	}

	/**
	 * Blocks the calling script thread until the condition is met or the wall-clock deadline expires.
	 *
	 * @return {@code true} if the condition was met, {@code false} if the wait timed out or was interrupted
	 */
	public boolean await()
	{
		lock.lock();
		try
		{
			while (!completed)
			{
				long remainingNanos = deadlineNanos - System.nanoTime();
				if (remainingNanos <= 0)
				{
					log.debug("TickWaiter wall-clock deadline expired before condition was met");
					completed = true;
					conditionMet = false;
					return false;
				}

				signal.await(remainingNanos, TimeUnit.NANOSECONDS);
			}

			return conditionMet;
		}
		catch (InterruptedException interruptedException)
		{
			log.debug("TickWaiter await() interrupted - returning false");
			Thread.currentThread().interrupt();
			return false;
		}
		finally
		{
			lock.unlock();
		}
	}

	/**
	 * Cancels this waiter, signaling completion with conditionMet=false.
	 * Used by TickDispatcher during cleanup (e.g., logout).
	 */
	void cancel()
	{
		signalCompletion(false);
	}

	/**
	 * Acquires the lock, sets completion state, and wakes all threads waiting on the condition.
	 * Called only from the client thread. Never blocks - the lock is released immediately.
	 *
	 * @param met whether the condition was evaluated and returned {@code true}
	 */
	private void signalCompletion(boolean met)
	{
		lock.lock();
		try
		{
			completed = true;
			conditionMet = met;
			signal.signalAll();
		}
		finally
		{
			lock.unlock();
		}
	}
}

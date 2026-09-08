package com.emyrk.banktags;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Deterministic {@link ScheduledExecutorService} for tests. Tasks are recorded with their due time on
 * a virtual clock; {@link #runDue(long)} advances the clock and runs everything that came due, on the
 * calling thread. Cancelled futures never run. Periodic tasks re-arm after each run.
 */
public final class FakeScheduler extends AbstractExecutorService implements ScheduledExecutorService
{
	private final List<Task> tasks = new ArrayList<>();
	private long nowSeconds;
	private boolean shutdown;

	/**
	 * Advances the virtual clock by {@code seconds} and runs every task due by then, in due order.
	 */
	public synchronized void runDue(long seconds)
	{
		nowSeconds += seconds;
		while (true)
		{
			Task next = null;
			for (Task task : tasks)
			{
				if (!task.cancelled && task.dueSeconds <= nowSeconds && (next == null || task.dueSeconds < next.dueSeconds))
				{
					next = task;
				}
			}
			if (next == null)
			{
				return;
			}
			if (next.periodSeconds > 0)
			{
				next.dueSeconds = nowSeconds + next.periodSeconds;
			}
			else
			{
				tasks.remove(next);
				next.done = true;
			}
			next.command.run();
		}
	}

	public synchronized int pendingCount()
	{
		int count = 0;
		for (Task task : tasks)
		{
			if (!task.cancelled)
			{
				count++;
			}
		}
		return count;
	}

	public synchronized long now()
	{
		return nowSeconds;
	}

	@Override
	public synchronized ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit)
	{
		Task task = new Task(command, nowSeconds + unit.toSeconds(delay), 0);
		tasks.add(task);
		return task;
	}

	@Override
	public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit)
	{
		return scheduleWithFixedDelay(command, initialDelay, period, unit);
	}

	@Override
	public synchronized ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit)
	{
		Task task = new Task(command, nowSeconds + unit.toSeconds(initialDelay), Math.max(1, unit.toSeconds(delay)));
		tasks.add(task);
		return task;
	}

	@Override
	public void execute(Runnable command)
	{
		command.run();
	}

	@Override
	public void shutdown()
	{
		shutdown = true;
	}

	@Override
	public List<Runnable> shutdownNow()
	{
		shutdown = true;
		return new ArrayList<>();
	}

	@Override
	public boolean isShutdown()
	{
		return shutdown;
	}

	@Override
	public boolean isTerminated()
	{
		return shutdown;
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit)
	{
		return true;
	}

	private final class Task implements ScheduledFuture<Object>
	{
		private final Runnable command;
		private long dueSeconds;
		private final long periodSeconds;
		private boolean cancelled;
		private boolean done;

		Task(Runnable command, long dueSeconds, long periodSeconds)
		{
			this.command = command;
			this.dueSeconds = dueSeconds;
			this.periodSeconds = periodSeconds;
		}

		@Override
		public long getDelay(TimeUnit unit)
		{
			return unit.convert(dueSeconds - nowSeconds, TimeUnit.SECONDS);
		}

		@Override
		public int compareTo(Delayed other)
		{
			return Long.compare(getDelay(TimeUnit.SECONDS), other.getDelay(TimeUnit.SECONDS));
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning)
		{
			synchronized (FakeScheduler.this)
			{
				if (done || cancelled)
				{
					return false;
				}
				cancelled = true;
				tasks.remove(this);
				return true;
			}
		}

		@Override
		public boolean isCancelled()
		{
			return cancelled;
		}

		@Override
		public boolean isDone()
		{
			return done || cancelled;
		}

		@Override
		public Object get() throws ExecutionException
		{
			throw new UnsupportedOperationException("tests must not block on futures");
		}

		@Override
		public Object get(long timeout, TimeUnit unit) throws ExecutionException, TimeoutException
		{
			throw new UnsupportedOperationException("tests must not block on futures");
		}
	}
}

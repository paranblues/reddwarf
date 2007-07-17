/*
 * Copyright 2007 Sun Microsystems, Inc. All rights reserved
 */

package com.sun.sgs.impl.kernel.schedule;

import com.sun.sgs.app.ExceptionRetryStatus;

import com.sun.sgs.impl.kernel.TaskHandler;

import com.sun.sgs.impl.sharedutil.LoggerWrapper;

import com.sun.sgs.kernel.ProfileCollector;
import com.sun.sgs.kernel.TaskOwner;

import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Package-private utility used to actually run a task and manage all of
 * the aspects of profiling and task retry.
 */
class TaskExecutor {

    // logger for this class
    private static final LoggerWrapper logger =
        new LoggerWrapper(Logger.getLogger(TaskExecutor.class.getName()));

    // the kernel's task handler used to set the task's owner
    private final TaskHandler taskHandler;

    // the single system scheduler
    private final SystemScheduler scheduler;

    // the optional collector of profiling data
    private final ProfileCollector collector;

    /**
     * Creates an instance of {@code TaskExecutor}.
     *
     * @param taskHandler the kernel's {@code TaskHandler}
     * @param scheduler the {@code SystemScheduler} providing the running tasks
     * @param collector the system's {@code ProfileCollector}, or {@code null}
     *                  if profiling is not enabled
     */
    TaskExecutor(TaskHandler taskHandler, SystemScheduler scheduler,
                 ProfileCollector collector) {
        if (taskHandler == null)
            throw new NullPointerException("A task handler must be provided");
        if (scheduler == null)
            throw new NullPointerException("A scheduler must be provided");

        this.taskHandler = taskHandler;
        this.scheduler = scheduler;
        this.collector = collector;
    }

    /**
     * Runs the given task, optionally retrying if the task fails.
     *
     * @param task the {@code ScheduledTask} to run
     * @param retry {@code true} if the task should be re-tried when it
     *              fails with {@code ExceptionRetryStatus} requesting the
     *              task be re-tried, {@code false} otherwise
     *
     * @throws InterruptedException if the thread is interrupted while
     *                              executing the task
     * @throws Exception if any exception occurs while executing the task
     *                   and {@code retry} is {@code false}
     */
    void runTask(ScheduledTask task, boolean retry)
        throws InterruptedException, Exception
    {
        for (int tryCount = 1; ; tryCount++) {
            try {
                if (collector != null) {
                    TaskOwner owner = task.getOwner();
                    int ready = scheduler.getReadyCount(owner.getContext());
                    collector.startTask(task.getTask(), owner,
                                        task.getStartTime(), ready);
                }

                taskHandler.runTaskAsOwner(task.getTask(), task.getOwner());

                if (collector != null)
                    collector.finishTask(tryCount, true);

                return;
            } catch (InterruptedException ie) {
                if (collector != null)
                    collector.finishTask(tryCount, false);

                if (logger.isLoggable(Level.WARNING))
                    logger.logThrow(Level.WARNING, ie, "skipping a task " +
                                    "that was interrupted: {0}", task);

                throw ie;
            } catch (Exception e) {
                if (collector != null)
                    collector.finishTask(tryCount, false);

                if (! retry)
                    throw e;

                if ((e instanceof ExceptionRetryStatus) &&
                    (((ExceptionRetryStatus)e).shouldRetry())) {
                    // NOTE: we're not doing anything fancy here to guess
                    // about re-scheduling, adding right to the ready queue,
                    // etc...this is just re-running in place until the task
                    // is done, but this is one of the first issues that will
                    // be investigated for optimization
                } else {
                    if (logger.isLoggable(Level.WARNING)) {
                        if (task.isRecurring()) {
                            logger.logThrow(Level.WARNING, e, "skipping a " +
                                            "recurrence of a task that " +
                                            "failed with a non-retryable " +
                                            "exception: {0}", task);
                        } else {
                            logger.logThrow(Level.WARNING, e, "dropping a " +
                                            "task that failed with a non-" +
                                            "retryable exception: {0}", task);
                        }
                    }

                    return;
                }
            }
        }
    }

}
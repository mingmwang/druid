/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.indexing.overlord;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.ByteSink;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closer;
import com.google.common.io.FileWriteMode;
import com.google.common.io.Files;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.inject.Inject;
import com.metamx.common.ISE;
import com.metamx.common.Pair;
import com.metamx.common.lifecycle.LifecycleStop;
import com.metamx.common.logger.Logger;
import com.metamx.emitter.EmittingLogger;
import io.druid.concurrent.Execs;
import io.druid.guice.annotations.Self;
import io.druid.indexing.common.TaskStatus;
import io.druid.indexing.common.config.TaskConfig;
import io.druid.indexing.common.task.Task;
import io.druid.indexing.common.tasklogs.LogUtils;
import io.druid.indexing.overlord.autoscaling.ScalingStats;
import io.druid.indexing.overlord.config.ForkingTaskRunnerConfig;
import io.druid.indexing.worker.config.WorkerConfig;
import io.druid.query.DruidMetrics;
import io.druid.server.DruidNode;
import io.druid.server.metrics.MonitorsConfig;
import io.druid.tasklogs.TaskLogPusher;
import io.druid.tasklogs.TaskLogStreamer;
import org.apache.commons.io.FileUtils;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Runs tasks in separate processes using the "internal peon" verb.
 */
public class ForkingTaskRunner implements TaskRunner, TaskLogStreamer
{
  private static final EmittingLogger log = new EmittingLogger(ForkingTaskRunner.class);
  private static final String CHILD_PROPERTY_PREFIX = "druid.indexer.fork.property.";
  private static final String TASK_RESTORE_FILENAME = "restore.json";
  private final ForkingTaskRunnerConfig config;
  private final TaskConfig taskConfig;
  private final Properties props;
  private final TaskLogPusher taskLogPusher;
  private final DruidNode node;
  private final ListeningExecutorService exec;
  private final ObjectMapper jsonMapper;
  private final PortFinder portFinder;

  // Writes must be synchronized. This is only a ConcurrentMap so "informational" reads can occur without waiting.
  private final Map<String, ForkingTaskRunnerWorkItem> tasks = Maps.newConcurrentMap();

  private volatile boolean stopping = false;

  @Inject
  public ForkingTaskRunner(
      ForkingTaskRunnerConfig config,
      TaskConfig taskConfig,
      WorkerConfig workerConfig,
      Properties props,
      TaskLogPusher taskLogPusher,
      ObjectMapper jsonMapper,
      @Self DruidNode node
  )
  {
    this.config = config;
    this.taskConfig = taskConfig;
    this.props = props;
    this.taskLogPusher = taskLogPusher;
    this.jsonMapper = jsonMapper;
    this.node = node;
    this.portFinder = new PortFinder(config.getStartPort());

    this.exec = MoreExecutors.listeningDecorator(
        Execs.multiThreaded(workerConfig.getCapacity(), "forking-task-runner-%d")
    );
  }

  @Override
  public List<Pair<Task, ListenableFuture<TaskStatus>>> restore()
  {
    final File restoreFile = getRestoreFile();
    final TaskRestoreInfo taskRestoreInfo;
    if (restoreFile.exists()) {
      try {
        taskRestoreInfo = jsonMapper.readValue(restoreFile, TaskRestoreInfo.class);
      }
      catch (Exception e) {
        log.error(e, "Failed to read restorable tasks from file[%s]. Skipping restore.", restoreFile);
        return ImmutableList.of();
      }
    } else {
      return ImmutableList.of();
    }

    final List<Pair<Task, ListenableFuture<TaskStatus>>> retVal = Lists.newArrayList();
    for (final String taskId : taskRestoreInfo.getRunningTasks()) {
      try {
        final File taskFile = new File(taskConfig.getTaskDir(taskId), "task.json");
        final Task task = jsonMapper.readValue(taskFile, Task.class);

        if (!task.getId().equals(taskId)) {
          throw new ISE("WTF?! Task[%s] restore file had wrong id[%s].", taskId, task.getId());
        }

        if (taskConfig.isRestoreTasksOnRestart() && task.canRestore()) {
          log.info("Restoring task[%s].", task.getId());
          retVal.add(Pair.of(task, run(task)));
        }
      }
      catch (Exception e) {
        log.warn(e, "Failed to restore task[%s]. Trying to restore other tasks.", taskId);
      }
    }

    log.info("Restored %,d tasks.", retVal.size());

    return retVal;
  }

  @Override
  public ListenableFuture<TaskStatus> run(final Task task)
  {
    synchronized (tasks) {
      if (!tasks.containsKey(task.getId())) {
        tasks.put(
            task.getId(),
            new ForkingTaskRunnerWorkItem(
                task,
                exec.submit(
                    new Callable<TaskStatus>()
                    {
                      @Override
                      public TaskStatus call()
                      {
                        final String attemptUUID = UUID.randomUUID().toString();
                        final File taskDir = taskConfig.getTaskDir(task.getId());
                        final File attemptDir = new File(taskDir, attemptUUID);

                        final ProcessHolder processHolder;
                        final int childPort;
                        final int childChatHandlerPort;

                        if (config.isSeparateIngestionEndpoint()) {
                          Pair<Integer, Integer> portPair = portFinder.findTwoConsecutiveUnusedPorts();
                          childPort = portPair.lhs;
                          childChatHandlerPort = portPair.rhs;
                        } else {
                          childPort = portFinder.findUnusedPort();
                          childChatHandlerPort = -1;
                        }

                        try {
                          final Closer closer = Closer.create();
                          try {
                            if (!attemptDir.mkdirs()) {
                              throw new IOException(String.format("Could not create directories: %s", attemptDir));
                            }

                            final File taskFile = new File(taskDir, "task.json");
                            final File statusFile = new File(attemptDir, "status.json");
                            final File logFile = new File(taskDir, "log");

                            // time to adjust process holders
                            synchronized (tasks) {
                              final ForkingTaskRunnerWorkItem taskWorkItem = tasks.get(task.getId());

                              if (taskWorkItem.shutdown) {
                                throw new IllegalStateException("Task has been shut down!");
                              }

                              if (taskWorkItem == null) {
                                log.makeAlert("WTF?! TaskInfo disappeared!").addData("task", task.getId()).emit();
                                throw new ISE("TaskInfo disappeared for task[%s]!", task.getId());
                              }

                              if (taskWorkItem.processHolder != null) {
                                log.makeAlert("WTF?! TaskInfo already has a processHolder")
                                   .addData("task", task.getId())
                                   .emit();
                                throw new ISE("TaskInfo already has processHolder for task[%s]!", task.getId());
                              }

                              final List<String> command = Lists.newArrayList();
                              final String childHost = node.getHost();
                              final String taskClasspath;
                              if (task.getClasspathPrefix() != null && !task.getClasspathPrefix().isEmpty()) {
                                taskClasspath = Joiner.on(File.pathSeparator).join(
                                    task.getClasspathPrefix(),
                                    config.getClasspath()
                                );
                              } else {
                                taskClasspath = config.getClasspath();
                              }

                              command.add(config.getJavaCommand());
                              command.add("-cp");
                              command.add(taskClasspath);

                              Iterables.addAll(command, new QuotableWhiteSpaceSplitter(config.getJavaOpts(), jsonMapper));

                              // Override task specific javaOpts
                              Object taskJavaOpts = task.getContextValue(
                                  ForkingTaskRunnerConfig.JAVA_OPTS_PROPERTY
                              );
                              if (taskJavaOpts != null) {
                                Iterables.addAll(
                                    command,
                                    new QuotableWhiteSpaceSplitter((String) taskJavaOpts, jsonMapper)
                                );
                              }

                              for (String propName : props.stringPropertyNames()) {
                                for (String allowedPrefix : config.getAllowedPrefixes()) {
                                  // See https://github.com/druid-io/druid/issues/1841
                                  if (propName.startsWith(allowedPrefix)
                                      && !ForkingTaskRunnerConfig.JAVA_OPTS_PROPERTY.equals(propName)) {
                                    command.add(
                                        String.format(
                                            "-D%s=%s",
                                            propName,
                                            props.getProperty(propName)
                                        )
                                    );
                                  }
                                }
                              }

                              // Override child JVM specific properties
                              for (String propName : props.stringPropertyNames()) {
                                if (propName.startsWith(CHILD_PROPERTY_PREFIX)) {
                                  command.add(
                                      String.format(
                                          "-D%s=%s",
                                          propName.substring(CHILD_PROPERTY_PREFIX.length()),
                                          props.getProperty(propName)
                                      )
                                  );
                                }
                              }

                              // Override task specific properties
                              final Map<String, Object> context = task.getContext();
                              if (context != null) {
                                for (String propName : context.keySet()) {
                                  if (propName.startsWith(CHILD_PROPERTY_PREFIX)) {
                                    command.add(
                                        String.format(
                                            "-D%s=%s",
                                            propName.substring(CHILD_PROPERTY_PREFIX.length()),
                                            task.getContextValue(propName)
                                        )
                                    );
                                  }
                                }
                              }

                              // Add dataSource and taskId for metrics
                              command.add(
                                  String.format(
                                      "-D%s%s=%s",
                                      MonitorsConfig.METRIC_DIMENSION_PREFIX,
                                      DruidMetrics.DATASOURCE,
                                      task.getDataSource()
                                  )
                              );
                              command.add(
                                  String.format(
                                      "-D%s%s=%s",
                                      MonitorsConfig.METRIC_DIMENSION_PREFIX,
                                      DruidMetrics.TASK_ID,
                                      task.getId()
                                  )
                              );

                              command.add(String.format("-Ddruid.host=%s", childHost));
                              command.add(String.format("-Ddruid.port=%d", childPort));
                              /**
                               * These are not enabled per default to allow the user to either set or not set them
                               * Users are highly suggested to be set in druid.indexer.runner.javaOpts
                               * See io.druid.concurrent.TaskThreadPriority#getThreadPriorityFromTaskPriority(int)
                               * for more information
                              command.add("-XX:+UseThreadPriorities");
                              command.add("-XX:ThreadPriorityPolicy=42");
                               */

                              if (config.isSeparateIngestionEndpoint()) {
                                command.add(String.format(
                                    "-Ddruid.indexer.task.chathandler.service=%s",
                                    "placeholder/serviceName"
                                ));
                                // Actual serviceName will be passed by the EventReceiverFirehose when it registers itself with ChatHandlerProvider
                                // Thus, "placeholder/serviceName" will be ignored
                                command.add(String.format("-Ddruid.indexer.task.chathandler.host=%s", childHost));
                                command.add(String.format(
                                    "-Ddruid.indexer.task.chathandler.port=%d",
                                    childChatHandlerPort
                                ));
                              }

                              command.add("io.druid.cli.Main");
                              command.add("internal");
                              command.add("peon");
                              command.add(taskFile.toString());
                              command.add(statusFile.toString());
                              String nodeType = task.getNodeType();
                              if (nodeType != null) {
                                command.add("--nodeType");
                                command.add(nodeType);
                              }

                              if (!taskFile.exists()) {
                                jsonMapper.writeValue(taskFile, task);
                              }

                              log.info("Running command: %s", Joiner.on(" ").join(command));
                              taskWorkItem.processHolder = new ProcessHolder(
                                  new ProcessBuilder(ImmutableList.copyOf(command)).redirectErrorStream(true).start(),
                                  logFile,
                                  childPort
                              );

                              processHolder = taskWorkItem.processHolder;
                              processHolder.registerWithCloser(closer);
                            }

                            log.info("Logging task %s output to: %s", task.getId(), logFile);
                            boolean runFailed = true;

                            final ByteSink logSink = Files.asByteSink(logFile, FileWriteMode.APPEND);
                            try (final OutputStream toLogfile = logSink.openStream()) {
                              ByteStreams.copy(processHolder.process.getInputStream(), toLogfile);
                              final int statusCode = processHolder.process.waitFor();
                              log.info("Process exited with status[%d] for task: %s", statusCode, task.getId());
                              if (statusCode == 0) {
                                runFailed = false;
                              }
                            }
                            finally {
                              // Upload task logs
                              taskLogPusher.pushTaskLog(task.getId(), logFile);
                            }

                            if (!runFailed) {
                              // Process exited successfully
                              return jsonMapper.readValue(statusFile, TaskStatus.class);
                            } else {
                              // Process exited unsuccessfully
                              return TaskStatus.failure(task.getId());
                            }
                          }
                          catch (Throwable t) {
                            throw closer.rethrow(t);
                          }
                          finally {
                            closer.close();
                          }
                        }
                        catch (Throwable t) {
                          log.info(t, "Exception caught during execution");
                          throw Throwables.propagate(t);
                        }
                        finally {
                          try {
                            synchronized (tasks) {
                              final ForkingTaskRunnerWorkItem taskWorkItem = tasks.remove(task.getId());
                              if (taskWorkItem != null && taskWorkItem.processHolder != null) {
                                taskWorkItem.processHolder.process.destroy();
                              }
                              if (!stopping) {
                                saveRunningTasks();
                              }
                            }

                            if (childChatHandlerPort > 0) {
                              portFinder.markPortUnused(childChatHandlerPort);
                            }

                            try {
                              if (!stopping && taskDir.exists()) {
                                log.info("Removing task directory: %s", taskDir);
                                FileUtils.deleteDirectory(taskDir);
                              }
                            }
                            catch (Exception e) {
                              log.makeAlert(e, "Failed to delete task directory")
                                 .addData("taskDir", taskDir.toString())
                                 .addData("task", task.getId())
                                 .emit();
                            }
                          }
                          catch (Exception e) {
                            log.error(e, "Suppressing exception caught while cleaning up task");
                          }
                        }
                      }
                    }
                )
            )
        );
      }
      saveRunningTasks();
      return tasks.get(task.getId()).getResult();
    }
  }

  @LifecycleStop
  public void stop()
  {
    stopping = true;
    exec.shutdown();

    synchronized (tasks) {
      for (ForkingTaskRunnerWorkItem taskWorkItem : tasks.values()) {
        if (taskWorkItem.processHolder != null) {
          log.info("Closing output stream to task[%s].", taskWorkItem.getTask().getId());
          try {
            taskWorkItem.processHolder.process.getOutputStream().close();
          }
          catch (Exception e) {
            log.warn(e, "Failed to close stdout to task[%s]. Destroying task.", taskWorkItem.getTask().getId());
            taskWorkItem.processHolder.process.destroy();
          }
        }
      }
    }

    final DateTime start = new DateTime();
    final long timeout = new Interval(start, taskConfig.getGracefulShutdownTimeout()).toDurationMillis();

    // Things should be terminating now. Wait for it to happen so logs can be uploaded and all that good stuff.
    log.info("Waiting up to %,dms for shutdown.", timeout);
    if (timeout > 0) {
      try {
        final boolean terminated = exec.awaitTermination(timeout, TimeUnit.MILLISECONDS);
        final long elapsed = System.currentTimeMillis() - start.getMillis();
        if (terminated) {
          log.info("Finished stopping in %,dms.", elapsed);
        } else {
          final Set<String> stillRunning = ImmutableSet.copyOf(tasks.keySet());

          log.makeAlert("Failed to stop forked tasks")
             .addData("stillRunning", stillRunning)
             .addData("elapsed", elapsed)
             .emit();

          log.warn(
              "Executor failed to stop after %,dms, not waiting for it! Tasks still running: [%s]",
              elapsed,
              Joiner.on("; ").join(stillRunning)
          );
        }
      }
      catch (InterruptedException e) {
        log.warn(e, "Interrupted while waiting for executor to finish.");
        Thread.currentThread().interrupt();
      }
    } else {
      log.warn("Ran out of time, not waiting for executor to finish!");
    }
  }

  @Override
  public void shutdown(final String taskid)
  {
    final ForkingTaskRunnerWorkItem taskInfo;

    synchronized (tasks) {
      taskInfo = tasks.get(taskid);

      if (taskInfo == null) {
        log.info("Ignoring request to cancel unknown task: %s", taskid);
        return;
      }

      taskInfo.shutdown = true;
    }

    if (taskInfo.processHolder != null) {
      // Will trigger normal failure mechanisms due to process exit
      log.info("Killing process for task: %s", taskid);
      taskInfo.processHolder.process.destroy();
    }
  }

  @Override
  public Collection<TaskRunnerWorkItem> getRunningTasks()
  {
    synchronized (tasks) {
      final List<TaskRunnerWorkItem> ret = Lists.newArrayList();
      for (final ForkingTaskRunnerWorkItem taskWorkItem : tasks.values()) {
        if (taskWorkItem.processHolder != null) {
          ret.add(taskWorkItem);
        }
      }
      return ret;
    }
  }

  @Override
  public Collection<TaskRunnerWorkItem> getPendingTasks()
  {
    synchronized (tasks) {
      final List<TaskRunnerWorkItem> ret = Lists.newArrayList();
      for (final ForkingTaskRunnerWorkItem taskWorkItem : tasks.values()) {
        if (taskWorkItem.processHolder == null) {
          ret.add(taskWorkItem);
        }
      }
      return ret;
    }
  }

  @Override
  public Collection<TaskRunnerWorkItem> getKnownTasks()
  {
    synchronized (tasks) {
      return Lists.<TaskRunnerWorkItem>newArrayList(tasks.values());
    }
  }

  @Override
  public Optional<ScalingStats> getScalingStats()
  {
    return Optional.absent();
  }

  @Override
  public void start()
  {
    // No state setup required
  }

  @Override
  public Optional<ByteSource> streamTaskLog(final String taskid, final long offset)
  {
    final ProcessHolder processHolder;

    synchronized (tasks) {
      final ForkingTaskRunnerWorkItem taskWorkItem = tasks.get(taskid);
      if (taskWorkItem != null && taskWorkItem.processHolder != null) {
        processHolder = taskWorkItem.processHolder;
      } else {
        return Optional.absent();
      }
    }

    return Optional.<ByteSource>of(
        new ByteSource()
        {
          @Override
          public InputStream openStream() throws IOException
          {
            return LogUtils.streamFile(processHolder.logFile, offset);
          }
        }
    );
  }

  // Save running tasks to a file, so they can potentially be restored on next startup. Suppresses exceptions that
  // occur while saving.
  private void saveRunningTasks()
  {
    final File restoreFile = getRestoreFile();
    final List<String> theTasks = Lists.newArrayList();
    for (ForkingTaskRunnerWorkItem forkingTaskRunnerWorkItem : tasks.values()) {
      theTasks.add(forkingTaskRunnerWorkItem.getTaskId());
    }

    try {
      Files.createParentDirs(restoreFile);
      jsonMapper.writeValue(restoreFile, new TaskRestoreInfo(theTasks));
    }
    catch (Exception e) {
      log.warn(e, "Failed to save tasks to restore file[%s]. Skipping this save.", restoreFile);
    }
  }

  private File getRestoreFile()
  {
    return new File(taskConfig.getBaseTaskDir(), TASK_RESTORE_FILENAME);
  }

  private static class TaskRestoreInfo
  {
    @JsonProperty
    private final List<String> runningTasks;

    @JsonCreator
    public TaskRestoreInfo(
        @JsonProperty("runningTasks") List<String> runningTasks
    )
    {
      this.runningTasks = runningTasks;
    }

    public List<String> getRunningTasks()
    {
      return runningTasks;
    }
  }

  private static class ForkingTaskRunnerWorkItem extends TaskRunnerWorkItem
  {
    private final Task task;

    private volatile boolean shutdown = false;
    private volatile ProcessHolder processHolder = null;

    private ForkingTaskRunnerWorkItem(
        Task task,
        ListenableFuture<TaskStatus> statusFuture
    )
    {
      super(task.getId(), statusFuture);
      this.task = task;
    }

    public Task getTask()
    {
      return task;
    }
  }

  private static class ProcessHolder
  {
    private final Process process;
    private final File logFile;
    private final int port;

    private ProcessHolder(Process process, File logFile, int port)
    {
      this.process = process;
      this.logFile = logFile;
      this.port = port;
    }

    private void registerWithCloser(Closer closer)
    {
      closer.register(process.getInputStream());
      closer.register(process.getOutputStream());
    }
  }
}

/**
 * Make an iterable of space delimited strings... unless there are quotes, which it preserves
 */
class QuotableWhiteSpaceSplitter implements Iterable<String>
{
  private static final Logger LOG = new Logger(QuotableWhiteSpaceSplitter.class);
  private final String string;
  private final ObjectMapper mapper;

  public QuotableWhiteSpaceSplitter(String string, ObjectMapper jsonMapper)
  {
    this.string = Preconditions.checkNotNull(string);
    this.mapper = jsonMapper;
  }

  @Override
  public Iterator<String> iterator()
  {
    try (JsonParser parser = mapper.getFactory().createParser(string)) {
      final JsonToken token = parser.nextToken();
      if (JsonToken.START_ARRAY.equals(token)) {
        return mapper.<List<String>>readValue(string, new TypeReference<List<String>>()
        {
        }).iterator();
      }
    }
    catch (IOException e) {
      LOG.debug(e, "Could not parse %s", string);
    }
    LOG.debug("Not json, hoping it is a good string : %s", string);
    return Splitter.on(
        new CharMatcher()
        {
          private boolean inQuotes = false;

          @Override
          public boolean matches(char c)
          {
            if ('"' == c) {
              inQuotes = !inQuotes;
            }
            if (inQuotes) {
              return false;
            }
            return CharMatcher.BREAKING_WHITESPACE.matches(c);
          }
        }
    ).omitEmptyStrings().split(string).iterator();
  }
}

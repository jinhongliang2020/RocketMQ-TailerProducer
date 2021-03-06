/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.flume.source.taildir.rotate;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
import com.ndpmedia.rocketmq.tailer.fs.FileEventListener;
import com.ndpmedia.rocketmq.tailer.fs.FileEventNotify;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.flume.ChannelException;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.FlumeException;
import org.apache.flume.PollableSource;
import org.apache.flume.conf.Configurable;
import org.apache.flume.instrumentation.SourceCounter;
import org.apache.flume.source.AbstractSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaildirSource extends AbstractSource implements
                                                  PollableSource, Configurable {

  private static final Logger logger = LoggerFactory.getLogger(TaildirSource.class);

  private Map<String, String> filePaths;
  private Table<String, String, String> headerTable;
  private int batchSize;
  private String positionFilePath;
  private boolean skipToEnd;
  private boolean byteOffsetHeader;

  private SourceCounter sourceCounter;
  private ReliableTaildirEventReader reader;
  private ScheduledExecutorService idleFileChecker;
  private ScheduledExecutorService positionWriter;
  private int retryInterval = 1000;
  private int maxRetryInterval = 5000;
  private int idleTimeout;
  private int checkIdleInterval = 5000;
  private int writePosInitDelay = 5000;
  private int writePosInterval;
  private boolean cachePatternMatching;

  private List<Long> existingInodes = new CopyOnWriteArrayList<Long>();
  private List<Long> idleInodes = new CopyOnWriteArrayList<Long>();
  private Long backoffSleepIncrement;
  private Long maxBackOffSleepInterval;
  private boolean fileHeader;
  private String fileHeaderKey;
  private FileEventNotify fileEventNotify;
  private ReentrantReadWriteLock positionLock = new ReentrantReadWriteLock();


  @Override
  public synchronized void start() {
    logger.info("{} TaildirSource source starting with directory: {}", getName(), filePaths);
    try {
      reader = new ReliableTaildirEventReader.Builder()
          .filePaths(filePaths)
          .headerTable(headerTable)
          .positionFilePath(positionFilePath)
          .skipToEnd(skipToEnd)
          .addByteOffset(byteOffsetHeader)
          .cachePatternMatching(cachePatternMatching)
          .annotateFileName(fileHeader)
          .fileNameHeader(fileHeaderKey)
          .build();
    } catch (IOException e) {
      throw new FlumeException("Error instantiating ReliableTaildirEventReader", e);
    }
    idleFileChecker = Executors.newSingleThreadScheduledExecutor(
        new ThreadFactoryBuilder().setNameFormat("idleFileChecker").build());
    idleFileChecker.scheduleWithFixedDelay(new idleFileCheckerRunnable(),
                                           idleTimeout, checkIdleInterval, TimeUnit.MILLISECONDS);

    positionWriter = Executors.newSingleThreadScheduledExecutor(
        new ThreadFactoryBuilder().setNameFormat("positionWriter").build());
    positionWriter.scheduleWithFixedDelay(new PositionWriterRunnable(),
                                          writePosInitDelay, writePosInterval,
                                          TimeUnit.MILLISECONDS);
    fileEventNotify = new FileEventNotify();
    fileEventNotify.registerListener(getMonitorPaths(), new FileEventListener() {
      @Override
      public void onCreate(String file) {
        logger.info("new file detected {}, begin to update files", file);
        try {
          reader.updateTailFiles();
        } catch (Exception e) {
          logger.error("file monitor listen onCreate fail", e);
        }

      }

      @Override
      public void onModify(String file) {
        //leave source to auto pull file
      }

      @Override
      public void onDelete(String file) {
        //leave source to auto process file
        try {
          for (Iterator<Entry<Long, TailFile>> iterator = reader.getTailFiles().entrySet()
              .iterator();iterator.hasNext();){
            Entry<Long, TailFile> entry = iterator.next();
            if(entry.getValue().getPath().equals(file)){
              logger.info("remove file listen onDelete: "+file);

              entry.getValue().close();
              iterator.remove();
              break;
            }
          }
        } catch (Exception e) {
          logger.error("file monitor listen onDelete fail", e);
        }
      }

      @Override
      public void onRename(String oldFile, String newFile) {
        logger.info("rename file detected {} -> {}, begin to update files", oldFile, newFile);
        try {
          //start scan now

          long inode = reader.getInode(new File(newFile));
          TailFile tailFile = reader.getTailFiles().get(inode);
          if (tailFile == null) {
            //this file does not match the pattern
            return;
          }
          tailFile.updatePath(inode, tailFile.getPath(), newFile);
          //save now
          writePosition();
          reader.updateTailFiles();
        } catch (Exception e) {
          logger.error("file monitor listen onRename fail", e);
        }
      }
    });
    super.start();
    logger.debug("TaildirSource started");
    sourceCounter.start();
  }

  private String[] getMonitorPaths() {
    Set<String> set = new HashSet<String>();
    for (String path : filePaths.values()) {
      String directory = path.substring(0, path.lastIndexOf("/"));
      File root = new File(directory);
      while(!root.isDirectory()){
        root = root.getParentFile();
      }
      set.add(root.getAbsolutePath());
    }
    return set.toArray(new String[]{});
  }

  @Override
  public synchronized void stop() {
    try {
      super.stop();
      ExecutorService[] services = {idleFileChecker, positionWriter};
      for (ExecutorService service : services) {
        service.shutdown();
        if (!service.awaitTermination(1, TimeUnit.SECONDS)) {
          service.shutdownNow();
        }
      }
      fileEventNotify.stop();
      // write the last position
      while (!writePosition()){

      };
      reader.close();
    } catch (InterruptedException e) {
      logger.info("Interrupted while awaiting termination", e);
    } catch (IOException e) {
      logger.info("Failed: " + e.getMessage(), e);
    }
    sourceCounter.stop();
    logger.info("Taildir source {} stopped. Metrics: {}", getName(), sourceCounter);
  }

  @Override
  public String toString() {
    return String.format("Taildir source: { positionFile: %s, skipToEnd: %s, "
                         + "byteOffsetHeader: %s, idleTimeout: %s, writePosInterval: %s }",
                         positionFilePath, skipToEnd, byteOffsetHeader, idleTimeout,
                         writePosInterval);
  }

  @Override
  public synchronized void configure(Context context) {
    String fileGroups = context.getString(TaildirSourceConfigurationConstants.FILE_GROUPS);
    Preconditions.checkState(fileGroups != null,
                             "Missing param: " + TaildirSourceConfigurationConstants.FILE_GROUPS);

    filePaths = selectByKeys(context.getSubProperties(
        TaildirSourceConfigurationConstants.FILE_GROUPS_PREFIX),
                             fileGroups.split("\\s+"));
    Preconditions.checkState(!filePaths.isEmpty(),
                             "Mapping for tailing files is empty or invalid: '"
                             + TaildirSourceConfigurationConstants.FILE_GROUPS_PREFIX + "'");

    String homePath = System.getProperty("user.home").replace('\\', '/');
    positionFilePath =
        context.getString(TaildirSourceConfigurationConstants.POSITION_FILE,
                          homePath + TaildirSourceConfigurationConstants.DEFAULT_POSITION_FILE);
    Path positionFile = Paths.get(positionFilePath);
    try {
      Files.createDirectories(positionFile.getParent());
    } catch (IOException e) {
      throw new FlumeException("Error creating positionFile parent directories", e);
    }
    headerTable = getTable(context, TaildirSourceConfigurationConstants.HEADERS_PREFIX);
    batchSize =
        context.getInteger(TaildirSourceConfigurationConstants.BATCH_SIZE,
                           TaildirSourceConfigurationConstants.DEFAULT_BATCH_SIZE);
    skipToEnd =
        context.getBoolean(TaildirSourceConfigurationConstants.SKIP_TO_END,
                           TaildirSourceConfigurationConstants.DEFAULT_SKIP_TO_END);
    byteOffsetHeader =
        context.getBoolean(TaildirSourceConfigurationConstants.BYTE_OFFSET_HEADER,
                           TaildirSourceConfigurationConstants.DEFAULT_BYTE_OFFSET_HEADER);
    idleTimeout =
        context.getInteger(TaildirSourceConfigurationConstants.IDLE_TIMEOUT,
                           TaildirSourceConfigurationConstants.DEFAULT_IDLE_TIMEOUT);
    writePosInterval =
        context.getInteger(TaildirSourceConfigurationConstants.WRITE_POS_INTERVAL,
                           TaildirSourceConfigurationConstants.DEFAULT_WRITE_POS_INTERVAL);
    cachePatternMatching = context.getBoolean(
        TaildirSourceConfigurationConstants.CACHE_PATTERN_MATCHING,
        TaildirSourceConfigurationConstants.DEFAULT_CACHE_PATTERN_MATCHING);

    backoffSleepIncrement = context.getLong(TailerConfig.BACKOFF_SLEEP_INCREMENT,
                                            TailerConfig.DEFAULT_BACKOFF_SLEEP_INCREMENT);
    maxBackOffSleepInterval = context.getLong(TailerConfig.MAX_BACKOFF_SLEEP,
                                              TailerConfig.DEFAULT_MAX_BACKOFF_SLEEP);
    fileHeader = context.getBoolean(TaildirSourceConfigurationConstants.FILENAME_HEADER,
                                    TaildirSourceConfigurationConstants.DEFAULT_FILE_HEADER);
    fileHeaderKey = context.getString(TaildirSourceConfigurationConstants.FILENAME_HEADER_KEY,
                                      TaildirSourceConfigurationConstants.DEFAULT_FILENAME_HEADER_KEY);

    if (sourceCounter == null) {
      sourceCounter = new SourceCounter(getName());
    }
  }

  private Map<String, String> selectByKeys(Map<String, String> map, String[] keys) {
    Map<String, String> result = Maps.newHashMap();
    for (String key : keys) {
      if (map.containsKey(key)) {
        result.put(key, map.get(key));
      }
    }
    return result;
  }

  private Table<String, String, String> getTable(Context context, String prefix) {
    Table<String, String, String> table = HashBasedTable.create();
    for (Entry<String, String> e : context.getSubProperties(prefix).entrySet()) {
      String[] parts = e.getKey().split("\\.", 2);
      table.put(parts[0], parts[1], e.getValue());
    }
    return table;
  }

  @VisibleForTesting
  protected SourceCounter getSourceCounter() {
    return sourceCounter;
  }

  @Override
  public Status process() {
    Status status = Status.READY;
    try {
      existingInodes.clear();
      existingInodes.addAll(reader.updateTailFiles());
      for (long inode : existingInodes) {
        TailFile tf = reader.getTailFiles().get(inode);
        if (tf.needTail()) {
          tailFileProcess(tf, true);
        }
      }
      closeTailFiles();
      try {
        TimeUnit.MILLISECONDS.sleep(retryInterval);
      } catch (InterruptedException e) {
        logger.info("Interrupted while sleeping");
      }
    } catch (Throwable t) {
      logger.error("Unable to tail files", t);
      status = Status.BACKOFF;
    }
    return status;
  }


  public long getBackOffSleepIncrement() {
    return backoffSleepIncrement;
  }


  public long getMaxBackOffSleepInterval() {
    return maxBackOffSleepInterval;
  }

  private void tailFileProcess(TailFile tf, boolean backoffWithoutNL)
      throws IOException, InterruptedException {
    while (true) {
      reader.setCurrentFile(tf);
      List<Event> events = reader.readEvents(batchSize, backoffWithoutNL);
      if (events.isEmpty()) {
        break;
      }
      sourceCounter.addToEventReceivedCount(events.size());
      sourceCounter.incrementAppendBatchReceivedCount();
      try {
        getChannelProcessor().processEventBatch(events);
        reader.commit();
      } catch (ChannelException ex) {
        logger.warn("The channel is full or unexpected failure. " +
                    "The source will try again after " + retryInterval + " ms");
        TimeUnit.MILLISECONDS.sleep(retryInterval);
        retryInterval = retryInterval << 1;
        retryInterval = Math.min(retryInterval, maxRetryInterval);
        continue;
      }
      retryInterval = 1000;
      sourceCounter.addToEventAcceptedCount(events.size());
      sourceCounter.incrementAppendBatchAcceptedCount();
      if (events.size() < batchSize) {
        break;
      }
    }
  }

  private void closeTailFiles() throws IOException, InterruptedException {
    for (long inode : idleInodes) {
      TailFile tf = reader.getTailFiles().get(inode);
      //already removed
      if (tf == null){
        return;
      }
      if (tf.getRaf() != null) { // when file has not closed yet
        tailFileProcess(tf, false);
        tf.close();
        logger.info("Closed file: " + tf.getPath() + ", inode: " + inode + ", pos: " + tf.getPos());
      }
    }
    idleInodes.clear();
  }

  /**
   * Runnable class that checks whether there are files which should be closed.
   */
  private class idleFileCheckerRunnable implements Runnable {

    @Override
    public void run() {
      try {
        long now = System.currentTimeMillis();
        List<TailFile> list = new ArrayList<>();
        list.addAll(reader.getTailFiles().values());
        for (TailFile tf : list) {
          if (tf.getLastUpdated() + idleTimeout < now && tf.getRaf() != null) {
            long inode = tf.getInode();
            if (idleInodes.contains(inode)){
              continue;
            }
            idleInodes.add(tf.getInode());
          }
        }
      } catch (Throwable t) {
        logger.error("Uncaught exception in IdleFileChecker thread", t);
      }
    }
  }

  /**
   * Runnable class that writes a position file which has the last read position
   * of each file.
   */
  private class PositionWriterRunnable implements Runnable {

    @Override
    public void run() {
      writePosition();
    }
  }

  private boolean writePosition() {
    logger.info("start to write position file");
    boolean locked = positionLock.writeLock().tryLock();
    if (!locked) {
      logger.info("position file lock fail");
      return false;
    }

    File file = new File(positionFilePath);
    FileWriter writer = null;
    try {
      writer = new FileWriter(file);
      String json = toPosInfoJson();
      writer.write(json);
    } catch (Throwable t) {
      logger.error("Failed writing positionFile", t);
    } finally {
      try {
        if (writer != null) {
          writer.flush();
          writer.close();
        }
      } catch (IOException e) {
        logger.error("Error: " + e.getMessage(), e);
      }
      positionLock.writeLock().unlock();
    }
    return true;
  }

  private String toPosInfoJson() {
    @SuppressWarnings("rawtypes")
    List<Map> posInfos = Lists.newArrayList();

    for (Map.Entry<Long, TailFile> entry : reader.getTailFiles().entrySet()) {
      TailFile tf = entry.getValue();
      posInfos.add(ImmutableMap.of("inode", entry.getKey(), "pos", tf.getPos(), "file", tf.getPath()));
    }
    return new Gson().toJson(posInfos);
  }
}

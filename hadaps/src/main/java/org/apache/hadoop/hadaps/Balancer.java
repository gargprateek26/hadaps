/*
 * Copyright 2013-2014 eXascale Infolab, University of Fribourg. All rights reserved.
 */
package org.apache.hadoop.hadaps;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

class Balancer {

  private static final Logger LOG = LoggerFactory.getLogger(Balancer.class);

  private static final int CONCURRENT_TASKS = 3;

  private final URI nameNode;
  private final List<Generation> generations;
  private final List<ParameterFile> parameterFiles;
  private final Configuration configuration;

  private final ThreadPoolExecutor threadPool = new ThreadPoolExecutor(
      CONCURRENT_TASKS, CONCURRENT_TASKS, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
  private final CompletionService<BalancerResult> completionService =
      new ExecutorCompletionService<BalancerResult>(threadPool);

  Balancer(URI nameNode, List<Generation> generations, List<ParameterFile> parameterFiles, Configuration configuration) {
    if (nameNode == null) throw new IllegalArgumentException();
    if (generations == null) throw new IllegalArgumentException();
    if (parameterFiles == null) throw new IllegalArgumentException();
    if (configuration == null) throw new IllegalArgumentException();

    this.nameNode = nameNode;
    this.generations = generations;
    this.parameterFiles = parameterFiles;
    this.configuration = configuration;
  }

  void run() throws IOException, InterruptedException {
    // Populate balancer files
    List<BalancerFile> balancerFiles = getBalancerFiles();

    // Now balance each file
    for (BalancerFile balancerFile : balancerFiles) {
      if (threadPool.getActiveCount() >= CONCURRENT_TASKS) {
        // Await completion of any submitted task

        try {
          BalancerResult result = completionService.take().get();
        } catch (ExecutionException e) {
          LOG.warn(e.getLocalizedMessage(), e);
        }
      }

      completionService.submit(new BalancerTask(balancerFile));
    }

    // Await completion of any submitted task
    while (threadPool.getActiveCount() > 0) {
      try {
        BalancerResult result = completionService.take().get();
      } catch (ExecutionException e) {
        LOG.warn(e.getLocalizedMessage(), e);
      }
    }

    // Initiate a proper shutdown
    threadPool.shutdown();
    threadPool.awaitTermination(10, TimeUnit.SECONDS);
  }

  private List<BalancerFile> getBalancerFiles() throws IOException {
    List<BalancerFile> balancerFiles = new ArrayList<BalancerFile>();

    // Iterate over each pattern
    for (ParameterFile parameterFile : parameterFiles) {
      Path globPath = new Path(parameterFile.getName());
      FileSystem fileSystem = globPath.getFileSystem(configuration);
      FileStatus[] stats = fileSystem.globStatus(globPath);

      if (stats != null && stats.length > 0) {
        // We have some matching paths

        for (FileStatus stat : stats) {
          populateBalancerFiles(balancerFiles, stat, parameterFile, fileSystem);
        }

        Collections.sort(balancerFiles);

        LOG.info("Matching files for pattern \"{}\": {}", globPath.toString(), balancerFiles);
      } else {
        LOG.info("No matching files for pattern \"{}\"", globPath.toString());
      }
    }

    return balancerFiles;
  }

  private void populateBalancerFiles(
      List<BalancerFile> balancerFiles, FileStatus status, ParameterFile parameterFile, FileSystem fileSystem)
      throws IOException {
    assert balancerFiles != null;
    assert status != null;
    assert parameterFile != null;
    assert fileSystem != null;

    if (status.isFile()) {
      balancerFiles.add(new BalancerFile(status, parameterFile, fileSystem));
    } else if (status.isDirectory()) {
      // Recurse into directory

      FileStatus[] stats = fileSystem.listStatus(status.getPath());
      for (FileStatus stat : stats) {
        populateBalancerFiles(balancerFiles, stat, parameterFile, fileSystem);
      }
    }
  }

}

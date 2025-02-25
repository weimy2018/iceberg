/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.io;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.hadoop.HadoopConfigurable;
import org.apache.iceberg.hadoop.SerializableConfiguration;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.util.SerializableSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FileIO implementation that uses location scheme to choose the correct FileIO implementation.
 */
public class ResolvingFileIO implements FileIO, HadoopConfigurable {
  private static final Logger LOG = LoggerFactory.getLogger(ResolvingFileIO.class);
  private static final String DEFAULT_SCHEME = "fs";
  private static final String FALLBACK_IMPL = "org.apache.iceberg.hadoop.HadoopFileIO";
  private static final Map<String, String> SCHEME_TO_FILE_IO = ImmutableMap.of(
      DEFAULT_SCHEME, FALLBACK_IMPL,
      "s3", "org.apache.iceberg.aws.s3.S3FileIO",
      "s3a", "org.apache.iceberg.aws.s3.S3FileIO",
      "s3n", "org.apache.iceberg.aws.s3.S3FileIO"
  );

  private final Map<String, FileIO> ioInstances = Maps.newHashMap();
  private Map<String, String> properties;
  private SerializableSupplier<Configuration> hadoopConf;

  @Override
  public InputFile newInputFile(String location) {
    return io(location).newInputFile(location);
  }

  @Override
  public OutputFile newOutputFile(String location) {
    return io(location).newOutputFile(location);
  }

  @Override
  public void deleteFile(String location) {
    io(location).deleteFile(location);
  }

  @Override
  public void initialize(Map<String, String> newProperties) {
    close(); // close and discard any existing FileIO instances
    this.properties = newProperties;
  }

  @Override
  public void close() {
    List<FileIO> instances = Lists.newArrayList();

    synchronized (ioInstances) {
      instances.addAll(ioInstances.values());
      ioInstances.clear();
    }

    for (FileIO io : instances) {
      io.close();
    }
  }

  @Override
  public void serializeConfWith(Function<Configuration, SerializableSupplier<Configuration>> confSerializer) {
    this.hadoopConf = confSerializer.apply(hadoopConf.get());
  }

  @Override
  public void setConf(Configuration conf) {
    this.hadoopConf = new SerializableConfiguration(conf)::get;
  }

  @Override
  public Configuration getConf() {
    return hadoopConf.get();
  }

  private FileIO io(String location) {
    String impl = implFromLocation(location);
    FileIO io = ioInstances.get(impl);
    if (io != null) {
      return io;
    }

    synchronized (ioInstances) {
      // double check while holding the lock
      io = ioInstances.get(impl);
      if (io != null) {
        return io;
      }

      Configuration conf = hadoopConf.get();

      try {
        io = CatalogUtil.loadFileIO(impl, properties, conf);
      } catch (IllegalArgumentException e) {
        LOG.warn("Failed to load FileIO implementation: {}, falling back to {}", impl, FALLBACK_IMPL, e);
        try {
          // couldn't load the normal class, fall back to HadoopFileIO
          io = CatalogUtil.loadFileIO(FALLBACK_IMPL, properties, conf);
        } catch (IllegalArgumentException suppressed) {
          LOG.warn("Failed to load FileIO implementation: {} (fallback)", FALLBACK_IMPL, suppressed);
          // both attempts failed, throw the original exception with the later exception suppressed
          e.addSuppressed(suppressed);
          throw e;
        }
      }

      ioInstances.put(impl, io);
    }

    return io;
  }

  private static String implFromLocation(String location) {
    return SCHEME_TO_FILE_IO.getOrDefault(scheme(location), FALLBACK_IMPL);
  }

  private static String scheme(String location) {
    int colonPos = location.indexOf(":");
    if (colonPos > 0) {
      return location.substring(0, colonPos);
    }

    return DEFAULT_SCHEME;
  }
}

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

package org.apache.iceberg.spark.actions;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Table;
import org.apache.iceberg.actions.BaseDeleteOrphanFilesActionResult;
import org.apache.iceberg.actions.DeleteOrphanFiles;
import org.apache.iceberg.exceptions.RuntimeIOException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.hadoop.HiddenPathFilter;
import org.apache.iceberg.relocated.com.google.common.annotations.VisibleForTesting;
import org.apache.iceberg.relocated.com.google.common.base.Joiner;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.base.Strings;
import org.apache.iceberg.relocated.com.google.common.collect.Iterators;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.spark.JobGroupInfo;
import org.apache.iceberg.util.Pair;
import org.apache.iceberg.util.PropertyUtil;
import org.apache.iceberg.util.Tasks;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.MapPartitionsFunction;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.util.SerializableConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;

import static org.apache.iceberg.TableProperties.GC_ENABLED;
import static org.apache.iceberg.TableProperties.GC_ENABLED_DEFAULT;

/**
 * An action that removes orphan metadata, data and delete files by listing a given location and comparing
 * the actual files in that location with content and metadata files referenced by all valid snapshots.
 * The location must be accessible for listing via the Hadoop {@link FileSystem}.
 * <p>
 * By default, this action cleans up the table location returned by {@link Table#location()} and
 * removes unreachable files that are older than 3 days using {@link Table#io()}. The behavior can be modified
 * by passing a custom location to {@link #location} and a custom timestamp to {@link #olderThan(long)}.
 * For example, someone might point this action to the data folder to clean up only orphan data files.
 * <p>
 * Configure an alternative delete method using {@link #deleteWith(Consumer)}.
 * <p>
 * For full control of the set of files being evaluated, use the {@link #compareToFileList(Dataset)} argument.  This
 * skips the directory listing - any files in the dataset provided which are not found in table metadata will
 * be deleted, using the same {@link Table#location()} and {@link #olderThan(long)} filtering as above.
 * <p>
 * <em>Note:</em> It is dangerous to call this action with a short retention interval as it might corrupt
 * the state of the table if another operation is writing at the same time.
 */
public class DeleteOrphanFilesSparkAction
    extends BaseSparkAction<DeleteOrphanFilesSparkAction> implements DeleteOrphanFiles {

  private static final Logger LOG = LoggerFactory.getLogger(DeleteOrphanFilesSparkAction.class);

  private PrefixMismatchMode prefixMismatchMode = PrefixMismatchMode.ERROR;
  private Map<String, String> equalSchemes;
  private Map<String, String> equalAuthorities;
  private final SerializableConfiguration hadoopConf;
  private final int partitionDiscoveryParallelism;
  private final Table table;
  private final Consumer<String> defaultDelete = new Consumer<String>() {
    @Override
    public void accept(String file) {
      table.io().deleteFile(file);
    }
  };

  private String location = null;
  private long olderThanTimestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3);
  private Dataset<Row> compareToFileList;
  private Consumer<String> deleteFunc = defaultDelete;
  private ExecutorService deleteExecutorService = null;

  DeleteOrphanFilesSparkAction(SparkSession spark, Table table) {
    super(spark);

    this.hadoopConf = new SerializableConfiguration(spark.sessionState().newHadoopConf());
    this.partitionDiscoveryParallelism = spark.sessionState().conf().parallelPartitionDiscoveryParallelism();
    this.table = table;
    this.location = table.location();

    ValidationException.check(
        PropertyUtil.propertyAsBoolean(table.properties(), GC_ENABLED, GC_ENABLED_DEFAULT),
        "Cannot delete orphan files: GC is disabled (deleting files may corrupt other tables)");
  }

  @Override
  protected DeleteOrphanFilesSparkAction self() {
    return this;
  }

  @Override
  public DeleteOrphanFilesSparkAction executeDeleteWith(ExecutorService executorService) {
    this.deleteExecutorService = executorService;
    return this;
  }

  @Override
  public DeleteOrphanFiles prefixMismatchMode(PrefixMismatchMode mismatchMode) {
    this.prefixMismatchMode = mismatchMode;
    return this;
  }

  @Override
  public DeleteOrphanFiles equalSchemes(Map<String, String> schemes) {
    this.equalSchemes = schemes;
    return this;
  }

  @Override
  public DeleteOrphanFiles equalAuthorities(Map<String, String> authorities) {
    this.equalAuthorities = authorities;
    return this;
  }

  @Override
  public DeleteOrphanFilesSparkAction location(String newLocation) {
    this.location = newLocation;
    return this;
  }

  @Override
  public DeleteOrphanFilesSparkAction olderThan(long newOlderThanTimestamp) {
    this.olderThanTimestamp = newOlderThanTimestamp;
    return this;
  }

  @Override
  public DeleteOrphanFilesSparkAction deleteWith(Consumer<String> newDeleteFunc) {
    this.deleteFunc = newDeleteFunc;
    return this;
  }

  public DeleteOrphanFilesSparkAction compareToFileList(Dataset<Row> files) {
    StructType schema = files.schema();

    StructField filePathField = schema.apply(FILE_PATH);
    Preconditions.checkArgument(
        filePathField.dataType() == DataTypes.StringType,
        "Invalid %s column: %s is not a string",
        FILE_PATH,
        filePathField.dataType());

    StructField lastModifiedField = schema.apply(LAST_MODIFIED);
    Preconditions.checkArgument(
        lastModifiedField.dataType() == DataTypes.TimestampType,
        "Invalid %s column: %s is not a timestamp",
        LAST_MODIFIED,
        lastModifiedField.dataType());

    this.compareToFileList = files;
    return this;
  }

  private Dataset<Row> filteredCompareToFileList() {
    Dataset<Row> files = compareToFileList;
    if (location != null) {
      files = files.filter(files.col(FILE_PATH).startsWith(location));
    }
    return files
        .filter(files.col(LAST_MODIFIED).lt(new Timestamp(olderThanTimestamp)))
        .select(files.col(FILE_PATH));
  }

  @Override
  public DeleteOrphanFiles.Result execute() {
    JobGroupInfo info = newJobGroupInfo("DELETE-ORPHAN-FILES", jobDesc());
    return withJobGroupInfo(info, this::doExecute);
  }

  private String jobDesc() {
    List<String> options = Lists.newArrayList();
    options.add("older_than=" + olderThanTimestamp);
    if (location != null) {
      options.add("location=" + location);
    }
    return String.format("Deleting orphan files (%s) from %s", Joiner.on(',').join(options), table.name());
  }

  private DeleteOrphanFiles.Result doExecute() {
    Dataset<Row> validContentFileDF = buildValidContentFileDF(table);
    Dataset<Row> validMetadataFileDF = buildValidMetadataFileDF(table);
    Dataset<Row> validFileDF = validContentFileDF.union(validMetadataFileDF);
    Dataset<Row> actualFileDF = compareToFileList == null ? buildActualFileDF() : filteredCompareToFileList();

    List<String> orphanFiles =
        findOrphanFiles(spark(),
            actualFileDF,
            validFileDF,
            equalSchemes,
            equalAuthorities,
            prefixMismatchMode);

    Tasks.foreach(orphanFiles)
        .noRetry()
        .executeWith(deleteExecutorService)
        .suppressFailureWhenFinished()
        .onFailure((file, exc) -> LOG.warn("Failed to delete file: {}", file, exc))
        .run(deleteFunc::accept);

    return new BaseDeleteOrphanFilesActionResult(orphanFiles);
  }

  private Dataset<Row> buildActualFileDF() {
    List<String> subDirs = Lists.newArrayList();
    List<String> matchingFiles = Lists.newArrayList();

    Predicate<FileStatus> predicate = file -> file.getModificationTime() < olderThanTimestamp;
    PathFilter pathFilter = PartitionAwareHiddenPathFilter.forSpecs(table.specs());

    // list at most 3 levels and only dirs that have less than 10 direct sub dirs on the driver
    listDirRecursively(location, predicate, hadoopConf.value(), 3, 10, subDirs, pathFilter, matchingFiles);

    JavaRDD<String> matchingFileRDD = sparkContext().parallelize(matchingFiles, 1);

    if (subDirs.isEmpty()) {
      return spark().createDataset(matchingFileRDD.rdd(), Encoders.STRING()).toDF(FILE_PATH);
    }

    int parallelism = Math.min(subDirs.size(), partitionDiscoveryParallelism);
    JavaRDD<String> subDirRDD = sparkContext().parallelize(subDirs, parallelism);

    Broadcast<SerializableConfiguration> conf = sparkContext().broadcast(hadoopConf);
    JavaRDD<String> matchingLeafFileRDD = subDirRDD.mapPartitions(
        listDirsRecursively(conf, olderThanTimestamp, pathFilter)
    );

    JavaRDD<String> completeMatchingFileRDD = matchingFileRDD.union(matchingLeafFileRDD);
    return spark().createDataset(completeMatchingFileRDD.rdd(), Encoders.STRING()).toDF(FILE_PATH);
  }

  private static void listDirRecursively(
      String dir, Predicate<FileStatus> predicate, Configuration conf, int maxDepth,
      int maxDirectSubDirs, List<String> remainingSubDirs, PathFilter pathFilter, List<String> matchingFiles) {

    // stop listing whenever we reach the max depth
    if (maxDepth <= 0) {
      remainingSubDirs.add(dir);
      return;
    }

    try {
      Path path = new Path(dir);
      FileSystem fs = path.getFileSystem(conf);

      List<String> subDirs = Lists.newArrayList();

      for (FileStatus file : fs.listStatus(path, pathFilter)) {
        if (file.isDirectory()) {
          subDirs.add(file.getPath().toString());
        } else if (file.isFile() && predicate.test(file)) {
          matchingFiles.add(file.getPath().toString());
        }
      }

      // stop listing if the number of direct sub dirs is bigger than maxDirectSubDirs
      if (subDirs.size() > maxDirectSubDirs) {
        remainingSubDirs.addAll(subDirs);
        return;
      }

      for (String subDir : subDirs) {
        listDirRecursively(
            subDir, predicate, conf, maxDepth - 1, maxDirectSubDirs, remainingSubDirs, pathFilter, matchingFiles);
      }
    } catch (IOException e) {
      throw new RuntimeIOException(e);
    }
  }

  private static FlatMapFunction<Iterator<String>, String> listDirsRecursively(
      Broadcast<SerializableConfiguration> conf,
      long olderThanTimestamp,
      PathFilter pathFilter) {

    return dirs -> {
      List<String> subDirs = Lists.newArrayList();
      List<String> files = Lists.newArrayList();

      Predicate<FileStatus> predicate = file -> file.getModificationTime() < olderThanTimestamp;

      int maxDepth = 2000;
      int maxDirectSubDirs = Integer.MAX_VALUE;

      dirs.forEachRemaining(dir -> {
        listDirRecursively(
                dir, predicate, conf.value().value(), maxDepth, maxDirectSubDirs, subDirs, pathFilter, files);
      });

      if (!subDirs.isEmpty()) {
        throw new RuntimeException("Could not list subdirectories, reached maximum subdirectory depth: " + maxDepth);
      }

      return files.iterator();
    };
  }

  @VisibleForTesting
  static List<String> findOrphanFiles(SparkSession spark,
                                      Dataset<Row> actualFileDF,
                                      Dataset<Row> validFileDF,
                                      Map<String, String> equalSchemes,
                                      Map<String, String> equalAuthorities,
                                      PrefixMismatchMode prefixMismatchMode) {
    Map<String, String> equalSchemesMap = flattenMap(equalSchemes);
    Map<String, String> equalAuthoritiesMap = flattenMap(equalAuthorities);

    Dataset<PathProxy> normalizedActualFileDF = actualFileDF.mapPartitions(
        toFileMetadata(equalSchemesMap, equalAuthoritiesMap),
        Encoders.bean(PathProxy.class)).as("actual");
    Dataset<PathProxy> normalizedValidFileDF = validFileDF.mapPartitions(
        toFileMetadata(equalSchemesMap, equalAuthoritiesMap),
        Encoders.bean(PathProxy.class)).as("valid");

    Column actualFileName = normalizedActualFileDF.col("path");
    Column validFileName = normalizedValidFileDF.col("path");

    SetAccumulator<Pair<String, String>> setAccumulator = new SetAccumulator<>();
    spark.sparkContext().register(setAccumulator);

    List<String> orphanFiles = normalizedActualFileDF.joinWith(normalizedValidFileDF,
            actualFileName.equalTo(validFileName), "leftouter")
        .mapPartitions(findOrphanFilesMapPartitions(prefixMismatchMode, setAccumulator), Encoders.STRING())
        .collectAsList();

    if (prefixMismatchMode == PrefixMismatchMode.ERROR && !setAccumulator.value().isEmpty()) {
      throw new ValidationException("Unable to deterministically find all orphan files." +
          " Found file paths that have same file path but different authorities/schemes. Conflicting" +
          " authorities/schemes found: %s", setAccumulator.value().toString());
    }
    return orphanFiles;
  }

  private static Map<String, String> flattenMap(Map<String, String> toBeFlattenedMap) {
    Map<String, String> flattenedMap = Maps.newHashMap();
    if (toBeFlattenedMap != null) {
      for (String key : toBeFlattenedMap.keySet()) {
        String value = toBeFlattenedMap.get(key);
        Arrays.stream(key.split(",")).map(String::trim)
            .forEach(splitKey -> flattenedMap.put(splitKey, value));
      }
    }
    return flattenedMap;
  }

  private static MapPartitionsFunction<Tuple2<PathProxy, PathProxy>, String> findOrphanFilesMapPartitions(
      PrefixMismatchMode mode,
      SetAccumulator<Pair<String, String>> conflicts) {
    return rows -> {
      Iterator<String> transformed = Iterators.transform(rows, row -> {
        PathProxy actual = row._1;
        PathProxy valid = row._2;
        if (valid == null) {
          return actual.getFilePath();
        }
        boolean schemeMatch = Strings.isNullOrEmpty(valid.getScheme()) ||
            valid.getScheme().equalsIgnoreCase(actual.getScheme());
        boolean authorityMatch = Strings.isNullOrEmpty(valid.getAuthority()) ||
            valid.getAuthority().equalsIgnoreCase(actual.getAuthority());
        if ((!schemeMatch || !authorityMatch) && mode == PrefixMismatchMode.DELETE) {
          return actual.getFilePath();
        } else {
          if (!schemeMatch) {
            conflicts.add(Pair.of(valid.getScheme(), actual.getScheme()));
          }
          if (!authorityMatch) {
            conflicts.add(Pair.of(valid.getAuthority(), actual.getAuthority()));
          }
        }
        return null;
      });
      return Iterators.filter(transformed, Objects::nonNull);
    };
  }

  private static MapPartitionsFunction<Row, PathProxy> toFileMetadata(
      Map<String, String> equalSchemesMap, Map<String, String> equalAuthoritiesMap) {
    return rows -> Iterators.transform(rows, row -> {
      String filePathAsString = row.getString(0);
      URI uri = new Path(filePathAsString).toUri();
      String scheme = equalSchemesMap.getOrDefault(uri.getScheme(), uri.getScheme());
      String authority = equalAuthoritiesMap.getOrDefault(uri.getAuthority(), uri.getAuthority());
      return new PathProxy(scheme, authority, uri.getPath(), filePathAsString);
    });
  }


  /**
   * A {@link PathFilter} that filters out hidden path, but does not filter out paths that would be marked
   * as hidden by {@link HiddenPathFilter} due to a partition field that starts with one of the characters that
   * indicate a hidden path.
   */
  @VisibleForTesting
  static class PartitionAwareHiddenPathFilter implements PathFilter, Serializable {

    private final Set<String> hiddenPathPartitionNames;

    PartitionAwareHiddenPathFilter(Set<String> hiddenPathPartitionNames) {
      this.hiddenPathPartitionNames = hiddenPathPartitionNames;
    }

    @Override
    public boolean accept(Path path) {
      boolean isHiddenPartitionPath = hiddenPathPartitionNames.stream().anyMatch(path.getName()::startsWith);
      return isHiddenPartitionPath || HiddenPathFilter.get().accept(path);
    }

    static PathFilter forSpecs(Map<Integer, PartitionSpec> specs) {
      if (specs == null) {
        return HiddenPathFilter.get();
      }

      Set<String> partitionNames = specs.values().stream()
          .map(PartitionSpec::fields)
          .flatMap(List::stream)
          .filter(partitionField -> partitionField.name().startsWith("_") || partitionField.name().startsWith("."))
          .map(partitionField -> partitionField.name() + "=")
          .collect(Collectors.toSet());

      return partitionNames.isEmpty() ? HiddenPathFilter.get() : new PartitionAwareHiddenPathFilter(partitionNames);
    }
  }

  public static class PathProxy implements Serializable {
    private String scheme;
    private String authority;
    private String path;
    private String filePath;

    public PathProxy(String scheme, String authority, String path, String filePathAsString) {
      this.scheme = scheme;
      this.authority = authority;
      this.path = path;
      this.filePath = filePathAsString;
    }

    public PathProxy() {
    }

    public String getScheme() {
      return scheme;
    }

    public String getAuthority() {
      return authority;
    }

    public String getPath() {
      return path;
    }

    public String getFilePath() {
      return filePath;
    }

    public void setScheme(String scheme) {
      this.scheme = scheme;
    }

    public void setAuthority(String authority) {
      this.authority = authority;
    }

    public void setPath(String path) {
      this.path = path;
    }

    public void setFilePath(String filePath) {
      this.filePath = filePath;
    }
  }
}

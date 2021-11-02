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

package org.apache.iceberg.data;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.Metrics;
import org.apache.iceberg.MetricsConfig;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.avro.Avro;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.hadoop.HadoopInputFile;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.mapping.NameMapping;
import org.apache.iceberg.orc.OrcMetrics;
import org.apache.iceberg.parquet.ParquetSchemaUtil;
import org.apache.iceberg.parquet.ParquetUtil;
import org.apache.iceberg.relocated.com.google.common.base.Joiner;
import org.apache.iceberg.types.ImportCompatibilityChecker;
import org.apache.iceberg.types.TypeUtil;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TableMigrationUtil {

  private static final PathFilter HIDDEN_PATH_FILTER =
      p -> !p.getName().startsWith("_") && !p.getName().startsWith(".");

  private static final Logger LOG = LoggerFactory.getLogger(TableMigrationUtil.class);

  private TableMigrationUtil() {
  }

  /**
   * Returns the data files in a partition by listing the partition location.
   * <p>
   * For Parquet and ORC partitions, this will read metrics from the file footer. For Avro partitions,
   * metrics are set to null.
   * <p>
   * Note:
   * 1. certain metrics, like NaN counts, that are only supported by iceberg file writers but not file footers,
   * will not be populated.
   * 2. Schema compatibility checks are currently done only for parquet file format
   *
   * @param partition partition key, e.g., "a=1/b=2"
   * @param uri           partition location URI
   * @param format        partition format, avro, parquet or orc
   * @param spec          a partition spec
   * @param conf          a Hadoop conf
   * @param metricsConfig a metrics conf
   * @param mapping       a name mapping
   * @param schema        table's schema
   * @return a List of DataFile
   */
  public static List<DataFile> listPartition(Map<String, String> partition, String uri, String format,
                                             PartitionSpec spec, Configuration conf, MetricsConfig metricsConfig,
                                             NameMapping mapping, Schema schema) {
    if (format.contains("avro")) {
      return listAvroPartition(partition, uri, spec, conf);
    } else if (format.contains("parquet")) {
      return listParquetPartition(partition, uri, spec, conf, metricsConfig, mapping, schema);
    } else if (format.contains("orc")) {
      return listOrcPartition(partition, uri, spec, conf, metricsConfig, mapping);
    } else {
      throw new UnsupportedOperationException("Unknown partition format: " + format);
    }
  }

  private static List<DataFile> listAvroPartition(Map<String, String> partitionPath, String partitionUri,
                                                  PartitionSpec spec, Configuration conf) {
    try {
      Path partition = new Path(partitionUri);
      FileSystem fs = partition.getFileSystem(conf);
      return Arrays.stream(fs.listStatus(partition, HIDDEN_PATH_FILTER))
          .filter(FileStatus::isFile)
          .map(stat -> {
            InputFile file = HadoopInputFile.fromLocation(stat.getPath().toString(), conf);
            long rowCount = Avro.rowCount(file);
            Metrics metrics = new Metrics(rowCount, null, null, null, null);
            String partitionKey = spec.fields().stream()
                .map(PartitionField::name)
                .map(name -> String.format("%s=%s", name, partitionPath.get(name)))
                .collect(Collectors.joining("/"));

            return DataFiles.builder(spec)
                .withPath(stat.getPath().toString())
                .withFormat("avro")
                .withFileSizeInBytes(stat.getLen())
                .withMetrics(metrics)
                .withPartitionPath(partitionKey)
                .build();

          }).collect(Collectors.toList());
    } catch (IOException e) {
      throw new RuntimeException("Unable to list files in partition: " + partitionUri, e);
    }
  }

  private static List<DataFile> listParquetPartition(Map<String, String> partitionPath, String partitionUri,
                                                     PartitionSpec spec, Configuration conf,
                                                     MetricsConfig metricsSpec, NameMapping mapping, Schema schema) {
    try {
      Path partition = new Path(partitionUri);
      FileSystem fs = partition.getFileSystem(conf);

      return Arrays.stream(fs.listStatus(partition, HIDDEN_PATH_FILTER))
          .filter(FileStatus::isFile)
          .map(stat -> {
            Metrics metrics;
            try {
              ParquetMetadata metadata = ParquetFileReader.readFooter(conf, stat);
              metrics = ParquetUtil.footerMetrics(metadata, Stream.empty(), metricsSpec, mapping);
              // Checks if the imported file schema is compatible with schema of the table to which it is imported
              canImportSchema(ParquetSchemaUtil.convert(metadata.getFileMetaData().getSchema()), schema);
            } catch (IOException e) {
              throw new RuntimeException("Unable to read the footer of the parquet file: " +
                  stat.getPath(), e);
            }
            String partitionKey = spec.fields().stream()
                .map(PartitionField::name)
                .map(name -> String.format("%s=%s", name, partitionPath.get(name)))
                .collect(Collectors.joining("/"));

            return DataFiles.builder(spec)
                .withPath(stat.getPath().toString())
                .withFormat("parquet")
                .withFileSizeInBytes(stat.getLen())
                .withMetrics(metrics)
                .withPartitionPath(partitionKey)
                .build();
          }).collect(Collectors.toList());
    } catch (IOException e) {
      throw new RuntimeException("Unable to list files in partition: " + partitionUri, e);
    }
  }

  private static List<DataFile> listOrcPartition(Map<String, String> partitionPath, String partitionUri,
                                                 PartitionSpec spec, Configuration conf,
                                                 MetricsConfig metricsSpec, NameMapping mapping) {
    try {
      Path partition = new Path(partitionUri);
      FileSystem fs = partition.getFileSystem(conf);

      return Arrays.stream(fs.listStatus(partition, HIDDEN_PATH_FILTER))
          .filter(FileStatus::isFile)
          .map(stat -> {
            Metrics metrics = OrcMetrics.fromInputFile(HadoopInputFile.fromPath(stat.getPath(), conf),
                metricsSpec, mapping);
            String partitionKey = spec.fields().stream()
                .map(PartitionField::name)
                .map(name -> String.format("%s=%s", name, partitionPath.get(name)))
                .collect(Collectors.joining("/"));

            return DataFiles.builder(spec)
                .withPath(stat.getPath().toString())
                .withFormat("orc")
                .withFileSizeInBytes(stat.getLen())
                .withMetrics(metrics)
                .withPartitionPath(partitionKey)
                .build();

          }).collect(Collectors.toList());
    } catch (IOException e) {
      throw new RuntimeException("Unable to list files in partition: " + partitionUri, e);
    }
  }

  /**
   * Check the if the schemas are compatible.
   * Throws {@link ValidationException} when incompatible
   * @param importSchema the schema of file being imported
   * @param tableSchema schema of the table to which file is to be imported
   */
  public static void canImportSchema(Schema importSchema, Schema tableSchema) {
    // Assigning ids by name look up, required for checking compatibility
    Schema schemaWithIds = TypeUtil.assignFreshIds(importSchema, tableSchema, new AtomicInteger(1000)::incrementAndGet);
    List<String> errors = ImportCompatibilityChecker.importCompatibilityErrors(tableSchema, schemaWithIds, false);
    if (!errors.isEmpty()) {
      String errorString = Joiner.on("\n\t").join(errors);
      throw new ValidationException("Imported file's schema not compatible with table's schema." +
          " Errors : %s", errorString);
    }
  }
}

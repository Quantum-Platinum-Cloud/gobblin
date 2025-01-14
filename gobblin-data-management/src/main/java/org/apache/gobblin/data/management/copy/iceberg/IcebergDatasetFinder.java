/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.data.management.copy.iceberg;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.gobblin.dataset.DatasetConstants;
import org.apache.gobblin.dataset.IterableDatasetFinder;
import org.apache.gobblin.util.HadoopUtils;

/**
 * Finds {@link IcebergDataset}s. Will look for tables in a database using a {@link IcebergCatalog},
 * and creates a {@link IcebergDataset} for each one.
 */
@Slf4j
@RequiredArgsConstructor
public class IcebergDatasetFinder implements IterableDatasetFinder<IcebergDataset> {

  public static final String ICEBERG_DATASET_PREFIX = DatasetConstants.PLATFORM_ICEBERG + ".dataset";
  public static final String ICEBERG_CLUSTER_KEY = "cluster";
  public static final String ICEBERG_DB_NAME = ICEBERG_DATASET_PREFIX + ".database.name";
  public static final String ICEBERG_TABLE_NAME = ICEBERG_DATASET_PREFIX + ".table.name";
  public static final String ICEBERG_SRC_CATALOG_CLASS_KEY = ICEBERG_DATASET_PREFIX + ".source.catalog.class";
  public static final String ICEBERG_SRC_CATALOG_URI_KEY = ICEBERG_DATASET_PREFIX + ".source.catalog.uri";
  public static final String DEFAULT_ICEBERG_CATALOG_CLASS = "org.apache.gobblin.data.management.copy.iceberg.IcebergHiveCatalog";
  public static final String ICEBERG_SRC_CLUSTER_NAME = ICEBERG_DATASET_PREFIX + ".source.cluster.name";

  protected final FileSystem sourceFs;
  private final Properties properties;

  /**
   * Finds all {@link IcebergDataset}s in the file system using the Iceberg Catalog.
   * Both Iceberg database name and table name are mandatory based on current implementation.
   * Later we may explore supporting datasets similar to Hive
   * @return List of {@link IcebergDataset}s in the file system.
   * @throws IOException
   */
  @Override
  public List<IcebergDataset> findDatasets() throws IOException {
    List<IcebergDataset> matchingDatasets = new ArrayList<>();
    if (StringUtils.isBlank(properties.getProperty(ICEBERG_DB_NAME)) || StringUtils.isBlank(properties.getProperty(ICEBERG_TABLE_NAME))) {
      throw new IllegalArgumentException(String.format("Iceberg database name: {%s} or Iceberg table name: {%s} is missing",
          ICEBERG_DB_NAME, ICEBERG_TABLE_NAME));
    }
    String dbName = properties.getProperty(ICEBERG_DB_NAME);
    String tblName = properties.getProperty(ICEBERG_TABLE_NAME);

    try {
      IcebergCatalog icebergCatalog = createIcebergCatalog(this.properties);
      /* Each Iceberg dataset maps to an Iceberg table
       * TODO: The user provided database and table names needs to be pre-checked and verified against the existence of a valid Iceberg table
       */
      matchingDatasets.add(createIcebergDataset(dbName, tblName, icebergCatalog, properties, sourceFs));
      log.info("Found {} matching datasets: {} for the database name: {} and table name: {}", matchingDatasets.size(),
          matchingDatasets, dbName, tblName);
      return matchingDatasets;
    } catch (ReflectiveOperationException exception) {
      throw new IOException(exception);
    }
  }

  @Override
  public Path commonDatasetRoot() {
    return new Path("/");
  }

  @Override
  public Iterator<IcebergDataset> getDatasetsIterator() throws IOException {
    return findDatasets().iterator();
  }

  protected IcebergDataset createIcebergDataset(String dbName, String tblName, IcebergCatalog icebergCatalog, Properties properties, FileSystem fs) {
    IcebergTable icebergTable = icebergCatalog.openTable(dbName, tblName);
    return new IcebergDataset(dbName, tblName, icebergTable, properties, fs);
  }

  protected IcebergCatalog createIcebergCatalog(Properties properties) throws IOException, ClassNotFoundException {
    Map<String, String> catalogProperties = new HashMap<>();
    String catalogUri = properties.getProperty(ICEBERG_SRC_CATALOG_URI_KEY);
    Preconditions.checkNotNull(catalogUri, "Catalog Table Service URI is required");
    catalogProperties.put(CatalogProperties.URI, catalogUri);
    // introducing an optional property for catalogs requiring cluster specific properties
    Optional.ofNullable(properties.getProperty(ICEBERG_SRC_CLUSTER_NAME)).ifPresent(value -> catalogProperties.put(ICEBERG_CLUSTER_KEY, value));
    Configuration configuration = HadoopUtils.getConfFromProperties(properties);
    String icebergCatalogClassName = properties.getProperty(ICEBERG_SRC_CATALOG_CLASS_KEY, DEFAULT_ICEBERG_CATALOG_CLASS);
    return IcebergCatalogFactory.create(icebergCatalogClassName, catalogProperties, configuration);
  }
}

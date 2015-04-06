/**
 * Copyright (C) 2014-2015 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.query.executor;

import com.linkedin.pinot.common.metrics.ServerMetrics;
import com.yammer.metrics.core.MetricsRegistry;
import com.linkedin.pinot.util.TestUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.linkedin.pinot.common.request.AggregationInfo;
import com.linkedin.pinot.common.request.BrokerRequest;
import com.linkedin.pinot.common.request.FilterQuery;
import com.linkedin.pinot.common.request.InstanceRequest;
import com.linkedin.pinot.common.request.QuerySource;
import com.linkedin.pinot.common.segment.ReadMode;
import com.linkedin.pinot.common.utils.DataTable;
import com.linkedin.pinot.core.data.manager.config.FileBasedInstanceDataManagerConfig;
import com.linkedin.pinot.core.data.manager.offline.FileBasedInstanceDataManager;
import com.linkedin.pinot.core.indexsegment.IndexSegment;
import com.linkedin.pinot.core.indexsegment.columnar.ColumnarSegmentLoader;
import com.linkedin.pinot.core.indexsegment.generator.SegmentGeneratorConfig;
import com.linkedin.pinot.core.query.executor.ServerQueryExecutorV1Impl;
import com.linkedin.pinot.core.segment.creator.SegmentIndexCreationDriver;
import com.linkedin.pinot.core.segment.creator.impl.SegmentCreationDriverFactory;
import com.linkedin.pinot.segments.v1.creator.SegmentTestUtils;


public class TestQueryExecutor {

  private final String SMALL_AVRO_DATA = "data/simpleData200001.avro";
  private static File INDEXES_DIR = new File(FileUtils.getTempDirectory() + File.separator + "TestQueryExecutorList");

  private List<IndexSegment> _indexSegmentList = new ArrayList<IndexSegment>();

  private static ServerQueryExecutorV1Impl _queryExecutor;

  private static Logger LOGGER = LoggerFactory.getLogger(TestQueryExecutor.class);
  public static final String PINOT_PROPERTIES = "pinot.properties";

  @BeforeClass
  public void setup() throws Exception {
    File confDir = new File(TestQueryExecutor.class.getClassLoader().getResource("conf").toURI());
    setupSegmentList(2);
    // ServerBuilder serverBuilder = new ServerBuilder(confDir.getAbsolutePath());
    String configFilePath = confDir.getAbsolutePath();

    // build _serverConf
    PropertiesConfiguration serverConf = new PropertiesConfiguration();
    serverConf.setDelimiterParsingDisabled(false);
    serverConf.load(new File(configFilePath, PINOT_PROPERTIES));

    FileBasedInstanceDataManager instanceDataManager = FileBasedInstanceDataManager.getInstanceDataManager();
    instanceDataManager.init(new FileBasedInstanceDataManagerConfig(serverConf.subset("pinot.server.instance")));
    instanceDataManager.start();

    for (int i = 0; i < 2; ++i) {
      instanceDataManager.getResourceDataManager("midas");
      instanceDataManager.getResourceDataManager("midas").addSegment(_indexSegmentList.get(i));
    }
    _queryExecutor = new ServerQueryExecutorV1Impl();
    _queryExecutor.init(serverConf.subset("pinot.server.query.executor"), instanceDataManager, new ServerMetrics(
        new MetricsRegistry()));
  }

  @AfterClass
  public void tearDown() {
    if (INDEXES_DIR.exists()) {
      FileUtils.deleteQuietly(INDEXES_DIR);
    }
  }

  private void setupSegmentList(int numberOfSegments) throws Exception {
    final String filePath = TestUtils.getFileFromResourceUrl(getClass().getClassLoader().getResource(SMALL_AVRO_DATA));
    _indexSegmentList.clear();
    if (INDEXES_DIR.exists()) {
      FileUtils.deleteQuietly(INDEXES_DIR);
    }
    INDEXES_DIR.mkdir();

    for (int i = 0; i < numberOfSegments; ++i) {
      final File segmentDir = new File(INDEXES_DIR, "segment_" + i);

      final SegmentGeneratorConfig config =
          SegmentTestUtils.getSegmentGenSpecWithSchemAndProjectedColumns(new File(filePath), segmentDir, "dim" + i,
              TimeUnit.DAYS, "midas", "testTable");
      config.setSegmentNamePostfix(String.valueOf(i));
      final SegmentIndexCreationDriver driver = SegmentCreationDriverFactory.get(null);
      driver.init(config);
      driver.build();

      File parent = new File(INDEXES_DIR, "segment_" + String.valueOf(i));
      String segmentName = parent.list()[0];
      _indexSegmentList.add(ColumnarSegmentLoader.load(new File(parent, segmentName), ReadMode.mmap));

      System.out.println("built at : " + segmentDir.getAbsolutePath());
    }
  }

  @Test
  public void testCountQuery() {

    BrokerRequest brokerRequest = getCountQuery();

    QuerySource querySource = new QuerySource();
    querySource.setResourceName("midas");
    querySource.setTableName("testTable");
    brokerRequest.setQuerySource(querySource);
    InstanceRequest instanceRequest = new InstanceRequest(0, brokerRequest);
    instanceRequest.setSearchSegments(new ArrayList<String>());
    for (IndexSegment segment : _indexSegmentList) {
      instanceRequest.getSearchSegments().add(segment.getSegmentName());
    }


    try {
      DataTable instanceResponse = _queryExecutor.processQuery(instanceRequest);
      LOGGER.info("InstanceResponse is " + instanceResponse.getLong(0, 0));
      Assert.assertEquals(instanceResponse.getLong(0, 0), 400002L);
      LOGGER.info("Time used for instanceResponse is " + instanceResponse.getMetadata().get("timeUsedMs"));
    } catch (Exception e) {
      e.printStackTrace();
      // Should never happen
      Assert.assertEquals(true, false);
    }
  }

  @Test
  public void testSumQuery() {
    BrokerRequest brokerRequest = getSumQuery();

    QuerySource querySource = new QuerySource();
    querySource.setResourceName("midas");
    querySource.setTableName("testTable");
    brokerRequest.setQuerySource(querySource);
    InstanceRequest instanceRequest = new InstanceRequest(0, brokerRequest);
    instanceRequest.setSearchSegments(new ArrayList<String>());
    for (IndexSegment segment : _indexSegmentList) {
      instanceRequest.getSearchSegments().add(segment.getSegmentName());
    }
    try {
      DataTable instanceResponse = _queryExecutor.processQuery(instanceRequest);
      LOGGER.info("InstanceResponse is " + instanceResponse.getDouble(0, 0));
      Assert.assertEquals(instanceResponse.getDouble(0, 0), 40000200000.0);
      LOGGER.info("Time used for instanceResponse is " + instanceResponse.getMetadata().get("timeUsedMs"));
    } catch (Exception e) {
      e.printStackTrace();
      // Should never happen
      Assert.assertEquals(true, false);
    }

  }

  @Test
  public void testMaxQuery() {

    BrokerRequest brokerRequest = getMaxQuery();

    QuerySource querySource = new QuerySource();
    querySource.setResourceName("midas");
    querySource.setTableName("testTable");
    brokerRequest.setQuerySource(querySource);
    InstanceRequest instanceRequest = new InstanceRequest(0, brokerRequest);
    instanceRequest.setSearchSegments(new ArrayList<String>());
    for (IndexSegment segment : _indexSegmentList) {
      instanceRequest.getSearchSegments().add(segment.getSegmentName());
    }
    try {
      DataTable instanceResponse = _queryExecutor.processQuery(instanceRequest);
      LOGGER.info("InstanceResponse is " + instanceResponse.getDouble(0, 0));
      Assert.assertEquals(instanceResponse.getDouble(0, 0), 200000.0);

      LOGGER.info("Time used for instanceResponse is " + instanceResponse.getMetadata().get("timeUsedMs"));
    } catch (Exception e) {
      e.printStackTrace();
      // Should never happen
      Assert.assertEquals(true, false);
    }

  }

  @Test
  public void testMinQuery() {
    BrokerRequest brokerRequest = getMinQuery();

    QuerySource querySource = new QuerySource();
    querySource.setResourceName("midas");
    querySource.setTableName("testTable");
    brokerRequest.setQuerySource(querySource);
    InstanceRequest instanceRequest = new InstanceRequest(0, brokerRequest);
    instanceRequest.setSearchSegments(new ArrayList<String>());
    for (IndexSegment segment : _indexSegmentList) {
      instanceRequest.getSearchSegments().add(segment.getSegmentName());
    }
    try {
      DataTable instanceResponse = _queryExecutor.processQuery(instanceRequest);
      LOGGER.info("InstanceResponse is " + instanceResponse.getDouble(0, 0));
      Assert.assertEquals(instanceResponse.getDouble(0, 0), 0.0);
      LOGGER.info("Time used for instanceResponse is " + instanceResponse.getMetadata().get("timeUsedMs"));
    } catch (Exception e) {
      e.printStackTrace();
      // Should never happen
      Assert.assertEquals(true, false);
    }

  }

  private BrokerRequest getCountQuery() {
    BrokerRequest query = new BrokerRequest();
    AggregationInfo aggregationInfo = getCountAggregationInfo();
    List<AggregationInfo> aggregationsInfo = new ArrayList<AggregationInfo>();
    aggregationsInfo.add(aggregationInfo);
    query.setAggregationsInfo(aggregationsInfo);
    FilterQuery filterQuery = getFilterQuery();
    query.setFilterQuery(filterQuery);
    return query;
  }

  private BrokerRequest getSumQuery() {
    BrokerRequest query = new BrokerRequest();
    AggregationInfo aggregationInfo = getSumAggregationInfo();
    List<AggregationInfo> aggregationsInfo = new ArrayList<AggregationInfo>();
    aggregationsInfo.add(aggregationInfo);
    query.setAggregationsInfo(aggregationsInfo);
    FilterQuery filterQuery = getFilterQuery();
    query.setFilterQuery(filterQuery);
    return query;
  }

  private BrokerRequest getMaxQuery() {
    BrokerRequest query = new BrokerRequest();
    AggregationInfo aggregationInfo = getMaxAggregationInfo();
    List<AggregationInfo> aggregationsInfo = new ArrayList<AggregationInfo>();
    aggregationsInfo.add(aggregationInfo);
    query.setAggregationsInfo(aggregationsInfo);
    FilterQuery filterQuery = getFilterQuery();
    query.setFilterQuery(filterQuery);
    return query;
  }

  private BrokerRequest getMinQuery() {
    BrokerRequest query = new BrokerRequest();
    AggregationInfo aggregationInfo = getMinAggregationInfo();
    List<AggregationInfo> aggregationsInfo = new ArrayList<AggregationInfo>();
    aggregationsInfo.add(aggregationInfo);
    query.setAggregationsInfo(aggregationsInfo);
    FilterQuery filterQuery = getFilterQuery();
    query.setFilterQuery(filterQuery);
    return query;
  }

  private FilterQuery getFilterQuery() {
    FilterQuery filterQuery = new FilterQuery();
    return null;
  }

  private AggregationInfo getCountAggregationInfo() {
    String type = "count";
    Map<String, String> params = new HashMap<String, String>();
    params.put("column", "met");

    AggregationInfo aggregationInfo = new AggregationInfo();
    aggregationInfo.setAggregationType(type);
    aggregationInfo.setAggregationParams(params);
    return aggregationInfo;
  }

  private AggregationInfo getSumAggregationInfo() {
    String type = "sum";
    Map<String, String> params = new HashMap<String, String>();
    params.put("column", "met");

    AggregationInfo aggregationInfo = new AggregationInfo();
    aggregationInfo.setAggregationType(type);
    aggregationInfo.setAggregationParams(params);
    return aggregationInfo;
  }

  private AggregationInfo getMaxAggregationInfo() {
    String type = "max";
    Map<String, String> params = new HashMap<String, String>();
    params.put("column", "met");

    AggregationInfo aggregationInfo = new AggregationInfo();
    aggregationInfo.setAggregationType(type);
    aggregationInfo.setAggregationParams(params);
    return aggregationInfo;
  }

  private AggregationInfo getMinAggregationInfo() {
    String type = "min";
    Map<String, String> params = new HashMap<String, String>();
    params.put("column", "met");

    AggregationInfo aggregationInfo = new AggregationInfo();
    aggregationInfo.setAggregationType(type);
    aggregationInfo.setAggregationParams(params);
    return aggregationInfo;
  }
}

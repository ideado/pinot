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
package com.linkedin.pinot.integration.tests;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.avro.file.DataFileStream;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;
import org.apache.commons.configuration.Configuration;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedin.pinot.broker.broker.BrokerTestUtils;
import com.linkedin.pinot.broker.broker.helix.HelixBrokerStarter;
import com.linkedin.pinot.common.data.Schema;
import com.linkedin.pinot.common.request.helper.ControllerRequestBuilder;
import com.linkedin.pinot.common.utils.CommonConstants.Helix;
import com.linkedin.pinot.common.utils.CommonConstants.Helix.DataSource;
import com.linkedin.pinot.common.utils.CommonConstants.Helix.DataSource.Realtime.Kafka;
import com.linkedin.pinot.common.utils.CommonConstants.Server;
import com.linkedin.pinot.common.utils.FileUploadUtils;
import com.linkedin.pinot.common.utils.ZkStarter;
import com.linkedin.pinot.controller.helix.ControllerRequestURLBuilder;
import com.linkedin.pinot.controller.helix.ControllerTest;
import com.linkedin.pinot.controller.helix.ControllerTestUtils;
import com.linkedin.pinot.core.data.GenericRow;
import com.linkedin.pinot.core.indexsegment.utils.AvroUtils;
import com.linkedin.pinot.core.realtime.impl.kafka.AvroRecordToPinotRowGenerator;
import com.linkedin.pinot.core.realtime.impl.kafka.KafkaMessageDecoder;
import com.linkedin.pinot.server.starter.helix.DefaultHelixStarterServerConfig;
import com.linkedin.pinot.server.starter.helix.HelixServerStarter;


/**
 * Base class for integration tests that involve a complete Pinot cluster.
 *
 */
public abstract class ClusterTest extends ControllerTest {
  private static final String _success = "success";
  protected List<HelixBrokerStarter> _brokerStarters = new ArrayList<HelixBrokerStarter>();
  protected List<HelixServerStarter> _serverStarters = new ArrayList<HelixServerStarter>();

  protected void startBroker() {
    startBrokers(1);
  }

  protected void startBrokers(int brokerCount) {
    try {
      for (int i = 0; i < brokerCount; ++i) {
        final String helixClusterName = getHelixClusterName();
        Configuration configuration = BrokerTestUtils.getDefaultBrokerConfiguration();
        configuration.setProperty("pinot.broker.time.out", 100 * 1000L);
        configuration.setProperty("pinot.broker.client.queryPort", Integer.toString(18099 + i));
        configuration.setProperty("pinot.broker.routing.table.builder.class", "random");
        overrideBrokerConf(configuration);
        _brokerStarters.add(BrokerTestUtils.startBroker(helixClusterName, ZkStarter.DEFAULT_ZK_STR, configuration));
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected void startServer() {
    startServers(1);
  }

  protected void startServers(int serverCount) {
    try {
      for (int i = 0; i < serverCount; i++) {
        Configuration configuration = DefaultHelixStarterServerConfig.loadDefaultServerConf();
        configuration.setProperty(Server.CONFIG_OF_INSTANCE_DATA_DIR, Server.DEFAULT_INSTANCE_DATA_DIR + "-" + i);
        configuration.setProperty(Server.CONFIG_OF_INSTANCE_SEGMENT_TAR_DIR, Server.DEFAULT_INSTANCE_SEGMENT_TAR_DIR
            + "-" + i);
        configuration.setProperty(Server.CONFIG_OF_NETTY_PORT,
            Integer.toString(Integer.valueOf(Helix.DEFAULT_SERVER_NETTY_PORT) + i));
        overrideOfflineServerConf(configuration);
        _serverStarters.add(new HelixServerStarter(getHelixClusterName(), ZkStarter.DEFAULT_ZK_STR, configuration));
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected void overrideOfflineServerConf(Configuration configuration) {
    // Do nothing, to be overridden by tests if they need something specific
  }

  protected void overrideBrokerConf(Configuration configuration) {
    // Do nothing, to be overridden by tests if they need something specific
  }

  protected void stopBroker() {
    for (HelixBrokerStarter brokerStarter : _brokerStarters) {
      BrokerTestUtils.stopBroker(brokerStarter);
    }
  }

  protected void stopServer() {
    for (HelixServerStarter helixServerStarter : _serverStarters) {
      if (helixServerStarter != null) {
        helixServerStarter.stop();
      }
    }
  }

  protected void addSchema(File schemaFile, String schemaName) throws Exception {
    FileUploadUtils.sendFile("localhost", ControllerTestUtils.DEFAULT_CONTROLLER_API_PORT, "schemas", schemaName,
        new FileInputStream(schemaFile), schemaFile.length());
  }

  protected void addOfflineTable(String tableName, String timeColumnName, String timeColumnType,
      int retentionTimeValue, String retentionTimeUnit, String brokerTenant, String serverTenant) throws Exception {
    JSONObject request =
        ControllerRequestBuilder.buildCreateOfflineTableJSON(tableName, serverTenant, brokerTenant, timeColumnName,
            "DAYS", retentionTimeUnit, String.valueOf(retentionTimeValue), 3, "BalanceNumSegmentAssignmentStrategy");
    sendPostRequest(ControllerRequestURLBuilder.baseUrl(CONTROLLER_BASE_API_URL).forTableCreate(), request.toString());
  }

  public static class AvroFileSchemaKafkaAvroMessageDecoder implements KafkaMessageDecoder {
    private static final Logger LOGGER = LoggerFactory.getLogger(AvroFileSchemaKafkaAvroMessageDecoder.class);
    public static File avroFile;
    private org.apache.avro.Schema _avroSchema;
    private AvroRecordToPinotRowGenerator _rowGenerator;
    private DecoderFactory _decoderFactory = new DecoderFactory();
    private DatumReader<GenericData.Record> _reader;

    @Override
    public void init(Map<String, String> props, Schema indexingSchema, String kafkaTopicName) throws Exception {
      // Load Avro schema
      DataFileStream<GenericRecord> reader = AvroUtils.getAvroReader(avroFile);
      _avroSchema = reader.getSchema();
      reader.close();
      _rowGenerator = new AvroRecordToPinotRowGenerator(indexingSchema);
      _reader = new GenericDatumReader<GenericData.Record>(_avroSchema);
    }

    @Override
    public GenericRow decode(byte[] payload) {
      try {
        GenericData.Record avroRecord =
            _reader.read(null, _decoderFactory.binaryDecoder(payload, 0, payload.length, null));
        return _rowGenerator.transform(avroRecord, _avroSchema);
      } catch (Exception e) {
        LOGGER.error("Caught exception", e);
        throw new RuntimeException(e);
      }
    }
  }

  protected void addRealtimeTable(String tableName, String timeColumnName, String timeColumnType, String kafkaZkUrl,
      String kafkaTopic, String schemaName, String serverTenant, String brokerTenant, File avroFile) throws Exception {
    JSONObject metadata = new JSONObject();
    metadata.put("streamType", "kafka");
    metadata.put(DataSource.STREAM_PREFIX + "." + Kafka.CONSUMER_TYPE, Kafka.ConsumerType.highLevel.toString());
    metadata.put(DataSource.STREAM_PREFIX + "." + Kafka.TOPIC_NAME, kafkaTopic);
    metadata.put(DataSource.STREAM_PREFIX + "." + Kafka.DECODER_CLASS,
        AvroFileSchemaKafkaAvroMessageDecoder.class.getName());
    metadata.put(DataSource.STREAM_PREFIX + "." + Kafka.ZK_BROKER_URL, kafkaZkUrl);
    metadata.put(DataSource.STREAM_PREFIX + "." + Kafka.HighLevelConsumer.ZK_CONNECTION_STRING, kafkaZkUrl);

    JSONObject request =
        ControllerRequestBuilder
            .buildCreateRealtimeTableJSON(tableName, serverTenant, brokerTenant, timeColumnName, timeColumnType,
                "rententionTimeUnit", "900", 1, "BalanceNumSegmentAssignmentStrategy", metadata, schemaName);
    sendPostRequest(ControllerRequestURLBuilder.baseUrl(CONTROLLER_BASE_API_URL).forTableCreate(), request.toString());

    AvroFileSchemaKafkaAvroMessageDecoder.avroFile = avroFile;

  }

  protected void addHybridTable(String tableName, String timeColumnName, String timeColumnType, String kafkaZkUrl,
      String kafkaTopic, String schemaName, String serverTenant, String brokerTenant, File avroFile) throws Exception {
    addRealtimeTable(tableName, timeColumnName, timeColumnType, kafkaZkUrl, kafkaTopic, schemaName, serverTenant,
        brokerTenant, avroFile);
    addOfflineTable(tableName, timeColumnName, timeColumnType, 900, "Days", brokerTenant, serverTenant);
  }
}

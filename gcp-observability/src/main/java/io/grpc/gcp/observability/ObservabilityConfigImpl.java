/*
 * Copyright 2022 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.gcp.observability;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.internal.JsonParser;
import io.grpc.internal.JsonUtil;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import io.opencensus.trace.Sampler;
import io.opencensus.trace.samplers.Samplers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * gRPC GcpObservability configuration processor.
 */
final class ObservabilityConfigImpl implements ObservabilityConfig {
  private static final String CONFIG_ENV_VAR_NAME = "GRPC_CONFIG_OBSERVABILITY";
  private static final String CONFIG_FILE_ENV_VAR_NAME = "GRPC_CONFIG_OBSERVABILITY_JSON";
  // Tolerance for floating-point comparisons.
  private static final double EPSILON = 1e-6;

  private boolean enableCloudLogging = false;
  private boolean enableCloudMonitoring = false;
  private boolean enableCloudTracing = false;
  private String destinationProjectId = null;
  private Long flushMessageCount = null;
  private List<LogFilter> logFilters;
  private List<EventType> eventTypes;
  private Sampler sampler;
  private Map<String, String> customTags;

  static ObservabilityConfigImpl getInstance() throws IOException {
    ObservabilityConfigImpl config = new ObservabilityConfigImpl();
    String configFile = System.getenv(CONFIG_FILE_ENV_VAR_NAME);
    if (configFile != null) {
      config.parseFile(configFile);
    } else {
      config.parse(System.getenv(CONFIG_ENV_VAR_NAME));
    }
    return config;
  }

  void parseFile(String configFile) throws IOException {
    parse(new String(Files.readAllBytes(Paths.get(configFile)), Charsets.UTF_8));
  }

  @SuppressWarnings("unchecked")
  void parse(String config) throws IOException {
    checkArgument(config != null, CONFIG_ENV_VAR_NAME + " value is null!");
    parseConfig((Map<String, ?>) JsonParser.parse(config));
  }

  private void parseConfig(Map<String, ?> config) {
    if (config != null) {
      Boolean value = JsonUtil.getBoolean(config, "enable_cloud_logging");
      if (value != null) {
        enableCloudLogging = value;
      }
      value = JsonUtil.getBoolean(config, "enable_cloud_monitoring");
      if (value != null) {
        enableCloudMonitoring = value;
      }
      value = JsonUtil.getBoolean(config, "enable_cloud_trace");
      if (value != null) {
        enableCloudTracing = value;
      }
      destinationProjectId = JsonUtil.getString(config, "destination_project_id");
      flushMessageCount = JsonUtil.getNumberAsLong(config, "flush_message_count");
      List<?> rawList = JsonUtil.getList(config, "log_filters");
      if (rawList != null) {
        List<Map<String, ?>> jsonLogFilters = JsonUtil.checkObjectList(rawList);
        ImmutableList.Builder<LogFilter> logFiltersBuilder = new ImmutableList.Builder<>();
        for (Map<String, ?> jsonLogFilter : jsonLogFilters) {
          logFiltersBuilder.add(parseJsonLogFilter(jsonLogFilter));
        }
        this.logFilters = logFiltersBuilder.build();
      }
      rawList = JsonUtil.getList(config, "event_types");
      if (rawList != null) {
        List<String> jsonEventTypes = JsonUtil.checkStringList(rawList);
        ImmutableList.Builder<EventType> eventTypesBuilder = new ImmutableList.Builder<>();
        for (String jsonEventType : jsonEventTypes) {
          eventTypesBuilder.add(convertEventType(jsonEventType));
        }
        this.eventTypes = eventTypesBuilder.build();
      }
      Double samplingRate = JsonUtil.getNumberAsDouble(config, "global_trace_sampling_rate");
      if (samplingRate == null) {
        this.sampler = Samplers.probabilitySampler(0.0);
      } else {
        checkArgument(
            samplingRate >= 0.0 && samplingRate <= 1.0,
            "'global_trace_sampling_rate' needs to be between [0.0, 1.0]");
        // Using alwaysSample() instead of probabilitySampler() because according to
        // {@link io.opencensus.trace.samplers.ProbabilitySampler#shouldSample}
        // there is a (very) small chance of *not* sampling if probability = 1.00.
        if (1 - samplingRate < EPSILON) {
          this.sampler = Samplers.alwaysSample();
        } else {
          this.sampler = Samplers.probabilitySampler(samplingRate);
        }
      }
      Map<String, ?> rawCustomTags = JsonUtil.getObject(config, "custom_tags");
      if (rawCustomTags != null) {
        ImmutableMap.Builder<String, String> builder = new ImmutableMap.Builder<>();
        for (Map.Entry<String, ?> entry: rawCustomTags.entrySet()) {
          checkArgument(
                  entry.getValue() instanceof String,
                  "'custom_tags' needs to be a map of <string, string>");
          builder.put(entry.getKey(), (String) entry.getValue());
        }
        customTags = builder.build();
      }
    }
  }

  private EventType convertEventType(String val) {
    switch (val) {
      case "GRPC_CALL_UNKNOWN":
        return EventType.GRPC_CALL_UNKNOWN;
      case "GRPC_CALL_REQUEST_HEADER":
        return EventType.GRPC_CALL_REQUEST_HEADER;
      case "GRPC_CALL_RESPONSE_HEADER":
        return EventType.GRPC_CALL_RESPONSE_HEADER;
      case "GRPC_CALL_REQUEST_MESSAGE":
        return EventType.GRPC_CALL_REQUEST_MESSAGE;
      case "GRPC_CALL_RESPONSE_MESSAGE":
        return EventType.GRPC_CALL_RESPONSE_MESSAGE;
      case "GRPC_CALL_TRAILER":
        return EventType.GRPC_CALL_TRAILER;
      case "GRPC_CALL_HALF_CLOSE":
        return EventType.GRPC_CALL_HALF_CLOSE;
      case "GRPC_CALL_CANCEL":
        return EventType.GRPC_CALL_CANCEL;
      default:
        throw new IllegalArgumentException("Unknown event type value:" + val);
    }
  }

  private LogFilter parseJsonLogFilter(Map<String, ?> logFilterMap) {
    return new LogFilter(JsonUtil.getString(logFilterMap, "pattern"),
        JsonUtil.getNumberAsInteger(logFilterMap, "header_bytes"),
        JsonUtil.getNumberAsInteger(logFilterMap, "message_bytes"));
  }

  @Override
  public boolean isEnableCloudLogging() {
    return enableCloudLogging;
  }

  @Override
  public boolean isEnableCloudMonitoring() {
    return enableCloudMonitoring;
  }

  @Override
  public boolean isEnableCloudTracing() {
    return enableCloudTracing;
  }

  @Override
  public String getDestinationProjectId() {
    return destinationProjectId;
  }

  @Override
  public Long getFlushMessageCount() {
    return flushMessageCount;
  }

  @Override
  public List<LogFilter> getLogFilters() {
    return logFilters;
  }

  @Override
  public List<EventType> getEventTypes() {
    return eventTypes;
  }

  @Override
  public Sampler getSampler() {
    return sampler;
  }

  @Override
  public Map<String, String> getCustomTags() {
    return customTags;
  }
}

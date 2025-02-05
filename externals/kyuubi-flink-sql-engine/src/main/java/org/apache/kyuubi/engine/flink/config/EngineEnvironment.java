/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.engine.flink.config;

import java.util.Collections;
import java.util.Map;
import org.apache.kyuubi.engine.flink.config.entries.ExecutionEntry;

/**
 * EngineEnvironment configuration that represents the content of an environment file.
 * EngineEnvironment files define engine, session, catalogs, tables, execution, and deployment
 * behavior. An environment might be defined by default or as part of a session. Environments can be
 * merged or enriched with properties.
 */
public class EngineEnvironment {

  public static final String EXECUTION_ENTRY = "execution";

  private ExecutionEntry execution;

  public EngineEnvironment() {
    this.execution = ExecutionEntry.DEFAULT_INSTANCE;
  }

  public ExecutionEntry getExecution() {
    return execution;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    sb.append("=================== Execution ====================\n");
    execution.asTopLevelMap().forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
    return sb.toString();
  }

  // --------------------------------------------------------------------------------------------

  /**
   * Merges two environments. The properties of the first environment might be overwritten by the
   * second one.
   */
  public static EngineEnvironment merge(EngineEnvironment env1, EngineEnvironment env2) {
    final EngineEnvironment mergedEnv = new EngineEnvironment();

    // merge execution properties
    mergedEnv.execution = ExecutionEntry.merge(env1.getExecution(), env2.getExecution());

    return mergedEnv;
  }

  public EngineEnvironment clone() {
    return enrich(this, Collections.emptyMap());
  }

  /** Enriches an environment with new/modified properties or views and returns the new instance. */
  public static EngineEnvironment enrich(EngineEnvironment env, Map<String, String> properties) {
    final EngineEnvironment enrichedEnv = new EngineEnvironment();

    // enrich execution properties
    enrichedEnv.execution = ExecutionEntry.enrich(env.execution, properties);

    return enrichedEnv;
  }
}

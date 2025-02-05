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

package org.apache.kyuubi.engine.flink.context;

import java.lang.reflect.Method;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.apache.commons.cli.Options;
import org.apache.flink.client.ClientUtils;
import org.apache.flink.client.cli.CustomCommandLine;
import org.apache.flink.client.deployment.ClusterClientServiceLoader;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableConfig;
import org.apache.flink.table.api.TableException;
import org.apache.flink.table.api.bridge.java.internal.StreamTableEnvironmentImpl;
import org.apache.flink.table.api.internal.TableEnvironmentInternal;
import org.apache.flink.table.catalog.CatalogManager;
import org.apache.flink.table.catalog.FunctionCatalog;
import org.apache.flink.table.catalog.GenericInMemoryCatalog;
import org.apache.flink.table.delegation.Executor;
import org.apache.flink.table.delegation.ExecutorFactory;
import org.apache.flink.table.delegation.Planner;
import org.apache.flink.table.delegation.PlannerFactory;
import org.apache.flink.table.factories.ComponentFactoryService;
import org.apache.flink.table.module.ModuleManager;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.TemporaryClassLoaderContext;
import org.apache.kyuubi.engine.flink.config.EngineEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Context for executing table programs. This class caches everything that can be cached across
 * multiple queries as long as the session context does not change. This must be thread-safe as it
 * might be reused across different query submissions.
 *
 * @param <ClusterID> cluster id
 */
public class ExecutionContext<ClusterID> {

  private static final Logger LOG = LoggerFactory.getLogger(ExecutionContext.class);

  private final EngineEnvironment engineEnvironment;
  private final ClassLoader classLoader;

  private final Configuration flinkConfig;

  private TableEnvironmentInternal tableEnv;
  private StreamExecutionEnvironment streamExecEnv;
  private Executor executor;

  private ExecutionContext(
      EngineEnvironment engineEnvironment,
      List<URL> dependencies,
      Configuration flinkConfig,
      ClusterClientServiceLoader clusterClientServiceLoader,
      Options commandLineOptions,
      List<CustomCommandLine> availableCommandLines)
      throws FlinkException {
    this.engineEnvironment = engineEnvironment;

    this.flinkConfig = flinkConfig;

    // create class loader
    classLoader =
        ClientUtils.buildUserCodeClassLoader(
            dependencies, Collections.emptyList(), this.getClass().getClassLoader(), flinkConfig);

    // Initialize the TableEnvironment.
    initializeTableEnvironment();
  }

  /**
   * Executes the given supplier using the execution context's classloader as thread classloader.
   */
  public <R> R wrapClassLoader(Supplier<R> supplier) {
    try (TemporaryClassLoaderContext ignored = TemporaryClassLoaderContext.of(classLoader)) {
      return supplier.get();
    }
  }

  /**
   * Executes the given Runnable using the execution context's classloader as thread classloader.
   */
  void wrapClassLoader(Runnable runnable) {
    try (TemporaryClassLoaderContext ignored = TemporaryClassLoaderContext.of(classLoader)) {
      runnable.run();
    }
  }

  public TableEnvironmentInternal getTableEnvironment() {
    return tableEnv;
  }

  /** Returns a builder for this {@link ExecutionContext}. */
  public static Builder builder(
      EngineEnvironment defaultEnv,
      EngineEnvironment sessionEnv,
      List<URL> dependencies,
      Configuration configuration,
      ClusterClientServiceLoader serviceLoader,
      Options commandLineOptions,
      List<CustomCommandLine> commandLines) {
    return new Builder(
        defaultEnv,
        sessionEnv,
        dependencies,
        configuration,
        serviceLoader,
        commandLineOptions,
        commandLines);
  }

  // ------------------------------------------------------------------------------------------------------------------
  // Non-public methods
  // ------------------------------------------------------------------------------------------------------------------

  private TableEnvironmentInternal createStreamTableEnvironment(
      StreamExecutionEnvironment env,
      EnvironmentSettings settings,
      TableConfig config,
      Executor executor,
      CatalogManager catalogManager,
      ModuleManager moduleManager,
      FunctionCatalog functionCatalog) {
    final Map<String, String> plannerProperties = settings.toPlannerProperties();
    final Planner planner =
        ComponentFactoryService.find(PlannerFactory.class, plannerProperties)
            .create(plannerProperties, executor, config, functionCatalog, catalogManager);

    return new StreamTableEnvironmentImpl(
        catalogManager,
        moduleManager,
        functionCatalog,
        config,
        env,
        planner,
        executor,
        settings.isStreamingMode(),
        classLoader);
  }

  private static Executor lookupExecutor(
      Map<String, String> executorProperties, StreamExecutionEnvironment executionEnvironment) {
    try {
      ExecutorFactory executorFactory =
          ComponentFactoryService.find(ExecutorFactory.class, executorProperties);
      Method createMethod =
          executorFactory
              .getClass()
              .getMethod("create", Map.class, StreamExecutionEnvironment.class);

      return (Executor)
          createMethod.invoke(executorFactory, executorProperties, executionEnvironment);
    } catch (Exception e) {
      throw new TableException(
          "Could not instantiate the executor. Make sure a planner module is on the classpath", e);
    }
  }

  private void initializeTableEnvironment() {
    final EnvironmentSettings settings = engineEnvironment.getExecution().getEnvironmentSettings();
    final TableConfig config = new TableConfig();
    final ModuleManager moduleManager = new ModuleManager();
    final CatalogManager catalogManager =
        CatalogManager.newBuilder()
            .classLoader(classLoader)
            .config(config.getConfiguration())
            .defaultCatalog(
                settings.getBuiltInCatalogName(),
                new GenericInMemoryCatalog(
                    settings.getBuiltInCatalogName(), settings.getBuiltInDatabaseName()))
            .build();
    final FunctionCatalog functionCatalog =
        new FunctionCatalog(config, catalogManager, moduleManager);

    // Must initialize the table engineEnvironment before actually the
    createTableEnvironment(settings, config, catalogManager, moduleManager, functionCatalog);

    // No need to register the catalogs if already inherit from the same session.
    initializeCatalogs();
  }

  private void createTableEnvironment(
      EnvironmentSettings settings,
      TableConfig config,
      CatalogManager catalogManager,
      ModuleManager moduleManager,
      FunctionCatalog functionCatalog) {
    if (engineEnvironment.getExecution().isStreamingPlanner()) {
      streamExecEnv = createStreamExecutionEnvironment();

      final Map<String, String> executorProperties = settings.toExecutorProperties();
      executor = lookupExecutor(executorProperties, streamExecEnv);
      tableEnv =
          createStreamTableEnvironment(
              streamExecEnv,
              settings,
              config,
              executor,
              catalogManager,
              moduleManager,
              functionCatalog);
    } else {
      throw new RuntimeException("Unsupported execution type specified.");
    }
  }

  private void initializeCatalogs() {
    // Switch to the current catalog.
    Optional<String> catalog = engineEnvironment.getExecution().getCurrentCatalog();
    catalog.ifPresent(tableEnv::useCatalog);

    // Switch to the current database.
    Optional<String> database = engineEnvironment.getExecution().getCurrentDatabase();
    database.ifPresent(tableEnv::useDatabase);
  }

  private StreamExecutionEnvironment createStreamExecutionEnvironment() {
    final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    env.setRestartStrategy(engineEnvironment.getExecution().getRestartStrategy());
    env.setParallelism(engineEnvironment.getExecution().getParallelism());
    env.setMaxParallelism(engineEnvironment.getExecution().getMaxParallelism());
    env.getConfig()
        .setAutoWatermarkInterval(engineEnvironment.getExecution().getPeriodicWatermarksInterval());
    return env;
  }

  // ~ Inner Class -------------------------------------------------------------------------------

  /** Builder for {@link ExecutionContext}. */
  public static class Builder {
    // Required members.
    private final EngineEnvironment sessionEnv;
    private final List<URL> dependencies;
    private final Configuration configuration;
    private final ClusterClientServiceLoader serviceLoader;
    private final Options commandLineOptions;
    private final List<CustomCommandLine> commandLines;

    private EngineEnvironment defaultEnv;
    private EngineEnvironment currentEnv;

    private Builder(
        EngineEnvironment defaultEnv,
        @Nullable EngineEnvironment sessionEnv,
        List<URL> dependencies,
        Configuration configuration,
        ClusterClientServiceLoader serviceLoader,
        Options commandLineOptions,
        List<CustomCommandLine> commandLines) {
      this.defaultEnv = defaultEnv;
      this.sessionEnv = sessionEnv;
      this.dependencies = dependencies;
      this.configuration = configuration;
      this.serviceLoader = serviceLoader;
      this.commandLineOptions = commandLineOptions;
      this.commandLines = commandLines;
    }

    public Builder env(EngineEnvironment engineEnvironment) {
      this.currentEnv = engineEnvironment;
      return this;
    }

    public ExecutionContext<?> build() {
      try {
        return new ExecutionContext<>(
            this.currentEnv == null
                ? EngineEnvironment.merge(defaultEnv, sessionEnv)
                : this.currentEnv,
            this.dependencies,
            this.configuration,
            this.serviceLoader,
            this.commandLineOptions,
            this.commandLines);
      } catch (Throwable t) {
        // catch everything such that a configuration does not crash the executor
        throw new RuntimeException("Could not create execution context.", t);
      }
    }
  }
}

package com.sudothought.agent;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.RateLimiter;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.protobuf.Empty;
import com.sudothought.common.ConfigVals;
import com.sudothought.common.MetricsServer;
import com.sudothought.common.SystemMetrics;
import com.sudothought.common.Utils;
import com.sudothought.common.ZipkinReporter;
import com.sudothought.grpc.AgentInfo;
import com.sudothought.grpc.HeartBeatRequest;
import com.sudothought.grpc.HeartBeatResponse;
import com.sudothought.grpc.PathMapSizeRequest;
import com.sudothought.grpc.PathMapSizeResponse;
import com.sudothought.grpc.ProxyServiceGrpc;
import com.sudothought.grpc.RegisterAgentRequest;
import com.sudothought.grpc.RegisterAgentResponse;
import com.sudothought.grpc.RegisterPathRequest;
import com.sudothought.grpc.RegisterPathResponse;
import com.sudothought.grpc.ScrapeRequest;
import com.sudothought.grpc.ScrapeResponse;
import com.sudothought.grpc.UnregisterPathRequest;
import com.sudothought.grpc.UnregisterPathResponse;
import com.typesafe.config.Config;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.prometheus.client.Summary;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static com.sudothought.common.EnvVars.AGENT_CONFIG;
import static com.sudothought.common.InstrumentedThreadFactory.newInstrumentedThreadFactory;
import static com.sudothought.common.Utils.toMillis;
import static com.sudothought.grpc.ProxyServiceGrpc.newBlockingStub;
import static com.sudothought.grpc.ProxyServiceGrpc.newStub;
import static io.grpc.ClientInterceptors.intercept;
import static java.lang.String.format;
import static java.util.concurrent.Executors.newCachedThreadPool;

public class Agent {

  private static final Logger logger = LoggerFactory.getLogger(Agent.class);

  private final AtomicBoolean            stopped                = new AtomicBoolean(false);
  private final Map<String, PathContext> pathContextMap         = Maps.newConcurrentMap();  // Map path to PathContext
  private final AtomicReference<String>  agentIdRef             = new AtomicReference<>();
  private final AtomicLong               lastMsgSent            = new AtomicLong();
  private final ExecutorService          heartbeatService       = Executors.newFixedThreadPool(1);
  private final ExecutorService          runService             = Executors.newFixedThreadPool(1);
  private final CountDownLatch           stoppedLatch           = new CountDownLatch(1);
  private final CountDownLatch           initialConnectionLatch = new CountDownLatch(1);
  private final OkHttpClient             okHttpClient           = new OkHttpClient();

  private final ConfigVals                    configVals;
  private final String                        inProcessServerName;
  private final String                        agentName;
  private final String                        hostname;
  private final int                           port;
  private final AgentMetrics                  metrics;
  private final ExecutorService               executorService;
  private final BlockingQueue<ScrapeResponse> scrapeResponseQueue;
  private final RateLimiter                   reconnectLimiter;
  private final MetricsServer                 metricsServer;
  private final List<Map<String, String>>     pathConfigs;
  private final ZipkinReporter                zipkinReporter;
  private final boolean                       testMode;

  private ManagedChannel                            channel      = null;
  private ProxyServiceGrpc.ProxyServiceBlockingStub blockingStub = null;
  private ProxyServiceGrpc.ProxyServiceStub         asyncStub    = null;

  public Agent(final ConfigVals configVals,
               final String inProcessServerName,
               final String agentName,
               final String proxyHost,
               final boolean metricsEnabled,
               final int metricsPort,
               final boolean testMode) {
    this.configVals = configVals;
    this.inProcessServerName = inProcessServerName;
    this.testMode = testMode;
    this.agentName = isNullOrEmpty(agentName) ? format("Unnamed-%s", Utils.getHostName()) : agentName;
    logger.info("Creating Agent {}", this.agentName);

    final int queueSize = this.getConfigVals().internal.scrapeResponseQueueSize;
    this.scrapeResponseQueue = new ArrayBlockingQueue<>(queueSize);

    if (metricsEnabled) {
      logger.info("Metrics server enabled");
      this.metricsServer = new MetricsServer(metricsPort, this.getConfigVals().metrics.path);
      this.metrics = new AgentMetrics(this);
      SystemMetrics.initialize(this.getConfigVals().metrics.standardExportsEnabled,
                               this.getConfigVals().metrics.memoryPoolsExportsEnabled,
                               this.getConfigVals().metrics.garbageCollectorExportsEnabled,
                               this.getConfigVals().metrics.threadExportsEnabled,
                               this.getConfigVals().metrics.classLoadingExportsEnabled,
                               this.getConfigVals().metrics.versionInfoExportsEnabled);
    }
    else {
      logger.info("Metrics server disabled");
      this.metricsServer = null;
      this.metrics = null;
    }

    this.executorService = newCachedThreadPool(this.isMetricsEnabled()
                                               ? newInstrumentedThreadFactory("agent_fetch",
                                                                              "Agent fetch",
                                                                              true)
                                               : new ThreadFactoryBuilder().setNameFormat("agent_fetch-%d")
                                                                           .setDaemon(true)
                                                                           .build());

    logger.info("Assigning proxy reconnect pause time to {} secs", this.getConfigVals().grpc.reconectPauseSecs);
    this.reconnectLimiter = RateLimiter.create(1.0 / this.getConfigVals().grpc.reconectPauseSecs);

    this.pathConfigs = configVals.agent.pathConfigs.stream()
                                                   .map(v -> ImmutableMap.of("name", v.name,
                                                                             "path", v.path,
                                                                             "url", v.url))
                                                   .peek(v -> logger.info("Proxy path /{} will be assigned to {}",
                                                                          v.get("path"), v.get("url")))
                                                   .collect(Collectors.toList());

    if (this.getConfigVals().zipkin.enabled) {
      final ConfigVals.Agent.Zipkin zipkin = this.getConfigVals().zipkin;
      final String zipkinHost = format("http://%s:%d/%s", zipkin.hostname, zipkin.port, zipkin.path);
      logger.info("Zipkin reporter enabled for {}", zipkinHost);
      this.zipkinReporter = new ZipkinReporter(zipkinHost, zipkin.serviceName);
    }
    else {
      logger.info("Zipkin reporter disabled");
      this.zipkinReporter = null;
    }

    if (proxyHost.contains(":")) {
      String[] vals = proxyHost.split(":");
      this.hostname = vals[0];
      this.port = Integer.valueOf(vals[1]);
    }
    else {
      this.hostname = proxyHost;
      this.port = 50051;
    }
  }

  public static void main(final String[] argv)
      throws IOException, InterruptedException {
    logger.info(Utils.getBanner("banners/agent.txt"));

    final AgentArgs args = new AgentArgs();
    args.parseArgs(Agent.class.getName(), argv);

    final Config config = Utils.readConfig(args.config, AGENT_CONFIG, true);
    final ConfigVals configVals = new ConfigVals(config);
    args.assignArgs(configVals);

    final Agent agent = new Agent(configVals,
                                  null,
                                  args.agent_name,
                                  args.proxy_host,
                                  !args.disable_metrics,
                                  args.metrics_port,
                                  false);
    agent.start();
    agent.waitUntilShutdown();
  }

  private void resetGrpcStubs() {
    logger.info("Assigning gRPC stubs");
    this.channel = isNullOrEmpty(this.inProcessServerName) ? NettyChannelBuilder.forAddress(this.hostname, this.port)
                                                                                .usePlaintext(true)
                                                                                .build()
                                                           : InProcessChannelBuilder.forName(this.inProcessServerName)
                                                                                    .usePlaintext(true)
                                                                                    .build();

    final List<ClientInterceptor> interceptors = Lists.newArrayList(new AgentClientInterceptor(this));
    /*
    if (this.getConfigVals().grpc.metricsEnabled)
      interceptors.add(MonitoringClientInterceptor.create(this.getConfigVals().grpc.allMetricsReported
                                                          ? Configuration.allMetrics()
                                                          : Configuration.cheapMetricsOnly()));
    if (this.zipkinReporter != null && this.getConfigVals().grpc.zipkinReportingEnabled)
      interceptors.add(BraveGrpcClientInterceptor.create(this.zipkinReporter.getBrave()));
    */
    this.blockingStub = newBlockingStub(intercept(this.channel, interceptors));
    this.asyncStub = newStub(intercept(this.channel, interceptors));
  }

  public void start()
      throws IOException {

    if (this.isMetricsEnabled())
      this.metricsServer.start();

    // Prime the limiter
    this.reconnectLimiter.acquire();

    Runtime.getRuntime()
           .addShutdownHook(
               new Thread(() -> {
                 System.err.println("*** Shutting down Agent ***");
                 Agent.this.stop();
                 System.err.println("*** Agent shut down ***");
               }));

    this.resetGrpcStubs();

    this.runService.submit(
        () -> {
          while (!this.isStopped()) {
            try {
              final AtomicBoolean connected = new AtomicBoolean(false);
              final AtomicBoolean disconnected = new AtomicBoolean(false);

              // Reset gRPC stubs if previous iteration had a successful connection
              if (this.getAgentId() != null)
                this.resetGrpcStubs();

              // Reset values for each connection attempt
              this.setAgentId(null);
              this.pathContextMap.clear();
              this.scrapeResponseQueue.clear();
              this.lastMsgSent.set(0);

              try {
                logger.info("Connecting to proxy at {}...", this.getProxyHost());
                this.connectAgent();
                logger.info("Connected to proxy at {}", this.getProxyHost());
                connected.set(true);

                if (this.isMetricsEnabled())
                  this.getMetrics().connects.labels("success").inc();

                this.registerAgent();
                this.registerPaths();
                this.startHeartBeat(disconnected);
                this.readRequestsFromProxy(disconnected);
                this.writeResponsesToProxy(disconnected);
              }
              catch (ConnectException e) {
                logger.debug("{} {}", e.getClass().getSimpleName(), e.getMessage());
              }
              catch (StatusRuntimeException e) {
                logger.info("Cannot connect to proxy at {} [{}]", this.getProxyHost(), e.getMessage());
              }

              if (connected.get())
                logger.info("Disconnected from proxy at {}", this.getProxyHost());
              else if (this.isMetricsEnabled())
                this.getMetrics().connects.labels("failure").inc();
            }
            catch (Throwable e) {
              logger.warn("Main loop", e);
            }
            finally {
              final double secsWaiting = this.reconnectLimiter.acquire();
              logger.info("Waited {} secs to reconnect", secsWaiting);
            }
          }
        });
  }

  public void stop() {
    this.stopped.set(true);

    if (this.isMetricsEnabled())
      this.metricsServer.stop();

    if (this.isZipkinReportingEnabled())
      this.zipkinReporter.close();

    this.heartbeatService.shutdownNow();
    this.runService.shutdownNow();

    try {
      this.channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
    }
    catch (InterruptedException e) {
      logger.warn("Thread interrupted", e);
    }

    stoppedLatch.countDown();
  }

  public void waitUntilShutdown()
      throws InterruptedException {
    this.stoppedLatch.await();
  }

  public boolean waitUntilShutdown(long timeout, TimeUnit unit)
      throws InterruptedException {
    return this.stoppedLatch.await(timeout, unit);
  }

  private void startHeartBeat(final AtomicBoolean disconnected) {
    if (this.getConfigVals().internal.heartbeatEnabled) {
      final long threadPauseMillis = this.getConfigVals().internal.heartbeatCheckPauseMillis;
      final int maxInactivitySecs = this.getConfigVals().internal.heartbeatMaxInactivitySecs;
      logger.info("Heartbeat scheduled to start after {} secs of inactivity", maxInactivitySecs);
      this.heartbeatService.submit(
          () -> {
            while (!disconnected.get()) {
              final long timeSinceLastWriteMillis = System.currentTimeMillis() - this.lastMsgSent.get();
              if (timeSinceLastWriteMillis > toMillis(maxInactivitySecs))
                try {
                  this.sendHeartBeat();
                }
                catch (StatusRuntimeException e) {
                  logger.info("Hearbeat failed {}", e.getStatus());
                  disconnected.set(true);
                }

              Utils.sleepForMillis(threadPauseMillis);
            }
            logger.info("Heartbeat completed");
          });

    }
    else {
      logger.info("Heartbeat disabled");
    }
  }

  private ScrapeResponse fetchUrl(final ScrapeRequest scrapeRequest) {
    int status_code = 404;
    final String path = scrapeRequest.getPath();
    final ScrapeResponse.Builder scrapeResponse = ScrapeResponse.newBuilder()
                                                                .setAgentId(scrapeRequest.getAgentId())
                                                                .setScrapeId(scrapeRequest.getScrapeId());
    final PathContext pathContext = this.pathContextMap.get(path);
    if (pathContext == null) {
      logger.warn("Invalid path in fetchUrl(): {}", path);
      if (this.isMetricsEnabled())
        this.getMetrics().scrapeRequests.labels("invalid_path").inc();
      return scrapeResponse.setValid(false)
                           .setStatusCode(status_code)
                           .setText("")
                           .setContentType("")
                           .build();
    }

    final Summary.Timer requestTimer = this.isMetricsEnabled()
                                       ? this.getMetrics().scrapeRequestLatency.labels(this.agentName).startTimer()
                                       : null;
    try {
      try (final Response response = pathContext.fetchUrl(scrapeRequest)) {
        status_code = response.code();
        if (response.isSuccessful()) {
          if (this.isMetricsEnabled())
            this.getMetrics().scrapeRequests.labels("success").inc();
          return scrapeResponse.setValid(true)
                               .setStatusCode(status_code)
                               .setText(response.body().string())
                               .setContentType(response.header(CONTENT_TYPE))
                               .build();
        }
      }
    }
    catch (IOException e) {
      logger.warn("IOException", e);
    }
    finally {
      if (requestTimer != null)
        requestTimer.observeDuration();
    }

    if (this.isMetricsEnabled())
      this.getMetrics().scrapeRequests.labels("unsuccessful").inc();

    return scrapeResponse.setValid(false)
                         .setStatusCode(status_code)
                         .setText("")
                         .setContentType("")
                         .build();
  }

  // If successful, this will create an agentContxt on the Proxy and an interceptor will
  // add an agent_id to the headers
  private void connectAgent() { this.blockingStub.connectAgent(Empty.getDefaultInstance()); }

  private void registerAgent()
      throws ConnectException {
    final RegisterAgentRequest request = RegisterAgentRequest.newBuilder()
                                                             .setAgentId(this.getAgentId())
                                                             .setAgentName(this.agentName)
                                                             .setHostname(Utils.getHostName())
                                                             .build();
    final RegisterAgentResponse response = this.blockingStub.registerAgent(request);
    this.markMsgSent();
    if (!response.getValid())
      throw new ConnectException("registerAgent()");
    this.initialConnectionLatch.countDown();
  }

  private void registerPaths()
      throws ConnectException {
    for (final Map<String, String> agentConfig : this.pathConfigs) {
      final String path = agentConfig.get("path");
      final String url = agentConfig.get("url");
      this.registerPath(path, url);
    }
  }

  public void registerPath(final String pathVal, final String url)
      throws ConnectException {
    final String path = checkNotNull(pathVal).startsWith("/") ? pathVal.substring(1) : pathVal;
    final long pathId = this.registerPathOnProxy(path);
    if (!this.testMode)
      logger.info("Registered {} as /{}", url, path);
    this.pathContextMap.put(path, new PathContext(this.okHttpClient, pathId, path, url));
  }

  public void unregisterPath(final String pathVal)
      throws ConnectException {
    final String path = checkNotNull(pathVal).startsWith("/") ? pathVal.substring(1) : pathVal;
    this.unregisterPathOnProxy(path);
    final PathContext pathContext = this.pathContextMap.remove(path);
    if (pathContext == null)
      logger.info("No path value /{} found in pathContextMap", path);
    else if (!this.testMode)
      logger.info("Unregistered /{} for {}", path, pathContext.getUrl());
  }

  public int pathMapSize()
      throws ConnectException {
    final PathMapSizeRequest request = PathMapSizeRequest.newBuilder()
                                                         .setAgentId(this.getAgentId())
                                                         .build();
    final PathMapSizeResponse response = this.blockingStub.pathMapSize(request);
    return response.getPathCount();
  }

  private long registerPathOnProxy(final String path)
      throws ConnectException {
    final RegisterPathRequest request = RegisterPathRequest.newBuilder()
                                                           .setAgentId(this.getAgentId())
                                                           .setPath(path)
                                                           .build();
    final RegisterPathResponse response = this.blockingStub.registerPath(request);
    this.markMsgSent();
    if (!response.getValid())
      throw new ConnectException("registerPath()");
    return response.getPathId();
  }

  private void unregisterPathOnProxy(final String path)
      throws ConnectException {
    final UnregisterPathRequest request = UnregisterPathRequest.newBuilder()
                                                               .setAgentId(this.getAgentId())
                                                               .setPath(path)
                                                               .build();
    final UnregisterPathResponse response = this.blockingStub.unregisterPath(request);
    this.markMsgSent();
    if (!response.getValid())
      throw new ConnectException("unregisterPath()");
  }

  private void readRequestsFromProxy(final AtomicBoolean disconnected) {
    this.asyncStub.readRequestsFromProxy(AgentInfo.newBuilder().setAgentId(this.getAgentId()).build(),
                                         new StreamObserver<ScrapeRequest>() {
                                           @Override
                                           public void onNext(final ScrapeRequest request) {
                                             executorService.submit(
                                                 () -> {
                                                   final ScrapeResponse response = fetchUrl(request);
                                                   try {
                                                     scrapeResponseQueue.put(response);
                                                   }
                                                   catch (InterruptedException e) {
                                                     logger.warn("Thread interrupted", e);
                                                   }
                                                 });
                                           }

                                           @Override
                                           public void onError(Throwable t) {
                                             final Status status = Status.fromThrowable(t);
                                             logger.info("Error in readRequestsFromProxy(): {}", status);
                                             disconnected.set(true);
                                           }

                                           @Override
                                           public void onCompleted() {
                                             disconnected.set(true);
                                           }
                                         });
  }

  private void writeResponsesToProxy(final AtomicBoolean disconnected) {
    final StreamObserver<ScrapeResponse> responseObserver = this.asyncStub.writeResponsesToProxy(
        new StreamObserver<Empty>() {
          @Override
          public void onNext(Empty empty) {

          }

          @Override
          public void onError(Throwable t) {
            final Status s = Status.fromThrowable(t);
            logger.info("Error in writeResponsesToProxy(): {} {}", s.getCode(), s.getDescription());
            disconnected.set(true);
          }

          @Override
          public void onCompleted() {
            disconnected.set(true);
          }
        });

    final long checkMillis = this.getConfigVals().internal.scrapeResponseQueueCheckMillis;
    while (!disconnected.get()) {
      try {
        // Set a short timeout to check if client has disconnected
        final ScrapeResponse response = this.scrapeResponseQueue.poll(checkMillis, TimeUnit.MILLISECONDS);
        if (response != null) {
          responseObserver.onNext(response);
          this.markMsgSent();
        }
      }
      catch (InterruptedException e) {
        logger.warn("Thread interrupted", e);
      }
    }

    responseObserver.onCompleted();
  }

  private void markMsgSent() {
    this.lastMsgSent.set(System.currentTimeMillis());
  }

  private void sendHeartBeat() {
    final String agentId = this.getAgentId();
    if (agentId != null) {
      final HeartBeatRequest request = HeartBeatRequest.newBuilder().setAgentId(agentId).build();
      final HeartBeatResponse response = this.blockingStub.sendHeartBeat(request);
      this.markMsgSent();
      // logger.info("HeartBeat sent");
      if (!response.getValid()) {
        logger.info("AgentId {} not found on proxy", agentId);
        throw new StatusRuntimeException(Status.NOT_FOUND);
      }
    }
  }

  public boolean awaitInitialConnection(long timeout, TimeUnit unit)
      throws InterruptedException {
    return this.initialConnectionLatch.await(timeout, unit);
  }

  private String getProxyHost() { return format("%s:%s", hostname, port); }

  public int getScrapeResponseQueueSize() { return this.scrapeResponseQueue.size(); }

  public AgentMetrics getMetrics() { return this.metrics; }

  public boolean isMetricsEnabled() { return this.metricsServer != null; }

  public boolean isZipkinReportingEnabled() { return this.zipkinReporter != null; }

  private boolean isStopped() { return this.stopped.get(); }

  public ManagedChannel getChannel() { return this.channel; }

  public String getAgentId() { return this.agentIdRef.get(); }

  public void setAgentId(final String agentId) { this.agentIdRef.set(agentId); }

  public ConfigVals.Agent getConfigVals() { return this.configVals.agent; }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
                      .add("agentId", this.getAgentId())
                      .add("agentName", this.agentName)
                      .add("metricsPort", this.isMetricsEnabled() ? this.metricsServer.getPort() : "Disabled")
                      .add("metricsPath", this.isMetricsEnabled() ? "/" + this.metricsServer.getPath() : "Disabled")
                      .add("proxyHost", this.getProxyHost())
                      .toString();
  }
}

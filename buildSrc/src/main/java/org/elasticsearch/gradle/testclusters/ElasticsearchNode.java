/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.gradle.testclusters;

import org.elasticsearch.GradleServicesAdapter;
import org.elasticsearch.gradle.Distribution;
import org.elasticsearch.gradle.OS;
import org.elasticsearch.gradle.Version;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class ElasticsearchNode {

    private final Logger logger = Logging.getLogger(ElasticsearchNode.class);
    private final String name;
    private final GradleServicesAdapter services;
    private final AtomicBoolean configurationFrozen = new AtomicBoolean(false);
    private final Path artifactsExtractDir;
    private final Path workingDir;

    private static final int ES_DESTROY_TIMEOUT = 20;
    private static final TimeUnit ES_DESTROY_TIMEOUT_UNIT = TimeUnit.SECONDS;
    private static final int NODE_UP_TIMEOUT = 30;
    private static final TimeUnit NODE_UP_TIMEOUT_UNIT = TimeUnit.SECONDS;

    private final LinkedHashMap<String, Predicate<ElasticsearchNode>> waitConditions;
    private final List<URI> plugins = new ArrayList<>();

    private final Path confPathRepo;
    private final Path configFile;
    private final Path confPathData;
    private final Path confPathLogs;
    private final Path transportPortFile;
    private final Path httpPortsFile;
    private final Path esStdoutFile;
    private final Path esStderrFile;
    private final Path tmpDir;

    private Distribution distribution;
    private String version;
    private File javaHome;
    private volatile Process esProcess;
    private final String path;

    ElasticsearchNode(String path, String name, GradleServicesAdapter services, File artifactsExtractDir, File workingDirBase) {
        this.path = path;
        this.name = name;
        this.services = services;
        this.artifactsExtractDir = artifactsExtractDir.toPath();
        this.workingDir = workingDirBase.toPath().resolve(safeName(name)).toAbsolutePath();
        confPathRepo = workingDir.resolve("repo");
        configFile = workingDir.resolve("config/elasticsearch.yml");
        confPathData = workingDir.resolve("data");
        confPathLogs = workingDir.resolve("logs");
        transportPortFile = confPathLogs.resolve("transport.ports");
        httpPortsFile = confPathLogs.resolve("http.ports");
        esStdoutFile = confPathLogs.resolve("es.stdout.log");
        esStderrFile = confPathLogs.resolve("es.stderr.log");
        tmpDir = workingDir.resolve("tmp");
        this.waitConditions = new LinkedHashMap<>();
        waitConditions.put("http ports file", node -> Files.exists(node.httpPortsFile));
        waitConditions.put("transport ports file", node -> Files.exists(node.transportPortFile));
        waitForUri("cluster health yellow", "/_cluster/health?wait_for_nodes=>=1&wait_for_status=yellow");
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        requireNonNull(version, "null version passed when configuring test cluster `" + this + "`");
        checkFrozen();
        this.version = version;
    }

    public Distribution getDistribution() {
        return distribution;
    }

    public void setDistribution(Distribution distribution) {
        requireNonNull(distribution, "null distribution passed when configuring test cluster `" + this + "`");
        checkFrozen();
        this.distribution = distribution;
    }

    public void plugin(URI plugin) {
        requireNonNull(plugin, "Plugin name can't be null");
        checkFrozen();
        this.plugins.add(plugin);
    }

    public void plugin(File plugin) {
        plugin(plugin.toURI());
    }

    public Path getConfigDir() {
        return configFile.getParent();
    }

    public void freeze() {
        requireNonNull(distribution, "null distribution passed when configuring test cluster `" + this + "`");
        requireNonNull(version, "null version passed when configuring test cluster `" + this + "`");
        requireNonNull(javaHome, "null javaHome passed when configuring test cluster `" + this + "`");
        logger.info("Locking configuration of `{}`", this);
        configurationFrozen.set(true);
    }

    public void setJavaHome(File javaHome) {
        requireNonNull(javaHome, "null javaHome passed when configuring test cluster `" + this + "`");
        checkFrozen();
        if (javaHome.exists() == false) {
            throw new TestClustersException("java home for `" + this + "` does not exists: `" + javaHome + "`");
        }
        this.javaHome = javaHome;
    }

    public File getJavaHome() {
        return javaHome;
    }

    private void waitForUri(String description, String uri) {
        waitConditions.put(description, (node) -> {
            try {
                URL url = new URL("http://" + this.getHttpPortInternal().get(0) + uri);
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("GET");
                con.setConnectTimeout(500);
                con.setReadTimeout(500);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
                    String response = reader.lines().collect(Collectors.joining("\n"));
                    logger.info("{} -> {} ->\n{}", this, uri, response);
                }
                return true;
            } catch (IOException e) {
                throw new IllegalStateException("Connection attempt to " + this + " failed", e);
            }
        });
    }

    /**
     * Returns a stream of lines in the generated logs similar to Files.lines
     *
     * @return stream of log lines
     */
    public Stream<String> logLines() throws IOException {
        return Files.lines(esStdoutFile, StandardCharsets.UTF_8);
    }

    synchronized void start() {
        logger.info("Starting `{}`", this);

        Path distroArtifact = artifactsExtractDir
            .resolve(distribution.getGroup())
            .resolve(distribution.getArtifactName() + "-" + getVersion());

        if (Files.exists(distroArtifact) == false) {
            throw new TestClustersException("Can not start " + this + ", missing: " + distroArtifact);
        }
        if (Files.isDirectory(distroArtifact) == false) {
            throw new TestClustersException("Can not start " + this + ", is not a directory: " + distroArtifact);
        }

        try {
            createWorkingDir(distroArtifact);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        createConfiguration();

        plugins.forEach(plugin -> runElaticsearchBinScript(
            "elasticsearch-plugin",
            "install", "--batch", plugin.toString())
        );

        startElasticsearchProcess();
    }

    private void runElaticsearchBinScript(String tool, String... args) {
        services.loggedExec(spec -> {
            spec.setEnvironment(getESEnvironment());
            spec.workingDir(workingDir);
            spec.executable(
                OS.conditionalString()
                    .onUnix(() -> "./bin/" + tool)
                    .onWindows(() -> "cmd")
                    .supply()
            );
            spec.args(
                OS.<List<String>>conditional()
                    .onWindows(() -> {
                        ArrayList<String> result = new ArrayList<>();
                        result.add("/c");
                        result.add("bin\\" + tool + ".bat");
                        for (String arg : args) {
                            result.add(arg);
                        }
                        return result;
                    })
                    .onUnix(() -> Arrays.asList(args))
                    .supply()
            );
        });
    }

    private Map<String, String> getESEnvironment() {
        Map<String, String> environment= new HashMap<>();
        environment.put("JAVA_HOME", getJavaHome().getAbsolutePath());
        environment.put("ES_PATH_CONF", configFile.getParent().toString());
        environment.put("ES_JAVA_OPTS", "-Xms512m -Xmx512m");
        environment.put("ES_TMPDIR", tmpDir.toString());
        // Windows requires this as it defaults to `c:\windows` despite ES_TMPDIR

        environment.put("TMP", tmpDir.toString());
        return environment;
    }

    private void startElasticsearchProcess() {
        final ProcessBuilder processBuilder = new ProcessBuilder();

        List<String> command = OS.<List<String>>conditional()
            .onUnix(() -> Arrays.asList("./bin/elasticsearch"))
            .onWindows(() -> Arrays.asList("cmd", "/c", "bin\\elasticsearch.bat"))
            .supply();
        processBuilder.command(command);
        processBuilder.directory(workingDir.toFile());
        Map<String, String> environment = processBuilder.environment();
        // Don't inherit anything from the environment for as that would  lack reproducibility
        environment.clear();
        environment.putAll(getESEnvironment());
        // don't buffer all in memory, make sure we don't block on the default pipes
        processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(esStderrFile.toFile()));
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(esStdoutFile.toFile()));
        logger.info("Running `{}` in `{}` for {} env: {}", command, workingDir, this, environment);
        try {
            esProcess = processBuilder.start();
        } catch (IOException e) {
            throw new TestClustersException("Failed to start ES process for " + this, e);
        }
    }

    public String getHttpSocketURI() {
        waitForAllConditions();
        return getHttpPortInternal().get(0);
    }

    public String getTransportPortURI() {
        waitForAllConditions();
        return getTransportPortInternal().get(0);
    }

    public List<String> getAllHttpSocketURI() {
        waitForAllConditions();
        return getHttpPortInternal();
    }

    public List<String> getAllTransportPortURI() {
        waitForAllConditions();
        return getTransportPortInternal();
    }

    synchronized void stop(boolean tailLogs) {
        if (esProcess == null && tailLogs) {
            // This is a special case. If start() throws an exception the plugin will still call stop
            // Another exception here would eat the orriginal.
            return;
        }
        logger.info("Stopping `{}`, tailLogs: {}", this, tailLogs);
        requireNonNull(esProcess, "Can't stop `" + this + "` as it was not started or already stopped.");
        // Test clusters are not reused, don't spend time on a graceful shutdown
        stopHandle(esProcess.toHandle(), true);
        if (tailLogs) {
            logFileContents("Standard output of node", esStdoutFile);
            logFileContents("Standard error of node", esStderrFile);
        }
        esProcess = null;
    }

    private void stopHandle(ProcessHandle processHandle, boolean forcibly) {
        // Stop all children first, ES could actually be a child when there's some wrapper process like on Windows.
        if (processHandle.isAlive() == false) {
            logger.info("Process was not running when we tried to terminate it.");
            return;
        }

        // Stop all children first, ES could actually be a child when there's some wrapper process like on Windows.
        processHandle.children().forEach(each -> stopHandle(each, forcibly));

        logProcessInfo(
            "Terminating elasticsearch process" + (forcibly ? " forcibly " : "gracefully") + ":",
            processHandle.info()
        );

        if (forcibly) {
            processHandle.destroyForcibly();
        } else {
            processHandle.destroy();
            waitForProcessToExit(processHandle);
            if (processHandle.isAlive() == false) {
                return;
            }
            logger.info("process did not terminate after {} {}, stopping it forcefully",
                ES_DESTROY_TIMEOUT, ES_DESTROY_TIMEOUT_UNIT);
            processHandle.destroyForcibly();
        }

        waitForProcessToExit(processHandle);
        if (processHandle.isAlive()) {
            throw new TestClustersException("Was not able to terminate elasticsearch process");
        }
    }

    private void logProcessInfo(String prefix, ProcessHandle.Info info) {
        logger.info(prefix + " commandLine:`{}` command:`{}` args:`{}`",
            info.commandLine().orElse("-"), info.command().orElse("-"),
            Arrays.stream(info.arguments().orElse(new String[]{}))
                .map(each -> "'" + each + "'")
                .collect(Collectors.joining(" "))
        );
    }

    private void logFileContents(String description, Path from) {
        logger.error("{} `{}`", description, this);
        try(Stream<String> lines = Files.lines(from, StandardCharsets.UTF_8)) {
            lines
                .map(line -> "  " + line)
                .forEach(logger::error);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void waitForProcessToExit(ProcessHandle processHandle) {
        try {
            processHandle.onExit().get(ES_DESTROY_TIMEOUT, ES_DESTROY_TIMEOUT_UNIT);
        } catch (InterruptedException e) {
            logger.info("Interrupted while waiting for ES process", e);
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            logger.info("Failure while waiting for process to exist", e);
        } catch (TimeoutException e) {
            logger.info("Timed out waiting for process to exit", e);
        }
    }

    private void createWorkingDir(Path distroExtractDir) throws IOException {
        syncWithLinks(distroExtractDir, workingDir);
        Files.createDirectories(configFile.getParent());
        Files.createDirectories(confPathRepo);
        Files.createDirectories(confPathData);
        Files.createDirectories(confPathLogs);
        Files.createDirectories(tmpDir);
    }

    /**
     * Does the equivalent of `cp -lr` and `chmod -r a-w` to save space and improve speed.
     * We remove write permissions to make sure files are note mistakenly edited ( e.x. the config file ) and changes
     * reflected across all copies. Permissions are retained to be able to replace the links.
     *
     * @param sourceRoot where to copy from
     * @param destinationRoot destination to link to
     */
    private void syncWithLinks(Path sourceRoot, Path destinationRoot) {
        if (Files.exists(destinationRoot)) {
            services.delete(destinationRoot);
        }

        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            stream.forEach(source -> {
                Path destination = destinationRoot.resolve(sourceRoot.relativize(source));
                if (Files.isDirectory(source)) {
                    try {
                        Files.createDirectories(destination);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Can't create directory " + destination.getParent(), e);
                    }
                } else {
                    try {
                        Files.createDirectories(destination.getParent());
                    } catch (IOException e) {
                        throw new UncheckedIOException("Can't create directory " + destination.getParent(), e);
                    }
                    try {
                        Files.createLink(destination, source);
                    } catch (IOException e) {
                        // Note does not work for network drives, e.g. Vagrant
                        throw new UncheckedIOException(
                            "Failed to create hard link " + destination + " pointing to " + source, e
                        );
                    }
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Can't walk source " + sourceRoot, e);
        }
    }

    private void createConfiguration()  {
        LinkedHashMap<String, String> config = new LinkedHashMap<>();

        String nodeName = safeName(name);
        config.put("cluster.name",nodeName);
        config.put("node.name", nodeName);
        config.put("path.repo", confPathRepo.toAbsolutePath().toString());
        config.put("path.data", confPathData.toAbsolutePath().toString());
        config.put("path.logs", confPathLogs.toAbsolutePath().toString());
        config.put("path.shared_data", workingDir.resolve("sharedData").toString());
        config.put("node.attr.testattr", "test");
        config.put("node.portsfile", "true");
        config.put("http.port", "0");
        config.put("transport.tcp.port", "0");
        // Default the watermarks to absurdly low to prevent the tests from failing on nodes without enough disk space
        config.put("cluster.routing.allocation.disk.watermark.low", "1b");
        config.put("cluster.routing.allocation.disk.watermark.high", "1b");
        // increase script compilation limit since tests can rapid-fire script compilations
        config.put("script.max_compilations_rate", "2048/1m");
        if (Version.fromString(version).getMajor() >= 6) {
            config.put("cluster.routing.allocation.disk.watermark.flood_stage", "1b");
        }
        if (Version.fromString(version).getMajor() >= 7) {
            config.put("cluster.initial_master_nodes", "[" + nodeName + "]");
        }
        try {
            // We create hard links  for the distribution, so we need to remove the config file before writing it
            // to prevent the changes to reflect across all copies.
            Files.delete(configFile);
            Files.write(
                configFile,
                config.entrySet().stream()
                    .map(entry -> entry.getKey() + ": " + entry.getValue())
                    .collect(Collectors.joining("\n"))
                    .getBytes(StandardCharsets.UTF_8)
            );
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write config file: " + configFile, e);
        }
        logger.info("Written config file:{} for {}", configFile, this);
    }

    private void checkFrozen() {
        if (configurationFrozen.get()) {
            throw new IllegalStateException("Configuration can not be altered, already locked");
        }
    }

    private static String safeName(String name) {
        return name
            .replaceAll("^[^a-zA-Z0-9]+", "")
            .replaceAll("[^a-zA-Z0-9]+", "-");
    }

    private List<String> getTransportPortInternal() {
        try {
            return readPortsFile(transportPortFile);
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Failed to read transport ports file: " + transportPortFile + " for " + this, e
            );
        }
    }

    private List<String> getHttpPortInternal() {
        try {
            return readPortsFile(httpPortsFile);
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Failed to read http ports file: " + httpPortsFile + " for " + this, e
            );
        }
    }

    private List<String> readPortsFile(Path file) throws IOException {
        try(Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.map(String::trim).collect(Collectors.toList());
        }
    }

    private void waitForAllConditions() {
        requireNonNull(esProcess, "Can't wait for `" + this + "` as it was stopped.");
        long startedAt = System.currentTimeMillis();
        logger.info("Starting to wait for cluster to come up");
        waitConditions.forEach((description, predicate) -> {
            long thisConditionStartedAt = System.currentTimeMillis();
            boolean conditionMet = false;
            Throwable lastException = null;
            while (
                System.currentTimeMillis() - startedAt < MILLISECONDS.convert(NODE_UP_TIMEOUT, NODE_UP_TIMEOUT_UNIT)
            ) {
                if (esProcess.isAlive() == false) {
                    throw new TestClustersException(
                        "process was found dead while waiting for " + description + ", " + this
                    );
                }
                try {
                    if(predicate.test(this)) {
                        conditionMet = true;
                        break;
                    }
                } catch (TestClustersException e) {
                    throw new TestClustersException(e);
                } catch (Exception e) {
                    if (lastException == null) {
                        lastException = e;
                    } else {
                        e.addSuppressed(lastException);
                        lastException = e;
                    }
                }
                try {
                    Thread.sleep(500);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (conditionMet == false) {
                String message = "`" + this + "` failed to wait for " + description + " after " +
                    NODE_UP_TIMEOUT + " " + NODE_UP_TIMEOUT_UNIT;
                if (lastException == null) {
                    throw new TestClustersException(message);
                } else {
                    throw new TestClustersException(message, lastException);
                }
            }
            logger.info(
                "{}: {} took {} seconds",
                this,  description,
                SECONDS.convert(System.currentTimeMillis() - thisConditionStartedAt, MILLISECONDS)
            );
        });
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ElasticsearchNode that = (ElasticsearchNode) o;
        return Objects.equals(name, that.name) &&
            Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, path);
    }

    @Override
    public String toString() {
        return "node{" + path + ":" + name + "}";
    }
}

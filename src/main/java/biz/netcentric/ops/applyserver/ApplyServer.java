/*
 * (C) Copyright 2019 Netcentric, a Cognizant Digital Business.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package biz.netcentric.ops.applyserver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import com.sun.net.httpserver.HttpServer;

/** Very simple web server that allows to upload a config zip or tar.gz, filter it on the fly according to env vars or a property file and
 * run a script that will apply the configuration.
 * 
 * The server should be started with the user that the server being controlled is started with as well. */
@SuppressWarnings("restriction")
public class ApplyServer {

    public static void main(String[] args) throws IOException {
        new ApplyServer(args);
    }

    ApplyServerConfig config;

    Map<String, String> properties = new TreeMap<>();

    private ApplyServer(String[] args) throws IOException {
        config = new ApplyServerConfig(args);
        if (config.isValid()) {
            setupFilteringProperties();
            startServer();
        }
    }

    private void setupFilteringProperties() throws IOException, FileNotFoundException {
        if (!config.isFiltering()) {
            return;
        }

        String propertiesFilename = config.getPropertiesFilename();
        File propertiesFile = propertiesFilename != null ? getFile(config.getDestination(), propertiesFilename) : null;
        if (propertiesFile != null) {
            if (!propertiesFile.exists()) {
                throw new IllegalStateException("Properties file " + propertiesFile + " does not exist");
            }
            Properties props = new Properties();
            try (InputStream is = new FileInputStream(propertiesFile)) {
                props.load(is);
            }
            for (Object key : props.keySet()) {
                String keyStr = key.toString();
                properties.put(keyStr, props.getProperty(keyStr));
            }
            System.out.println("Loaded " + props.size() + " properties from " + propertiesFile);
        }

        Map<String, String> systemEnv = System.getenv();
        for (String key : systemEnv.keySet()) {
            if (key.startsWith("_") || key.startsWith(".")) {
                continue;
            }
            String value = systemEnv.get(key);
            properties.put(key.replace("_", "."), value);
        }
    }

    private void startServer() throws IOException {

        String pidFile = config.getPidFile();
        if (StringUtils.isNotBlank(pidFile)) {
            String pid = getProcessPid();
            FileUtils.write(new File(config.getDestination(), pidFile), pid, StandardCharsets.UTF_8);
            System.out.println("Wrote pid " + pid + " to file " + pidFile);
        }
        Map<String, String> commands = config.getCommands();
        if (!commands.isEmpty()) {
            System.out.println("Commands: ");
            for (String path : commands.keySet()) {
                System.out.println("   " + path + " => " + commands.get(path));
            }
        }
        if (config.isEnableDownload()) {
            Pattern excludeFromDownloadPattern = config.getExcludeFromDownloadPattern();
            System.out.println(
                    "Downloads are enabled" + (excludeFromDownloadPattern != ApplyServerConfig.EXCLUDE_FROM_DOWNLOAD_PATTERN_DEFAULT
                            ? " with exclude pattern " + excludeFromDownloadPattern
                            : ""));
        }
        if (config.getIpRange() != null) {
            System.out.println("Client IPs are restricted to IP range " + config.getIpRange().getCidrSignature());
        }

        System.out.println("Listening at " + config.getServerPort()
                + (!config.isDisableUpload() ? " to update location " + config.getDestination() + " with " : " to execute ")
                + config.getScript());

        HttpServer server = HttpServer.create(new InetSocketAddress(config.getServerPort()), 0);
        server.createContext("/", new ApplyServerHttpHandler(config, properties));
        server.setExecutor(Executors.newFixedThreadPool(10)); // max 10 requests in parallel, but we decline parallel script executions
                                                              // further down
        server.start();
    }

    private String getProcessPid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        if (name.contains("@")) {
            String pid = StringUtils.substringBefore(name, "@");
            return pid;
        } else {
            throw new IllegalStateException(
                    "ManagementFactory.getRuntimeMXBean().getName() unexpectedly returned name without pid before '@'");
        }
    }

    static File getFile(String basePath, String path) throws IOException {
        File fileFromBasePath = new File(path);
        if (fileFromBasePath.isAbsolute()) {
            return fileFromBasePath;
        } else {
            return new File(basePath, path);
        }
    }

}

/*
 * (C) Copyright 2019 Netcentric, a Cognizant Digital Business.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package biz.netcentric.ops.applyserver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.lang3.StringUtils;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

@SuppressWarnings("restriction")
class ApplyServerHttpHandler implements HttpHandler {

    static final String HEADER_LOCATION = "Location";
    static final String HEADER_CONTENT_TYPE = "Content-Type";
    static final String CONTENT_TYPE_HTML = "text/html";

    static final String HEADER_APIKEY = "apikey";
    static final String APPLY_LOGFILE_DEFAULT = "_apply.log";

    static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

    private final ApplyServerConfig config;

    private CopyOnWriteArrayList<ScriptResult> lastResults = new CopyOnWriteArrayList<>();
    private Map<String, Lock> scriptLocks = new ConcurrentHashMap<>();

    private Map<String, String> properties;

    private String upSinceMessage;

    ZipInflater zipInflater;
    ZipDeflater zipDeflater;

    ApplyServerHttpHandler(ApplyServerConfig config, Map<String, String> properties) {

        this.config = config;
        this.properties = properties;

        zipInflater = new ZipInflater();
        zipDeflater = new ZipDeflater(config.getDestination(), this.config.isFiltering(), this.config.getExcludeFromFilteringRegex(),
                properties);

        initScriptLocks(config);

        upSinceMessage = "Up since " + new SimpleDateFormat(DATE_FORMAT).format(new Date());
    }

    private void initScriptLocks(ApplyServerConfig config) {
        scriptLocks.put(config.getScript(), new ReentrantLock());
        for (String command : config.getCommands().values()) {
            scriptLocks.put(command, new ReentrantLock());
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        ByteArrayOutputStream resultLog = new ByteArrayOutputStream();
        PrintWriter resultLogWriter = new PrintWriter(resultLog);
        String scriptToRun = null;
        try {

            String apiKey = this.config.getApiKey();
            if (StringUtils.isNotBlank(apiKey)) {
                Headers requestHeaders = exchange.getRequestHeaders();
                if (!requestHeaders.containsKey(HEADER_APIKEY)) {
                    sendShortResult(exchange, 400, "Header " + HEADER_APIKEY + " is missing in request.", resultLogWriter,
                            resultLog);
                }
                List<String> list = requestHeaders.get(HEADER_APIKEY);
                if (list.isEmpty() || !StringUtils.equals(list.get(0).trim(), apiKey)) {
                    sendShortResult(exchange, 400, "Invalid value in header " + HEADER_APIKEY, resultLogWriter, resultLog);
                }
            }

            String method = exchange.getRequestMethod();
            URI requestUri = exchange.getRequestURI();
            String requestPath = requestUri.getPath();
            Map<String, String> requestParams = readParameters(requestUri);

            if ("GET".equals(method)) {
                handleGet(exchange, requestPath);
            } else if ("POST".equals(method)) {
            	
            	// IP range is only checked for post requests
            	if(config.getIpRange()!=null) {
            		String remoteIpToCheck = exchange.getRemoteAddress().getAddress().getHostAddress();
					if(!config.getIpRange().isInRange(remoteIpToCheck)) {
						throw new IllegalArgumentException("IP Address "+ remoteIpToCheck + " not allowed");
					}
            	}
            	
                resultLogWriter
                        .println("Request from " + exchange.getRemoteAddress() + " at "
                                + new SimpleDateFormat(DATE_FORMAT).format(new Date()));

                scriptToRun = getScriptToRun(requestPath);
                handlePostSynchronized(exchange, resultLog, resultLogWriter, scriptToRun, requestPath, requestParams);
            } else {
                throw new IllegalArgumentException("Only http method GET and POST is supported");
            }

        } catch (IllegalArgumentException e) {
            sendShortResult(exchange, 400, e.getMessage(), resultLogWriter, resultLog);
            this.lastResults.add(new ScriptResult(scriptToRun, "failed", 400, resultLog.toString()));
        } catch (Exception e) {
            PrintWriter writer = new PrintWriter(resultLog);
            e.printStackTrace(writer);
            writer.flush();
            sendShortResult(exchange, 500, "Unexpected error: " + e.getMessage(), resultLogWriter, resultLog);
            this.lastResults.add(new ScriptResult(scriptToRun, "failed", 500, resultLog.toString()));
        } finally {
            if (resultLogWriter != null) {
                resultLogWriter.close();
            }
            exchange.close();
        }

    }

    private void handleGet(HttpExchange exchange, String path) throws IOException {
        String runUrlPrefix = "/run/";
        String downloadUrl = "/download.tar.gz";
        if (path.startsWith(runUrlPrefix)) {
            String urlIndex = StringUtils.substringAfter(path, runUrlPrefix);
            try {
                int index = Integer.parseInt(urlIndex) - 1;
                if (index < this.lastResults.size()) {
                    sendShortResult(exchange, 200, this.lastResults.get(index).getScriptOutput());
                } else {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException e) { // NumberFormatException is also an IllegalArgumentException
                exchange.getResponseHeaders().add(HEADER_LOCATION, "/");
                sendShortResult(exchange, 302, "Invalid index '" + urlIndex+"'");
            }
        } else if (path.equals(downloadUrl)) {
            streamDownload(exchange);
        } else {
            exchange.getResponseHeaders().add(HEADER_CONTENT_TYPE, CONTENT_TYPE_HTML);
            Deque<String> htmlLinks = new LinkedList<String>();
            for (int i = 0; i < this.lastResults.size(); i++) {
                ScriptResult scriptResult = this.lastResults.get(i);
                htmlLinks.addFirst(
                        "<a href=\"" + runUrlPrefix + (i + 1) + "\">" + new SimpleDateFormat(DATE_FORMAT).format(scriptResult.getTime()) + " "
                                + scriptResult.getScriptName() + ": <strong>" + scriptResult.getResult() + "</strong> (" + scriptResult.getResultCode()
                                + ")</a>");
            }
            sendShortResult(exchange, 200,
                    "<html><head><title>Apply Server</title></head>\n<body><h1>Apply Server</h1>\n<div>" + upSinceMessage
                            + (this.config.isEnableDownload()
                                    ? " | <a href=\"" + downloadUrl + "\">Download files</a> from destination as tar.gz"
                                    : "")
                            + "</div><br/>\n\n"
                            + (!htmlLinks.isEmpty() ? StringUtils.join(htmlLinks, "<br>\n") : "<i>no script executions</i>")
                            + "<br/>\n<br/>\n"
                            + "\n\n</body></html>");
        }
    }

    private String getScriptToRun(String requestPath) {
        String scriptToRun;
        scriptToRun = mapCommandFromRequestPath(requestPath);
        if (scriptToRun == null) {
            scriptToRun = this.config.getScript(); // use default
        }
        return scriptToRun;
    }

    private void handlePostSynchronized(HttpExchange exchange, ByteArrayOutputStream resultLog, PrintWriter resultLogWriter,
            String scriptToRun,
            String requestPath, Map<String, String> requestParams) throws ExecuteException, IOException, FileNotFoundException {
        Lock lock = this.scriptLocks.get(scriptToRun);

        if (lock.tryLock()) {
            try {
                handlePost(exchange, resultLog, resultLogWriter, requestPath, requestParams, scriptToRun);
            } finally {
                lock.unlock();
            }
        } else {
            sendShortResult(exchange, 400, "Script " + scriptToRun + " is already running - skipped request.", resultLogWriter,
                    resultLog);
            this.lastResults.add(new ScriptResult(scriptToRun, "failed", 400, resultLog.toString()));
        }
    }

    private void handlePost(HttpExchange exchange, ByteArrayOutputStream resultLog, PrintWriter resultLogWriter,
            String requestPath,
            Map<String, String> requestParams, String scriptToRun)
            throws ExecuteException, IOException, FileNotFoundException {

        long startTime = System.currentTimeMillis();
        boolean debug = Boolean.valueOf(requestParams.get("debug"));

        if (!this.config.isDisableUpload()) {
            resultLogWriter.println("Processing entity " + requestPath);
            handleUpload(exchange, resultLogWriter, requestPath, requestParams, debug);
        } else {
            resultLogWriter.println("Processing request " + requestPath);
        }

        runApplyScript(resultLogWriter, resultLog, scriptToRun);

        resultLogWriter.println("Finished after " + (System.currentTimeMillis() - startTime) + "ms");
        resultLogWriter.flush();

        exchange.sendResponseHeaders(200, resultLog.size());
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resultLog.toByteArray());
        }

        try (OutputStream fileOs = new FileOutputStream(
                ApplyServer.getFile(this.config.getDestination(), APPLY_LOGFILE_DEFAULT))) {
            fileOs.write(resultLog.toByteArray());
        }

        this.lastResults.add(new ScriptResult(scriptToRun, "success", 200, resultLog.toString()));
    }

    private void handleUpload(HttpExchange exchange, PrintWriter resultLogWriter, String requestPath,
            Map<String, String> requestParams, boolean debug) {
        String extension = null;
        try (InputStream is = exchange.getRequestBody()) {

            resultLogWriter.println("--- Placing files:");
            if (this.config.isFiltering()) {
                Pattern excludeFromFilteringRegex = this.config.getExcludeFromFilteringRegex();
                resultLogWriter.println("Filtering turned on with "
                        + (excludeFromFilteringRegex == ApplyServerConfig.EXCLUDE_FROM_FILTERING_REGEX_DEFAULT ? "default excludes"
                                : "exclude: " + excludeFromFilteringRegex));
            }

            Map<String, String> propertiesUsed = new TreeMap<>();
            int count = 0;
            if (requestParams.containsKey("format")) {
                extension = requestParams.get("format");
            } else if (requestPath.contains(".")) {
                String filename = StringUtils.substringAfterLast(requestPath, "/");
                String[] bits = filename.split("\\.");
                extension = bits[bits.length - 1];
                if (extension.equals("gz") && bits.length > 2) {
                    extension = bits[bits.length - 2] + "." + extension;
                }
            } else {
            	
            	if(!this.config.isOptionalPayload() || is.available() > 0) {
                    throw new IllegalArgumentException(
                            "Filename needs to be given as path of request or request parameter 'format' has to be used (actual: '"
                                    + requestPath  + "')");            		
            	} else {
            		resultLogWriter.println("No payload given (payload is optional)");
            		return;
            	}

            }
            boolean isZippedTar = extension.equals("tar.gz") || extension.equals("tgz");
            boolean isTar = extension.equals("tar");
            if (isZippedTar || isTar) {
                InputStream tarIs = is;
                if (isZippedTar) {
                    tarIs = new GzipCompressorInputStream(tarIs);
                }
                count = zipDeflater.extractTar(tarIs, resultLogWriter, propertiesUsed);
            } else if (extension.equals("zip")) {
                count = zipDeflater.extractZip(is, resultLogWriter, propertiesUsed);
            } else {
                throw new IllegalArgumentException("Unsupported format " + extension
                        + " - provide format either as extension in request path or as parameter 'format' in request). Request: "
                        + requestPath);
            }
            if (count == 0) {
                throw new NoFilesInRequestBodyException("Request body for " + requestPath + " did not contain any files.");
            }

            // log filtering only if a count was found
            if (this.config.isFiltering()) {
                if (debug) {
                    resultLogWriter.println("Properties for filtering: ");
                    for (String key : this.properties.keySet()) {
                        resultLogWriter.println("   " + key + "=" + StringUtils.abbreviate(this.properties.get(key), "...", 70));
                    }
                } else {
                    resultLogWriter.println("Filtered with " + this.properties.size() + " properties");
                }
            } else {
                resultLogWriter.println("Filtering is disabled");
            }

            if (!propertiesUsed.isEmpty()) {
                resultLogWriter.println("Result of filtering: ");
                for (String key : propertiesUsed.keySet()) {
                    resultLogWriter.println("   " + key + "=" + propertiesUsed.get(key));
                }
            }

        } catch (NoFilesInRequestBodyException | IOException e) {
            resultLogWriter.println("No http entity payload was sent OR the payload sent was not in expected format " + extension);
            if (!this.config.isOptionalPayload()) {
                throw new IllegalArgumentException(
                        "No http entity payload was given in request OR the payload sent was not in expected format " + extension
                                + " (use e.g. --data-binary for curl or start server with --optional-payload)");
            }
        }
    }

    private String mapCommandFromRequestPath(String path) {
        String commandPath = null;
        String[] pathSegments = path.split("/");
        if (pathSegments.length >= 2) {
            commandPath = "/" + pathSegments[1];
        }
        String command = null;
        Map<String, String> commands = this.config.getCommands();
        if (StringUtils.isNotBlank(commandPath) && commands.containsKey(commandPath)) {
            command = commands.get(commandPath);
        }
        return command;
    }

    private Map<String, String> readParameters(URI requestUri) {
        Map<String, String> queryParams = new HashMap<String, String>();
        String query = requestUri.getQuery();
        if (query == null) {
            return queryParams;
        }
        String[] queryParamsBits = query.split("[?&]");
        for (String queryParamsBit : queryParamsBits) {
            String[] keyAndValue = queryParamsBit.split("=", 2);
            String key = keyAndValue[0];
            String value = keyAndValue.length > 1 ? keyAndValue[1] : "true";
            queryParams.put(key, value);
        }
        return queryParams;
    }

    private void runApplyScript(PrintWriter resultLogWriter, OutputStream os, String scriptToRun)
            throws ExecuteException, IOException {

        String executableStr = StringUtils.substringBefore(scriptToRun, " ");
        File executableFile = ApplyServer.getFile(this.config.getDestination(), executableStr);
        if (!executableFile.exists()) {
            throw new IllegalArgumentException("Script file " + executableFile + " does not exist");
        }
        executableFile.setExecutable(true);
        resultLogWriter.println("--- Executing apply script: " + scriptToRun);
        resultLogWriter.flush();
        org.apache.commons.exec.CommandLine cmdLine = org.apache.commons.exec.CommandLine
                .parse(scriptToRun.replaceFirst(executableStr, executableFile.getAbsolutePath()));
        DefaultExecutor executor = new DefaultExecutor();
        executor.setWorkingDirectory(executableFile.getParentFile().getAbsoluteFile());
        executor.setStreamHandler(new PumpStreamHandler(os));
        executor.setExitValues(IntStream.range(0, 100).toArray());
        int exitValue = executor.execute(cmdLine);
        resultLogWriter.println("--- Apply script '" + scriptToRun + "' returned " + exitValue);
        if (exitValue > 0) {
            throw new IllegalStateException("Script '" + scriptToRun + "' returned exit code " + exitValue);
        }

    }

    private void sendShortResult(HttpExchange exchange, int code, String rawMessage) throws IOException {
        sendShortResult(exchange, code, rawMessage, null, null);
    }

    private void sendShortResult(HttpExchange exchange, int code, String rawMessage, PrintWriter resultLogWriter,
            ByteArrayOutputStream resultLog) throws IOException {

        String responseMessage = rawMessage;
        if (resultLog != null && resultLog.size() > 0) {
            responseMessage = "An error occured - logs up to this point:\n" + resultLog.toString(StandardCharsets.UTF_8.toString())
                    + "\n\nERROR: "
                    + responseMessage + "\n\n";
        } else {
            responseMessage += "\n";
        }

        if (resultLogWriter != null) {
            // also make sure the the response log contains the error message (for subsequent GET requests)
            resultLogWriter.println(rawMessage);
        }

        try (OutputStream os = exchange.getResponseBody()) {
            exchange.sendResponseHeaders(code, responseMessage.length());
            os.write(responseMessage.getBytes());
            os.flush();
        }
    }

    private void streamDownload(HttpExchange exchange) throws FileNotFoundException, IOException {
        try (OutputStream os = exchange.getResponseBody()) {
            exchange.getResponseHeaders().add(HEADER_CONTENT_TYPE, "application/gzip");
            exchange.sendResponseHeaders(200, 0);
            zipInflater.createTarGz(this.config.getDestination(), os, this.config.getExcludeFromDownloadPattern());
            os.flush();
        }
    }

}
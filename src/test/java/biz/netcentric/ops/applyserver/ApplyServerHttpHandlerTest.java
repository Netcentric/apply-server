/*
 * (C) Copyright 2019 Netcentric, a Cognizant Digital Business.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package biz.netcentric.ops.applyserver;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

@SuppressWarnings("restriction")
@RunWith(MockitoJUnitRunner.class)
public class ApplyServerHttpHandlerTest {

    private ApplyServerHttpHandler applyServerHttpHandler;

    @Spy
    HttpExchange exchange;

    @Spy
    Headers responseHeaders;

    Map<String, String> properties;

    ByteArrayOutputStream out;

    File tempDir;

    @Before
    public void setup() throws URISyntaxException, IOException {
        properties = new HashMap<String, String>();
        properties.put("testProp1", "val1");
        properties.put("testProp2", "val2");

        // defaults

        when(exchange.getRequestURI()).thenReturn(new URI("/"));
        when(exchange.getResponseHeaders()).thenReturn(responseHeaders);
        when(exchange.getResponseBody()).thenReturn(out = new ByteArrayOutputStream());

        tempDir = Files.createTempDirectory("apply-server-test").toFile();
        applyServerHttpHandler = new ApplyServerHttpHandler(
                new ApplyServerConfig(("-d " + tempDir.getAbsolutePath() + " -p 3000").split(" ")), properties);
    }

    @After
    public void teardown() throws IOException {
        FileUtils.deleteDirectory(tempDir);
    }

    @Test
    public void testGet() throws IOException {
        when(exchange.getRequestMethod()).thenReturn("GET");

        applyServerHttpHandler.handle(exchange);

        String responseText = out.toString(StandardCharsets.UTF_8.name());

        assertThat(responseText, containsString("<title>Apply Server</title>"));
        assertThat(responseText, containsString("Up since "));

        verify(exchange, times(1)).sendResponseHeaders(eq(200), anyLong());
        verify(responseHeaders, times(1)).add(ApplyServerHttpHandler.HEADER_CONTENT_TYPE, ApplyServerHttpHandler.CONTENT_TYPE_HTML);
    }

    @Test
    public void testPutIsNotAllowed() throws IOException {
        when(exchange.getRequestMethod()).thenReturn("PUT");

        applyServerHttpHandler.handle(exchange);

        verify(exchange, times(1)).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    public void testPostWithPayload() throws IOException, URISyntaxException {
        when(exchange.getRequestMethod()).thenReturn("POST");

        String testPayloadFilename = "/test-payload.tar.gz";
        when(exchange.getRequestBody()).thenReturn(getClass().getResourceAsStream(testPayloadFilename));
        when(exchange.getRequestURI()).thenReturn(new URI(testPayloadFilename));

        File confFile = new File(tempDir, "testFile.conf");

        File applyScriptFile = new File(tempDir, "_apply.sh");
        FileUtils.writeStringToFile(applyScriptFile,
                "echo 'Contents of file " + confFile.getName() + "'\ncat " + confFile.getName() + "\necho", StandardCharsets.UTF_8.name());

        applyServerHttpHandler.handle(exchange);

        verify(exchange, times(1)).sendResponseHeaders(eq(200), anyLong());

        String responseText = out.toString(StandardCharsets.UTF_8.name());

        assertTrue("File " + confFile + " should exist", confFile.exists());
        String configFileContents = FileUtils.readFileToString(confFile, StandardCharsets.UTF_8.name());

        assertThat(configFileContents, containsString("testProp1=val1"));
        assertThat(configFileContents, containsString("testProp2=val2"));

    }

    @Test
    public void testPostWithoutPayloadUsingDefaultCommand() throws IOException, URISyntaxException {
        when(exchange.getRequestMethod()).thenReturn("POST");

        File defaultScript = new File(tempDir, "defaultScript.sh");

        applyServerHttpHandler = new ApplyServerHttpHandler(
                new ApplyServerConfig(("-d " + tempDir.getAbsolutePath() + " -p 3000 -du -s "+defaultScript.getName()+"").split(" ")), properties);


        String logMarkerDefaultScript = "default";
        FileUtils.writeStringToFile(defaultScript, "echo '"+logMarkerDefaultScript+"'", StandardCharsets.UTF_8.name());


        when(exchange.getRequestURI()).thenReturn(new URI("/"));
        applyServerHttpHandler.handle(exchange);
        verify(exchange, times(1)).sendResponseHeaders(eq(200), anyLong());
        assertThat(out.toString(StandardCharsets.UTF_8.name()), containsString(logMarkerDefaultScript));
    }

    @Test
    public void testPostWithoutPayloadUsingSpecialCommand() throws IOException, URISyntaxException {
        when(exchange.getRequestMethod()).thenReturn("POST");

        File defaultScript = new File(tempDir, "defaultScript.sh");
        String specialScriptPath = "/command1";
        File specialScript = new File(tempDir, "command1.sh");
        applyServerHttpHandler = new ApplyServerHttpHandler(
                new ApplyServerConfig(("-d " + tempDir.getAbsolutePath() + " -p 3000 -du -s "+defaultScript.getName()+" -c "+specialScriptPath+"="+specialScript.getName()+"").split(" ")), properties);

        String logMarkerSpecialScript = "special";
        FileUtils.writeStringToFile(specialScript, "echo '"+logMarkerSpecialScript+"'", StandardCharsets.UTF_8.name());

        when(exchange.getRequestURI()).thenReturn(new URI(specialScriptPath));
        applyServerHttpHandler.handle(exchange);
        verify(exchange, times(1)).sendResponseHeaders(eq(200), anyLong());
        assertThat(out.toString(StandardCharsets.UTF_8.name()), containsString(logMarkerSpecialScript));

    }

}

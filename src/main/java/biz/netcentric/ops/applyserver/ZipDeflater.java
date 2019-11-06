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
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

public class ZipDeflater {
    static final Pattern VAR_REGEX = Pattern.compile("\\$\\{([a-zA-z][a-zA-z0-9._]+)\\}");

    private final String destination;
    private final boolean isFiltering;
    private final Pattern excludeFromFilteringRegex;
    private final Map<String, String> properties;

    public ZipDeflater(String destination, boolean isFiltering, Pattern excludeFromFilteringRegex, Map<String, String> properties) {
        this.destination = destination;
        this.isFiltering = isFiltering;
        this.excludeFromFilteringRegex = excludeFromFilteringRegex;
        this.properties = properties;
    }

    int extractZip(InputStream is, PrintWriter resultLogWriter, Map<String, String> propertiesUsed) throws IOException {
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry zipEntry = zis.getNextEntry();

            while (zipEntry != null) {
                if (zipEntry.isDirectory()) {
                    zipEntry = zis.getNextEntry();
                    continue;
                }
                String fileName = zipEntry.getName();
                processFile(fileName, zis, resultLogWriter, propertiesUsed);
                count++;
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
        return count;
    }

    int extractTar(InputStream is, PrintWriter resultLogWriter, Map<String, String> propertiesUsed) throws IOException {
        int count = 0;
        try (TarArchiveInputStream fin = new TarArchiveInputStream(is)) {
            TarArchiveEntry entry;
            while ((entry = fin.getNextTarEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                processFile(entryName, fin, resultLogWriter, propertiesUsed);
                count++;
            }
        }
        return count;
    }

    private void processFile(String entryName, InputStream fileContentsIs, PrintWriter resultLogWriter, Map<String, String> propertiesUsed)
            throws IOException {
        File curfile = new File(destination, entryName);
        File parent = curfile.getParentFile();
        if (!parent.exists()) {
            parent.mkdirs();
        }

        byte[] fileContentByteArray = IOUtils.toByteArray(fileContentsIs);

        boolean fileWritten = false;
        boolean excludeFileFromFiltering = excludeFromFilteringRegex.matcher(entryName).find();
        if (isFiltering && !excludeFileFromFiltering) {
            try {
                String fileContents = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(fileContentByteArray))
                        .toString();
                String fileContentsFiltered = filterFileContents(fileContents, entryName, resultLogWriter, propertiesUsed);
                FileUtils.write(curfile, fileContentsFiltered, StandardCharsets.UTF_8);
                fileWritten = true;
            } catch (CharacterCodingException e) {
                resultLogWriter.println("Could not filter file " + entryName + ", using original (" + e.getMessage() + ")");
            }
        }

        if (!fileWritten) {
            resultLogWriter.println("Extracted " + StringUtils.rightPad(entryName, 50) + " (not filtered)");
            FileUtils.writeByteArrayToFile(curfile, fileContentByteArray);
        }

    }

    private String filterFileContents(String fileContents, String entryName, PrintWriter resultLogWriter,
            Map<String, String> propertiesUsed) {

        StringBuffer sb = new StringBuffer();
        int count = 0;
        Matcher matcher = VAR_REGEX.matcher(fileContents);
        while (matcher.find()) {
            String key = matcher.group(1);
            String value;
            if (this.properties.containsKey(key)) {
                value = this.properties.get(key);
                propertiesUsed.put(key, value);
            } else {
                value = matcher.group(0);
                String valFromSysEnv = System.getenv().get(key);
                propertiesUsed.put(key, 
                        StringUtils.isNotBlank(valFromSysEnv) ? 
                                "<left unchanged but OS Env contains value '"+valFromSysEnv+"' that may become active>" : 
                                "<left unchanged>");
            }

            value = value.replace("$", "\\$"); // ensure remaining variables are not treated as back reference
            matcher.appendReplacement(sb, value);
            count++;
        }
        matcher.appendTail(sb);
        resultLogWriter
                .println("Extracted " + StringUtils.rightPad(entryName, 50)
                        + (count > 0 ? " (replaced " + count + " variables)" : " (no variables found)"));
        return sb.toString();
    }
}

/*
 * (C) Copyright 2019 Netcentric, a Cognizant Digital Business.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package biz.netcentric.ops.applyserver;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
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

        // Using ZipArchiveInputStream does not make all properties accessible => Use of tmp file and ZipFile works
        // see https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/archivers/zip/ZipArchiveEntry.html#getExternalAttributes--
        File tempZipFile = File.createTempFile("applyserver-", "-temp.zip");
        FileUtils.copyInputStreamToFile(is, tempZipFile);

        int count = 0;
        try (ZipFile zipFile = new ZipFile(tempZipFile)) {
            
            Enumeration<? extends ZipArchiveEntry> entries = zipFile.getEntries();
            ZipArchiveEntry entry;
            while (entries.hasMoreElements()) {
               entry = entries.nextElement();
               if (entry.isDirectory()) {
                    continue;
                }

                String entryName = entry.getName();
                processFile(entryName, zipFile.getInputStream(entry), resultLogWriter, propertiesUsed, entry.isUnixSymlink());
                count++;
            }
        }
        
        FileUtils.deleteQuietly(tempZipFile);

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
                
                InputStream relevantEntryInputStream = fin;
                
                boolean symbolicLink = entry.isSymbolicLink();
                if(symbolicLink) {
                    relevantEntryInputStream = new ByteArrayInputStream(entry.getLinkName().getBytes(StandardCharsets.ISO_8859_1));
                }

                processFile(entryName, relevantEntryInputStream, resultLogWriter, propertiesUsed, symbolicLink);
                count++;
            }
        }
        return count;
    }

    private void processFile(String entryName, InputStream fileContentsIs, PrintWriter resultLogWriter, Map<String, String> propertiesUsed, boolean isSymlink)
            throws IOException {
        File curfile = new File(destination, entryName);
        
        if(isSymlink) {
            createSymlink(entryName, fileContentsIs, resultLogWriter, curfile);
            return;
        }

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

    private void createSymlink(String entryName, InputStream fileContentsIs, PrintWriter resultLogWriter, File curfile) {
        Path symlinkTarget = null;
        try {
            symlinkTarget = new File(IOUtils.toString(fileContentsIs, StandardCharsets.ISO_8859_1)).toPath();
            if(curfile.exists()) {
                curfile.delete(); // createSymbolicLink requires the file to not exist
            }
            Files.createSymbolicLink(curfile.toPath(), symlinkTarget);
            resultLogWriter.println("Created symbolic link " + entryName + " -> "+symlinkTarget);
        } catch (Exception e) {
            resultLogWriter.println("Could not create symbolic link " + entryName + " -> "+symlinkTarget +": " + e.getMessage() + " ("+e.getClass()+")");
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

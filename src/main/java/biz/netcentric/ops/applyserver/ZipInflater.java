/*
 * (C) Copyright 2019 Netcentric, a Cognizant Digital Business.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package biz.netcentric.ops.applyserver;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.regex.Pattern;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

class ZipInflater {

    void createTarGz(String pathToCompress, OutputStream out, Pattern excludePattern) throws FileNotFoundException, IOException {
        TarArchiveOutputStream tarOutputStream = null;
        try {
            tarOutputStream = new TarArchiveOutputStream(new GzipCompressorOutputStream(new BufferedOutputStream(out)));
            addFileToTarGz(tarOutputStream, pathToCompress, "", true, excludePattern);
            tarOutputStream.finish();
        } finally {
            tarOutputStream.close();
        }
    }

    private void addFileToTarGz(TarArchiveOutputStream tarOutputStream, String pathToAdd, String tarBasePath, boolean isRoot,
            Pattern excludePattern)
            throws IOException {

        File fileToAdd = new File(pathToAdd);

        String entryName = !isRoot ? tarBasePath + fileToAdd.getName() : "";
        if (excludePattern != null && excludePattern.matcher(entryName).find()) {
            return;
        }
        if (fileToAdd.isFile()) {
            TarArchiveEntry tarEntry = new TarArchiveEntry(fileToAdd, entryName);
            tarOutputStream.putArchiveEntry(tarEntry);
            FileInputStream in = new FileInputStream(fileToAdd);
            IOUtils.copy(in, tarOutputStream);
            in.close();
            tarOutputStream.closeArchiveEntry();
        } else {
            if (!isRoot) {
                TarArchiveEntry tarEntry = new TarArchiveEntry(fileToAdd, entryName);
                tarOutputStream.putArchiveEntry(tarEntry);
                tarOutputStream.closeArchiveEntry();
            }
            File[] children = fileToAdd.listFiles();
            if (children != null) {
                for (File child : children) {
                    addFileToTarGz(tarOutputStream, child.getAbsolutePath(), StringUtils.isNotEmpty(entryName) ? entryName + "/" : "",
                            false, excludePattern);
                }
            }
        }
    }
}
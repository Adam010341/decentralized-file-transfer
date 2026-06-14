package com.airdrop.infrastructure.util;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ZipUtil {

    public interface ZipProgressListener {
        void onProgress(long bytesProcessed, long totalBytes);
    }

    /**
     * 將指定的資料夾打包成 ZIP 檔，不回報進度
     */
    public static void zipDirectory(File sourceDir, File zipFile) throws IOException {
        zipDirectory(sourceDir, zipFile, null);
    }

    /**
     * 將指定的資料夾打包成 ZIP 檔，並支援進度回報
     *
     * @param sourceDir 要壓縮的來源資料夾
     * @param zipFile   輸出的 ZIP 檔案
     * @param listener  進度監聽器
     * @throws IOException
     */
    public static void zipDirectory(File sourceDir, File zipFile, ZipProgressListener listener) throws IOException {
        Path sourcePath = sourceDir.toPath();
        
        // 1. 先計算總大小
        long totalBytes = 0;
        if (listener != null) {
            try (java.util.stream.Stream<Path> stream = Files.walk(sourcePath)) {
                totalBytes = stream.filter(p -> p.toFile().isFile())
                                   .mapToLong(p -> p.toFile().length())
                                   .sum();
            }
        }
        
        final long finalTotalBytes = totalBytes;
        final long[] bytesProcessed = {0};

        // 2. 開始壓縮
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            Files.walkFileTree(sourcePath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path targetFile = sourcePath.relativize(file);
                    String zipPath = sourceDir.getName() + "/" + targetFile.toString().replace("\\", "/");
                    
                    zos.putNextEntry(new ZipEntry(zipPath));
                    
                    try (FileInputStream fis = new FileInputStream(file.toFile())) {
                        byte[] buffer = new byte[8192];
                        int length;
                        while ((length = fis.read(buffer)) > 0) {
                            zos.write(buffer, 0, length);
                            if (listener != null) {
                                bytesProcessed[0] += length;
                                listener.onProgress(bytesProcessed[0], finalTotalBytes);
                            }
                        }
                    }
                    
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(sourcePath)) {
                        Path targetDir = sourcePath.relativize(dir);
                        String zipPath = sourceDir.getName() + "/" + targetDir.toString().replace("\\", "/") + "/";
                        zos.putNextEntry(new ZipEntry(zipPath));
                        zos.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /**
     * 將 ZIP 檔解壓縮到指定的目標資料夾中
     *
     * @param zipFile  要解壓縮的 ZIP 檔案
     * @param destDir  解壓縮目的地資料夾
     * @throws IOException
     */
    public static void unzip(File zipFile, File destDir) throws IOException {
        if (!destDir.exists()) {
            destDir.mkdirs();
        }

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                File newFile = newFile(destDir, zipEntry);
                if (zipEntry.isDirectory()) {
                    if (!newFile.isDirectory() && !newFile.mkdirs()) {
                        throw new IOException("Failed to create directory " + newFile);
                    }
                } else {
                    File parent = newFile.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Failed to create directory " + parent);
                    }

                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
    }

    /**
     * 防範 Zip Slip 漏洞：確保解壓縮的路徑在目標資料夾內
     */
    private static File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        File destFile = new File(destinationDir, zipEntry.getName());

        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }

        return destFile;
    }
}

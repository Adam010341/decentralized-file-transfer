package com.airdrop.domain.model;

import java.util.Objects;

/**
 * FileTask represents a file transfer task (either sending or receiving).
 */
public class FileTask {
    private String id;
    private String filePath;
    private String fileName;
    private long fileSize;
    private long bytesTransferred;
    private Status status;

    private boolean isDirectory;

    public enum Status {
        WAITING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public FileTask() {
    }

    public FileTask(String id, String filePath, String fileName, long fileSize) {
        this.id = id;
        this.filePath = filePath;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.bytesTransferred = 0;
        this.status = Status.WAITING;
        this.isDirectory = false;
    }
    
    public FileTask(String id, String filePath, String fileName, long fileSize, boolean isDirectory) {
        this.id = id;
        this.filePath = filePath;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.bytesTransferred = 0;
        this.status = Status.WAITING;
        this.isDirectory = isDirectory;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public long getBytesTransferred() {
        return bytesTransferred;
    }

    public void setBytesTransferred(long bytesTransferred) {
        this.bytesTransferred = bytesTransferred;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public boolean isDirectory() {
        return isDirectory;
    }

    public void setDirectory(boolean directory) {
        isDirectory = directory;
    }

    public double getProgress() {
        if (fileSize <= 0) return 0.0;
        return (double) bytesTransferred / fileSize;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileTask fileTask = (FileTask) o;
        return java.util.Objects.equals(id, fileTask.id);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(id);
    }

    @Override
    public String toString() {
        return "FileTask{" +
                "id='" + id + '\'' +
                ", fileName='" + fileName + '\'' +
                ", fileSize=" + fileSize +
                ", isDirectory=" + isDirectory +
                ", progress=" + String.format("%.2f%%", getProgress() * 100) +
                ", status=" + status +
                '}';
    }
}

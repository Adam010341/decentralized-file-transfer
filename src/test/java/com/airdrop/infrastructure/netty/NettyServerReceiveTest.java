package com.airdrop.infrastructure.netty;

import com.airdrop.domain.model.FileTask;
import com.airdrop.usecase.port.in.FileTransferListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.DataOutputStream;
import java.io.File;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the receiving side of {@link NettyServer} over a real loopback TCP
 * connection. A plain {@link Socket} plays the sender, so these tests do not
 * depend on {@link NettyClient} or on multicast discovery.
 */
public class NettyServerReceiveTest {

    private NettyServer server;
    private String fileName;

    @BeforeEach
    public void setUp() {
        server = new NettyServer();
        fileName = "receive-test-" + UUID.randomUUID() + ".bin";
    }

    @AfterEach
    public void tearDown() {
        server.stop();
        deleteQuietly(new File("downloaded_" + fileName));
        deleteQuietly(new File(fileName));
    }

    @Test
    public void receivedFileIsSavedAsDownloadedPrefixInWorkingDirectory() throws Exception {
        byte[] data = randomBytes(64 * 1024);
        AtomicReference<FileTask> received = new AtomicReference<>();
        CountDownLatch allBytesArrived = new CountDownLatch(1);

        server.startTcpServer(0, new FileTransferListener() {
            @Override
            public void onProgressUpdated(FileTask task) {
                if (task.getFileSize() > 0 && task.getBytesTransferred() >= task.getFileSize()) {
                    received.set(task);
                    allBytesArrived.countDown();
                }
            }

            @Override
            public void onError(FileTask task, String errorMessage) {
                allBytesArrived.countDown();
            }
        });

        send(server.getBoundTcpPort(), fileName, data.length, data);

        assertTrue(allBytesArrived.await(5, TimeUnit.SECONDS), "receiver should get every byte");
        FileTask task = received.get();
        assertNotNull(task, "receiver should report the finished task");
        assertEquals(fileName, task.getFileName(), "task file name is the name the sender declared");
        assertEquals("./downloaded_" + fileName, task.getFilePath(), "file is saved as downloaded_<name>");

        File saved = new File("downloaded_" + fileName);
        assertTrue(saved.isFile(), "downloaded_<name> should exist in the working directory");
        assertArrayEquals(data, Files.readAllBytes(saved.toPath()), "saved bytes must match sent bytes");
        assertFalse(new File(fileName).exists(), "receiver must not write to ./<name>");
    }

    @Test
    public void receiverMarksTaskCompletedWhenAllBytesArrive() throws Exception {
        byte[] data = randomBytes(16 * 1024);
        AtomicReference<FileTask> completed = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        server.startTcpServer(0, new FileTransferListener() {
            @Override
            public void onProgressUpdated(FileTask task) {
                if (task.getStatus() == FileTask.Status.COMPLETED) {
                    completed.set(task);
                    done.countDown();
                }
            }

            @Override
            public void onError(FileTask task, String errorMessage) {
                error.set(errorMessage);
                done.countDown();
            }
        });

        send(server.getBoundTcpPort(), fileName, data.length, data);

        assertTrue(done.await(5, TimeUnit.SECONDS), "receiver should mark the task COMPLETED after the sender closes");
        assertNull(error.get(), "a complete transfer must not be reported as an error");
        assertEquals(data.length, completed.get().getBytesTransferred());
    }

    @Test
    public void receiverReportsErrorWhenConnectionClosesEarly() throws Exception {
        byte[] partial = randomBytes(40);
        AtomicReference<FileTask> failed = new AtomicReference<>();
        CountDownLatch errorReported = new CountDownLatch(1);

        server.startTcpServer(0, new FileTransferListener() {
            @Override
            public void onProgressUpdated(FileTask task) {
                // not used
            }

            @Override
            public void onError(FileTask task, String errorMessage) {
                failed.set(task);
                errorReported.countDown();
            }
        });

        // Declare 100 bytes but send only 40, then close.
        send(server.getBoundTcpPort(), fileName, 100, partial);

        assertTrue(errorReported.await(5, TimeUnit.SECONDS), "a short transfer should be reported as an error");
        assertNotEquals(FileTask.Status.COMPLETED, failed.get().getStatus());
    }

    /** Writes the metadata frame and the payload the way NettyClient does, then closes. */
    private static void send(int port, String name, long declaredSize, byte[] payload) throws Exception {
        String metadata = "task-" + UUID.randomUUID() + "|" + name + "|" + declaredSize + "|test-sender";
        byte[] header = metadata.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", port);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
            out.writeInt(header.length);
            out.write(header);
            out.write(payload);
            out.flush();
        }
    }

    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);
        return data;
    }

    private static void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }
}

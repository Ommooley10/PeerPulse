package org.p1.service;

import org.p1.utils.UploadUtils;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;

public class FileSharer {

    private final HashMap<Integer, FileMeta> availableFiles = new HashMap<>();

    public int offerFile(String path, String contentType) {
        int port;
        while (true) {
            port = UploadUtils.generateCode();
            if (!availableFiles.containsKey(port)) {
                availableFiles.put(port, new FileMeta(path, contentType));
                return port;
            }
        }
    }

    public void startFileServer(int port) {
        FileMeta meta = availableFiles.get(port);
        if (meta == null) return;

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            Socket client = serverSocket.accept();
            new Thread(new FileSenderHandler(client, meta)).start();
        } catch (IOException ignored) {}
    }

    static class FileMeta {
        String path;
        String contentType;

        FileMeta(String p, String c) {
            path = p;
            contentType = c;
        }
    }

    private static class FileSenderHandler implements Runnable {

        private final Socket socket;
        private final FileMeta meta;

        FileSenderHandler(Socket s, FileMeta m) {
            socket = s;
            meta = m;
        }

        @Override
        public void run() {
            try (FileInputStream fis = new FileInputStream(meta.path);
                 OutputStream os = socket.getOutputStream()) {

                String header =
                        "Filename: " + new File(meta.path).getName() + "\n" +
                                "Content-Type: " + meta.contentType + "\n\n";
                os.write(header.getBytes());

                byte[] buffer = new byte[64 * 1024];
                int r;
                while ((r = fis.read(buffer)) != -1)
                    os.write(buffer, 0, r);
            } catch (Exception ignored) {
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }
}

package org.p1.controller;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.io.IOUtils;
import org.p1.service.FileSharer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileController {

    private final FileSharer fileSharer;
    private final HttpServer httpServer;
    private final String uploadDir;
    private final ExecutorService executorService;

    public FileController(int port) throws IOException {
        this.fileSharer = new FileSharer();
        this.httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        this.uploadDir = System.getProperty("java.io.tmpdir") + File.separator + "peerlink-uploads";
        this.executorService = Executors.newFixedThreadPool(10);

        File dir = new File(uploadDir);
        if (!dir.exists()) dir.mkdirs();

        httpServer.createContext("/upload", new UploadHandler());
        httpServer.createContext("/download", new DownloadHandler());
        httpServer.createContext("/", new CORSHandler());
        httpServer.setExecutor(executorService);
    }

    public void start() {
        httpServer.start();
        System.out.println("API server started on port " + httpServer.getAddress().getPort());
    }

    public void stop() {
        httpServer.stop(0);
        executorService.shutdown();
    }

    /* ---------------- CORS ---------------- */

    private static class CORSHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers h = exchange.getResponseHeaders();
            h.add("Access-Control-Allow-Origin", "*");
            h.add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
            h.add("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            exchange.sendResponseHeaders(404, 0);
            exchange.close();
        }
    }

    /* ---------------- UPLOAD ---------------- */

    private class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {

            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.startsWith("multipart/form-data")) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
            byte[] requestData = IOUtils.toByteArray(exchange.getRequestBody());

            Multiparser.ParseResult result =
                    new Multiparser(requestData, boundary).parse();

            if (result == null) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            String uniqueName =
                    UUID.randomUUID() + "_" + new File(result.fileName).getName();
            String path = uploadDir + File.separator + uniqueName;

            try (FileOutputStream fos = new FileOutputStream(path)) {
                fos.write(result.fileContent);
            }

            int port = fileSharer.offerFile(path, result.contentType);
            new Thread(() -> fileSharer.startFileServer(port)).start();

            String response = "{\"port\":" + port + "}";
            headers.add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length());

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }

    /* ---------------- MULTIPART PARSER ---------------- */

    private static class Multiparser {
        private final byte[] data;
        private final String boundary;

        Multiparser(byte[] data, String boundary) {
            this.data = data;
            this.boundary = boundary;
        }

        ParseResult parse() {
            try {
                String s = new String(data);

                String fnKey = "filename=\"";
                int fnStart = s.indexOf(fnKey) + fnKey.length();
                int fnEnd = s.indexOf("\"", fnStart);
                String fileName = s.substring(fnStart, fnEnd);

                String ctKey = "Content-Type: ";
                int ctStart = s.indexOf(ctKey, fnEnd);
                int ctEnd = s.indexOf("\r\n", ctStart);
                String contentType = s.substring(ctStart + ctKey.length(), ctEnd);

                int dataStart = s.indexOf("\r\n\r\n", ctEnd) + 4;
                byte[] boundaryBytes = ("\r\n--" + boundary).getBytes();
                int dataEnd = findSequence(data, boundaryBytes, dataStart);

                byte[] fileBytes = new byte[dataEnd - dataStart];
                System.arraycopy(data, dataStart, fileBytes, 0, fileBytes.length);

                return new ParseResult(fileName, fileBytes, contentType);
            } catch (Exception e) {
                return null;
            }
        }

        static int findSequence(byte[] src, byte[] seq, int start) {
            outer:
            for (int i = start; i <= src.length - seq.length; i++) {
                for (int j = 0; j < seq.length; j++)
                    if (src[i + j] != seq[j]) continue outer;
                return i;
            }
            return -1;
        }

        static class ParseResult {
            String fileName;
            byte[] fileContent;
            String contentType;

            ParseResult(String f, byte[] c, String t) {
                fileName = f;
                fileContent = c;
                contentType = t;
            }
        }
    }

    /* ---------------- DOWNLOAD ---------------- */

    private class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {

            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            int port = Integer.parseInt(
                    exchange.getRequestURI().getPath().replace("/download/", "")
            );

            try (Socket socket = new Socket("localhost", port)) {

                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(socket.getInputStream()));

                String fileName = "file";
                String contentType = "application/octet-stream";
                String line;

                while (!(line = reader.readLine()).isEmpty()) {
                    if (line.startsWith("Filename: "))
                        fileName = line.substring(10);
                    if (line.startsWith("Content-Type: "))
                        contentType = line.substring(14);
                }

                File temp = File.createTempFile("download-", ".bin");
                try (FileOutputStream fos = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[64 * 1024];
                    int r;
                    InputStream in = socket.getInputStream();
                    while ((r = in.read(buffer)) != -1)
                        fos.write(buffer, 0, r);
                }

                headers.add("Content-Type", contentType);
                headers.add("Content-Disposition",
                        "attachment; filename=\"" + fileName + "\"");
                headers.add("Accept-Ranges", "bytes");
                headers.add("Content-Length", String.valueOf(temp.length()));

                exchange.sendResponseHeaders(200, temp.length());

                try (FileInputStream fis = new FileInputStream(temp);
                     OutputStream os = exchange.getResponseBody()) {

                    byte[] buffer = new byte[64 * 1024];
                    int r;
                    while ((r = fis.read(buffer)) != -1)
                        os.write(buffer, 0, r);
                }

                temp.delete();
            }
        }
    }
}

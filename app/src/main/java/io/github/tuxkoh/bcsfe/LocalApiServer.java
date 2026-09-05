package io.github.tuxkoh.bcsfe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A small loopback-only HTTP bridge for repeatable editor smoke tests. */
final class LocalApiServer {
    static final int DEFAULT_PORT = 8765;

    interface Handler {
        Response handle(String method, String target, Map<String, String> headers, byte[] body);
    }

    static final class Response {
        final int status;
        final String contentType;
        final byte[] body;
        Response(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body == null ? new byte[0] : body;
        }
        static Response text(int status, String body) {
            return new Response(status, "application/json; charset=utf-8",
                    body.getBytes(StandardCharsets.UTF_8));
        }
        static Response download(int status, String fileName, byte[] body) {
            // Explicit download metadata is required by several OEM browsers
            // and file managers (including some tablet document providers).
            return new Response(status, "application/octet-stream; charset=binary",
                    body).withDownloadName(fileName);
        }
        private String downloadName;
        private Response withDownloadName(String value) { downloadName = value; return this; }
    }

    private final int port;
    private final Handler handler;
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private Thread acceptThread;

    LocalApiServer(int port, Handler handler) {
        this.port = port;
        this.handler = handler;
    }

    synchronized void start() {
        if (running) return;
        try {
            ServerSocket socket = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
            socket.setReuseAddress(true);
            serverSocket = socket;
            running = true;
            acceptThread = new Thread(this::acceptLoop, "bcsfe-local-api");
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (IOException ignored) {
            // The editor remains fully usable if the optional test port is busy.
            running = false;
        }
    }

    synchronized void stop() {
        running = false;
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        clients.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(5000);
                clients.execute(() -> serve(socket));
            } catch (SocketException closed) {
                if (running) continue;
                return;
            } catch (IOException ignored) {
                if (!running) return;
            }
        }
    }

    private void serve(Socket socket) {
        try (Socket connection = socket;
             InputStream input = connection.getInputStream();
             OutputStream output = connection.getOutputStream()) {
            Request request = readRequest(input);
            Response response = request == null
                    ? Response.text(400, "{\"error\":\"invalid request\"}")
                    : handler.handle(request.method, request.target, request.headers, request.body);
            if (response == null) response = Response.text(500, "{\"error\":\"no response\"}");
            writeResponse(output, response);
        } catch (Exception ignored) {
            // Do not expose save data or credentials through logcat.
        }
    }

    private static final int MAX_BODY = 8 * 1024 * 1024;

    private static Request readRequest(InputStream input) throws IOException {
        String requestLine = readLine(input, 8192);
        if (requestLine == null) return null;
        String[] first = requestLine.split(" ", 3);
        if (first.length != 3) return null;
        Map<String, String> headers = new HashMap<>();
        for (;;) {
            String line = readLine(input, 8192);
            if (line == null || line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
        }
        int length = 0;
        try { length = Integer.parseInt(headers.getOrDefault("content-length", "0")); }
        catch (NumberFormatException ignored) { return null; }
        if (length < 0 || length > MAX_BODY) return null;
        byte[] body = new byte[length];
        int at = 0;
        while (at < length) {
            int read = input.read(body, at, length - at);
            if (read < 0) return null;
            at += read;
        }
        return new Request(first[0], first[1], headers, body);
    }

    private static String readLine(InputStream input, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < max; i++) {
            int value = input.read();
            if (value < 0) return out.size() == 0 ? null : out.toString(StandardCharsets.US_ASCII);
            if (value == '\n') return out.toString(StandardCharsets.US_ASCII).replace("\r", "");
            out.write(value);
        }
        return null;
    }

    private static void writeResponse(OutputStream output, Response response) throws IOException {
        String reason = response.status == 200 ? "OK" : response.status == 204 ? "No Content"
                : response.status == 400 ? "Bad Request" : response.status == 404 ? "Not Found"
                : response.status == 409 ? "Conflict" : response.status == 500 ? "Internal Server Error" : "Error";
        StringBuilder header = new StringBuilder("HTTP/1.1 ").append(response.status).append(" ").append(reason).append("\r\n")
                .append("Content-Type: ").append(response.contentType).append("\r\n")
                .append("Content-Length: ").append(response.body.length).append("\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Access-Control-Allow-Origin: *\r\n")
                .append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                .append("Access-Control-Allow-Headers: Content-Type\r\n");
        if (response.downloadName != null) {
            String safeName = response.downloadName.replaceAll("[^A-Za-z0-9._-]", "_");
            header.append("Content-Disposition: attachment; filename=\"").append(safeName).append("\"\r\n");
        }
        header.append("Connection: close\r\n\r\n");
        output.write(header.toString().getBytes(StandardCharsets.US_ASCII));
        output.write(response.body);
        output.flush();
    }

    private static final class Request {
        final String method, target;
        final Map<String, String> headers;
        final byte[] body;
        Request(String method, String target, Map<String, String> headers, byte[] body) {
            this.method = method; this.target = target;
            this.headers = Collections.unmodifiableMap(headers); this.body = body;
        }
    }
}

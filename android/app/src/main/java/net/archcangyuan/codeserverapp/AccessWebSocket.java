package net.archcangyuan.codeserverapp;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal RFC 6455 WebSocket client for Cloudflare Access TCP tunnels, the same
 * transport {@code cloudflared access rdp} uses: a WebSocket to the application
 * hostname authenticated with the {@code Cf-Access-Token} header, carrying the
 * raw TCP stream as binary messages.
 */
final class AccessWebSocket implements AutoCloseable {
    /** Thrown when Access redirects to its login page: the token is missing or expired. */
    static final class LoginRequiredException extends IOException {
        LoginRequiredException(String message) {
            super(message);
        }
    }

    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private volatile String closeReason = "";
    private static final int OPCODE_CONTINUATION = 0x0;
    private static final int OPCODE_TEXT = 0x1;
    private static final int OPCODE_BINARY = 0x2;
    private static final int OPCODE_CLOSE = 0x8;
    private static final int OPCODE_PING = 0x9;
    private static final int OPCODE_PONG = 0xA;

    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;
    /** Clients mask the frames they send; servers must not. */
    private final boolean client;
    private final SecureRandom random = new SecureRandom();
    private final Object writeLock = new Object();
    private volatile boolean closed;

    private AccessWebSocket(Socket socket, InputStream input, OutputStream output, boolean client) {
        this.socket = socket;
        this.input = input;
        this.output = output;
        this.client = client;
    }

    /**
     * Completes the server side of a WebSocket upgrade whose request headers
     * have already been read from {@code input}.
     */
    static AccessWebSocket acceptServer(Socket socket, InputStream input, String key)
        throws IOException {
        OutputStream output = socket.getOutputStream();
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: " + expectedAccept(key) + "\r\n"
            + "\r\n";
        output.write(response.getBytes(StandardCharsets.US_ASCII));
        output.flush();
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(0);
        return new AccessWebSocket(socket, input, output, false);
    }

    /** Opens {@code wss://host:443/} with the given Access credential. */
    static AccessWebSocket connect(String host, AccessCredential credential) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, 443), CONNECT_TIMEOUT_MS);
            SSLSocket tlsSocket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(socket, host, 443, true);
            SSLParameters parameters = tlsSocket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tlsSocket.setSSLParameters(parameters);
            tlsSocket.startHandshake();
            return handshake(tlsSocket, host, credential);
        } catch (IOException | RuntimeException exception) {
            socket.close();
            throw exception;
        }
    }

    /** Opens a plain {@code ws://} connection; used by tests against a local server. */
    static AccessWebSocket connectPlain(String host, int port, AccessCredential credential)
        throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return handshake(socket, host + ":" + port, credential);
        } catch (IOException | RuntimeException exception) {
            socket.close();
            throw exception;
        }
    }

    private static AccessWebSocket handshake(
        Socket socket,
        String hostHeader,
        AccessCredential credential
    ) throws IOException {
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(CONNECT_TIMEOUT_MS);
        InputStream input = new BufferedInputStream(socket.getInputStream());
        OutputStream output = socket.getOutputStream();

        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        StringBuilder request = new StringBuilder()
            .append("GET / HTTP/1.1\r\n")
            .append("Host: ").append(hostHeader).append("\r\n")
            .append("Upgrade: websocket\r\n")
            .append("Connection: Upgrade\r\n")
            .append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            .append("Sec-WebSocket-Version: 13\r\n");
        for (Map.Entry<String, String> header : credential.headers().entrySet()) {
            String value = header.getValue();
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IOException("Invalid Access credential");
            }
            request.append(header.getKey()).append(": ").append(value).append("\r\n");
        }
        request.append("User-Agent: YourWorkspace\r\n").append("\r\n");
        output.write(request.toString().getBytes(StandardCharsets.UTF_8));
        output.flush();

        String statusLine = readLine(input);
        String[] statusParts = statusLine.split(" ", 3);
        int status = statusParts.length >= 2 ? parseStatus(statusParts[1]) : -1;
        String accept = null;
        String location = null;
        for (String line = readLine(input); !line.isEmpty(); line = readLine(input)) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            if (name.equals("sec-websocket-accept")) {
                accept = value;
            } else if (name.equals("location")) {
                location = value;
            }
        }

        if (status != 101) {
            boolean loginRedirect = (status == 302 || status == 303 || status == 307)
                && location != null
                && location.contains("/cdn-cgi/access/login");
            if (loginRedirect || status == 401 || status == 403) {
                throw new LoginRequiredException(credential.isServiceToken()
                    ? "Cloudflare Access rejected the service token"
                    : "Cloudflare Access sign-in required");
            }
            throw new IOException("Tunnel handshake failed: " + statusLine);
        }
        if (accept == null || !accept.equals(expectedAccept(key))) {
            throw new IOException("Tunnel handshake failed: invalid Sec-WebSocket-Accept");
        }
        socket.setSoTimeout(0);
        return new AccessWebSocket(socket, input, output, true);
    }

    private static int parseStatus(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static String expectedAccept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String readLine(InputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int value = input.read();
            if (value < 0) {
                throw new EOFException("Connection closed during tunnel handshake");
            }
            if (value == '\n') {
                int length = line.length();
                if (length > 0 && line.charAt(length - 1) == '\r') {
                    line.setLength(length - 1);
                }
                return line.toString();
            }
            if (line.length() > 8192) {
                throw new IOException("Tunnel handshake header too long");
            }
            line.append((char) value);
        }
    }

    /**
     * Checks the whole path to the remote desktop: sends an RDP X.224
     * Connection Request through the tunnel and waits for the Connection
     * Confirm. Returns a short human-readable result.
     */
    static String probeRemoteDesktop(String host, AccessCredential credential) {
        // TPKT + X.224 Connection Request with RDP_NEG_REQ (TLS | CredSSP).
        byte[] request = {
            0x03, 0x00, 0x00, 0x13, 0x0e, (byte) 0xe0, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x08, 0x00, 0x03, 0x00, 0x00, 0x00
        };
        try (AccessWebSocket tunnel = connect(host, credential)) {
            tunnel.setReadTimeout(10_000);
            tunnel.sendBinary(request, 0, request.length);
            byte[] response = tunnel.readMessage();
            if (response == null) {
                return "Tunnel opened, but the remote side closed it without answering. "
                    + "Check that the tunnel's service is rdp://… and the PC is reachable.";
            }
            if (response.length >= 6 && response[0] == 0x03 && (response[5] & 0xF0) == 0xD0) {
                return "OK: the remote desktop answered through Cloudflare.";
            }
            return "Tunnel opened, but the answer is not RDP (" + response.length + " bytes).";
        } catch (LoginRequiredException exception) {
            return credential.isServiceToken()
                ? "Cloudflare rejected the service token: check it and the app's policy."
                : "Cloudflare rejected the token: sign in again.";
        } catch (java.net.SocketTimeoutException exception) {
            return "Tunnel opened, but the remote desktop did not answer within 10 s.";
        } catch (IOException exception) {
            return "Failed: " + exception.getMessage();
        }
    }

    /** Sends one binary message. Safe to call from any thread. */
    void sendBinary(byte[] data, int offset, int length) throws IOException {
        sendFrame(OPCODE_BINARY, data, offset, length);
    }

    void sendPing() throws IOException {
        sendFrame(OPCODE_PING, new byte[0], 0, 0);
    }

    private void sendFrame(int opcode, byte[] data, int offset, int length) throws IOException {
        int maskBit = client ? 0x80 : 0;
        byte[] header;
        if (length < 126) {
            header = new byte[] { (byte) (0x80 | opcode), (byte) (maskBit | length) };
        } else if (length < 65_536) {
            header = new byte[] {
                (byte) (0x80 | opcode), (byte) (maskBit | 126),
                (byte) (length >>> 8), (byte) length
            };
        } else {
            header = new byte[10];
            header[0] = (byte) (0x80 | opcode);
            header[1] = (byte) (maskBit | 127);
            for (int index = 0; index < 8; index++) {
                header[2 + index] = (byte) (((long) length) >>> (56 - 8 * index));
            }
        }
        byte[] mask = null;
        byte[] payload;
        if (client) {
            mask = new byte[4];
            random.nextBytes(mask);
            payload = new byte[length];
            for (int index = 0; index < length; index++) {
                payload[index] = (byte) (data[offset + index] ^ mask[index & 3]);
            }
        } else {
            payload = data;
        }
        synchronized (writeLock) {
            output.write(header);
            if (mask != null) {
                output.write(mask);
                output.write(payload, 0, length);
            } else {
                output.write(payload, offset, length);
            }
            output.flush();
        }
    }

    /**
     * Reads the next data message, answering pings along the way. Returns
     * {@code null} when the server closes the connection.
     */
    byte[] readMessage() throws IOException {
        ByteArrayOutputStream message = null;
        while (true) {
            int first = input.read();
            if (first < 0) {
                return null;
            }
            int second = readByte();
            boolean fin = (first & 0x80) != 0;
            int opcode = first & 0x0F;
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7F;
            if (length == 126) {
                length = ((long) readByte() << 8) | readByte();
            } else if (length == 127) {
                length = 0;
                for (int index = 0; index < 8; index++) {
                    length = (length << 8) | readByte();
                }
            }
            if (length > 64L * 1024 * 1024) {
                throw new IOException("Tunnel message too large");
            }
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(mask);
            }
            byte[] payload = new byte[(int) length];
            readFully(payload);
            if (mask != null) {
                for (int index = 0; index < payload.length; index++) {
                    payload[index] ^= mask[index & 3];
                }
            }

            switch (opcode) {
            case OPCODE_PING:
                sendFrame(OPCODE_PONG, payload, 0, payload.length);
                continue;
            case OPCODE_PONG:
                continue;
            case OPCODE_CLOSE:
                closeReason = payload.length >= 2
                    ? "close " + (((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF))
                        + (payload.length > 2
                            ? " " + new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8)
                            : "")
                    : "close";
                return null;
            case OPCODE_TEXT:
            case OPCODE_BINARY:
            case OPCODE_CONTINUATION:
                if (fin && message == null) {
                    return payload;
                }
                if (message == null) {
                    message = new ByteArrayOutputStream();
                }
                message.write(payload);
                if (fin) {
                    return message.toByteArray();
                }
                continue;
            default:
                throw new IOException("Unsupported tunnel frame opcode " + opcode);
            }
        }
    }

    private int readByte() throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("Tunnel closed mid-frame");
        }
        return value;
    }

    private void readFully(byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int count = input.read(buffer, offset, buffer.length - offset);
            if (count < 0) {
                throw new EOFException("Tunnel closed mid-frame");
            }
            offset += count;
        }
    }

    /** Sets a read timeout in milliseconds for {@link #readMessage()}; 0 waits forever. */
    void setReadTimeout(int millis) throws IOException {
        socket.setSoTimeout(millis);
    }

    /** How the other side closed: its close code and reason, if it sent one. */
    String closeReason() {
        return closeReason;
    }

    /** Records why reading from this WebSocket stopped, unless a close frame said so. */
    void noteClose(String reason) {
        if (closeReason.isEmpty()) {
            closeReason = reason;
        }
    }

    boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            synchronized (writeLock) {
                output.write(client
                    ? new byte[] { (byte) (0x80 | OPCODE_CLOSE), (byte) 0x80, 0, 0, 0, 0 }
                    : new byte[] { (byte) (0x80 | OPCODE_CLOSE), 0 });
                output.flush();
            }
        } catch (IOException ignored) {
            // The peer may already be gone.
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing left to release.
        }
    }
}

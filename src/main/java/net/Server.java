package net;

import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import lombok.val;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

//TODO: add multithreading
//TODO: add correct exceptions handling
@SuppressWarnings("WeakerAccess")
@Log4j2
public class Server {
    private static final String RESPONSE_OK =
            "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html\r\n" +
                    "Content-Length: %d\r\n" +
                    "Connection: close\r\n\r\n%s";

    private static final String RESPONSE_NOT_FOUND =
            "HTTP/1.1 404 Not Found\r\n" +
                    "Connection: close\r\n\r\n";

    private static final String START_HTML =
            "<html>\n" +
                    " <head>\n" +
                    "  <title>Starter</title>\n" +
                    "  <meta charset=\"utf-8\"/>" +
                    " </head>\n" +
                    " <body>\n" +
                    " <h1>Различные виды запросов</h1>" +
                    " <h2>GET</h2>" +
                    " <form method=\"get\" action=\"/auth\">\n" +
                    "    Enter your name: <input type=\"text\" name=\"username\"><br />\n" +
                    "    Enter your password: <input type=\"password\" name=\"password\"><br />\n" +
                    "    <input type=\"submit\" value=\"SEND\" />\n" +
                    "  </form>\n" +
                    " <br>" +
                    " <h2>POST</h2>" +
                    " <form method=\"post\" action=\"/auth\">\n" +
                    "    Enter your name: <input type=\"text\" name=\"username\"><br />\n" +
                    "    Enter your password: <input type=\"password\" name=\"password\"><br />\n" +
                    "    <input type=\"submit\" value=\"SEND\" />\n" +
                    "  </form>\n" +
                    " </body>\n" +
                    "</html>";

    private static final String HTML =
            "<html><head><meta charset=\"utf-8\"/></head><body><h1>%s</h1><pre>%s</pre></body></html>";


    private final int port;
    private final int bufferCapacity;
    private final Map<SelectionKey, HttpRequest> map = new HashMap<>();

    public Server(int port, int bufferCapacity) {
        this.port = port;
        this.bufferCapacity = bufferCapacity;
    }

    @SuppressWarnings("unused")
    public Server(int port) {
        this(port, 1024);
    }

    @SuppressWarnings("unused")
    public Server() {
        this(1024, 1024);
    }

    public void start() {
        try (val serverSocketChannel = openAndBind(port);
             val selector = Selector.open()) {
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            while (!Thread.currentThread().isInterrupted()) {
                selector.select();
                selector.selectedKeys().removeIf(key -> {
                    if (!key.isValid()) {
                        log.debug("Key is not valid");
                    } else if (key.isAcceptable()) {
                        accept(key);
                    } else if (key.isReadable()) {
                        read(key);
                    } else if (key.isWritable()) {
                        write(key);
                    }
                    return true;
                });
            }
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    private ServerSocketChannel openAndBind(int port) throws IOException {
        val serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.bind(new InetSocketAddress(port));
        serverSocketChannel.configureBlocking(false);
        return serverSocketChannel;
    }

    private void accept(SelectionKey key) {
        try {
            val socketChannel = ((ServerSocketChannel) key.channel()).accept();
            socketChannel.configureBlocking(false);
            val newKey = socketChannel.register(key.selector(), SelectionKey.OP_READ);
            //allocateDirect аллоцирует буффер в памяти последовательно
            newKey.attach(ByteBuffer.allocateDirect(bufferCapacity));
            map.put(newKey, new HttpRequest());
            log.info(String.format("Accepted {%s}", socketChannel));
        } catch (IOException e) {
            log.error("Error while accepting");
        }
    }


    private void read(SelectionKey key) {
        val socketChannel = (SocketChannel) key.channel();
        try {
            val request = map.get(key);
            val buffer = (ByteBuffer) key.attachment();
            val bytesRead = socketChannel.read(buffer);
            log.debug(String.format("Received %d bytes", bytesRead));
            buffer.flip();
            buffer.mark();
            while (buffer.hasRemaining()) {
                byte b = buffer.get();
                if (b == '\n' &&
                        buffer.position() != 0 &&
                        buffer.get(buffer.position() - 2) == '\r') {
                    //line read
                    int end = buffer.position();
                    buffer.reset();
                    //in buffer also are \r and \n
                    val line = extractString(buffer, end - buffer.position() - 2);
                    buffer.get();
                    buffer.get();
                    buffer.mark();
                    int currentPhase = request.getPhase();
                    if (currentPhase == 0) {
                        //reading request line
                        String[] contentAndTail = line.split("\\s", 2);
                        request.setMethod(HttpMethod.valueOf(contentAndTail[0]));
                        contentAndTail = contentAndTail[1].split("[?\\s]", 2);
                        request.setPath(contentAndTail[0]);
                        String version;
                        if (contentAndTail[1].startsWith("HTTP")) {
                            version = contentAndTail[1];
                        } else {
                            contentAndTail = contentAndTail[1].split("\\s", 2);
                            request.getParams().putAll(getParams(contentAndTail[0]));
                            version = contentAndTail[1];
                        }
                        request.setVersion(version);
                        request.setPhase(currentPhase + 1);
                    } else if (currentPhase == 1) {
                        //reading request headers
                        if (line.isEmpty()) {
                            request.setPhase(currentPhase + 1);
                        } else {
                            val map = request.getHeaders();
                            String[] header = line.split(":\\s", 2);
                            map.put(header[0], header[1]);
                        }
                    }
                }
            }
            if (request.getPhase() == 2) {
                String length = request.getHeaders().get("Content-Length");
                if (length != null) {
                    int end = buffer.position();
                    buffer.reset();
                    String body = extractString(buffer, end - buffer.position());
                    buffer.clear();
                    int len = Integer.parseInt(length);
                    request.addToBody(body);
                    if (body.length() != len) {
                        //not full message received
                        log.debug("Received not a full message. Continue...");
                        return;
                    }
                }
                buffer.clear();
                log.debug(String.format("Request: %s", request));
                key.interestOps(SelectionKey.OP_WRITE);
                return;
            }
            //if here, not full message received. Waiting for the next part, don't change OP
            buffer.reset();
            buffer.compact();
            log.debug("Received not a full message. Continue...");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String extractString(ByteBuffer buffer, int length) {
        byte[] useful = new byte[length];
        buffer.get(useful, 0, useful.length);
        return new String(useful);
    }

    @SneakyThrows(IOException.class)
    private void write(SelectionKey key) {
        val socketChannel = (SocketChannel) key.channel();
        val request = map.get(key);
        val buffer = (ByteBuffer) key.attachment();
        String response = null;
        switch (request.getMethod()) {
            case GET:
                if (request.getPath().equals("/") && request.getParams().size() == 0) {
                    response = getResponse(START_HTML);
                } else if (request.getParams().size() > 0) {
                    response = getResponse(String.format(HTML, request.getMethod().name(), request.toString()));
                }
                break;
            case POST:
                response = getResponse(String.format(HTML, request.getMethod().name(), request.toString()));
                break;
            default:
                response = RESPONSE_NOT_FOUND;
        }
        log.debug(String.format("Response: %s", response));
        //noinspection ConstantConditions
        val bytes = response.getBytes();
        for (int i = 0; i < bytes.length; i += bufferCapacity) {
            buffer.put(Arrays.copyOfRange(bytes, i, i + bufferCapacity));
            buffer.flip();
            socketChannel.write(buffer);
            buffer.clear();
        }
        request.clear();
        socketChannel.close();
    }

    private String getResponse(String content) {
        return String.format(RESPONSE_OK, content.length(), content);
    }

    private Map<String, String> getParams(String s) {
        val params = new HashMap<String, String>();
        for (String param : s.split("&")) {
            String[] split = param.split("=", 2);
            params.put(split[0], split[1]);
        }
        return params;
    }

    public static void main(String[] args) {
        new Server(1024, 1024).start();
    }
}

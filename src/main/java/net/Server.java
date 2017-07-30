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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("WeakerAccess")
@Log4j2
public class Server {
    private static final String RESPONSE =
            "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html\r\n" +
                    "Content-Length: %d\r\n" +
                    "Connection: close\r\n\r\n%s";

    private static final String HTML =
            "<html><head><meta charset=\"utf-8\"/></head><body><h1>Привет от Habrahabr`а!..</h1></body></html>";


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
            socketChannel.read(buffer);
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
                        val httpMethod = HttpMethod.valueOf(contentAndTail[0]);

                        contentAndTail = contentAndTail[1].split("[?\\s]", 2);
                        val path = contentAndTail[0];

                        Map<String, String> params = contentAndTail[1].startsWith("HTTP") ?
                                Collections.emptyMap() :
                                getParams(contentAndTail[1].split("\\s", 2)[0]);

                        request.setMethod(httpMethod);
                        request.setPath(path);
                        request.setParams(params);
                        request.setPhase(currentPhase + 1);
                    } else if (currentPhase == 1) {
                        //reading request headers
                        if (line.isEmpty()) {
                            request.setPhase(currentPhase + 1);
                        } else {
                            if (request.getHeaders() == null) {
                                request.setHeaders(new HashMap<>());
                            }
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
                    int len = Integer.parseInt(length);
                    request.addToBody(body);
                    if (body.length() != len) {
                        buffer.clear();
                        //not full message received
                        return;
                    }
                }
                key.interestOps(SelectionKey.OP_WRITE);
            }
            //if here, not full message received. Waiting for the next part, don't change OP
            buffer.reset();
            buffer.compact();
        } catch (
                IOException e)

        {
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
        val buffer = ByteBuffer.wrap(getResponse(HTML).getBytes());
        while (buffer.hasRemaining()) {
            socketChannel.write(buffer);
        }
        socketChannel.close();
    }

    private String getResponse(String content) {
        return String.format(RESPONSE, content.length(), content);
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
        new Server(1024, 128).start();
    }
}

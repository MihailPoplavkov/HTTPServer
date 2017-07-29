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

    public Server(int port) {
        this.port = port;
    }

    @SneakyThrows(IOException.class)
    public void start() {
        val serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.bind(new InetSocketAddress(port));
        serverSocketChannel.configureBlocking(false);
        val selector = Selector.open();
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        while (!Thread.currentThread().isInterrupted()) {
            selector.select();
            selector.selectedKeys().removeIf(key -> {
                if (key.isAcceptable()) {
                    accept(key);
                }
                return true;
            });
        }
    }

    private void accept(SelectionKey key) {
        try {
            SocketChannel socketChannel = ((ServerSocketChannel) key.channel()).accept();
            socketChannel.configureBlocking(false);
            socketChannel.register(key.selector(), SelectionKey.OP_READ);
            //that's bad
            socketChannel.write(ByteBuffer.wrap(getResponse(HTML).getBytes()));
            log.info("Accepted");
        } catch (IOException e) {
            log.error("Error while accepting");
        }
    }

    private String getResponse(String content) {
        return String.format(RESPONSE, content.length(), content);
    }

    public static void main(String[] args) {
        new Server(1024).start();
    }
}

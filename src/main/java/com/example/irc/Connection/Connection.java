package com.example.irc.Connection;

import java.io.IOException;

public interface Connection {
    void send(String message);
    String receive() throws IOException;
    void close() throws IOException;
    boolean isConnected();
}

package com.example.irc.MessageHandler;

public class ServerMessageHandler extends MessageHandler {
    @Override
    protected void validateMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Message cannot be empty");
        }
    }

    @Override
    protected void processMessage(String message) {
        System.out.println("Received: " + message);
    }
}
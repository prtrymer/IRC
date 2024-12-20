package com.example.irc.MessageHandler;

public class ClientMessageHandler extends MessageHandler {
    @Override
    protected void validateMessage(String message) {
        // Базова валідація повідомлення клієнта
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Message cannot be empty");
        }
    }

    @Override
    protected void processMessage(String message) {
        // Обробка повідомлення клієнта
        System.out.println("Sending: " + message);
    }
}

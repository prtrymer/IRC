package com.example.irc.MessageHandler;

public abstract class MessageHandler {
    public final void handleMessage(String message) {
        validateMessage(message);
        processMessage(message);
        logMessage(message);
    }

    protected abstract void validateMessage(String message);
    protected abstract void processMessage(String message);

    protected void logMessage(String message) {
        System.out.println("Logging message: " + message);
    }
}

package com.example.irc;

import com.example.irc.Connection.Client.ClientConnectionFactory;
import com.example.irc.Connection.Connection;
import com.example.irc.Connection.ConnectionFactory;
import com.example.irc.MessageHandler.ClientMessageHandler;
import com.example.irc.MessageHandler.MessageCallback;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.SocketException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

public class IRCClient {
    private final BufferedReader consoleReader;
    private final AtomicReference<Connection> connectionRef;
    private final ConnectionFactory connectionFactory;
    private final ClientMessageHandler messageHandler;

    private String currentServerAddress;
    private int currentServerPort;
    private String username;
    private String password;
    private volatile boolean isRunning;
    private volatile boolean isReconnecting;

    private static final int MAX_RETRIES = 5;
    private static final int RETRY_DELAY_MS = 5000;
    private static final int SOCKET_TIMEOUT_MS = 30000; // 30 seconds

    public IRCClient() {
        this.consoleReader = new BufferedReader(new InputStreamReader(System.in));
        this.connectionRef = new AtomicReference<>();
        this.connectionFactory = new ClientConnectionFactory();
        this.messageHandler = (ClientMessageHandler) connectionFactory.createMessageHandler();
        this.isRunning = true;
        this.isReconnecting = false;

        initializeMessageHandler();
    }

    public static void main(String[] args) {
        IRCClient client = new IRCClient();
        try {
            client.start(args);
        } catch (Exception e) {
            System.out.println("Fatal error: " + e.getMessage());
            System.exit(1);
        }
    }

    public void start(String[] args) throws Exception {
        printWelcomeMessage();
        setupInitialConnection(args);
        startMessageReceiver();
        runInputLoop();
        cleanup();
    }

    private void printWelcomeMessage() {
        System.out.println("IRC Client Connection Setup");
        System.out.println("==========================");
    }

    private void setupInitialConnection(String[] args) throws Exception {
        currentServerAddress = args.length > 0 ? args[0] : promptUser("Enter server address", "localhost");
        currentServerPort = args.length > 1 ? Integer.parseInt(args[1]) : promptPort("Enter server port", 6667);
        username = args.length > 2 ? args[2] : promptUser("Enter username", "Guest" + new Random().nextInt(10000));
        password = promptPassword("Enter password");

        connectToServer(currentServerAddress, currentServerPort);
    }

    private void initializeMessageHandler() {
        messageHandler.setMessageCallback(new MessageCallback() {
            @Override
            public void onNicknameChange(String newNickname) {
                System.out.println("Nickname changed to: " + newNickname);
            }

            @Override
            public void onChannelChange(String channel) {
                System.out.println("Now in channel: " + channel);
            }

            @Override
            public void onRegistrationStatus(boolean registered) {
                System.out.println("Registration status changed to: " + registered);
            }

            @Override
            public void onServerMessage(String message) {
                System.out.println(message);
            }

            @Override
            public void sendServerCommand(String command) {
                sendCommand(command);
            }

            @Override
            public void closeConnection() {
                shutdown();
            }

            @Override
            public void reconnectToServer(String host, int port) {
                handleServerChange(host, port);
            }
        });
    }

    private synchronized void sendCommand(String command) {
        Connection conn = connectionRef.get();
        if (conn != null && !isReconnecting) {
            try {
                conn.send(command);
            } catch (Exception e) {
                System.out.println("Error sending command: " + e.getMessage());
                handleConnectionError();
            }
        }
    }

    private void handleConnectionError() {
        if (!isReconnecting) {
            try {
                isReconnecting = true;
                reconnect(currentServerAddress, currentServerPort);
            } catch (IOException e) {
                System.out.println("Failed to recover from connection error: " + e.getMessage());
            } finally {
                isReconnecting = false;
            }
        }
    }

    private void connectToServer(String address, int port) throws Exception {
        System.out.printf("\nConnecting to %s:%d as %s...\n\n", address, port, username);
        cleanupConnection();

        try {
            Socket socket = new Socket(address, port);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            socket.setKeepAlive(true);

            Connection connection = connectionFactory.createConnection(socket);
            connectionRef.set(connection);

            connection.send("PRIVMSG" +" " + username +" " + password);
            connection.send("NICK " + username);
            connection.send("USER " + username + " 0 * :Java IRC Client");

            System.out.printf("Connected to %s:%d\n", address, port);

            currentServerAddress = address;
            currentServerPort = port;
        } catch (Exception e) {
            connectionRef.set(null);
            throw new Exception("Failed to connect: " + e.getMessage(), e);
        }
    }

    private void cleanupConnection() {
        Connection existingConnection = connectionRef.get();
        if (existingConnection != null) {
            try {
                existingConnection.close();
            } catch (IOException e) {
                System.out.println("Warning: Error closing previous connection: " + e.getMessage());
            }
            connectionRef.set(null);
        }
    }

    private void handleServerChange(String newHost, int newPort) {
        if (!isReconnecting) {
            try {
                isReconnecting = true;
                connectToServer(newHost, newPort);
            } catch (Exception e) {
                System.out.println("Failed to connect to new server: " + e.getMessage());
                try {
                    reconnect(currentServerAddress, currentServerPort);
                } catch (IOException reconnectError) {
                    System.out.println("Reconnection failed: " + reconnectError.getMessage());
                }
            } finally {
                isReconnecting = false;
            }
        }
    }

    private synchronized void reconnect(String address, int port) throws IOException {
        if (isReconnecting) return;

        int retries = 0;
        isReconnecting = true;

        try {
            cleanupConnection();

            while (retries < MAX_RETRIES && connectionRef.get() == null) {
                try {
                    Socket socket = new Socket(address, port);
                    socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                    socket.setKeepAlive(true);

                    Connection newConnection = connectionFactory.createConnection(socket);
                    connectionRef.set(newConnection);

                    newConnection.send("NICK " + username);
                    newConnection.send("USER " + username + " 0 * :Java IRC Client");

                    System.out.printf("Successfully reconnected to %s:%d\n", address, port);
                    return;
                } catch (IOException e) {
                    retries++;
                    System.out.printf("Connection attempt %d failed. Retrying in %d seconds...\n",
                            retries, RETRY_DELAY_MS / 1000);
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Connection interrupted");
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            throw new IOException("Failed to connect after " + MAX_RETRIES + " attempts");
        } finally {
            isReconnecting = false;
        }
    }

    private void messageReceiverLoop() {
        while (isRunning) {
            if (isReconnecting) {
                try {
                    Thread.sleep(1000);
                    continue;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            Connection connection = connectionRef.get();
            if (connection == null) {
                try {
                    Thread.sleep(1000);
                    continue;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            try {
                String message = connection.receive();
                if (message == null) {
                    handleConnectionError();
                    continue;
                }
                messageHandler.processMessage(message);
            } catch (SocketException e) {
                System.out.println("Socket error: " + e.getMessage());
                handleConnectionError();
            } catch (IOException e) {
                System.out.println("Connection error: " + e.getMessage());
                handleConnectionError();
            }
        }
    }

    private void startMessageReceiver() {
        Thread receiverThread = new Thread(this::messageReceiverLoop);
        receiverThread.setDaemon(true);
        receiverThread.start();
    }

    private void runInputLoop() throws IOException {
        System.out.println("\nWelcome to Java IRC Client!");
        System.out.println("Type /help for a list of available commands.\n");

        String userInput;
        while (isRunning && (userInput = consoleReader.readLine()) != null) {
            messageHandler.handleUserInput(userInput);
        }
    }

    private void shutdown() {
        isRunning = false;
        cleanupConnection();
    }

    private void cleanup() {
        shutdown();
    }

    private String promptUser(String message, String defaultValue) throws IOException {
        System.out.printf("%s [%s]: ", message, defaultValue);
        String input = consoleReader.readLine();
        return input.isEmpty() ? defaultValue : input;
    }

    private int promptPort(String message, int defaultValue) throws IOException {
        while (true) {
            try {
                System.out.printf("%s [%s]: ", message, defaultValue);
                String input = consoleReader.readLine();
                if (input.isEmpty()) return defaultValue;

                int port = Integer.parseInt(input);
                if (port > 0 && port < 65536) return port;

                System.out.println("Please enter a valid port number (1-65535)");
            } catch (NumberFormatException e) {
                System.out.println("Please enter a valid number");
            }
        }
    }

    private String promptPassword(String message) throws IOException {
        Console console = System.console();
        if (console != null) {
            char[] passwordArray = console.readPassword(message + ": ");
            return new String(passwordArray);
        } else {
            return promptUser(message, "");
        }
    }
}
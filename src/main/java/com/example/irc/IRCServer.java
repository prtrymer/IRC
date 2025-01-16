package com.example.irc;

import com.example.irc.Chat.ChatRoom;
import com.example.irc.Chat.ChatUser;
import com.example.irc.User.User;
import com.example.irc.User.UserDatabaseSingleton;
import com.example.irc.User.UserService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class IRCServer {
    private final Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();
    private final Map<String, ChatRoom> chatRooms = new ConcurrentHashMap<>();
    private final Map<String, String> channelTopics = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> channelCreationTimes = new ConcurrentHashMap<>();
    private final Map<String, Set<UserChannelInfo>> channelUsers = new ConcurrentHashMap<>();
    private final UserDatabaseSingleton userDatabase;
    private final ServerConfig serverConfig;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private final String SERVER_NAME = "MyIRCServer";
    private final String SERVER_VERSION = "1.0.1";
    private static final int SOCKET_TIMEOUT = 600000;
    private static final int PING_INTERVAL = 30000;
    private static final int PONG_TIMEOUT = 10000;

    private record UserChannelInfo(String username, String email, User user) {

        @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                UserChannelInfo that = (UserChannelInfo) o;
                return username.equals(that.username);
            }

            @Override
            public int hashCode() {
                return Objects.hash(username);
            }
        }

    @Autowired
    public IRCServer(UserService userService, ServerConfig serverConfig) {
        this.userDatabase = UserDatabaseSingleton.getInstance(userService);
        this.serverConfig = serverConfig;
        createChannel("#main", "Welcome to the main channel!");
        createChannel("#help", "Get help with IRC commands and features");
    }

    private void createChannel(String name, String topic) {
        chatRooms.putIfAbsent(name, new ChatRoom(name));
        channelTopics.putIfAbsent(name, topic);
        channelCreationTimes.putIfAbsent(name, LocalDateTime.now());
        channelUsers.putIfAbsent(name, ConcurrentHashMap.newKeySet());
    }

    @PostConstruct
    public void startServer() {
        running = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(serverConfig.getDefaultPort()));
                System.out.println("IRC Server started on port " + serverConfig.getDefaultPort());

                while (running) {
                    Socket socket = serverSocket.accept();
                    socket.setKeepAlive(true);

                    ClientHandler clientHandler = new ClientHandler(socket);
                    clients.add(clientHandler);
                    new Thread(clientHandler).start();
                }
            } catch (IOException e) {
                if (running) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private class ClientHandler implements Runnable {
        private boolean isAuthenticated = false;
        private final Socket socket;
        private final BufferedReader in;
        private final PrintWriter out;
        private User user;
        private String username = "";
        private final Set<String> channels = new HashSet<>();
        private boolean registered = false;
        private long lastPingSent = 0;
        private long lastMessageReceived = 0;
        private boolean waitingForPong = false;
        private LocalDateTime connectionTime;
        private String awayMessage = null;

        public ClientHandler(Socket socket) throws IOException {
            this.socket = socket;
            this.socket.setSoTimeout(SOCKET_TIMEOUT);
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.out = new PrintWriter(socket.getOutputStream(), true);
            this.lastMessageReceived = System.currentTimeMillis();
            this.connectionTime = LocalDateTime.now();
        }

        private void sendNumericReply(int code, String message) {
            out.println(":" + SERVER_NAME + " " + String.format("%03d", code) + " " +
                    (username.isEmpty() ? "*" : username) + " " + message);
        }

        private void sendServerMessage(String message) {
            out.println(":" + SERVER_NAME + " NOTICE " + username + " :" + message);
        }

        @Override
        public void run() {
            try {
                startPingChecker();

                String line;
                while ((line = readLineWithTimeout()) != null) {
                    lastMessageReceived = System.currentTimeMillis();
                    if (waitingForPong && line.startsWith("PONG")) {
                        waitingForPong = false;
                    }
                    handleIRCMessage(line);
                }
            } catch (SocketTimeoutException e) {
                System.out.println("Client timeout (no response): " + username);
            } catch (IOException e) {
                if (running) {
                    System.out.println("Client connection error: " + e.getMessage());
                }
            } finally {
                cleanup();
            }
        }

        private String readLineWithTimeout() throws IOException {
            try {
                return in.readLine();
            } catch (SocketTimeoutException e) {
                if (waitingForPong && System.currentTimeMillis() - lastPingSent > PONG_TIMEOUT) {
                    throw new SocketTimeoutException("PING timeout");
                }
                return null;
            }
        }

        private void startPingChecker() {
            new Thread(() -> {
                while (!socket.isClosed()) {
                    try {
                        Thread.sleep(PING_INTERVAL);
                        long now = System.currentTimeMillis();
                        if (now - lastMessageReceived > PING_INTERVAL && !waitingForPong) {
                            sendPing();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }).start();
        }

        private void sendPing() {
            out.println("PING :" + SERVER_NAME);
            out.flush();
            lastPingSent = System.currentTimeMillis();
            waitingForPong = true;
        }

        private void handleIRCMessage(String message) {
            if (message == null) return;
            String[] parts = message.split(" ", 3);
            if (parts.length == 0) return;

            String command = parts[0].toUpperCase();
            switch (command) {
                case "REGISTER":
                    if (parts.length < 3) {
                        sendNumericReply(461, "REGISTER :Not enough parameters. Usage: REGISTER <username> <password> <email>");
                        return;
                    }
                    handleRegistration(parts[1], parts[2], parts[1] + "@example.com");
                    break;

                case "AUTH":
                    if (parts.length < 3) {
                        sendNumericReply(461, "AUTH :Not enough parameters. Usage: AUTH <username> <password>");
                        return;
                    }
                    handleAuthentication(parts[1], parts[2]);
                    break;

                case "JOIN":
                    if (!isAuthenticated) {
                        sendNumericReply(484, ":Must authenticate first");
                        return;
                    }
                    if (parts.length < 2) return;
                    String channelName = parts[1].startsWith("#") ? parts[1] : "#" + parts[1];
                    joinChannel(channelName);
                    break;

                case "PART":
                    if (!registered) return;
                    if (parts.length < 2) return;
                    String partChannel = parts[1];
                    if (!partChannel.startsWith("#")) {
                        partChannel = "#" + partChannel;
                    }
                    partChannel(partChannel);
                    break;

                case "LIST":
                    sendEnhancedChannelList();
                    break;

                case "NAMES":
                    if (parts.length < 2) {
                        channels.forEach(this::sendChannelNames);
                    } else {
                        String namesChannel = parts[1];
                        if (!namesChannel.startsWith("#")) {
                            namesChannel = "#" + namesChannel;
                        }
                        sendChannelNames(namesChannel);
                    }
                    break;

                case "PRIVMSG":
                    if (!isAuthenticated) {
                        handleRegistration(parts[1], parts[2], parts[1] + "@example.com");
                        handleAuthentication(parts[1], parts[2]);
                        return;
                    }
                    if (parts.length < 3) return;
                    handlePrivMsg(parts[1], parts[2].startsWith(":") ? parts[2].substring(1) : parts[2]);
                    break;

                case "QUIT":
                    cleanup();
                    break;

                case "PONG":
                    waitingForPong = false;
                    break;

                default:
                    if (!isAuthenticated && !command.equals("QUIT")) {
                        sendNumericReply(484, ":Must authenticate first");
                    }
            }
        }

        private void handleRegistration(String username, String password, String email) {
            Optional<User> result = userDatabase.registerUser(username, password, email);
            if (result.isPresent()) {
                registered = true;
                this.user = result.get();
                this.username = username;
                isAuthenticated = true;
                sendServerMessage("NickServ " + username + "Account " + username + " has been successfully registered");
            } else {
                sendServerMessage("Registration failed. Username may be taken.");
            }
        }

        private void handleAuthentication(String username, String password) {
            Optional<User> result = userDatabase.authenticateUser(username, password);
            if (result.isPresent()) {
                isAuthenticated = true;
                this.user = result.get();
                this.username = username;
                this.user.setOnline(true);
                sendServerMessage("Authentication successful.");
                sendNumericReply(001, ":Welcome to IRC Network, " + username);
                sendServerMessage("NickServ" + " " + username + " " + "You are now identified with NickServ");
            } else {
                sendServerMessage("Authentication failed.");
            }
        }

        private void handlePrivMsg(String target, String message) {
            if (target.startsWith("#")) {
                if (chatRooms.containsKey(target)) {
                    broadcastToChannel(target, ":" + username + " PRIVMSG " + target + " :" + message);
                }
            } else {
                clients.stream()
                        .filter(c -> c.username.equals(target))
                        .findFirst()
                        .ifPresent(c -> c.out.println(":" + username + " PRIVMSG " + target + " :" + message));
            }
        }

        private void sendEnhancedChannelList() {
            sendNumericReply(321, "Channel :Users Members");

            for (Map.Entry<String, ChatRoom> entry : chatRooms.entrySet()) {
                String channel = entry.getKey();
                Set<UserChannelInfo> users = channelUsers.get(channel);
                String topic = channelTopics.getOrDefault(channel, "No topic set");

                String usersList = users.stream()
                        .map(info -> info.username + "(" + info.email + ")")
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("No users");

                sendNumericReply(322, channel + " " + users.size() + " :" + topic + " [" + usersList + "]");
            }

            sendNumericReply(323, ":End of /LIST");
        }

        private void joinChannel(String channelName) {
            if (!chatRooms.containsKey(channelName)) {
                createChannel(channelName, "Welcome to " + channelName);
            }

            ChatRoom room = chatRooms.get(channelName);
            channels.add(channelName);

            UserChannelInfo userInfo = new UserChannelInfo(
                    username,
                    user.getEmail(),
                    user
            );
            channelUsers.get(channelName).add(userInfo);

            room.addComponent(new ChatUser(username, out));

            String joinMessage = ":" + username + "!" + username + "@" + socket.getInetAddress().getHostName() +
                    " JOIN " + channelName;
            broadcastToChannel(channelName, joinMessage);

            String topic = channelTopics.getOrDefault(channelName, "No topic set");
            sendNumericReply(332, channelName + " :" + topic);

            sendChannelNames(channelName);
        }

        private void partChannel(String channelName) {
            if (chatRooms.containsKey(channelName)) {
                ChatRoom room = chatRooms.get(channelName);
                room.removeComponent(new ChatUser(username, out));
                channels.remove(channelName);

                Set<UserChannelInfo> users = channelUsers.get(channelName);
                if (users != null) {
                    users.removeIf(info -> info.username.equals(username));
                }

                broadcastToChannel(channelName, ":" + username + " PART " + channelName);
            }
        }

        private void sendChannelNames(String channelName) {
            Set<UserChannelInfo> users = channelUsers.get(channelName);
            if (users != null) {
                StringBuilder names = new StringBuilder();
                users.forEach(info -> {
                    names.append(info.username).append(" ");
                });
                sendNumericReply(353, "= " + channelName + " :" + names.toString());
                sendNumericReply(366, channelName + " :End of /NAMES list");
            }
        }

        private void cleanup() {
            if (user != null) {
                user.setOnline(false);
            }
            new HashSet<>(channels).forEach(this::partChannel);
            clients.remove(this);
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void broadcastToChannel(String channelName, String message) {
        ChatRoom room = chatRooms.get(channelName);
        if (room != null) {
            room.sendMessage(message);
        }
    }
}
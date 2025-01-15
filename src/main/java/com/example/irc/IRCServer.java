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
    private final Map<String, Set<String>> channelUsers = new ConcurrentHashMap<>();
    private final UserDatabaseSingleton userDatabase;
    private final ServerConfig serverConfig;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private final String SERVER_NAME = "MyIRCServer";
    private final String SERVER_VERSION = "1.0.1";
    private static final int SOCKET_TIMEOUT = 60000;
    private static final int PING_INTERVAL = 30000;
    private static final int PONG_TIMEOUT = 10000;
    private final LocalDateTime serverStartTime = LocalDateTime.now();

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
        private String nickname = "";
        private String username = "";
        private final Set<String> channels = new HashSet<>();
        private boolean registered = false;
        private long lastPingSent = 0;
        private long lastMessageReceived = 0;
        private boolean waitingForPong = false;
        private LocalDateTime connectionTime;
        private String realName = "";
        private String awayMessage = null;
        private String email = "";
        private String password = "";


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
                    (nickname.isEmpty() ? "*" : nickname) + " " + message);
        }

        private void sendServerMessage(String message) {
            out.println(":" + SERVER_NAME + " NOTICE " + nickname + " :" + message);
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
                System.out.println("Client timeout (no response): " + nickname);
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
                        if (now - lastMessageReceived > PING_INTERVAL) {
                            // No message received for PING_INTERVAL, send PING
                            if (!waitingForPong) {
                                sendPing();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }).start();
        }

        private void sendPing() {
            String pingMessage = "PING :" + SERVER_NAME;
            out.println(pingMessage);
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
                case "PRIVMSG":
                    if (parts.length < 3) {
                        sendNumericReply(461, "PRIVMSG :Not enough parameters. Usage: PRIVMSG <username> <password>");
                        return;
                    }
                    String privUsername = parts[1];
                    String privPassword = parts[2];
                    String privEmail = privUsername + "@example.com";
                    isAuthenticated = true;
                    handleRegistration(privUsername, privPassword, privEmail);
                    break;

                case "AUTH":
                    if (parts.length < 3) {
                        sendNumericReply(461, "AUTH :Not enough parameters. Usage: AUTH <username> <password>");
                        return;
                    }
                    String authUsername = parts[1];
                    String authPassword = parts[2];
                    handleAuthentication(authUsername, authPassword);
                    break;

                case "NICK":
                    if (!isAuthenticated) {
                        sendNumericReply(484, ":Must authenticate first");
                        return;
                    }
                    if (parts.length < 2) {
                        sendNumericReply(431, ":No nickname given");
                        return;
                    }
                    String newNick = parts[1];
                    handleNickChange(newNick);
                    break;

                case "PONG":
                    waitingForPong = false;
                    return;

                case "LIST":
                    sendEnhancedChannelList();
                    break;

                case "JOIN":
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

                case "PING":
                    out.println("PONG :" + (parts.length > 1 ? parts[1] : SERVER_NAME));
                    break;

                case "QUIT":
                    String quitMessage = parts.length > 1 ? message.substring(message.indexOf(" :") + 2) : "Client Quit";
                    cleanup();
                    break;

                case "TOPIC":
                    if (!registered) return;
                    if (parts.length < 2) {
                        sendNumericReply(461, "TOPIC :Not enough parameters");
                        return;
                    }
                    String thisChannelName = parts[1];
                    if (!thisChannelName.startsWith("#")) {
                        thisChannelName = "#" + thisChannelName;
                    }
                    if (parts.length > 2) {
                        String newTopic = parts[2].startsWith(":") ? parts[2].substring(1) : parts[2];
                        setChannelTopic(thisChannelName, newTopic);
                    } else {
                        sendChannelTopic(thisChannelName);
                    }
                    break;

                case "VERSION":
                    sendNumericReply(351, SERVER_NAME + " " + SERVER_VERSION + " :Running since " +
                            serverStartTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                    break;

                case "AWAY":
                    if (parts.length > 1) {
                        awayMessage = parts[1].startsWith(":") ? parts[1].substring(1) : parts[1];
                        sendNumericReply(306, ":You have been marked as away");
                    } else {
                        awayMessage = null;
                        sendNumericReply(305, ":You are no longer marked as away");
                    }
                    break;

                case "WHOIS":
                    if (parts.length < 2) {
                        sendNumericReply(461, "WHOIS :Not enough parameters");
                        return;
                    }
                    String targetNick = parts[1];
                    handleWhoisCommand(targetNick);
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

                case "MODE":
                    if (parts.length < 2) {
                        sendNumericReply(461, "MODE :Not enough parameters");
                        return;
                    }
                    handleModeCommand(parts);
                    break;
                default:
                    if (!isAuthenticated && !command.equals("QUIT")) {
                        sendNumericReply(484, ":Must authenticate first");
                        return;
                    }
            }
        }

        private void handleNickServCommand(String message) {
            if (message.startsWith(":REGISTER")) {
                String[] params = message.substring(9).split(" ");
                if (params.length >= 2) {
                    handleRegistration(nickname, params[0], params[1]);
                }
            } else if (message.startsWith(":IDENTIFY")) {
                String[] params = message.substring(9).split(" ");
                if (params.length >= 2) {
                    handleAuthentication(params[0], params[1]);
                }
            }
        }

        private void sendEnhancedChannelList() {
            sendNumericReply(321, "Channel :Users Name");

            for (Map.Entry<String, ChatRoom> entry : chatRooms.entrySet()) {
                String channel = entry.getKey();
                ChatRoom room = entry.getValue();
                String topic = channelTopics.getOrDefault(channel, "No topic set");
                int userCount = room.getUserCount();

                // Format: channel userCount :topic
                sendNumericReply(322, channel + " " + userCount + " :" + topic);

                // Add small delay to prevent flooding
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            sendNumericReply(323, ":End of /LIST");
        }

        private void handleRegistration(String username, String password, String email) {
            Optional<User> result = userDatabase.registerUser(username, password, email);
            if (result.isPresent()) {
                registered = true;
                sendServerMessage("Registration successful. You can now identify using IDENTIFY command.");
            } else {
                sendServerMessage("Registration failed. Username may be taken.");
            }
        }

        private void handleAuthentication(String username, String password) {
            Optional<User> result = userDatabase.authenticateUser(username, password);
            if (result.isPresent()) {
                isAuthenticated = true;
                this.user = result.get();
                sendServerMessage("You are now identified.");
            } else {
                sendServerMessage("Authentication failed.");
            }
        }

        private void handleNickChange(String newNick) {
            if (clients.stream().anyMatch(c -> c != this && c.nickname.equalsIgnoreCase(newNick))) {
                sendNumericReply(433, newNick + " :Nickname is already in use");
                return;
            }

            Optional<User> updateResult = userDatabase.updateUser(this.nickname, newNick);
            if (updateResult.isPresent()) {
                String oldNick = this.nickname;
                this.nickname = newNick;
                broadcastToAll(":" + oldNick + " NICK :" + newNick);
            } else {
                sendNumericReply(432, newNick + " :Nickname change failed");
            }
        }

        private void broadcastToAll(String message) {
            clients.forEach(client -> client.out.println(message));
        }


        private void handleWhoisCommand(String targetNick) {
            Optional<ClientHandler> targetClient = clients.stream()
                    .filter(c -> c.nickname.equalsIgnoreCase(targetNick))
                    .findFirst();

            if (targetClient.isPresent()) {
                ClientHandler target = targetClient.get();
                String userHost = target.socket.getInetAddress().getHostName();

                // Send WHOIS information
                sendNumericReply(311, target.nickname + " " + target.username + " " +
                        userHost + " * :" + (target.realName.isEmpty() ? "No real name" : target.realName));

                // Send channels
                if (!target.channels.isEmpty()) {
                    sendNumericReply(319, target.nickname + " :" + String.join(" ", target.channels));
                }

                // Send server info
                sendNumericReply(312, target.nickname + " " + SERVER_NAME + " :Connected to IRC Server");

                // Send away message if set
                if (target.awayMessage != null) {
                    sendNumericReply(301, target.nickname + " :" + target.awayMessage);
                }

                // Send connection time
                sendNumericReply(317, target.nickname + " " +
                        (System.currentTimeMillis() - target.connectionTime.atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()) / 1000 +
                        " " + target.connectionTime.atZone(java.time.ZoneOffset.UTC).toEpochSecond() +
                        " :seconds idle, signon time");

                sendNumericReply(318, target.nickname + " :End of /WHOIS list");
            } else {
                sendNumericReply(401, targetNick + " :No such nick/channel");
            }
        }

        private void handleModeCommand(String[] parts) {
            String target = parts[1];
            if (target.startsWith("#")) {
                // Channel mode
                ChatRoom room = chatRooms.get(target);
                if (room != null) {
                    if (parts.length == 2) {
                        // Query channel modes
                        sendNumericReply(324, target + " +nt"); // Example channel modes
                    }
                } else {
                    sendNumericReply(403, target + " :No such channel");
                }
            } else {
                // User mode
                if (target.equals(nickname)) {
                    if (parts.length == 2) {
                        sendNumericReply(221, "+i"); // Example user modes
                    }
                } else {
                    sendNumericReply(502, ":Can't change mode for other users");
                }
            }
        }

        private void setChannelTopic(String channelName, String topic) {
            if (chatRooms.containsKey(channelName)) {
                channelTopics.put(channelName, topic);
                broadcastToChannel(channelName, ":" + nickname + " TOPIC " + channelName + " :" + topic);
            } else {
                sendNumericReply(403, channelName + " :No such channel");
            }
        }

        private void sendChannelTopic(String channelName) {
            if (chatRooms.containsKey(channelName)) {
                String topic = channelTopics.getOrDefault(channelName, "No topic is set");
                sendNumericReply(332, channelName + " :" + topic);
            } else {
                sendNumericReply(403, channelName + " :No such channel");
            }
        }

        private void joinChannel(String channelName) {
            if (!chatRooms.containsKey(channelName)) {
                createChannel(channelName, "Welcome to " + channelName);
            }

            ChatRoom room = chatRooms.get(channelName);
            channels.add(channelName);

            // Update channel users list
            channelUsers.computeIfAbsent(channelName, k -> ConcurrentHashMap.newKeySet())
                    .add(nickname);

            room.addComponent(new ChatUser(nickname, out));

            // Send join confirmation
            String joinMessage = ":" + nickname + "!" + username + "@" + socket.getInetAddress().getHostName() +
                    " JOIN " + channelName;
            broadcastToChannel(channelName, joinMessage);

            // Send topic
            sendChannelTopic(channelName);

            // Send names list
            sendChannelNames(channelName);

            // Send channel creation time
            LocalDateTime creationTime = channelCreationTimes.get(channelName);
            if (creationTime != null) {
                sendNumericReply(329, channelName + " " + creationTime.atZone(java.time.ZoneOffset.UTC).toEpochSecond());
            }
        }

        private void checkRegistration() {
            if (!registered && !nickname.isEmpty() && !username.isEmpty()) {
                registered = true;
                sendNumericReply(001, ":Welcome to the IRC Network " + nickname + "!" + username + "@" + socket.getInetAddress().getHostName());
                sendNumericReply(002, ":Your host is " + SERVER_NAME + ", running version " + SERVER_VERSION);
                sendNumericReply(003, ":This server was created " + new Date());
                sendNumericReply(004, SERVER_NAME + " " + SERVER_VERSION + " o o");
            }
        }


        private void partChannel(String channelName) {
            if (chatRooms.containsKey(channelName)) {
                ChatRoom room = chatRooms.get(channelName);
                room.removeComponent(new ChatUser(nickname, out));
                channels.remove(channelName);

                Set<String> users = channelUsers.get(channelName);
                if (users != null) {
                    users.remove(nickname);
                }

                broadcastToChannel(channelName, ":" + nickname + " PART " + channelName);
            }
        }


        private void handlePrivMsg(String target, String message) {
            if (target.startsWith("#")) {
                // Channel message
                if (chatRooms.containsKey(target)) {
                    broadcastToChannel(target, ":" + nickname + " PRIVMSG " + target + " :" + message);
                }
            } else {
                // Private message
                clients.stream()
                        .filter(c -> c.nickname.equals(target))
                        .findFirst()
                        .ifPresent(c -> c.out.println(":" + nickname + " PRIVMSG " + target + " :" + message));
            }
        }

        private void broadcastToChannel(String channelName, String message) {
            ChatRoom room = chatRooms.get(channelName);
            if (room != null) {
                room.sendMessage(message);
            }
        }

        private void sendChannelList() {
            sendNumericReply(321, "Channel :Users  Name");
            for (Map.Entry<String, ChatRoom> entry : chatRooms.entrySet()) {
                sendNumericReply(322, entry.getKey() + " " +
                        ((ChatRoom)entry.getValue()).getUserCount() + " :Channel topic");
            }
            sendNumericReply(323, ":End of /LIST");
        }

        private void sendChannelNames(String channelName) {
            ChatRoom room = chatRooms.get(channelName);
            if (room != null) {
                StringBuilder names = new StringBuilder();
                room.getUsers().forEach(user -> names.append(user).append(" "));
                sendNumericReply(353, "= " + channelName + " :" + names.toString());
                sendNumericReply(366, channelName + " :End of /NAMES list");
            }
        }

        public void cleanup() {
            new HashSet<>(channels).forEach(this::partChannel);

            clients.remove(this);
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
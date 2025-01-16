package com.example.irc.MessageHandler;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class ClientMessageHandler extends MessageHandler {
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final Map<String, String> channelTopics = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> channelUsers = new ConcurrentHashMap<>();
    private final Map<Integer, Consumer<String[]>> numericCommandHandlers = new HashMap<>();
    private final Map<String, CommandInfo> commands = new HashMap<>();
    private String currentChannel = "";
    private String currentNickname = "Guest" + new Random().nextInt(10000);
    private boolean isRegistered = false;
    private MessageCallback messageCallback;
    private static final String VERSION = "1.0.0";
    private static final String CLIENT_NAME = "Java IRC Client";
    private final List<ChannelInfo> channelList = new ArrayList<>();
    private boolean isCollectingChannels = false;
    private static final int CHANNELS_PER_PAGE = 10;
    private int currentChannelPage = 0;
    private boolean isViewingChannelList = false;


    public void setCollectingChannels(boolean collectingChannels) {
        isCollectingChannels = collectingChannels;
    }

    private record CommandInfo(String syntax, String description, String examples) {}

    private record ChannelInfo(String channel, String users, String topic) {
    }

    public ClientMessageHandler() {
        initializeNumericHandlers();
        initializeCommands();
    }

    private void initializeCommands() {
        commands.put("/help", new CommandInfo(
                "/help [command]",
                "Display help information. Use '/help <command>' for specific command help",
                "Examples:\n/help\n/help /join"
        ));
        commands.put("/join", new CommandInfo(
                "/join #channel",
                "Join a channel. The # prefix is optional",
                "Examples:\n/join #general\n/join testing"
        ));
        commands.put("/part", new CommandInfo(
                "/part #channel",
                "Leave a channel. The # prefix is optional",
                "Examples:\n/part #general\n/part testing"
        ));
        commands.put("/msg", new CommandInfo(
                "/msg <nickname> <message>",
                "Send a private message to a user",
                "Example:\n/msg Alice Hello, how are you?"
        ));
        commands.put("/nick", new CommandInfo(
                "/nick <new_nickname>",
                "Change your nickname",
                "Example:\n/nick NewNick"
        ));
        commands.put("/list", new CommandInfo(
                "/list",
                "List all available channels on the server",
                "Example:\n/list"
        ));
        commands.put("/list [page]", new CommandInfo(
                "/list [page]",
                "List available channels. Use /list <page> to view different pages",
                "Examples:\n/list\n/list 2"
        ));
        commands.put("/listnext", new CommandInfo(
                "/listnext",
                "Show next page of channels",
                "Example:\n/listnext"
        ));
        commands.put("/listprev", new CommandInfo(
                "/listprev",
                "Show previous page of channels",
                "Example:\n/listprev"
        ));
        commands.put("/listquit", new CommandInfo(
                "/listquit",
                "Exit from channel list view",
                "Example:\n/listquit"
        ));
        commands.put("/whois", new CommandInfo(
                "/whois <nickname>",
                "Get information about a specific user",
                "Example:\n/whois Bob"
        ));
        commands.put("/quit", new CommandInfo(
                "/quit [message]",
                "Disconnect from the server and exit the client",
                "Examples:\n/quit\n/quit Goodbye everyone!"
        ));
        commands.put("/topic", new CommandInfo(
                "/topic [#channel] [new topic]",
                "View or set the topic of the current or specified channel",
                "Examples:\n/topic\n/topic #general\n/topic #general New topic here"
        ));
        commands.put("/names", new CommandInfo(
                "/names [#channel]",
                "List users in the current or specified channel",
                "Examples:\n/names\n/names #general"
        ));
        commands.put("/version", new CommandInfo(
                "/version",
                "Display client version information",
                "Example:\n/version"
        ));
        commands.put("/connect", new CommandInfo(
                "/connect <host> [port]",
                "Connect to a specified IRC server",
                "Examples:\n/connect irc.example.com\n/connect irc.example.com 6667"
        ));
    }

    public void handleUserInput(String input) {
        if (input.isEmpty()) return;

        if (input.startsWith("/")) {
            String[] parts = input.split(" ");
            String command = parts[0].toLowerCase();

            switch (command) {
                case "/help" -> displayHelp(parts);
                case "/version" -> displayVersion();
                case "/topic" -> handleTopicCommand(parts, input);
                case "/names" -> handleNamesCommand(parts);
                case "/join" -> handleJoinCommand(parts);
                case "/part" -> handlePartCommand(parts);
                case "/msg" -> handleMessageCommand(parts, input);
                case "/nick" -> handleNickCommand(parts);
                case "/connect" -> handleConnectCommand(parts);
                case "/list" -> {
                    if (parts.length > 1) {
                        try {
                            int page = Integer.parseInt(parts[1]) - 1;
                            if (page >= 0) {
                                currentChannelPage = page;
                                isViewingChannelList = true;
                                displayChannelPage();
                            } else {
                                messageCallback.onServerMessage("Invalid page number");
                            }
                        } catch (NumberFormatException e) {
                            messageCallback.onServerMessage("Invalid page number");
                        }
                    } else {
                        channelList.clear();
                        isCollectingChannels = true;
                        isViewingChannelList = true;
                        currentChannelPage = 0;
                        messageCallback.sendServerCommand("LIST");
                    }
                }
                case "/listquit" -> exitChannelList();
                case "/whois" -> {
                    if (parts.length > 1) {
                        messageCallback.sendServerCommand("WHOIS " + parts[1]);
                    }
                }
                case "/quit" -> {
                    String quitMessage = parts.length > 1 ?
                            input.substring(input.indexOf(' ') + 1) : "Goodbye!";
                    messageCallback.sendServerCommand("QUIT :" + quitMessage);
                    messageCallback.closeConnection();
                }
                default -> messageCallback.onServerMessage("Unknown command: " + command);
            }
        } else {
            messageCallback.sendServerCommand("PRIVMSG " + getCurrentChannel() + " :" + input);
        }
    }

    private void displayHelp(String[] parts) {
        if (parts.length == 1) {
            StringBuilder help = new StringBuilder("\nAvailable Commands:\n");
            commands.forEach((cmd, info) ->
                    help.append(String.format("%-15s - %s%n", cmd, info.description))
            );
            help.append("\nUse '/help <command>' for more detailed information about a specific command.");
            messageCallback.onServerMessage(help.toString());
        } else {
            String commandName = parts[1].toLowerCase();
            if (!commandName.startsWith("/")) {
                commandName = "/" + commandName;
            }

            CommandInfo info = commands.get(commandName);
            if (info != null) {
                String helpText = String.format("\nCommand: %s\nSyntax: %s\nDescription: %s\n\n%s",
                        commandName, info.syntax, info.description, info.examples);
                messageCallback.onServerMessage(helpText);
            } else {
                messageCallback.onServerMessage("No help available for: " + commandName);
            }
        }
    }

    private void displayVersion() {
        String versionInfo = String.format("""
                        
                        Client Information:
                        Name: %s
                        Version: %s
                        Java Version: %s
                        OS: %s %s""",
                CLIENT_NAME, VERSION,
                System.getProperty("java.version"),
                System.getProperty("os.name"),
                System.getProperty("os.version"));
        messageCallback.onServerMessage(versionInfo);
    }

    private void handleTopicCommand(String[] parts, String fullInput) {
        if (parts.length == 1) {
            String topic = channelTopics.get(getCurrentChannel());
            messageCallback.onServerMessage("Current topic: " + (topic != null ? topic : "No topic set"));
        } else {
            String channel = parts[1];
            if (!channel.startsWith("#")) channel = "#" + channel;
            if (parts.length == 2) {
                messageCallback.sendServerCommand("TOPIC " + channel);
            } else {
                String newTopic = fullInput.substring(fullInput.indexOf(parts[2]));
                messageCallback.sendServerCommand("TOPIC " + channel + " :" + newTopic);
            }
        }
    }

    private void handleNamesCommand(String[] parts) {
        String targetChannel = parts.length > 1 ? parts[1] : getCurrentChannel();
        if (!targetChannel.startsWith("#")) targetChannel = "#" + targetChannel;
        Set<String> users = channelUsers.get(targetChannel);
        if (users != null) {
            StringBuilder userList = new StringBuilder("Users in " + targetChannel + ":\n");
            users.forEach(user -> userList.append("- ").append(user).append("\n"));
            messageCallback.onServerMessage(userList.toString());
        } else {
            messageCallback.sendServerCommand("NAMES " + targetChannel);
        }
    }

    private void handleConnectCommand(String[] parts) {
        if (parts.length < 2) {
            messageCallback.onServerMessage("Usage: /connect <host> [port]");
            return;
        }

        String host = parts[1];
        int port = parts.length > 2 ? Integer.parseInt(parts[2]) : 6667;

        messageCallback.onServerMessage("Attempting to connect to " + host + ":" + port);
        messageCallback.reconnectToServer(host, port);
    }

    private void handleJoinCommand(String[] parts) {
        if (parts.length > 1) {
            String channel = parts[1];
            if (!channel.startsWith("#")) {
                channel = "#" + channel;
            }
            // Exit channel list if we're viewing it
            if (isViewingChannelList) {
                exitChannelList();
            }
            messageCallback.sendServerCommand("JOIN " + channel);
        }
    }

    private void handlePartCommand(String[] parts) {
        if (parts.length > 1) {
            String channel = parts[1];
            if (!channel.startsWith("#")) {
                channel = "#" + channel;
            }
            messageCallback.sendServerCommand("PART " + channel);
        }
    }

    private void handleMessageCommand(String[] parts, String fullInput) {
        if (parts.length > 2) {
            String recipient = parts[1];
            String message = fullInput.substring(fullInput.indexOf(parts[2]));
            messageCallback.sendServerCommand("PRIVMSG " + recipient + " :" + message);
        }
    }

    private void handleNickCommand(String[] parts) {
        if (parts.length > 1) {
            currentNickname = parts[1];
            messageCallback.sendServerCommand("NICK " + currentNickname);
        }
    }

    private void initializeNumericHandlers() {
        numericCommandHandlers.put(1, this::handleWelcome);
        numericCommandHandlers.put(321, this::handleListStart);
        numericCommandHandlers.put(322, this::handleListItem);
        numericCommandHandlers.put(323, this::handleListEnd);
        numericCommandHandlers.put(332, this::handleTopic);
        numericCommandHandlers.put(353, this::handleNames);
        numericCommandHandlers.put(433, this::handleNicknameInUse);
    }

    @Override
    protected void validateMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Message cannot be empty");
        }
    }

    @Override
    public void processMessage(String message) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        messageCallback.onServerMessage(timestamp + " ← " + message);

        if (message.startsWith("PING")) {
            handlePing(message);
            return;
        }

        String[] parts = message.split(" ");

        if (parts.length > 1 && parts[1].matches("\\d{3}")) {
            int numeric = Integer.parseInt(parts[1]);
            Consumer<String[]> handler = numericCommandHandlers.get(numeric);
            if (handler != null) {
                handler.accept(parts);
            }
        }

        if (message.contains("PRIVMSG") && message.contains("#")) {
            handleChannelMessage(message);
        }

        if (message.contains("JOIN") && message.contains("#")) {
            handleJoin(message);
        }

        if (message.contains("PART") || message.contains("QUIT")) {
            handlePartOrQuit(message);
        }

        if (message.contains("NickServ")) {
            handleNickServMessage(message);
        }
    }

    private void handlePing(String message) {
        messageCallback.sendServerCommand("PONG " + message.substring(5));
    }

    private void handleWelcome(String[] parts) {
        messageCallback.onServerMessage("Successfully connected to server!");
        if (!isRegistered) {
            messageCallback.onServerMessage("Attempting to identify with NickServ...");
        }
    }

    private void handleListStart(String[] parts) {
        channelList.clear();
        isCollectingChannels = true;
        messageCallback.onServerMessage("Collecting channel list...");
    }

    private void handleListItem(String[] parts) {
        if (parts.length >= 5) {
            String channel = parts[3];
            String users = parts[4];

            // Get topic (everything after the channel and users count)
            StringBuilder topic = new StringBuilder();
            for (int i = 5; i < parts.length; i++) {
                if (i == 5 && parts[i].startsWith(":")) {
                    topic.append(parts[i].substring(1));
                } else {
                    topic.append(" ").append(parts[i]);
                }
            }

            channelList.add(new ChannelInfo(channel, users, topic.toString().trim()));
        }
    }

    private void handleListEnd(String[] parts) {
        isCollectingChannels = false;
        displayChannelPage();
    }

    private void exitChannelList() {
        if (isViewingChannelList) {
            isViewingChannelList = false;
            messageCallback.onServerMessage("Exited channel list view.");
        }
    }

    private void displayChannelPage() {
        if (!isViewingChannelList) {
            return;
        }

        if (channelList.isEmpty()) {
            messageCallback.onServerMessage("No channels found.");
            return;
        }

        int totalPages = (int) Math.ceil(channelList.size() / (double) CHANNELS_PER_PAGE);
        int startIndex = currentChannelPage * CHANNELS_PER_PAGE;
        int endIndex = Math.min(startIndex + CHANNELS_PER_PAGE, channelList.size());

        if (startIndex >= channelList.size()) {
            messageCallback.onServerMessage("Invalid page number.");
            return;
        }

        StringBuilder output = new StringBuilder();
        output.append(String.format("\nChannel List (Page %d/%d):\n", currentChannelPage + 1, totalPages));
        output.append("Channel            | Users | Topic\n");
        output.append("----------------------------------------\n");

        for (int i = startIndex; i < endIndex; i++) {
            ChannelInfo info = channelList.get(i);
            output.append(String.format("%-18s | %-5s | %s\n",
                    info.channel,
                    info.users,
                    info.topic));
        }

        output.append("----------------------------------------\n");
        output.append(String.format("Showing channels %d-%d of %d\n",
                startIndex + 1, endIndex, channelList.size()));
        output.append("Use /listnext, /listprev, or /list <page> to navigate\n");
        output.append("Use /listquit to exit list view\n");
        output.append("Use /join #channel to join a channel\n");

        messageCallback.onServerMessage(output.toString());
    }

    private void showNextChannelPage() {
        if (!isViewingChannelList) {
            return;
        }

        int totalPages = (int) Math.ceil(channelList.size() / (double) CHANNELS_PER_PAGE);
        if (currentChannelPage < totalPages - 1) {
            currentChannelPage++;
            displayChannelPage();
        } else {
            messageCallback.onServerMessage("Already at the last page.");
        }
    }


    private void showPreviousChannelPage() {
        if (!isViewingChannelList) {
            return;
        }

        if (currentChannelPage > 0) {
            currentChannelPage--;
            displayChannelPage();
        } else {
            messageCallback.onServerMessage("Already at the first page.");
        }
    }

    private void handleTopic(String[] parts) {
        if (parts.length >= 4) {
            String channel = parts[3];
            String topic = Arrays.toString(parts).substring(Arrays.toString(parts).indexOf(':', 1) + 1);
            channelTopics.put(channel, topic);
            messageCallback.onServerMessage("Topic for " + channel + ": " + topic);
        }
    }

    private void handleNames(String[] parts) {
        if (parts.length >= 5) {
            String channel = parts[4];
            String[] users = Arrays.toString(parts)
                    .substring(Arrays.toString(parts).indexOf(':', 1) + 1)
                    .split(" ");
            channelUsers.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet())
                    .addAll(Arrays.asList(users));
        }
    }

    private void handleNicknameInUse(String[] parts) {
        String newNick = currentNickname + "_";
        messageCallback.onServerMessage("Nickname in use, trying: " + newNick);
        currentNickname = newNick;
        messageCallback.onNicknameChange(newNick);
    }

    private void handleChannelMessage(String message) {
        String channel = message.substring(
                message.indexOf("#"),
                message.indexOf(":", message.indexOf("#"))
        ).trim();
        setCurrentChannel(channel);
    }

    private void handleJoin(String message) {
        String channel = message.substring(message.indexOf("#")).trim();
        setCurrentChannel(channel);
        messageCallback.sendServerCommand("TOPIC " + channel);
        messageCallback.sendServerCommand("NAMES " + channel);
    }

    private void handlePartOrQuit(String message) {
        String nickname = message.substring(1, message.indexOf("!"));
        channelUsers.values().forEach(users -> users.remove(nickname));
    }

    private void handleNickServMessage(String message) {
        if (message.contains("registered")) {
            isRegistered = true;
            messageCallback.onRegistrationStatus(true);
            messageCallback.onServerMessage("Successfully registered with NickServ!");
        } else if (message.contains("identified")) {
            messageCallback.onServerMessage("Successfully identified with NickServ!");
        }
    }

    private void setCurrentChannel(String channel) {
        this.currentChannel = channel;
        messageCallback.onChannelChange(channel);
    }

    public String getCurrentChannel() {
        return currentChannel.isEmpty() ? "general" : currentChannel;
    }

    public void setMessageCallback(MessageCallback messageCallback) {
        this.messageCallback = messageCallback;
    }

}
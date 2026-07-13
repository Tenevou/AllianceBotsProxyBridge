package ru.alliancemc.alliancebots.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.ProxyPingEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

public final class AllianceBotsProxyBridgePlugin extends Plugin implements Listener {
    private static final String CHANNEL = "AllianceBots";
    private static final String BUNGEE_CHANNEL = "BungeeCord";
    private static final long STALE_TIMEOUT_MILLIS = 15000L;

    private final Map<String, ServerCount> counts = new LinkedHashMap<String, ServerCount>();
    private boolean tabHookAttempted;
    private boolean displayEnabled = true;
    private boolean displayMotd = true;
    private boolean displayPlayerCount = true;
    private boolean displayTabPlaceholders = true;
    private boolean debug;

    @Override
    public void onEnable() {
        loadBridgeConfig();
        getProxy().registerChannel(CHANNEL);
        getProxy().registerChannel(BUNGEE_CHANNEL);
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerCommand(this, new OnlineCommand(this));
        getProxy().getScheduler().schedule(this, new Runnable() {
            @Override
            public void run() {
                pruneStaleCounts();
                if (displayTabPlaceholders) {
                    hookTabPlaceholders();
                }
            }
        }, 1L, 5L, TimeUnit.SECONDS);
        if (displayTabPlaceholders) {
            hookTabPlaceholders();
        }
        getLogger().info("AllianceBots proxy bridge v" + getDescription().getVersion()
                + " enabled on channel " + CHANNEL
                + " display=" + displayEnabled
                + " motd=" + displayMotd
                + " playercount=" + displayPlayerCount
                + " tab=" + displayTabPlaceholders
                + ".");
    }

    @Override
    public void onDisable() {
        getProxy().unregisterChannel(CHANNEL);
        getProxy().unregisterChannel(BUNGEE_CHANNEL);
        counts.clear();
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (BUNGEE_CHANNEL.equals(event.getTag())) {
            handleBungeeCordMessage(event);
            return;
        }
        if (!CHANNEL.equals(event.getTag())) {
            return;
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(event.getData()));
            String action = input.readUTF();
            if (!"ONLINE".equalsIgnoreCase(action)) {
                return;
            }
            String serverName = input.readUTF();
            int botOnline = Math.max(0, input.readInt());
            input.readLong();
            if (event.getSender() instanceof Server) {
                serverName = ((Server) event.getSender()).getInfo().getName();
            }
            if (serverName == null || serverName.trim().isEmpty()) {
                serverName = "unknown";
            }
            synchronized (counts) {
                counts.put(normalizeServerKey(serverName),
                        new ServerCount(serverName, botOnline, System.currentTimeMillis()));
            }
            if (debug) {
                getLogger().info("Received bot online report: " + serverName + "=" + botOnline);
            }
        } catch (Exception ex) {
            getLogger().warning("Failed to read AllianceBots bridge message: " + ex.getMessage());
        }
    }

    private void handleBungeeCordMessage(PluginMessageEvent event) {
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(event.getData()));
            String action = input.readUTF();
            if (!"PlayerCount".equalsIgnoreCase(action)) {
                return;
            }
            String serverName = input.readUTF();
            String resolvedServerName = resolveServerName(serverName);
            Server server = findServerConnection(event);
            if (debug) {
                getLogger().info("BungeeCord PlayerCount request server=" + serverName
                        + " resolved=" + resolvedServerName
                        + " sender=" + connectionName(event.getSender())
                        + " receiver=" + connectionName(event.getReceiver())
                        + " display=" + displayEnabled
                        + " playercount=" + displayPlayerCount);
            }
            if (server == null || !displayEnabled || !displayPlayerCount) {
                return;
            }
            int online = getVirtualOnline(resolvedServerName);
            if (debug) {
                getLogger().info("BungeeCord PlayerCount response server=" + serverName
                        + " resolved=" + resolvedServerName
                        + " online=" + online);
            }

            final Server responseServer = server;
            final byte[] response = createPlayerCountResponse(serverName, online);
            event.setCancelled(true);
            responseServer.sendData(BUNGEE_CHANNEL, response);
            getProxy().getScheduler().schedule(this, new Runnable() {
                @Override
                public void run() {
                    responseServer.sendData(BUNGEE_CHANNEL, response);
                }
            }, 100L, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            getLogger().warning("Failed to answer BungeeCord PlayerCount: " + ex.getMessage());
        }
    }

    private byte[] createPlayerCountResponse(String serverName, int online) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeUTF("PlayerCount");
        output.writeUTF(serverName);
        output.writeInt(online);
        return bytes.toByteArray();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onServerPingLowest(ProxyPingEvent event) {
        applyVirtualMotdOnline(event, "LOWEST");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerPingHighest(ProxyPingEvent event) {
        applyVirtualMotdOnline(event, "HIGHEST");
    }

    private void applyVirtualMotdOnline(ProxyPingEvent event, String stage) {
        ServerPing ping = event.getResponse();
        if (ping == null || ping.getPlayers() == null) {
            return;
        }
        if (!displayEnabled || !displayMotd) {
            if (debug) {
                getLogger().info("MOTD ping " + stage + " skipped display=" + displayEnabled
                        + " motd=" + displayMotd);
            }
            return;
        }
        int before = ping.getPlayers().getOnline();
        int virtualOnline = getVirtualOnline();
        ping.getPlayers().setOnline(virtualOnline);
        event.setResponse(ping);
        if (debug) {
            getLogger().info("MOTD ping " + stage + " online " + before + " -> " + virtualOnline
                    + " bots=" + getBotOnline()
                    + " real=" + getProxy().getOnlineCount());
        }
    }

    public int getBotOnline() {
        pruneStaleCounts();
        int total = 0;
        synchronized (counts) {
            for (ServerCount count : counts.values()) {
                total += count.online;
            }
        }
        return total;
    }

    public int getBotOnline(String serverName) {
        pruneStaleCounts();
        if (serverName == null || serverName.trim().isEmpty() || "ALL".equalsIgnoreCase(serverName)) {
            return getBotOnline();
        }
        synchronized (counts) {
            ServerCount count = counts.get(normalizeServerKey(serverName));
            if (count != null) {
                return count.online;
            }
            for (Map.Entry<String, ServerCount> entry : counts.entrySet()) {
                if (entry.getValue().displayName.equalsIgnoreCase(serverName)) {
                    return entry.getValue().online;
                }
            }
        }
        return 0;
    }

    public int getVirtualOnline() {
        return getProxy().getOnlineCount() + getBotOnline();
    }

    public int getVirtualOnline(String serverName) {
        if (serverName == null || serverName.trim().isEmpty() || "ALL".equalsIgnoreCase(serverName)) {
            return getVirtualOnline();
        }
        int real = 0;
        for (String name : getProxy().getServers().keySet()) {
            if (name.equalsIgnoreCase(serverName)) {
                real = getProxy().getServerInfo(name).getPlayers().size();
                break;
            }
        }
        return real + getBotOnline(serverName);
    }

    public int getDisplayedOnline() {
        return displayEnabled ? getVirtualOnline() : getProxy().getOnlineCount();
    }

    public int getDisplayedOnline(String serverName) {
        if (displayEnabled) {
            return getVirtualOnline(serverName);
        }
        return getRealOnline(serverName);
    }

    public int getRealOnline(String serverName) {
        if (serverName == null || serverName.trim().isEmpty() || "ALL".equalsIgnoreCase(serverName)) {
            return getProxy().getOnlineCount();
        }
        for (String name : getProxy().getServers().keySet()) {
            if (name.equalsIgnoreCase(serverName)) {
                return getProxy().getServerInfo(name).getPlayers().size();
            }
        }
        return 0;
    }

    public Map<String, Integer> getServerCountsSnapshot() {
        pruneStaleCounts();
        Map<String, Integer> snapshot = new LinkedHashMap<String, Integer>();
        synchronized (counts) {
            for (Map.Entry<String, ServerCount> entry : counts.entrySet()) {
                snapshot.put(entry.getValue().displayName, entry.getValue().online);
            }
        }
        return Collections.unmodifiableMap(snapshot);
    }

    private void pruneStaleCounts() {
        long now = System.currentTimeMillis();
        synchronized (counts) {
            counts.entrySet().removeIf(entry -> now - entry.getValue().updatedAt > STALE_TIMEOUT_MILLIS);
        }
    }

    private void hookTabPlaceholders() {
        if (tabHookAttempted || !displayTabPlaceholders) {
            return;
        }
        try {
            Class<?> tabApiClass = Class.forName("me.neznamy.tab.api.TabAPI");
            Object api = tabApiClass.getMethod("getInstance").invoke(null);
            Object placeholderManager = tabApiClass.getMethod("getPlaceholderManager").invoke(api);
            int registered = 0;
            List<String> failed = new ArrayList<String>();
            registered += registerServerPlaceholder(placeholderManager, "%alliancebots_bots%",
                    new Supplier<String>() {
                        @Override
                        public String get() {
                            return String.valueOf(getBotOnline());
                        }
                    });
            registered += registerServerPlaceholder(placeholderManager, "%alliancebots_online%",
                    new Supplier<String>() {
                        @Override
                        public String get() {
                            return String.valueOf(getDisplayedOnline());
                        }
                    });
            registered += registerServerPlaceholder(placeholderManager, "%ab_online%",
                    new Supplier<String>() {
                        @Override
                        public String get() {
                            return String.valueOf(getDisplayedOnline());
                        }
                    });
            registered += registerServerPlaceholder(placeholderManager, "%online%",
                    new Supplier<String>() {
                        @Override
                        public String get() {
                            return String.valueOf(getDisplayedOnline());
                        }
            });
            for (final String serverName : getProxy().getServers().keySet()) {
                List<String> aliases = serverPlaceholderAliases(serverName);
                for (String alias : aliases) {
                    String bungeePlaceholder = "%bungee_" + alias + "%";
                    String safePlaceholder = "%ab_bungee_" + alias + "%";
                    if (registerServerPlaceholder(placeholderManager, bungeePlaceholder, new Supplier<String>() {
                        @Override
                        public String get() {
                            return String.valueOf(getDisplayedOnline(serverName));
                        }
                    }) > 0) {
                        registered++;
                    } else {
                        failed.add(bungeePlaceholder);
                    }
                    registered += registerServerPlaceholder(placeholderManager, safePlaceholder, new Supplier<String>() {
                        @Override
                        public String get() {
                            return String.valueOf(getDisplayedOnline(serverName));
                        }
                    });
                    registered += registerServerPlaceholder(placeholderManager,
                            "%alliancebots_online_" + alias + "%", new Supplier<String>() {
                                @Override
                                public String get() {
                                    return String.valueOf(getDisplayedOnline(serverName));
                                }
                            });
                }
            }
            tabHookAttempted = true;
            if (registered > 0) {
                getLogger().info("Registered TAB placeholders: %alliancebots_bots%, %alliancebots_online%, %ab_online%, %bungee_<server>%, %ab_bungee_<server>%.");
            }
            if (!failed.isEmpty()) {
                getLogger().warning("Could not override TAB placeholders " + failed
                        + ". Use matching %ab_bungee_<server>% placeholders if TAB keeps its built-in bungee placeholders.");
            }
        } catch (ClassNotFoundException ignored) {
            // TAB is optional. Server-list ping and /abonline still work.
        } catch (Exception ex) {
            tabHookAttempted = true;
            getLogger().warning("TAB placeholder hook failed: " + ex.getMessage());
        }
    }

    private Server findServerConnection(PluginMessageEvent event) {
        if (event.getSender() instanceof Server) {
            return (Server) event.getSender();
        }
        if (event.getReceiver() instanceof Server) {
            return (Server) event.getReceiver();
        }
        return null;
    }

    private String connectionName(Connection connection) {
        return connection == null ? "null" : connection.getClass().getName();
    }

    private String resolveServerName(String serverName) {
        if (serverName == null) {
            return "";
        }
        String requested = serverName.trim();
        if (requested.isEmpty() || "ALL".equalsIgnoreCase(requested)) {
            return requested;
        }
        for (String name : getProxy().getServers().keySet()) {
            if (name.equalsIgnoreCase(requested)) {
                return name;
            }
        }
        return requested;
    }

    private String normalizeServerKey(String serverName) {
        if (serverName == null) {
            return "";
        }
        return serverName.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> serverPlaceholderAliases(String serverName) {
        List<String> aliases = new ArrayList<String>();
        aliases.add(serverName);
        String lowerCase = serverName.toLowerCase(Locale.ROOT);
        if (!serverName.equals(lowerCase)) {
            aliases.add(lowerCase);
        }
        return aliases;
    }

    private void loadBridgeConfig() {
        Properties properties = new Properties();
        File file = getConfigFile();
        if (file.isFile()) {
            try (FileInputStream input = new FileInputStream(file)) {
                properties.load(input);
            } catch (Exception ex) {
                getLogger().warning("Failed to load config.properties: " + ex.getMessage());
            }
        }
        displayEnabled = getBoolean(properties, "display.enabled", true);
        displayMotd = getBoolean(properties, "display.motd", true);
        displayPlayerCount = getBoolean(properties, "display.bungee-playercount", true);
        displayTabPlaceholders = getBoolean(properties, "display.tab-placeholders", true);
        debug = getBoolean(properties, "debug", false);
        saveBridgeConfig();
    }

    private void saveBridgeConfig() {
        try {
            if (!getDataFolder().isDirectory()) {
                getDataFolder().mkdirs();
            }
            Properties properties = new Properties();
            properties.setProperty("display.enabled", String.valueOf(displayEnabled));
            properties.setProperty("display.motd", String.valueOf(displayMotd));
            properties.setProperty("display.bungee-playercount", String.valueOf(displayPlayerCount));
            properties.setProperty("display.tab-placeholders", String.valueOf(displayTabPlaceholders));
            properties.setProperty("debug", String.valueOf(debug));
            try (FileOutputStream output = new FileOutputStream(getConfigFile())) {
                properties.store(output, "AllianceBotsProxyBridge");
            }
        } catch (Exception ex) {
            getLogger().warning("Failed to save config.properties: " + ex.getMessage());
        }
    }

    private File getConfigFile() {
        return new File(getDataFolder(), "config.properties");
    }

    private boolean getBoolean(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }
        return "true".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value)
                || "1".equals(value);
    }

    private void setDisplayEnabled(boolean value) {
        displayEnabled = value;
        saveBridgeConfig();
    }

    private void setDisplayMotd(boolean value) {
        displayMotd = value;
        saveBridgeConfig();
    }

    private void setDisplayPlayerCount(boolean value) {
        displayPlayerCount = value;
        saveBridgeConfig();
    }

    private void setDisplayTabPlaceholders(boolean value) {
        displayTabPlaceholders = value;
        tabHookAttempted = false;
        saveBridgeConfig();
        if (value) {
            hookTabPlaceholders();
        }
    }

    private void setDebug(boolean value) {
        debug = value;
        saveBridgeConfig();
    }

    private int registerServerPlaceholder(Object placeholderManager, String identifier, Supplier<String> supplier) {
        unregisterPlaceholder(placeholderManager, identifier);
        for (Method method : placeholderManager.getClass().getMethods()) {
            if (!"registerServerPlaceholder".equals(method.getName()) || method.getParameterTypes().length != 3) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (!String.class.equals(types[0]) || !Supplier.class.isAssignableFrom(types[2])) {
                continue;
            }
            try {
                Object refresh = types[1] == long.class || types[1] == Long.class ? Long.valueOf(1000L) : Integer.valueOf(1000);
                method.invoke(placeholderManager, identifier, refresh, supplier);
                return 1;
            } catch (Exception ignored) {
                return 0;
            }
        }
        return 0;
    }

    private void unregisterPlaceholder(Object placeholderManager, String identifier) {
        for (Method method : placeholderManager.getClass().getMethods()) {
            String name = method.getName();
            if (!("unregisterPlaceholder".equals(name) || "unregisterServerPlaceholder".equals(name))
                    || method.getParameterTypes().length != 1
                    || !String.class.equals(method.getParameterTypes()[0])) {
                continue;
            }
            try {
                method.invoke(placeholderManager, identifier);
            } catch (Exception ignored) {
                // Older TAB versions can refuse unregistering internal placeholders.
            }
            return;
        }
    }

    private static final class ServerCount {
        private final String displayName;
        private final int online;
        private final long updatedAt;

        private ServerCount(String displayName, int online, long updatedAt) {
            this.displayName = displayName;
            this.online = online;
            this.updatedAt = updatedAt;
        }
    }

    private static final class OnlineCommand extends Command {
        private final AllianceBotsProxyBridgePlugin plugin;

        private OnlineCommand(AllianceBotsProxyBridgePlugin plugin) {
            super("abonline", "alliancebotsbridge.admin", "alliancebotsonline");
            this.plugin = plugin;
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (args.length >= 1 && handleControlCommand(sender, args)) {
                return;
            }
            sender.sendMessage(ChatColor.GRAY + "Bridge version: " + ChatColor.GREEN
                    + plugin.getDescription().getVersion());
            sender.sendMessage(ChatColor.GRAY + "Real online: " + ChatColor.GREEN + plugin.getProxy().getOnlineCount());
            sender.sendMessage(ChatColor.GRAY + "Bot online: " + ChatColor.GREEN + plugin.getBotOnline());
            sender.sendMessage(ChatColor.GRAY + "Virtual online: " + ChatColor.GREEN + plugin.getVirtualOnline());
            sender.sendMessage(ChatColor.GRAY + "Display: " + color(plugin.displayEnabled)
                    + onOff(plugin.displayEnabled)
                    + ChatColor.GRAY + " MOTD: " + color(plugin.displayMotd) + onOff(plugin.displayMotd)
                    + ChatColor.GRAY + " PlayerCount: " + color(plugin.displayPlayerCount) + onOff(plugin.displayPlayerCount)
                    + ChatColor.GRAY + " TAB: " + color(plugin.displayTabPlaceholders) + onOff(plugin.displayTabPlaceholders)
                    + ChatColor.GRAY + " Debug: " + color(plugin.debug) + onOff(plugin.debug));
            if (args.length >= 1) {
                sender.sendMessage(ChatColor.GRAY + args[0] + " virtual: " + ChatColor.GREEN
                        + plugin.getVirtualOnline(args[0]));
                sender.sendMessage(ChatColor.GRAY + args[0] + " displayed: " + ChatColor.GREEN
                        + plugin.getDisplayedOnline(args[0]));
            }
            Map<String, Integer> servers = plugin.getServerCountsSnapshot();
            if (servers.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "Servers: " + ChatColor.WHITE + "no bot reports yet");
                return;
            }
            for (Map.Entry<String, Integer> entry : servers.entrySet()) {
                sender.sendMessage(ChatColor.GRAY + entry.getKey() + ": " + ChatColor.GREEN + entry.getValue());
            }
        }

        private boolean handleControlCommand(CommandSender sender, String[] args) {
            String sub = args[0].toLowerCase();
            if ("help".equals(sub)) {
                sendHelp(sender);
                return true;
            }
            if ("reload".equals(sub)) {
                plugin.loadBridgeConfig();
                plugin.tabHookAttempted = false;
                if (plugin.displayTabPlaceholders) {
                    plugin.hookTabPlaceholders();
                }
                sender.sendMessage(ChatColor.GREEN + "AllianceBotsProxyBridge config reloaded.");
                return true;
            }
            if ("status".equals(sub)) {
                return false;
            }
            if ("display".equals(sub) || "motd".equals(sub) || "playercount".equals(sub)
                    || "tab".equals(sub) || "debug".equals(sub)) {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /abonline " + sub + " <on|off>");
                    return true;
                }
                boolean value = parseToggle(args[1]);
                if ("display".equals(sub)) {
                    plugin.setDisplayEnabled(value);
                } else if ("motd".equals(sub)) {
                    plugin.setDisplayMotd(value);
                } else if ("playercount".equals(sub)) {
                    plugin.setDisplayPlayerCount(value);
                } else if ("tab".equals(sub)) {
                    plugin.setDisplayTabPlaceholders(value);
                } else if ("debug".equals(sub)) {
                    plugin.setDebug(value);
                }
                sender.sendMessage(ChatColor.GREEN + "Set " + sub + " to " + onOff(value) + ".");
                return true;
            }
            return false;
        }

        private void sendHelp(CommandSender sender) {
            sender.sendMessage(ChatColor.YELLOW + "/abonline " + ChatColor.GRAY + "- show online and display status");
            sender.sendMessage(ChatColor.YELLOW + "/abonline <server> " + ChatColor.GRAY + "- show server virtual online");
            sender.sendMessage(ChatColor.YELLOW + "/abonline display <on|off> " + ChatColor.GRAY + "- master virtual-online switch");
            sender.sendMessage(ChatColor.YELLOW + "/abonline motd <on|off> " + ChatColor.GRAY + "- change proxy MOTD online");
            sender.sendMessage(ChatColor.YELLOW + "/abonline playercount <on|off> " + ChatColor.GRAY + "- answer Bungee PlayerCount placeholders");
            sender.sendMessage(ChatColor.YELLOW + "/abonline tab <on|off> " + ChatColor.GRAY + "- register TAB placeholders on proxy");
            sender.sendMessage(ChatColor.YELLOW + "/abonline debug <on|off> " + ChatColor.GRAY + "- log bot reports and PlayerCount requests");
            sender.sendMessage(ChatColor.YELLOW + "/abonline reload " + ChatColor.GRAY + "- reload config.properties");
        }

        private boolean parseToggle(String value) {
            return "on".equalsIgnoreCase(value)
                    || "true".equalsIgnoreCase(value)
                    || "yes".equalsIgnoreCase(value)
                    || "1".equals(value);
        }

        private String onOff(boolean value) {
            return value ? "ON" : "OFF";
        }

        private ChatColor color(boolean value) {
            return value ? ChatColor.GREEN : ChatColor.RED;
        }
    }
}

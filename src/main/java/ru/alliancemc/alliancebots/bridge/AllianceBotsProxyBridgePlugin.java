package ru.alliancemc.alliancebots.bridge;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.ProxyPingEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

public final class AllianceBotsProxyBridgePlugin extends Plugin implements Listener {
    private static final String CHANNEL = "AllianceBots";
    private static final long STALE_TIMEOUT_MILLIS = 15000L;

    private final Map<String, ServerCount> counts = new LinkedHashMap<String, ServerCount>();
    private boolean tabHookAttempted;

    @Override
    public void onEnable() {
        getProxy().registerChannel(CHANNEL);
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerCommand(this, new OnlineCommand(this));
        getProxy().getScheduler().schedule(this, new Runnable() {
            @Override
            public void run() {
                pruneStaleCounts();
                hookTabPlaceholders();
            }
        }, 1L, 5L, TimeUnit.SECONDS);
        hookTabPlaceholders();
        getLogger().info("AllianceBots proxy bridge enabled on channel " + CHANNEL + ".");
    }

    @Override
    public void onDisable() {
        getProxy().unregisterChannel(CHANNEL);
        counts.clear();
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
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
                counts.put(serverName, new ServerCount(botOnline, System.currentTimeMillis()));
            }
        } catch (Exception ex) {
            getLogger().warning("Failed to read AllianceBots bridge message: " + ex.getMessage());
        }
    }

    @EventHandler
    public void onServerPing(ProxyPingEvent event) {
        ServerPing ping = event.getResponse();
        if (ping == null || ping.getPlayers() == null) {
            return;
        }
        ping.getPlayers().setOnline(getVirtualOnline());
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

    public int getVirtualOnline() {
        return getProxy().getOnlineCount() + getBotOnline();
    }

    public Map<String, Integer> getServerCountsSnapshot() {
        pruneStaleCounts();
        Map<String, Integer> snapshot = new LinkedHashMap<String, Integer>();
        synchronized (counts) {
            for (Map.Entry<String, ServerCount> entry : counts.entrySet()) {
                snapshot.put(entry.getKey(), entry.getValue().online);
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
        if (tabHookAttempted) {
            return;
        }
        try {
            Class<?> tabApiClass = Class.forName("me.neznamy.tab.api.TabAPI");
            Object api = tabApiClass.getMethod("getInstance").invoke(null);
            Object placeholderManager = tabApiClass.getMethod("getPlaceholderManager").invoke(api);
            int registered = 0;
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
                            return String.valueOf(getVirtualOnline());
                        }
                    });
            registered += registerServerPlaceholder(placeholderManager, "%ab_online%",
                    new Supplier<String>() {
                        @Override
                        public String get() {
                            return String.valueOf(getVirtualOnline());
                        }
                    });
            registerServerPlaceholder(placeholderManager, "%online%",
                    new Supplier<String>() {
                        @Override
                        public String get() {
                            return String.valueOf(getVirtualOnline());
                        }
                    });
            tabHookAttempted = true;
            if (registered > 0) {
                getLogger().info("Registered TAB placeholders: %alliancebots_bots%, %alliancebots_online%, %ab_online%.");
            }
        } catch (ClassNotFoundException ignored) {
            // TAB is optional. Server-list ping and /abonline still work.
        } catch (Exception ex) {
            tabHookAttempted = true;
            getLogger().warning("TAB placeholder hook failed: " + ex.getMessage());
        }
    }

    private int registerServerPlaceholder(Object placeholderManager, String identifier, Supplier<String> supplier) {
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

    private static final class ServerCount {
        private final int online;
        private final long updatedAt;

        private ServerCount(int online, long updatedAt) {
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
            sender.sendMessage(ChatColor.GRAY + "Real online: " + ChatColor.GREEN + plugin.getProxy().getOnlineCount());
            sender.sendMessage(ChatColor.GRAY + "Bot online: " + ChatColor.GREEN + plugin.getBotOnline());
            sender.sendMessage(ChatColor.GRAY + "Virtual online: " + ChatColor.GREEN + plugin.getVirtualOnline());
            Map<String, Integer> servers = plugin.getServerCountsSnapshot();
            if (servers.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "Servers: " + ChatColor.WHITE + "no bot reports yet");
                return;
            }
            for (Map.Entry<String, Integer> entry : servers.entrySet()) {
                sender.sendMessage(ChatColor.GRAY + entry.getKey() + ": " + ChatColor.GREEN + entry.getValue());
            }
        }
    }
}

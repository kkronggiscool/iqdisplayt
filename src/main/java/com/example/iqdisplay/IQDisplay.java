package com.example.iqdisplay;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class IQDisplay extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String TEAM_PREFIX = "iq_";

    private Plugin iqPlugin;
    private Method getIq;

    // Loaded once so the async chat thread never touches the config object.
    private String chatFormat;
    private String nametagPrefix;
    private String nametagSuffix;
    private String noIqText;

    /** What each player's nametag currently shows (main thread only). */
    private final Map<UUID, Integer> applied = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        chatFormat = getConfig().getString("chat.format", "<gray>[IQ <iq>] <player>: <message>");
        nametagPrefix = getConfig().getString("nametag.prefix", "");
        nametagSuffix = getConfig().getString("nametag.suffix", "");
        noIqText = getConfig().getString("no-iq-text", "?");

        iqPlugin = getServer().getPluginManager().getPlugin("IQTest");
        if (iqPlugin == null) {
            getLogger().severe("IQTest is not installed! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            getIq = iqPlugin.getClass().getMethod("getIq", UUID.class);
        } catch (NoSuchMethodException e) {
            getLogger().severe("Your IQTest is too old (no public getIq method). Update IQTest! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        removeAllTeams(); // clear anything left over from a crash
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, (Runnable) this::refreshAll, 20L, 40L);
    }

    @Override
    public void onDisable() {
        removeAllTeams();
        applied.clear();
    }

    // ------------------------------------------------------------------ IQ lookup

    private Integer iqOf(UUID id) {
        try {
            return (Integer) getIq.invoke(iqPlugin, id);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    private TagResolver iqTag(Integer iq) {
        if (iq == null) {
            return Placeholder.parsed("iq", "<gray>" + noIqText + "</gray>");
        }
        return Placeholder.parsed("iq", "<bold><color:" + iqColor(iq) + ">" + iq + "</color></bold>");
    }

    private static String iqColor(int iq) {
        if (iq < 0) return "#ff3b3b";
        if (iq < 20) return "#ff9a3c";
        if (iq < 50) return "#ffd23f";
        if (iq < 80) return "#9ef01a";
        return "#37e6ff";
    }

    // ------------------------------------------------------------------ chat

    /**
     * Runs last. Messages blocked by IQTest (locked players, too long, cooldown) are skipped.
     * We cancel the normal chat message and send our own formatted one, so no other chat
     * formatting can override the IQ and name.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player source = event.getPlayer();
        Component formatted = MM.deserialize(chatFormat,
                iqTag(iqOf(source.getUniqueId())),
                Placeholder.component("player", source.displayName()),
                Placeholder.component("message", event.message()));
        event.setCancelled(true);
        Bukkit.broadcast(formatted);
    }

    // ------------------------------------------------------------------ nametags

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        getServer().getScheduler().runTaskLater(this, () -> refresh(p), 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        applied.remove(p.getUniqueId());
        Team team = mainBoard().getTeam(teamName(p));
        if (team != null) team.unregister();
    }

    private void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
    }

    /** Updates one player's nametag if their IQ changed since last time. */
    private void refresh(Player p) {
        if (!p.isOnline()) return;
        Integer iq = iqOf(p.getUniqueId());
        if (Objects.equals(iq, applied.get(p.getUniqueId()))) return;

        Scoreboard board = mainBoard();
        String name = teamName(p);
        Team team = board.getTeam(name);

        if (iq == null) { // no IQ (yet): no tag
            if (team != null) team.unregister();
            applied.remove(p.getUniqueId());
            return;
        }

        if (team == null) team = board.registerNewTeam(name);
        team.prefix(nametagPrefix.isEmpty() ? Component.empty() : MM.deserialize(nametagPrefix, iqTag(iq)));
        team.suffix(nametagSuffix.isEmpty() ? Component.empty() : MM.deserialize(nametagSuffix, iqTag(iq)));
        if (!team.hasEntry(p.getName())) team.addEntry(p.getName());
        applied.put(p.getUniqueId(), iq);
    }

    private static Scoreboard mainBoard() {
        return Bukkit.getScoreboardManager().getMainScoreboard();
    }

    private static String teamName(Player p) {
        return TEAM_PREFIX + p.getUniqueId().toString().substring(0, 8);
    }

    private void removeAllTeams() {
        Scoreboard board = mainBoard();
        for (Team team : new java.util.ArrayList<>(board.getTeams())) {
            if (team.getName().startsWith(TEAM_PREFIX)) team.unregister();
        }
    }
}

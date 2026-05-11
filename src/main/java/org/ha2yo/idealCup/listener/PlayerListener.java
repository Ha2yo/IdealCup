package org.ha2yo.idealCup.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.ha2yo.idealCup.game.IdealCupGame;
import org.ha2yo.idealCup.resource.ResourcePackSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class PlayerListener implements Listener {
    private final IdealCupGame game;
    private final ResourcePackSender resourcePackSender;

    public PlayerListener(IdealCupGame game, ResourcePackSender resourcePackSender) {
        this.game = game;
        this.resourcePackSender = resourcePackSender;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        game.handleJoin(event.getPlayer());
        resourcePackSender.sendTo(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        game.handleQuit(event.getPlayer());
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        resourcePackSender.handleStatus(event);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (game.handleAdminPlaybackControl(event.getPlayer(), event.getAction())) {
            event.setCancelled(true);
            return;
        }
        if (game.handleAdminSkip(event.getPlayer())) {
            event.setCancelled(true);
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (game.handleMouseVote(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        if (game.isCurrentDebater(event.getPlayer())) {
            event.renderer((source, sourceDisplayName, message, viewer) ->
                    Component.text("<", NamedTextColor.YELLOW)
                            .append(Component.text(source.getName(), NamedTextColor.YELLOW))
                            .append(Component.text("> ", NamedTextColor.YELLOW))
                            .append(message.color(NamedTextColor.YELLOW)));
            return;
        }
        if (event.getPlayer().isOp()) {
            event.renderer((source, sourceDisplayName, message, viewer) ->
                    Component.text("<", NamedTextColor.WHITE)
                            .append(Component.text(source.getName(), NamedTextColor.WHITE))
                            .append(Component.text("> ", NamedTextColor.WHITE))
                            .append(message));
        }
    }
}

package org.ha2yo.idealCup.command;

import org.ha2yo.idealCup.game.IdealCupGame;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class SupplyCommand implements CommandExecutor {
    private final IdealCupGame game;
    private final SupplyType supplyType;

    public SupplyCommand(IdealCupGame game, SupplyType supplyType) {
        this.game = game;
        this.supplyType = supplyType;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "이 명령어는 플레이어만 사용할 수 있습니다.");
            return true;
        }

        if (supplyType == SupplyType.REMOTE && !player.hasPermission("idealcup.admin")) {
            player.sendMessage(ChatColor.RED + "관리자만 사용할 수 있습니다.");
            return true;
        }

        if (supplyType == SupplyType.MOUSE) {
            game.giveMouseItem(player);
            player.sendMessage(ChatColor.GREEN + "마우스 아이템을 지급했습니다.");
        } else if (supplyType == SupplyType.SPYGLASS) {
            game.giveSpyglassItem(player);
            player.sendMessage(ChatColor.GREEN + "망원경을 지급했습니다.");
        } else {
            game.giveRemoteItem(player);
            player.sendMessage(ChatColor.GREEN + "관리자 전용 리모컨을 지급했습니다.");
        }
        return true;
    }

    public enum SupplyType {
        MOUSE,
        SPYGLASS,
        REMOTE
    }
}

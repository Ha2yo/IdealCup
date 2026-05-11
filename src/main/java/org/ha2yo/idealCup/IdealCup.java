package org.ha2yo.idealCup;

import org.ha2yo.idealCup.command.IdealCupCommand;
import org.ha2yo.idealCup.command.SupplyCommand;
import org.ha2yo.idealCup.game.IdealCupGame;
import org.ha2yo.idealCup.listener.PlayerListener;
import org.ha2yo.idealCup.resource.CandidateRepository;
import org.ha2yo.idealCup.resource.ResourcePackSender;
import org.ha2yo.idealCup.visual.CandidateDisplay;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class IdealCup extends JavaPlugin {
    private CandidateRepository candidateRepository;
    private CandidateDisplay candidateDisplay;
    private IdealCupGame game;
    private ResourcePackSender resourcePackSender;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        candidateRepository = new CandidateRepository(this);
        resourcePackSender = new ResourcePackSender(this);
        candidateDisplay = new CandidateDisplay(this);
        candidateDisplay.clearPersistedBoard();
        game = new IdealCupGame(this, candidateRepository, candidateDisplay);

        IdealCupCommand commandExecutor = new IdealCupCommand(this, candidateRepository, game);
        PluginCommand command = getCommand("idealcup");
        if (command != null) {
            command.setExecutor(commandExecutor);
            command.setTabCompleter(commandExecutor);
        }
        PluginCommand mouseCommand = getCommand("마우스");
        if (mouseCommand != null) {
            mouseCommand.setExecutor(new SupplyCommand(game, SupplyCommand.SupplyType.MOUSE));
        }
        PluginCommand spyglassCommand = getCommand("망원경");
        if (spyglassCommand != null) {
            spyglassCommand.setExecutor(new SupplyCommand(game, SupplyCommand.SupplyType.SPYGLASS));
        }
        PluginCommand remoteCommand = getCommand("리모컨");
        if (remoteCommand != null) {
            remoteCommand.setExecutor(new SupplyCommand(game, SupplyCommand.SupplyType.REMOTE));
        }

        getServer().getPluginManager().registerEvents(new PlayerListener(game, resourcePackSender), this);
        candidateRepository.reload();
    }

    @Override
    public void onDisable() {
        if (game != null) {
            game.stop(false);
        }
        if (candidateDisplay != null) {
            candidateDisplay.clear();
        }
    }
}

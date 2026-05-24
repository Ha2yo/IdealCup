package org.ha2yo.idealCup;

import org.ha2yo.idealCup.command.IdealCupCommand;
import org.ha2yo.idealCup.command.SupplyCommand;
import org.ha2yo.idealCup.game.IdealCupGame;
import org.ha2yo.idealCup.listener.PlayerListener;
import org.ha2yo.idealCup.resource.CandidateRepository;
import org.ha2yo.idealCup.visual.CandidateDisplay;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class IdealCup extends JavaPlugin {
    private static final String YT_DLP_DOWNLOAD_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
    private static final String FFMPEG_DOWNLOAD_URL = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";

    private CandidateRepository candidateRepository;
    private CandidateDisplay candidateDisplay;
    private IdealCupGame game;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        createDefaultFolders();
        installDefaultToolsAsync();

        candidateRepository = new CandidateRepository(this);
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

        getServer().getPluginManager().registerEvents(new PlayerListener(game), this);
        disableAdvancementAnnouncements();
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

    private void disableAdvancementAnnouncements() {
        for (World world : Bukkit.getWorlds()) {
            world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        }
    }

    private void createDefaultFolders() {
        createFolder("tools");
    }

    private void createFolder(String name) {
        File folder = new File(getDataFolder(), name);
        if (!folder.exists() && !folder.mkdirs()) {
            getLogger().warning("plugins/IdealCup/" + name + " 폴더를 만들 수 없습니다.");
        }
    }

    private void installDefaultToolsAsync() {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            File toolFolder = new File(getDataFolder(), "tools");
            try {
                Files.createDirectories(toolFolder.toPath());
                installYtDlpIfMissing(new File(toolFolder, "yt-dlp.exe"));
                installFfmpegIfMissing(new File(toolFolder, "ffmpeg.exe"));
            } catch (IOException exception) {
                getLogger().warning("기본 도구 자동 준비에 실패했습니다: " + exception.getMessage());
            }
        });
    }

    private void installYtDlpIfMissing(File targetFile) throws IOException {
        if (targetFile.isFile()) {
            return;
        }
        getLogger().info("tools/yt-dlp.exe가 없어 자동으로 다운로드합니다.");
        try (InputStream inputStream = URI.create(YT_DLP_DOWNLOAD_URL).toURL().openStream()) {
            Files.copy(inputStream, targetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        getLogger().info("tools/yt-dlp.exe 다운로드 완료");
    }

    private void installFfmpegIfMissing(File targetFile) throws IOException {
        if (targetFile.isFile()) {
            return;
        }
        getLogger().info("tools/ffmpeg.exe가 없어 자동으로 다운로드합니다.");
        try (InputStream inputStream = URI.create(FFMPEG_DOWNLOAD_URL).toURL().openStream();
             ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (!entry.isDirectory() && name.endsWith("/bin/ffmpeg.exe")) {
                    Files.copy(zipInputStream, targetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    getLogger().info("tools/ffmpeg.exe 다운로드 완료");
                    return;
                }
            }
        }
        throw new IOException("다운로드한 ffmpeg 압축 파일에서 bin/ffmpeg.exe를 찾지 못했습니다.");
    }
}

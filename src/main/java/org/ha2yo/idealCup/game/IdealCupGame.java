package org.ha2yo.idealCup.game;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.ha2yo.idealCup.model.Candidate;
import org.ha2yo.idealCup.resource.CandidateRepository;
import org.ha2yo.idealCup.visual.CandidateDisplay;
import org.ha2yo.idealCup.visual.LocationConfig;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class IdealCupGame {
    private static final long DEFAULT_MEDIA_PLAYBACK_TICKS = 200L;
    private static final long ADMIN_SKIP_DEBOUNCE_MILLIS = 350L;
    private static final String ENDING_BGM_MANIFEST_PATH = "assets/idealcup/ending_bgm.yml";
    private static final Pattern ENDING_SOUND_ENTRY_PATTERN = Pattern.compile("assets/idealcup/sounds/ending_theme(\\d*)\\.ogg", Pattern.CASE_INSENSITIVE);

    private final JavaPlugin plugin;
    private final CandidateRepository candidateRepository;
    private final CandidateDisplay candidateDisplay;
    private final Random random = new Random();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final Map<UUID, VoteChoice> votes = new HashMap<>();
    private final Map<String, RankingEntry> rankingEntries = new HashMap<>();
    private final Set<UUID> eligibleVoters = new HashSet<>();

    private BossBar bossBar;
    private GamePhase phase = GamePhase.IDLE;
    private ArrayDeque<Candidate> currentQueue = new ArrayDeque<>();
    private List<Candidate> nextRoundWinners = new ArrayList<>();
    private Match currentMatch;
    private Candidate lastRunnerUp;
    private List<Candidate> displayedCandidates = List.of();
    private BukkitTask mediaPlaybackTask;
    private BukkitTask rankingAudioTask;
    private boolean mediaPlaying;
    private Candidate playingCandidate;
    private String rankingAudioSoundKey;
    private Runnable activeCountdownDone;
    private long lastAdminSkipMillis;
    private String cupName = "IdealCup";
    private UUID currentDebater;
    private boolean startTitleShown;
    private int initialSize;
    private int currentRoundSize;
    private int currentMatchNumber;
    private int currentTotalMatches;
    private boolean finalRankingAvailable;
    private boolean finalRankingShown;

    public IdealCupGame(JavaPlugin plugin, CandidateRepository candidateRepository, CandidateDisplay candidateDisplay) {
        this.plugin = plugin;
        this.candidateRepository = candidateRepository;
        this.candidateDisplay = candidateDisplay;
    }

    public boolean isRunning() {
        return phase != GamePhase.IDLE;
    }

    public boolean hasDisplayLocations() {
        return candidateDisplay.hasLocations();
    }

    public boolean isVoting() {
        return phase == GamePhase.VOTING;
    }

    public boolean canVote(Player player) {
        return isVoting() && eligibleVoters.contains(player.getUniqueId());
    }

    public String status() {
        if (!isRunning()) {
            return "진행 중인 이상형 월드컵이 없습니다.";
        }
        return cupName + ": " + currentRoundSize + "강, " + currentMatchNumber + "/" + currentTotalMatches + " 경기, 상태 " + phaseName();
    }

    public void start(Player sender, String name, int size) {
        if (isRunning()) {
            sender.sendMessage(ChatColor.RED + "이미 이상형 월드컵이 진행 중입니다.");
            return;
        }
        if (!isPowerOfTwo(size) || size < 2) {
            sender.sendMessage(ChatColor.RED + "참가자 수는 2 이상의 2의 거듭제곱이어야 합니다.");
            return;
        }
        candidateRepository.reload();
        for (String warning : candidateRepository.getWarnings()) {
            sender.sendMessage(ChatColor.YELLOW + warning);
        }
        if (!candidateDisplay.hasLocations()) {
            sender.sendMessage(ChatColor.RED + "먼저 표시 영역을 설정하세요: /idealcup set pos1, /idealcup set pos2");
            return;
        }
        if (!candidateRepository.hasEnoughCandidates(size)) {
            int validCount = candidateRepository.getCandidates().size();
            sender.sendMessage(ChatColor.RED + "유효한 후보가 부족합니다. 필요: " + size + "개, 현재: " + validCount + "개");
            return;
        }

        cupName = name;
        startTitleShown = false;
        initialSize = size;
        finalRankingAvailable = false;
        finalRankingShown = false;
        resetRankingAudio();
        rankingEntries.clear();
        resetHistory();
        Bukkit.getOnlinePlayers().forEach(this::prepareGameInventory);
        List<Candidate> selected = new ArrayList<>(candidateRepository.getCandidates());
        Collections.shuffle(selected, random);
        selected = new ArrayList<>(selected.subList(0, size));
        startRound(selected);
    }

    public void stop(boolean announce) {
        cancelTasks();
        removeBossBar();
        resetMediaPlayback();
        resetRankingAudio();
        candidateDisplay.clear();
        phase = GamePhase.IDLE;
        startTitleShown = false;
        currentQueue.clear();
        nextRoundWinners.clear();
        votes.clear();
        eligibleVoters.clear();
        currentDebater = null;
        currentMatch = null;
        displayedCandidates = List.of();
        finalRankingAvailable = false;
        finalRankingShown = false;
        rankingEntries.clear();
        if (announce) {
            Bukkit.broadcastMessage(ChatColor.RED + "이상형 월드컵이 중지되었습니다.");
        }
    }

    public void forceWin(VoteChoice choice, Player sender) {
        if (!isRunning() || currentMatch == null) {
            sender.sendMessage(ChatColor.RED + "진행 중인 경기가 없습니다.");
            return;
        }
        cancelTasks();
        Candidate winner = choice == VoteChoice.LEFT ? currentMatch.left() : currentMatch.right();
        Candidate loser = choice == VoteChoice.LEFT ? currentMatch.right() : currentMatch.left();
        long leftVotes = votes.values().stream().filter(voteChoice -> voteChoice == VoteChoice.LEFT).count();
        long rightVotes = votes.values().stream().filter(voteChoice -> voteChoice == VoteChoice.RIGHT).count();
        recordMatchVotes(leftVotes, rightVotes);
        finishMatch(winner, loser, "관리자가 결과를 강제 처리했습니다.");
    }

    public void preview(Candidate candidate) {
        resetMediaPlayback();
        displayedCandidates = List.of(candidate);
        candidateDisplay.showPreview(candidate);
        playGlobalSound("minecraft:block.note_block.hat", 0.55F, 1.35F);
    }

    public void handleJoin(Player player) {
        teleportToConfiguredLocation(player, "locations.lobby");
        if (phase == GamePhase.VOTING) {
            eligibleVoters.add(player.getUniqueId());
            player.sendActionBar(Component.text("마우스로 후보를 바라보고 우클릭하여 투표하세요."));
        } else if (isRunning()) {
            player.sendMessage(ChatColor.YELLOW + "이상형 월드컵이 진행 중입니다. 다음 투표가 시작되면 참여할 수 있습니다.");
        }
        if (bossBar != null) {
            bossBar.addPlayer(player);
        }
    }

    public void handleResourcePackReady(Player player) {
        teleportToConfiguredLocation(player, "locations.cinema");
    }

    private void teleportToConfiguredLocation(Player player, String path) {
        Location location = LocationConfig.read(plugin, path);
        if (location != null) {
            player.teleport(location);
        }
    }

    public void handleQuit(Player player) {
        UUID uuid = player.getUniqueId();
        votes.remove(uuid);
        eligibleVoters.remove(uuid);
        if (bossBar != null) {
            bossBar.removePlayer(player);
        }
    }

    public boolean handleMouseVote(Player player) {
        if (!canVote(player)) {
            return false;
        }
        if (!isMouseItem(player.getInventory().getItemInMainHand())) {
            return false;
        }
        Optional<VoteChoice> lookChoice = candidateDisplay.voteChoiceFromLook(player);
        if (lookChoice.isEmpty()) {
            player.sendActionBar(Component.text("화면의 왼쪽 또는 오른쪽 후보를 바라보고 우클릭하세요."));
            return true;
        }
        VoteChoice choice = lookChoice.get();

        votes.put(player.getUniqueId(), choice);
        Candidate candidate = choice == VoteChoice.LEFT ? currentMatch.left() : currentMatch.right();
        player.sendActionBar(Component.text("투표함: " + candidate.name()));
        playPlayerSound(player, "minecraft:ui.button.click", 0.7F, 1.4F);
        return true;
    }

    private void startRound(List<Candidate> candidates) {
        Collections.shuffle(candidates, random);
        currentQueue = new ArrayDeque<>(candidates);
        nextRoundWinners = new ArrayList<>();
        currentRoundSize = candidates.size();
        currentMatchNumber = 0;
        currentTotalMatches = currentRoundSize / 2;
        phase = GamePhase.RESULT;
        if (!startTitleShown) {
            startTitleShown = true;
            Bukkit.broadcastMessage(ChatColor.AQUA + cupName + " " + currentRoundSize + "강을 시작합니다.");
            playGlobalSound("minecraft:block.note_block.bell", 0.9F, 1.1F);
            scheduleLater(this::startNextMatch, plugin.getConfig().getInt("timing.round-transition-seconds", 5));
            return;
        }
        scheduleLater(this::startNextMatch, 0);
    }

    private void startNextMatch() {
        if (phase == GamePhase.IDLE) {
            return;
        }
        if (currentQueue.isEmpty()) {
            finishRound();
            return;
        }

        Candidate left = currentQueue.poll();
        Candidate right = currentQueue.poll();
        if (left == null || right == null) {
            finishRound();
            return;
        }

        currentMatchNumber++;
        currentMatch = new Match(left, right, currentRoundSize, currentMatchNumber, currentTotalMatches);
        phase = GamePhase.PREVIEW;
        votes.clear();
        eligibleVoters.clear();
        resetMediaPlayback();
        displayedCandidates = List.of(left, right);
        candidateDisplay.showMatch(left, right, cupName, initialSize, currentRoundSize, currentMatchNumber, currentTotalMatches);
        playGlobalSound("minecraft:block.note_block.hat", 0.65F, 1.25F);
        ensureBossBar();
        addAllPlayersToBossBar();
        bossBar.setTitle(currentRoundSize + "강 - " + left.name() + " 대 " + right.name());
        bossBar.setColor(BarColor.BLUE);
        bossBar.setProgress(1.0D);

        int previewSeconds = plugin.getConfig().getInt("timing.preview-seconds", 5);
        countdown(previewSeconds, remaining -> bossBar.setTitle(currentRoundSize + "강 - " + left.name() + " 대 " + right.name()), this::startVote);
    }

    private void startVote() {
        phase = GamePhase.VOTING;
        votes.clear();
        eligibleVoters.clear();
        playGlobalSound("minecraft:block.note_block.pling", 0.85F, 1.35F);
        for (Player player : Bukkit.getOnlinePlayers()) {
            eligibleVoters.add(player.getUniqueId());
            ensureVotingTools(player);
            player.sendActionBar(Component.text("마우스로 후보를 바라보고 우클릭하여 투표하세요."));
        }

        int voteSeconds = plugin.getConfig().getInt("timing.vote-seconds", 20);
        countdown(voteSeconds, remaining -> {
            bossBar.setColor(BarColor.BLUE);
            updateBossBarTimer(remaining, voteSeconds);
            if (currentMatch != null) {
                bossBar.setTitle("투표 마감까지 " + remaining + "초");
            }
        }, this::endVote);
    }

    private void endVote() {
        if (currentMatch == null) {
            return;
        }
        long leftVotes = votes.values().stream().filter(choice -> choice == VoteChoice.LEFT).count();
        long rightVotes = votes.values().stream().filter(choice -> choice == VoteChoice.RIGHT).count();

        if (leftVotes > rightVotes) {
            recordMatchVotes(leftVotes, rightVotes);
            finishMatch(currentMatch.left(), currentMatch.right(), currentMatch.left().name() + " 승리: " + leftVotes + " vs " + rightVotes);
        } else if (rightVotes > leftVotes) {
            recordMatchVotes(leftVotes, rightVotes);
            finishMatch(currentMatch.right(), currentMatch.left(), currentMatch.right().name() + " 승리: " + rightVotes + " vs " + leftVotes);
        } else {
            startDebate(leftVotes, rightVotes);
        }
    }

    private void startDebate(long leftVotes, long rightVotes) {
        phase = GamePhase.DEBATE;
        if (currentMatch == null) {
            return;
        }
        Optional<Player> leftRepresentative = pickRepresentative(VoteChoice.LEFT);
        Optional<Player> rightRepresentative = pickRepresentative(VoteChoice.RIGHT);
        Bukkit.broadcastMessage(ChatColor.GOLD + "동표가 나왔으므로 각 진영별로 대표를 뽑아 변론을 시작합니다.");
        playGlobalSound("minecraft:block.note_block.bass", 0.9F, 0.8F);

        int debateSeconds = plugin.getConfig().getInt("timing.debate-seconds", 20);
        scheduleLater(() -> runDebateTurn(currentMatch.left(), leftRepresentative, "locations.debate-left", debateSeconds,
                () -> scheduleNextTick(() -> runDebateTurn(currentMatch.right(), rightRepresentative, "locations.debate-right", debateSeconds, this::startVote))), 2);
    }

    private void runDebateTurn(Candidate candidate, Optional<Player> representative, String locationPath, int debateSeconds, Runnable done) {
        String representativeName = representative.map(Player::getName).orElse("없음");
        String title = candidate.name() + "측 대표: " + representativeName;
        announceRepresentative(candidate, representative);

        Location originalLocation = null;
        Player player = representative.orElse(null);
        if (player != null && player.isOnline()) {
            currentDebater = player.getUniqueId();
            originalLocation = player.getLocation();
            Location debateLocation = LocationConfig.read(plugin, locationPath);
            if (debateLocation != null) {
                player.teleport(debateLocation);
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, (debateSeconds + 2) * 20, 0, false, false, true));
            player.showTitle(net.kyori.adventure.title.Title.title(
                    Component.text("변론 대표"),
                    Component.text(candidate.name() + "측 주장을 시작하세요")
            ));
        }

        Location returnLocation = originalLocation;
        Player returningPlayer = player;
        countdown(debateSeconds, remaining -> {
            bossBar.setColor(BarColor.RED);
            updateBossBarTimer(remaining, debateSeconds);
            bossBar.setTitle(title);
        }, () -> {
            if (returningPlayer != null && returningPlayer.isOnline() && returnLocation != null) {
                returningPlayer.removePotionEffect(PotionEffectType.GLOWING);
                returningPlayer.teleport(returnLocation);
            }
            if (returningPlayer != null && returningPlayer.getUniqueId().equals(currentDebater)) {
                currentDebater = null;
            }
            done.run();
        });
    }

    private void announceRepresentative(Candidate candidate, Optional<Player> representative) {
        Bukkit.broadcastMessage(ChatColor.YELLOW + candidate.name() + "측 대표: " + representative.map(Player::getName).orElse("없음"));
    }

    private Optional<Player> pickRepresentative(VoteChoice choice) {
        List<Player> players = new ArrayList<>();
        for (Map.Entry<UUID, VoteChoice> entry : votes.entrySet()) {
            if (entry.getValue() == choice) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    players.add(player);
                }
            }
        }
        if (players.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(players.get(random.nextInt(players.size())));
    }

    private void finishMatch(Candidate winner, Candidate loser, String message) {
        phase = GamePhase.RESULT;
        lastRunnerUp = loser;
        nextRoundWinners.add(winner);
        ensureBossBar();
        bossBar.setColor(BarColor.GREEN);
        bossBar.setProgress(1.0D);
        bossBar.setTitle(message);
        resetMediaPlayback();
        if (currentMatch != null) {
            displayedCandidates = List.of(currentMatch.left(), currentMatch.right());
        }
        if (currentMatch != null) {
            candidateDisplay.showMatchResult(currentMatch.left(), currentMatch.right(), winner, cupName, currentRoundSize, currentMatchNumber, currentTotalMatches);
        }
        recordRankingWin(winner);
        recordRankingLoss(loser);
        saveMatchHistory(winner, loser, message);
        Bukkit.broadcastMessage(ChatColor.AQUA + message);
        playGlobalSound("minecraft:entity.player.levelup", 0.9F, 1.15F);
        bossBar.setTitle(message + " | 관리자는 리모컨으로 넘길 수 있습니다.");
        int resultSeconds = plugin.getConfig().getInt("timing.result-seconds", 3);
        countdown(resultSeconds, remaining -> bossBar.setTitle(message), this::startNextMatch);
    }

    private void finishRound() {
        if (nextRoundWinners.size() == 1) {
            finishTournament(nextRoundWinners.get(0));
            return;
        }
        startRound(new ArrayList<>(nextRoundWinners));
    }

    private void finishTournament(Candidate winner) {
        phase = GamePhase.RESULT;
        resetMediaPlayback();
        displayedCandidates = List.of(winner);
        candidateDisplay.showWinner(winner, cupName, initialSize);
        ensureBossBar();
        bossBar.setColor(BarColor.PURPLE);
        bossBar.setProgress(1.0D);
        bossBar.setTitle("최종 우승: " + winner.name());
        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "이상형 월드컵 최종 우승: " + winner.name());
        playWinnerFanfare();
        Bukkit.getOnlinePlayers().forEach(player -> player.showTitle(net.kyori.adventure.title.Title.title(
                Component.text("최종 우승"),
                Component.text(winner.name())
        )));
        Bukkit.getOnlinePlayers().forEach(this::sendResultDialogButton);
        saveTournamentHistory(winner);
        finalRankingAvailable = true;
        finalRankingShown = false;
        bossBar.setColor(BarColor.BLUE);
        bossBar.setTitle("최종 결과 보기: /idealcup result");
        phase = GamePhase.IDLE;
        startTitleShown = false;
        currentQueue.clear();
        nextRoundWinners.clear();
        votes.clear();
        eligibleVoters.clear();
    }

    private void resetHistory() {
        File historyFile = new File(plugin.getDataFolder(), "history.yml");
        YamlConfiguration historyConfig = new YamlConfiguration();
        historyConfig.set("tournament.name", cupName);
        historyConfig.set("tournament.size", initialSize);
        historyConfig.set("tournament.started-at", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        historyConfig.set("matches", new ArrayList<>());
        saveHistoryConfig(historyConfig, historyFile);
    }

    private void saveMatchHistory(Candidate winner, Candidate loser, String reason) {
        File historyFile = new File(plugin.getDataFolder(), "history.yml");
        YamlConfiguration historyConfig = YamlConfiguration.loadConfiguration(historyFile);
        List<Map<String, Object>> matches = copyHistoryRecords(historyConfig.getMapList("matches"));

        Map<String, Object> record = new HashMap<>();
        record.put("time", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        record.put("round-size", currentRoundSize);
        record.put("match-number", currentMatchNumber);
        record.put("total-matches", currentTotalMatches);
        if (currentMatch != null) {
            record.put("left-id", currentMatch.left().id());
            record.put("left-name", currentMatch.left().name());
            record.put("right-id", currentMatch.right().id());
            record.put("right-name", currentMatch.right().name());
        }
        record.put("winner-id", winner.id());
        record.put("winner-name", winner.name());
        record.put("loser-id", loser.id());
        record.put("loser-name", loser.name());
        record.put("reason", reason);
        matches.add(record);

        historyConfig.set("matches", matches);
        saveHistoryConfig(historyConfig, historyFile);
    }

    private void saveTournamentHistory(Candidate winner) {
        File historyFile = new File(plugin.getDataFolder(), "history.yml");
        YamlConfiguration historyConfig = YamlConfiguration.loadConfiguration(historyFile);
        historyConfig.set("tournament.finished-at", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        historyConfig.set("tournament.winner-id", winner.id());
        historyConfig.set("tournament.winner-name", winner.name());
        if (lastRunnerUp != null) {
            historyConfig.set("tournament.runner-up-id", lastRunnerUp.id());
            historyConfig.set("tournament.runner-up-name", lastRunnerUp.name());
        }
        historyConfig.set("rankings", rankingHistoryRecords());
        saveHistoryConfig(historyConfig, historyFile);
    }

    private List<Map<String, Object>> rankingHistoryRecords() {
        List<Map<String, Object>> rankings = new ArrayList<>();
        List<RankingEntry> entries = sortedRankingEntries();
        for (int index = 0; index < entries.size(); index++) {
            RankingEntry entry = entries.get(index);
            Map<String, Object> record = new HashMap<>();
            record.put("rank", index + 1);
            record.put("id", entry.id());
            record.put("name", entry.name());
            record.put("result", entry.resultLabel(index + 1));
            record.put("votes", entry.votes());
            rankings.add(record);
        }
        return rankings;
    }

    public void openResultDialog(Player player) {
        if (rankingEntries.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "아직 확인할 최종 결과가 없습니다.");
            return;
        }
        List<DialogBody> bodies = new ArrayList<>();
        bodies.add(DialogBody.plainMessage(Component.text(cupName + " 결과", NamedTextColor.GOLD), 420));
        List<RankingEntry> entries = sortedRankingEntries();
        Component resultTable = Component.empty();
        for (int index = 0; index < entries.size(); index++) {
            RankingEntry entry = entries.get(index);
            resultTable = resultTable
                    .append(Component.text((index + 1) + "위 " + entry.resultLabel(index + 1) + " · " + entry.votes() + "표", index == 0 ? NamedTextColor.GOLD : NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text(entry.name(), NamedTextColor.AQUA));
            if (index + 1 < entries.size()) {
                resultTable = resultTable.append(Component.newline()).append(Component.newline());
            }
        }
        bodies.add(DialogBody.plainMessage(resultTable, 420));

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.empty())
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(bodies)
                        .inputs(List.of())
                        .build())
                .type(DialogType.notice(ActionButton.create(Component.text("닫기"), null, 80, null))));
        player.showDialog(dialog);
    }

    private void sendResultDialogButton(Player player) {
        player.sendMessage(Component.empty()
                .append(Component.text("[최종 결과 보기]", NamedTextColor.GOLD)
                        .clickEvent(ClickEvent.runCommand("/idealcup result"))
                        .hoverEvent(HoverEvent.showText(Component.text("클릭해서 결과 창을 엽니다."))))
                .append(Component.text(" 월드컵 결과를 확인할 수 있습니다.", NamedTextColor.GRAY)));
    }

    private List<Map<String, Object>> copyHistoryRecords(List<Map<?, ?>> records) {
        List<Map<String, Object>> copiedRecords = new ArrayList<>();
        for (Map<?, ?> record : records) {
            Map<String, Object> copiedRecord = new HashMap<>();
            for (Map.Entry<?, ?> entry : record.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    copiedRecord.put(key, entry.getValue());
                }
            }
            copiedRecords.add(copiedRecord);
        }
        return copiedRecords;
    }

    private void saveHistoryConfig(YamlConfiguration historyConfig, File historyFile) {
        try {
            historyConfig.save(historyFile);
            if (plugin.getConfig().contains("history")) {
                plugin.getConfig().set("history", null);
                plugin.saveConfig();
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("history.yml 저장에 실패했습니다: " + exception.getMessage());
        }
    }

    private void countdown(int seconds, Consumer<Integer> tick, Runnable done) {
        cancelTasks();
        activeCountdownDone = done;
        BukkitRunnable runnable = new BukkitRunnable() {
            private int remaining = seconds;

            @Override
            public void run() {
                if (phase == GamePhase.IDLE) {
                    cancel();
                    return;
                }
                if (mediaPlaying) {
                    return;
                }
                if (remaining <= 0) {
                    cancel();
                    if (activeCountdownDone == done) {
                        activeCountdownDone = null;
                    }
                    done.run();
                    return;
                }
                tick.accept(remaining);
                remaining--;
            }
        };
        tasks.add(runnable.runTaskTimer(plugin, 0L, 20L));
    }

    private void scheduleLater(Runnable runnable, int seconds) {
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin, runnable, seconds * 20L));
    }

    private void scheduleNextTick(Runnable runnable) {
        tasks.add(Bukkit.getScheduler().runTask(plugin, runnable));
    }

    private void cancelTasks() {
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
    }

    private void ensureBossBar() {
        if (bossBar == null) {
            bossBar = Bukkit.createBossBar("IdealCup", BarColor.BLUE, BarStyle.SOLID);
        }
    }

    private void addAllPlayersToBossBar() {
        if (bossBar == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            bossBar.addPlayer(player);
        }
    }

    private void removeBossBar() {
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
    }

    private void updateBossBarTimer(int remaining, int total) {
        if (bossBar == null || total <= 0) {
            return;
        }
        bossBar.setProgress(Math.max(0.0D, Math.min(1.0D, (double) remaining / (double) total)));
    }

    private void playGlobalSound(String sound, float volume, float pitch) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            playPlayerSound(player, sound, volume, pitch);
        }
    }

    private void playPlayerSound(Player player, String sound, float volume, float pitch) {
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    private void playWinnerFanfare() {
        playGlobalSound("minecraft:ui.toast.challenge_complete", 1.0F, 1.0F);
        playGlobalSound("minecraft:entity.firework_rocket.twinkle", 0.9F, 1.2F);
        playGlobalSound("minecraft:entity.player.levelup", 0.85F, 1.35F);
    }

    public boolean handleAdminPlaybackControl(Player player, org.bukkit.event.block.Action action) {
        if (action != org.bukkit.event.block.Action.LEFT_CLICK_AIR && action != org.bukkit.event.block.Action.LEFT_CLICK_BLOCK) {
            return false;
        }
        if (!player.hasPermission("idealcup.admin") || !isRemoteItem(player.getInventory().getItemInMainHand())) {
            return false;
        }
        if (finalRankingShown) {
            player.sendActionBar(Component.text("결과 표시 중에는 영상 재생을 조작할 수 없습니다."));
            return true;
        }
        if (displayedCandidates.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "재생할 후보 화면이 없습니다.");
            return true;
        }
        Optional<Candidate> lookedCandidate = displayedCandidateFromLook(player);
        if (lookedCandidate.isEmpty()) {
            player.sendActionBar(Component.text("재생할 후보 영상을 바라보고 좌클릭하세요."));
            return true;
        }

        Candidate candidate = lookedCandidate.get();
        if (!candidate.manualPlayback()) {
            player.sendActionBar(Component.text("이 후보는 자동 재생됩니다."));
            return true;
        }
        if (mediaPlaying && candidate.equals(playingCandidate)) {
            resetMediaPlayback();
            player.sendActionBar(Component.text("후보 영상을 처음 프레임으로 되돌렸습니다."));
            return true;
        }

        startMediaPlayback(candidate);
        player.sendActionBar(Component.text(candidate.name() + " 영상을 재생합니다."));
        return true;
    }

    public boolean handleAdminSkip(Player player, org.bukkit.event.block.Action action) {
        if (action != org.bukkit.event.block.Action.RIGHT_CLICK_AIR && action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return false;
        }
        if (!player.hasPermission("idealcup.admin")) {
            return false;
        }
        ItemStack itemStack = player.getInventory().getItemInMainHand();
        if (!isRemoteItem(itemStack)) {
            return false;
        }
        if (!isRunning() && finalRankingAvailable) {
            showFinalRanking();
            return true;
        }
        if (!isRunning()) {
            return false;
        }
        if (mediaPlaying) {
            player.sendActionBar(Component.text("영상 재생 중에는 타이머를 넘길 수 없습니다."));
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - lastAdminSkipMillis < ADMIN_SKIP_DEBOUNCE_MILLIS) {
            return true;
        }
        if (activeCountdownDone == null) {
            player.sendMessage(ChatColor.YELLOW + "넘길 수 있는 타이머가 없습니다.");
            return true;
        }
        lastAdminSkipMillis = now;
        Runnable done = activeCountdownDone;
        activeCountdownDone = null;
        cancelTasks();
        done.run();
        return true;
    }

    private void recordRankingWin(Candidate candidate) {
        RankingEntry entry = rankingEntries.get(candidate.id());
        if (entry == null) {
            entry = new RankingEntry(candidate, candidate.id(), candidate.name(), 0, 0, currentRoundSize);
        }
        rankingEntries.put(candidate.id(), entry.withWin(currentRoundSize));
    }

    private void recordRankingLoss(Candidate candidate) {
        RankingEntry entry = rankingEntries.get(candidate.id());
        if (entry == null) {
            entry = new RankingEntry(candidate, candidate.id(), candidate.name(), 0, 0, currentRoundSize);
        }
        rankingEntries.put(candidate.id(), entry.withLoss(currentRoundSize));
    }

    private void recordMatchVotes(long leftVotes, long rightVotes) {
        if (currentMatch == null) {
            return;
        }
        addCandidateVotes(currentMatch.left(), leftVotes);
        addCandidateVotes(currentMatch.right(), rightVotes);
    }

    private void addCandidateVotes(Candidate candidate, long votes) {
        RankingEntry entry = rankingEntries.get(candidate.id());
        if (entry == null) {
            entry = new RankingEntry(candidate, candidate.id(), candidate.name(), 0, 0, currentRoundSize);
        }
        rankingEntries.put(candidate.id(), entry.withVotes(votes));
    }

    private void showFinalRanking() {
        if (finalRankingShown) {
            return;
        }
        resetMediaPlayback();
        finalRankingShown = true;
        List<RankingEntry> entries = sortedRankingEntries();
        List<CandidateDisplay.RankingRow> rows = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            RankingEntry entry = entries.get(index);
            rows.add(new CandidateDisplay.RankingRow(entry.candidate(), entry.name(), entry.resultLabel(index + 1), entry.votes()));
        }
        candidateDisplay.showRanking(cupName, rows);
        startFinalRankingAudio(rows);
        ensureBossBar();
        bossBar.setColor(BarColor.BLUE);
        bossBar.setProgress(1.0D);
        bossBar.setTitle("최종 결과 보기: /idealcup result");
    }

    private List<RankingEntry> sortedRankingEntries() {
        return rankingEntries.values().stream()
                .sorted(Comparator
                        .comparingInt(RankingEntry::wins).reversed()
                        .thenComparingInt(RankingEntry::lastRoundSize)
                        .thenComparing(Comparator.comparingLong(RankingEntry::votes).reversed())
                        .thenComparing(RankingEntry::name))
                .toList();
    }

    public int previewRankingScroll(List<Candidate> candidates, int limit) {
        resetMediaPlayback();
        resetRankingAudio();
        removeBossBar();
        displayedCandidates = List.of();

        int shown = Math.min(Math.max(0, limit), candidates.size());
        List<CandidateDisplay.RankingRow> rows = new ArrayList<>();
        for (int index = 0; index < shown; index++) {
            Candidate candidate = candidates.get(index);
            rows.add(new CandidateDisplay.RankingRow(
                    candidate,
                    candidate.name(),
                    previewRankingResultLabel(index + 1),
                    Math.max(0, shown - index)
            ));
        }
        candidateDisplay.showRanking("랭킹 스크롤 테스트", rows);
        startFinalRankingAudio(rows);
        return shown;
    }

    private String previewRankingResultLabel(int rank) {
        if (rank == 1) {
            return "우승";
        }
        if (rank == 2) {
            return "준우승";
        }
        return "테스트";
    }

    private Optional<Candidate> displayedCandidateFromLook(Player player) {
        Optional<VoteChoice> lookChoice = candidateDisplay.voteChoiceFromLook(player);
        if (lookChoice.isEmpty()) {
            return Optional.empty();
        }
        if (displayedCandidates.size() == 1) {
            return Optional.of(displayedCandidates.get(0));
        }
        if (lookChoice.get() == VoteChoice.LEFT && !displayedCandidates.isEmpty()) {
            return Optional.of(displayedCandidates.get(0));
        }
        if (lookChoice.get() == VoteChoice.RIGHT && displayedCandidates.size() > 1) {
            return Optional.of(displayedCandidates.get(1));
        }
        return Optional.empty();
    }

    private void startMediaPlayback(Candidate candidate) {
        resetMediaPlayback();
        mediaPlaying = true;
        playingCandidate = candidate;
        candidateDisplay.setPlaybackFrame(candidate, 0);
        if (candidate.soundKey() != null) {
            playGlobalSound(candidate.soundKey(), 1.0F, 1.0F);
        }
        long totalTicks = playbackTicks(candidate);
        updateMediaPlaybackBossBar(candidate, totalTicks, 0L);
        int[] frameIndex = {0};
        int[] remainingTicks = {frameTicks(candidate, frameIndex[0])};
        long[] elapsedTicks = {0L};
        mediaPlaybackTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!mediaPlaying) {
                return;
            }
            elapsedTicks[0]++;
            if (elapsedTicks[0] % 20L == 0L) {
                updateMediaPlaybackBossBar(candidate, totalTicks, elapsedTicks[0]);
            }
            if (elapsedTicks[0] >= totalTicks) {
                resetMediaPlayback();
                return;
            }
            if (maxFrameCount(candidate) <= 1) {
                return;
            }
            remainingTicks[0]--;
            if (remainingTicks[0] > 0) {
                return;
            }
            frameIndex[0]++;
            if (frameIndex[0] >= maxFrameCount(candidate)) {
                resetMediaPlayback();
                return;
            }
            candidateDisplay.setPlaybackFrame(candidate, frameIndex[0]);
            remainingTicks[0] = frameTicks(candidate, frameIndex[0]);
        }, 1L, 1L);
    }

    private void updateMediaPlaybackBossBar(Candidate candidate, long totalTicks, long elapsedTicks) {
        ensureBossBar();
        addAllPlayersToBossBar();
        long boundedTotalTicks = Math.max(1L, totalTicks);
        long boundedElapsedTicks = Math.max(0L, Math.min(elapsedTicks, boundedTotalTicks));
        long totalSeconds = Math.max(1L, (boundedTotalTicks + 19L) / 20L);
        bossBar.setColor(BarColor.PURPLE);
        bossBar.setProgress(Math.max(0.0D, Math.min(1.0D, (double) boundedElapsedTicks / (double) boundedTotalTicks)));
        bossBar.setTitle("영상 재생: " + candidate.name() + " (" + totalSeconds + "초)");
    }

    private void resetMediaPlayback() {
        if (mediaPlaybackTask != null) {
            mediaPlaybackTask.cancel();
            mediaPlaybackTask = null;
        }
        if (mediaPlaying && playingCandidate != null) {
            stopCandidateSound(playingCandidate);
            candidateDisplay.resetPlayback(playingCandidate);
        }
        mediaPlaying = false;
        playingCandidate = null;
    }

    private void startFinalRankingAudio(List<CandidateDisplay.RankingRow> rows) {
        resetRankingAudio();
        List<EndingBgmTrack> endingTracks = endingBgmTracks();
        if (!endingTracks.isEmpty()) {
            playEndingBgm(endingTracks, 0);
        }
    }

    private List<EndingBgmTrack> endingBgmTracks() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("ending-bgm");
        if (section == null || !section.getBoolean("enabled", true)) {
            return List.of();
        }

        List<EndingBgmTrack> tracks = new ArrayList<>();
        for (Map<?, ?> trackConfig : plugin.getConfig().getMapList("ending-bgm.tracks")) {
            Object soundValue = trackConfig.get("sound");
            Object secondsValue = trackConfig.get("seconds");
            if (!(soundValue instanceof String sound) || sound.isBlank()) {
                continue;
            }
            double seconds = secondsValue instanceof Number number ? number.doubleValue() : 0.0D;
            if (seconds <= 0.0D) {
                continue;
            }
            tracks.add(new EndingBgmTrack(normalizeSoundKey(sound), durationSecondsToTicks(seconds)));
        }
        if (!tracks.isEmpty()) {
            return tracks;
        }
        List<EndingBgmTrack> manifestTracks = endingBgmManifestTracks();
        return manifestTracks.isEmpty() ? autoDetectedEndingBgmTracks(section) : manifestTracks;
    }

    private List<EndingBgmTrack> endingBgmManifestTracks() {
        File packFile = new File(plugin.getDataFolder(), "resourcepack.zip");
        if (!packFile.isFile()) {
            return List.of();
        }

        try (ZipFile zipFile = new ZipFile(packFile, StandardCharsets.UTF_8)) {
            ZipEntry manifestEntry = zipFile.getEntry(ENDING_BGM_MANIFEST_PATH);
            if (manifestEntry == null) {
                return List.of();
            }

            YamlConfiguration manifest;
            try (var inputStream = zipFile.getInputStream(manifestEntry);
                 var reader = new java.io.InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                manifest = YamlConfiguration.loadConfiguration(reader);
            }

            List<EndingBgmTrack> tracks = new ArrayList<>();
            for (String modelName : manifest.getStringList("order")) {
                String sound = manifest.getString("tracks." + modelName + ".sound", "idealcup:" + modelName);
                long ticks = manifest.getLong("tracks." + modelName + ".ticks", 0L);
                if (ticks > 0L) {
                    tracks.add(new EndingBgmTrack(normalizeSoundKey(sound), ticks));
                }
            }
            return tracks;
        } catch (IOException exception) {
            plugin.getLogger().warning("엔딩 BGM 메타를 불러올 수 없습니다: " + exception.getMessage());
            return List.of();
        }
    }

    private List<EndingBgmTrack> autoDetectedEndingBgmTracks(ConfigurationSection section) {
        File packFile = new File(plugin.getDataFolder(), "resourcepack.zip");
        if (!packFile.isFile()) {
            return List.of();
        }

        List<DetectedEndingSound> detectedSounds = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(packFile, StandardCharsets.UTF_8)) {
            var entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Matcher matcher = ENDING_SOUND_ENTRY_PATTERN.matcher(entry.getName().replace('\\', '/'));
                if (!entry.isDirectory() && matcher.matches()) {
                    String suffix = matcher.group(1);
                    int order = suffix == null || suffix.isBlank() ? 1 : Integer.parseInt(suffix);
                    String modelName = order == 1 ? "ending_theme" : "ending_theme" + order;
                    detectedSounds.add(new DetectedEndingSound(modelName, order));
                }
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("엔딩 BGM을 불러올 수 없습니다: " + exception.getMessage());
            return List.of();
        }

        detectedSounds.sort(Comparator.comparingInt(DetectedEndingSound::order));
        List<EndingBgmTrack> tracks = new ArrayList<>();
        for (DetectedEndingSound sound : detectedSounds) {
            double seconds = section.getDouble("durations." + sound.modelName(), section.getDouble("default-seconds", 120.0D));
            if (seconds > 0.0D) {
                tracks.add(new EndingBgmTrack("idealcup:" + sound.modelName(), durationSecondsToTicks(seconds)));
            }
        }
        return tracks;
    }

    private void playEndingBgm(List<EndingBgmTrack> tracks, int index) {
        if (tracks.isEmpty()) {
            rankingAudioTask = null;
            rankingAudioSoundKey = null;
            return;
        }

        EndingBgmTrack track = tracks.get(index % tracks.size());
        rankingAudioSoundKey = track.soundKey();
        playGlobalSound(track.soundKey(), 1.0F, 1.0F);
        long nextDelayTicks = track.durationTicks() + endingBgmGapTicks();
        rankingAudioTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            stopGlobalSound(track.soundKey());
            playEndingBgm(tracks, index + 1);
        }, nextDelayTicks);
    }

    private void resetRankingAudio() {
        if (rankingAudioTask != null) {
            rankingAudioTask.cancel();
            rankingAudioTask = null;
        }
        if (rankingAudioSoundKey != null) {
            stopGlobalSound(rankingAudioSoundKey);
            rankingAudioSoundKey = null;
        }
    }

    private void stopCandidateSound(Candidate candidate) {
        if (candidate.soundKey() == null) {
            return;
        }
        stopGlobalSound(candidate.soundKey());
    }

    private void stopGlobalSound(String soundKey) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.stopSound(soundKey);
        }
    }

    private long playbackTicks(Candidate candidate) {
        long ticks = candidate.playbackTicks();
        return ticks > 0L ? ticks : DEFAULT_MEDIA_PLAYBACK_TICKS;
    }

    private long endingBgmGapTicks() {
        double seconds = plugin.getConfig().getDouble("ending-bgm.gap-seconds", 1.0D);
        return Math.max(0L, Math.round(Math.max(0.0D, seconds) * 20.0D));
    }

    private long durationSecondsToTicks(double seconds) {
        return Math.max(1L, Math.round(seconds * 20.0D));
    }

    private String normalizeSoundKey(String sound) {
        String trimmed = sound.trim();
        return trimmed.contains(":") ? trimmed : "idealcup:" + trimmed;
    }

    private int maxFrameCount(Candidate candidate) {
        return candidate.frameItemModels().size();
    }

    private int frameTicks(Candidate candidate, int frameIndex) {
        if (frameIndex < candidate.frameTicks().size()) {
            return candidate.frameTicks().get(frameIndex);
        }
        return 1;
    }

    public void giveMouseItem(Player player) {
        player.getInventory().addItem(createMouseItem());
    }

    public void giveSpyglassItem(Player player) {
        player.getInventory().addItem(createNamedItem(Material.SPYGLASS, "망원경"));
    }

    public void giveRemoteItem(Player player) {
        player.getInventory().addItem(createRemoteItem());
    }

    private void prepareGameInventory(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setItemInOffHand(null);
        player.getInventory().setItem(0, createMouseItem());
        if (player.hasPermission("idealcup.admin")) {
            player.getInventory().setItem(1, createRemoteItem());
        }
        player.getInventory().setItem(8, createNamedItem(Material.SPYGLASS, "망원경"));
    }

    private void ensureVotingTools(Player player) {
        if (!player.getInventory().containsAtLeast(createMouseItem(), 1)) {
            giveMouseItem(player);
        }
        if (!player.getInventory().containsAtLeast(createNamedItem(Material.SPYGLASS, "망원경"), 1)) {
            giveSpyglassItem(player);
        }
    }

    private ItemStack createMouseItem() {
        ItemStack itemStack = createNamedItem(Material.BLAZE_ROD, "마우스");
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.lore(List.of(Component.text("블레이즈 막대로 화면을 바라보고 우클릭하여 투표하세요")));
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    private ItemStack createRemoteItem() {
        return createNamedItem(Material.BREEZE_ROD, "관리자 전용 리모컨");
    }

    private ItemStack createNamedItem(Material material, String name) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.displayName(Component.text(name));
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    private boolean isMouseItem(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() != Material.BLAZE_ROD || !itemStack.hasItemMeta()) {
            return false;
        }
        return Component.text("마우스").equals(itemStack.getItemMeta().displayName());
    }

    private boolean isRemoteItem(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() != Material.BREEZE_ROD || !itemStack.hasItemMeta()) {
            return false;
        }
        return Component.text("관리자 전용 리모컨").equals(itemStack.getItemMeta().displayName());
    }

    public boolean isCurrentDebater(Player player) {
        return player != null && player.getUniqueId().equals(currentDebater);
    }

    private boolean isPowerOfTwo(int value) {
        return value > 0 && (value & (value - 1)) == 0;
    }

    private String phaseName() {
        return switch (phase) {
            case IDLE -> "대기 중";
            case PREVIEW -> "후보 표시 중";
            case VOTING -> "투표 중";
            case DEBATE -> "변론 중";
            case RESULT -> "결과 표시 중";
        };
    }

    private record RankingEntry(Candidate candidate, String id, String name, int wins, long votes, int lastRoundSize) {
        private RankingEntry withWin(int roundSize) {
            return new RankingEntry(candidate, id, name, wins + 1, votes, roundSize);
        }

        private RankingEntry withLoss(int roundSize) {
            return new RankingEntry(candidate, id, name, wins, votes, roundSize);
        }

        private RankingEntry withVotes(long addedVotes) {
            return new RankingEntry(candidate, id, name, wins, votes + Math.max(0L, addedVotes), lastRoundSize);
        }

        private String resultLabel(int rank) {
            if (rank == 1) {
                return "우승";
            }
            if (rank == 2) {
                return "준우승";
            }
            return lastRoundSize + "강 진출";
        }
    }

    private record EndingBgmTrack(String soundKey, long durationTicks) {
    }

    private record DetectedEndingSound(String modelName, int order) {
    }

}

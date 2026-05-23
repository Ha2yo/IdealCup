package org.ha2yo.idealCup.command;

import org.ha2yo.idealCup.game.IdealCupGame;
import org.ha2yo.idealCup.model.Candidate;
import org.ha2yo.idealCup.resource.CandidateRepository;
import org.ha2yo.idealCup.resource.ResourcePackBuilder;
import org.ha2yo.idealCup.resource.SourceFetcher;
import org.ha2yo.idealCup.visual.LocationConfig;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class IdealCupCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final CandidateRepository candidateRepository;
    private final IdealCupGame game;
    private final ResourcePackBuilder resourcePackBuilder;
    private final SourceFetcher sourceFetcher;
    private boolean buildingResourcePack;
    private boolean fetchingSources;

    public IdealCupCommand(JavaPlugin plugin, CandidateRepository candidateRepository, IdealCupGame game) {
        this.plugin = plugin;
        this.candidateRepository = candidateRepository;
        this.game = game;
        this.resourcePackBuilder = new ResourcePackBuilder(plugin);
        this.sourceFetcher = new SourceFetcher(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("result")) {
            showResult(sender);
            return true;
        }
        if (!sender.hasPermission("idealcup.admin")) {
            sender.sendMessage(ChatColor.RED + "권한이 없습니다.");
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);
        switch (subCommand) {
            case "start" -> start(sender, args);
            case "stop" -> game.stop(true);
            case "fetchsources" -> fetchSources(sender, args);
            case "buildpack" -> buildPack(sender, args);
            case "packready" -> packReady(sender, args);
            case "result" -> showResult(sender);
            case "rankingtest" -> rankingTest(sender, args);
            case "status" -> sender.sendMessage(ChatColor.AQUA + game.status());
            case "play" -> play(sender, args);
            case "set" -> setLocation(sender, args);
            default -> sendHelp(sender, label);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("idealcup.admin")) {
            if (args.length == 1) {
                return filter(List.of("result"), args[0]);
            }
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filter(Arrays.asList("start", "stop", "fetchsources", "buildpack", "status", "play", "set", "packready", "result", "rankingtest"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("play")) {
            candidateRepository.reload();
            return filter(candidateRepository.getCandidates().stream().map(Candidate::id).toList(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("fetchsources")) {
            return filter(Arrays.asList("force", "1", "2", "3", "4", "6", "8"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("fetchsources") && args[1].equalsIgnoreCase("force")) {
            return filter(Arrays.asList("1", "2", "3", "4", "6", "8"), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("buildpack")) {
            return filter(Arrays.asList("128", "192", "256", "384", "512"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("buildpack")) {
            return filter(ResourcePackBuilder.ALLOWED_ANIMATION_FPS.stream().map(String::valueOf).toList(), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("buildpack")) {
            return filter(Arrays.asList("1", "2", "3", "4", "6", "8"), args[3]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            return filter(Arrays.asList("2", "4", "8", "16", "32", "64", "128"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("rankingtest")) {
            return filter(Arrays.asList("4", "8", "16", "32", "64"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            return filter(Arrays.asList("pos1", "pos2", "debate-left", "debate-right", "lobby", "cinema", "debatetime", "votetime"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")
                && (args[1].equalsIgnoreCase("debatetime") || args[1].equalsIgnoreCase("votetime"))) {
            return filter(Arrays.asList("10", "15", "20", "30", "45", "60"), args[2]);
        }
        return Collections.emptyList();
    }

    private void start(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "이 명령어는 플레이어만 사용할 수 있습니다.");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "사용법: /idealcup start <후보수> <월드컵이름>");
            return;
        }
        try {
            int size = Integer.parseInt(args[1]);
            String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            plugin.reloadConfig();
            candidateRepository.reload();
            game.start(player, name, size);
        } catch (NumberFormatException exception) {
            sender.sendMessage(ChatColor.RED + "후보 수는 숫자로 입력해야 합니다.");
        }
    }

    private void buildPack(CommandSender sender, String[] args) {
        if (buildingResourcePack) {
            sender.sendMessage(ChatColor.RED + "리소스팩 변환이 이미 진행 중입니다.");
            return;
        }
        int maxImageSize = 256;
        int workerCount = 0;
        int animationFps = 5;
        if (args.length >= 2) {
            try {
                maxImageSize = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                sender.sendMessage(ChatColor.RED + "픽셀 수는 숫자로 입력해야 합니다.");
                return;
            }
            if (maxImageSize < 16 || maxImageSize > 2048) {
                sender.sendMessage(ChatColor.RED + "픽셀 수는 16 이상 2048 이하로 입력하세요.");
                return;
            }
        }
        if (args.length >= 3) {
            try {
                animationFps = Integer.parseInt(args[2]);
            } catch (NumberFormatException exception) {
                sender.sendMessage(ChatColor.RED + "프레임은 숫자로 입력해야 합니다.");
                return;
            }
            if (!ResourcePackBuilder.ALLOWED_ANIMATION_FPS.contains(animationFps)) {
                sender.sendMessage(ChatColor.RED + "프레임은 1 이상 20 이하로 입력하세요.");
                return;
            }
        }
        if (args.length >= 4) {
            try {
                workerCount = Integer.parseInt(args[3]);
            } catch (NumberFormatException exception) {
                sender.sendMessage(ChatColor.RED + "병렬 처리 개수는 숫자로 입력해야 합니다.");
                return;
            }
            if (workerCount < 1 || workerCount > 8) {
                sender.sendMessage(ChatColor.RED + "병렬 처리 개수는 1 이상 8 이하로 입력하세요.");
                return;
            }
        }
        int buildMaxImageSize = maxImageSize;
        int buildWorkerCount = workerCount;
        int buildAnimationFps = animationFps;
        String packDescription = args.length >= 5 ? String.join(" ", Arrays.copyOfRange(args, 4, args.length)) : null;
        buildingResourcePack = true;
        sender.sendMessage(ChatColor.YELLOW + "리소스팩 생성을 시작합니다. 최대 픽셀: " + buildMaxImageSize
                + ", 병렬 처리: " + (buildWorkerCount > 0 ? buildWorkerCount : "2")
                + ", FPS: " + buildAnimationFps);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ResourcePackBuilder.BuildResult result = resourcePackBuilder.build(
                        buildMaxImageSize,
                        buildWorkerCount,
                        buildAnimationFps,
                        packDescription,
                        message -> sendProgress(sender, message),
                        (message, warning) -> sendWarningProgress(sender, message)
                );
                Bukkit.getScheduler().runTask(plugin, () -> {
                    buildingResourcePack = false;
                    for (String warning : result.warnings()) {
                        sender.sendMessage(ChatColor.RED + warning);
                    }
                    if (!result.success()) {
                        sender.sendMessage(ChatColor.RED + "리소스팩 생성에 실패했습니다.");
                        return;
                    }
                    candidateRepository.reload();
                    sender.sendMessage(ChatColor.GREEN + "리소스팩을 생성했습니다. 생성된 후보: " + result.candidates() + "개");
                });
            } catch (RuntimeException exception) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    buildingResourcePack = false;
                    sender.sendMessage(ChatColor.RED + "리소스팩 생성 중 오류가 발생했습니다: " + exception.getMessage());
                });
            }
        });
    }

    private void fetchSources(CommandSender sender, String[] args) {
        if (fetchingSources) {
            sender.sendMessage(ChatColor.RED + "이미 URL 후보 준비가 진행 중입니다.");
            return;
        }
        boolean force = args.length >= 2 && args[1].equalsIgnoreCase("force");
        int workerCount = 2;
        int workerArgIndex = force ? 2 : 1;
        if (args.length > workerArgIndex) {
            try {
                workerCount = Integer.parseInt(args[workerArgIndex]);
            } catch (NumberFormatException exception) {
                sender.sendMessage(ChatColor.RED + "병렬 처리 개수는 숫자로 입력해야 합니다.");
                return;
            }
            if (workerCount < 1 || workerCount > 8) {
                sender.sendMessage(ChatColor.RED + "병렬 처리 개수는 1 이상 8 이하로 입력하세요.");
                return;
            }
        }
        int fetchWorkerCount = workerCount;
        fetchingSources = true;
        sender.sendMessage(ChatColor.YELLOW + "URL 후보 준비를 시작합니다. 병렬 처리: " + fetchWorkerCount + "개"
                + (force ? ", 기존 파일을 덮어씁니다." : ", 기존 파일은 유지합니다."));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            SourceFetcher.FetchResult result = sourceFetcher.fetch(force, fetchWorkerCount, message -> sendProgress(sender, message));
            Bukkit.getScheduler().runTask(plugin, () -> {
                fetchingSources = false;
                for (String warning : result.warnings()) {
                    sender.sendMessage(ChatColor.RED + warning);
                }
                if (!result.success()) {
                    sender.sendMessage(ChatColor.RED + "URL 후보 준비에 실패했습니다.");
                    return;
                }
                sender.sendMessage(ChatColor.GREEN + "URL 후보를 준비했습니다. 준비된 후보: " + result.candidates() + "개");
                sender.sendMessage(ChatColor.GRAY + "이제 /idealcup buildpack [픽셀수] [fps] [병렬개수]를 실행하세요.");
            });
        });
    }

    private void sendProgress(CommandSender sender, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.GRAY + message));
    }

    private void sendWarningProgress(CommandSender sender, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + message));
    }

    private void play(CommandSender sender, String[] args) {
        if (game.isRunning()) {
            sender.sendMessage(ChatColor.RED + "월드컵 진행 중에는 미리보기를 재생할 수 없습니다.");
            return;
        }
        if (!game.hasDisplayLocations()) {
            sender.sendMessage(ChatColor.RED + "먼저 표시 영역을 설정하세요: /idealcup set pos1, /idealcup set pos2");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "사용법: /idealcup play <번호>");
            return;
        }

        candidateRepository.reload();
        Candidate candidate = findCandidate(args[1]);
        if (candidate == null) {
            sender.sendMessage(ChatColor.RED + "후보를 찾을 수 없습니다: " + args[1]);
            return;
        }

        game.preview(candidate);
        sender.sendMessage(ChatColor.GREEN + "후보 " + candidate.id() + " 미리보기를 재생합니다.");
    }

    private void showResult(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "결과 창은 플레이어만 열 수 있습니다.");
            return;
        }
        game.openResultDialog(player);
    }

    private void rankingTest(CommandSender sender, String[] args) {
        if (game.isRunning()) {
            sender.sendMessage(ChatColor.RED + "월드컵 진행 중에는 랭킹 스크롤 테스트를 실행할 수 없습니다.");
            return;
        }
        if (!game.hasDisplayLocations()) {
            sender.sendMessage(ChatColor.RED + "먼저 표시 영역을 설정하세요: /idealcup set pos1, /idealcup set pos2");
            return;
        }

        int limit = 64;
        if (args.length >= 2) {
            try {
                limit = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                sender.sendMessage(ChatColor.RED + "개수는 숫자로 입력해야 합니다.");
                return;
            }
        }
        if (limit < 1) {
            sender.sendMessage(ChatColor.RED + "개수는 1개 이상이어야 합니다.");
            return;
        }

        candidateRepository.reload();
        List<Candidate> candidates = candidateRepository.getCandidates();
        if (candidates.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "표시할 후보가 없습니다. /idealcup buildpack을 먼저 실행하세요.");
            return;
        }

        int shown = game.previewRankingScroll(candidates, limit);
        sender.sendMessage(ChatColor.GREEN + "랭킹 스크롤 테스트를 표시합니다: " + shown + "개");
    }

    private Candidate findCandidate(String input) {
        String id = input.trim();
        for (Candidate candidate : candidateRepository.getCandidates()) {
            if (candidate.id().equalsIgnoreCase(id)) {
                return candidate;
            }
        }
        if (id.matches("\\d+")) {
            String paddedId = String.format("%02d", Integer.parseInt(id));
            for (Candidate candidate : candidateRepository.getCandidates()) {
                if (candidate.id().equalsIgnoreCase(paddedId)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private void packReady(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "사용법: /idealcup packready <player>");
            return;
        }
        Player player = Bukkit.getPlayerExact(args[1]);
        if (player == null) {
            sender.sendMessage(ChatColor.RED + "플레이어를 찾을 수 없습니다: " + args[1]);
            return;
        }
        game.handleResourcePackReady(player);
    }

    private void setLocation(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("debatetime")) {
            setDebateTime(sender, args);
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("votetime")) {
            setVoteTime(sender, args);
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "표시 위치는 플레이어만 설정할 수 있습니다.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "사용법: /idealcup set <pos1|pos2|debate-left|debate-right|lobby|cinema|debatetime|votetime>");
            return;
        }
        String position = args[1].toLowerCase(Locale.ROOT);
        if (!position.equals("pos1") && !position.equals("pos2") && !position.equals("debate-left") && !position.equals("debate-right") && !position.equals("lobby") && !position.equals("cinema")) {
            sender.sendMessage(ChatColor.RED + "알 수 없는 설정입니다. 위치는 pos1, pos2, debate-left, debate-right, lobby, cinema 중 하나로 입력하세요.");
            sender.sendMessage(ChatColor.RED + "시간 설정은 /idealcup set votetime <초> 또는 /idealcup set debatetime <초>를 사용하세요.");
            return;
        }
        LocationConfig.write(plugin, "locations." + position, player.getLocation());
        sender.sendMessage(ChatColor.GREEN + position + " 표시 위치를 저장했습니다.");
    }

    private void setDebateTime(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "사용법: /idealcup set debatetime <초>");
            return;
        }
        try {
            int seconds = Integer.parseInt(args[2]);
            if (seconds < 1) {
                sender.sendMessage(ChatColor.RED + "변론 시간은 1초 이상이어야 합니다.");
                return;
            }
            plugin.getConfig().set("timing.debate-seconds", seconds);
            plugin.saveConfig();
            sender.sendMessage(ChatColor.GREEN + "변론 시간을 " + seconds + "초로 설정했습니다.");
        } catch (NumberFormatException exception) {
            sender.sendMessage(ChatColor.RED + "변론 시간은 숫자로 입력해야 합니다.");
        }
    }

    private void setVoteTime(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "사용법: /idealcup set votetime <초>");
            return;
        }
        try {
            int seconds = Integer.parseInt(args[2]);
            if (seconds < 1) {
                sender.sendMessage(ChatColor.RED + "투표 시간은 1초 이상이어야 합니다.");
                return;
            }
            plugin.getConfig().set("timing.vote-seconds", seconds);
            plugin.saveConfig();
            sender.sendMessage(ChatColor.GREEN + "투표 시간을 " + seconds + "초로 설정했습니다.");
        } catch (NumberFormatException exception) {
            sender.sendMessage(ChatColor.RED + "투표 시간은 숫자로 입력해야 합니다.");
        }
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.AQUA + "/" + label + " start <후보수> <월드컵이름>");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " stop - 월드컵 중지");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " fetchsources [force] [병렬개수] - URL 후보 준비");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " buildpack [픽셀수] [fps] [병렬개수] [팩이름] - 리소스팩 생성");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " status - 진행 상태 확인");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " play <번호> - 후보 미리보기 재생");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " result - 최종 결과 창 열기");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " rankingtest [개수] - 랭킹 스크롤 화면 테스트");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " set <pos1|pos2|debate-left|debate-right|lobby|cinema> - 표시/변론/이동 위치 설정");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " set debatetime <초> - 변론 시간 설정");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " set votetime <초> - 투표 시간 설정");
    }

    private List<String> filter(List<String> values, String prefix) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                result.add(value);
            }
        }
        return result;
    }
}

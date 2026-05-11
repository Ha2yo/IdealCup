package org.ha2yo.idealCup.command;

import org.ha2yo.idealCup.game.IdealCupGame;
import org.ha2yo.idealCup.game.VoteChoice;
import org.ha2yo.idealCup.model.Candidate;
import org.ha2yo.idealCup.resource.CandidateRepository;
import org.ha2yo.idealCup.resource.ResourcePackBuilder;
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

    public IdealCupCommand(JavaPlugin plugin, CandidateRepository candidateRepository, IdealCupGame game) {
        this.plugin = plugin;
        this.candidateRepository = candidateRepository;
        this.game = game;
        this.resourcePackBuilder = new ResourcePackBuilder(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
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
            case "buildpack" -> buildPack(sender, args);
            case "unpack" -> unpack(sender, args);
            case "status" -> sender.sendMessage(ChatColor.AQUA + game.status());
            case "forcewin" -> forceWin(sender, args);
            case "play" -> play(sender, args);
            case "set" -> setLocation(sender, args);
            default -> sendHelp(sender, label);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("idealcup.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filter(Arrays.asList("start", "stop", "buildpack", "unpack", "status", "forcewin", "play", "set"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("play")) {
            candidateRepository.reload();
            return filter(candidateRepository.getCandidates().stream().map(Candidate::id).toList(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("unpack")) {
            return filter(List.of("force"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("buildpack")) {
            return filter(Arrays.asList("64", "96", "128", "192", "256", "512"), args[1]);
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("start")) {
            return filter(Arrays.asList("2", "4", "8", "16", "32", "64", "128"), args[args.length - 1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("forcewin")) {
            return filter(Arrays.asList("left", "right"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            return filter(Arrays.asList("pos1", "pos2", "debate-left", "debate-right", "debatetime", "votetime"), args[1]);
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
            sender.sendMessage(ChatColor.RED + "사용법: /idealcup start <월드컵이름> <참가자수>");
            return;
        }
        try {
            int size = Integer.parseInt(args[args.length - 1]);
            String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1));
            plugin.reloadConfig();
            candidateRepository.reload();
            game.start(player, name, size);
        } catch (NumberFormatException exception) {
            sender.sendMessage(ChatColor.RED + "참가자 수는 숫자로 입력해야 합니다.");
        }
    }

    private void buildPack(CommandSender sender, String[] args) {
        int maxImageSize = 128;
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
        int buildMaxImageSize = maxImageSize;
        sender.sendMessage(ChatColor.YELLOW + "리소스팩 생성을 시작합니다. 최대 픽셀: " + buildMaxImageSize);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ResourcePackBuilder.BuildResult result = resourcePackBuilder.build(
                    buildMaxImageSize,
                    message -> sendProgress(sender, message),
                    (message, warning) -> sendWarningProgress(sender, message)
            );
            Bukkit.getScheduler().runTask(plugin, () -> {
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
        });
    }

    private void unpack(CommandSender sender, String[] args) {
        boolean force = args.length >= 2 && args[1].equalsIgnoreCase("force");
        sender.sendMessage(ChatColor.YELLOW + "리소스팩 원본 복원을 시작합니다.");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ResourcePackBuilder.BuildResult result = resourcePackBuilder.unpack(force, message -> sendProgress(sender, message));
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (String warning : result.warnings()) {
                    sender.sendMessage(ChatColor.RED + warning);
                }
                if (!result.success()) {
                    sender.sendMessage(ChatColor.RED + "리소스팩 원본 복원에 실패했습니다.");
                    return;
                }
                sender.sendMessage(ChatColor.GREEN + "리소스팩 내용을 복원했습니다. 복원된 후보: " + result.candidates() + "개");
            });
        });
    }

    private void sendProgress(CommandSender sender, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.GRAY + message));
    }

    private void sendWarningProgress(CommandSender sender, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + message));
    }

    private void forceWin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "이 명령어는 플레이어만 사용할 수 있습니다.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "사용법: /idealcup forcewin <left(왼쪽)|right(오른쪽)>");
            return;
        }
        String side = args[1].toLowerCase(Locale.ROOT);
        if (side.equals("left")) {
            game.forceWin(VoteChoice.LEFT, player);
        } else if (side.equals("right")) {
            game.forceWin(VoteChoice.RIGHT, player);
        } else {
            sender.sendMessage(ChatColor.RED + "방향은 left(왼쪽) 또는 right(오른쪽)로 입력해야 합니다.");
        }
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
            sender.sendMessage(ChatColor.RED + "사용법: /idealcup set <pos1|pos2|debate-left|debate-right|debatetime|votetime>");
            return;
        }
        String position = args[1].toLowerCase(Locale.ROOT);
        if (!position.equals("pos1") && !position.equals("pos2") && !position.equals("debate-left") && !position.equals("debate-right")) {
            sender.sendMessage(ChatColor.RED + "알 수 없는 설정입니다. 위치는 pos1, pos2, debate-left, debate-right 중 하나로 입력하세요.");
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
        sender.sendMessage(ChatColor.AQUA + "/" + label + " start <월드컵이름> <참가자수>");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " stop - 월드컵 중지");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " buildpack [픽셀수] - 리소스팩 생성 (기본 128)");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " unpack [force] - resourcepack.zip에서 복원본 추출");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " status - 진행 상태 확인");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " forcewin <left(왼쪽)|right(오른쪽)> - 강제 승리");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " play <번호> - 후보 미리보기 재생");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " set <pos1|pos2|debate-left|debate-right> - 표시/변론 위치 설정");
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

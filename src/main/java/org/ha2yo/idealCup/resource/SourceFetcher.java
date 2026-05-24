package org.ha2yo.idealCup.resource;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class SourceFetcher {
    private static final String SOURCE_FOLDER = "resourcepack-src";
    private static final String MANIFEST_PATH = "candidates.yml";
    private static final String YT_DLP_DOWNLOAD_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
    private static final String FFMPEG_DOWNLOAD_URL = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";

    private final JavaPlugin plugin;

    public SourceFetcher(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public FetchResult fetch(boolean force, Consumer<String> progress) {
        return fetch(force, 2, progress);
    }

    public FetchResult fetch(boolean force, int workerCount, Consumer<String> progress) {
        List<String> warnings = new ArrayList<>();
        File sourceFolder = new File(plugin.getDataFolder(), SOURCE_FOLDER);
        File imageFolder = new File(sourceFolder, "images");
        File manifestFile = new File(sourceFolder, MANIFEST_PATH);
        YamlConfiguration sourceConfig = loadSourceConfig(manifestFile, warnings);
        if (sourceConfig == null) {
            return new FetchResult(false, 0, warnings);
        }

        ConfigurationSection candidatesSection = sourceConfig.getConfigurationSection("candidates");
        if (candidatesSection == null) {
            warnings.add(SOURCE_FOLDER + "/" + MANIFEST_PATH + "에 candidates 섹션이 없습니다.");
            return new FetchResult(false, 0, warnings);
        }

        YamlConfiguration manifest = YamlConfiguration.loadConfiguration(manifestFile);
        List<SourceEntry> entries = new ArrayList<>();
        int prepared = 0;

        try {
            Files.createDirectories(imageFolder.toPath());
            for (String id : candidatesSection.getKeys(false)) {
                String basePath = "candidates." + id + ".";
                String name = sourceConfig.getString(basePath + "name", id);
                String url = sourceConfig.getString(basePath + "url", "");
                double startSeconds = parseSeconds(sourceConfig.get(basePath + "start"));
                double durationSeconds = parseSeconds(sourceConfig.get(basePath + "duration"));

                if (url == null || url.isBlank()) {
                    manifest.set("candidates." + id + ".name", name == null || name.isBlank() ? id : name);
                    continue;
                }
                if (durationSeconds <= 0.0D) {
                    warnings.add("후보 " + id + " 제외: duration은 0보다 커야 합니다.");
                    continue;
                }

                File outputFile = new File(imageFolder, id + ".mp4");
                manifest.set("candidates." + id + ".name", name == null || name.isBlank() ? id : name);
                if (outputFile.isFile() && !force) {
                    progress.accept("기존 파일 유지: " + outputFile.getName());
                    prepared++;
                    continue;
                }
                entries.add(new SourceEntry(id, name == null || name.isBlank() ? id : name, url, startSeconds, durationSeconds, outputFile));
            }
            prepared += downloadEntries(entries, force, workerCount, progress, warnings);
            Files.createDirectories(manifestFile.toPath().getParent());
            manifest.save(manifestFile);
            return new FetchResult(prepared > 0, prepared, warnings);
        } catch (Exception exception) {
            warnings.add("URL 후보 준비 중 오류가 발생했습니다: " + exception.getMessage());
            return new FetchResult(false, prepared, warnings);
        }
    }

    private int downloadEntries(List<SourceEntry> entries, boolean force, int workerCount, Consumer<String> progress, List<String> warnings) throws InterruptedException {
        if (entries.isEmpty()) {
            return 0;
        }

        int threads = Math.max(1, Math.min(workerCount, Math.min(entries.size(), 8)));
        progress.accept("URL 후보 병렬 준비: " + entries.size() + "개, 동시 처리 " + threads + "개" + (force ? " (강제 덮어쓰기)" : ""));

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        AtomicInteger started = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger succeeded = new AtomicInteger();
        ConcurrentLinkedQueue<String> workerWarnings = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (SourceEntry entry : entries) {
                futures.add(executor.submit(() -> {
                    int current = started.incrementAndGet();
                    progress.accept("URL 후보 다운로드 중: " + current + "/" + entries.size() + " (" + entry.id() + " " + entry.name() + ")");
                    try {
                        downloadClip(entry.id(), entry.url(), entry.startSeconds(), entry.durationSeconds(), entry.outputFile());
                        succeeded.incrementAndGet();
                        int done = completed.incrementAndGet();
                        progress.accept("URL 후보 완료: " + done + "/" + entries.size() + " (" + entry.id() + ")");
                    } catch (IOException exception) {
                        int done = completed.incrementAndGet();
                        workerWarnings.add("후보 " + entry.id() + " 다운로드 실패: " + exception.getMessage());
                        progress.accept("URL 후보 실패: " + done + "/" + entries.size() + " (" + entry.id() + ")");
                    }
                }));
            }

            executor.shutdown();
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    workerWarnings.add("URL 후보 작업 실패: " + (cause == null ? exception.getMessage() : cause.getMessage()));
                }
            }
        } finally {
            executor.shutdownNow();
        }
        warnings.addAll(workerWarnings);
        return succeeded.get();
    }

    private YamlConfiguration loadSourceConfig(File manifestFile, List<String> warnings) {
        if (manifestFile.isFile()) {
            YamlConfiguration manifest = YamlConfiguration.loadConfiguration(manifestFile);
            if (hasUrlSources(manifest)) {
                return manifest;
            }
        }

        warnings.add("plugins/IdealCup/" + SOURCE_FOLDER + "/" + MANIFEST_PATH + "에 URL이 있는 후보가 없습니다.");
        warnings.add("URL 후보에 url, start, duration을 입력한 뒤 다시 실행하세요.");
        return null;
    }

    private boolean hasUrlSources(YamlConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("candidates");
        if (section == null) {
            return false;
        }
        for (String id : section.getKeys(false)) {
            String url = config.getString("candidates." + id + ".url", "");
            if (url != null && !url.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private void downloadClip(String id, String url, double startSeconds, double durationSeconds, File outputFile) throws IOException {
        Path tempFolder = Files.createTempDirectory(plugin.getDataFolder().toPath(), "idealcup-source-");
        try {
            String ytDlp = resolveYtDlp();
            String start = formatSeconds(startSeconds);
            String end = formatSeconds(startSeconds + durationSeconds);
            List<String> command = List.of(
                    ytDlp,
                    "--no-playlist",
                    "--concurrent-fragments",
                    "8",
                    "-f",
                    "bv*[height<=1080]+ba/b[height<=1080]/b[height<=1080]/bv*+ba/b",
                    "--download-sections",
                    "*" + start + "-" + end,
                    "--force-keyframes-at-cuts",
                    "--merge-output-format",
                    "mp4",
                    "-o",
                    tempFolder.resolve(id + ".%(ext)s").toString(),
                    url
            );
            runCommand(command, "yt-dlp 다운로드 실패");

            File downloadedFile = newestRegularFile(tempFolder);
            if (downloadedFile == null) {
                throw new IOException("다운로드된 파일을 찾지 못했습니다.");
            }

            Files.createDirectories(outputFile.toPath().getParent());
            transcodeClip(downloadedFile, outputFile);
        } finally {
            deleteFolder(tempFolder);
        }
    }

    private void transcodeClip(File inputFile, File outputFile) throws IOException {
        String ffmpeg = resolveFfmpeg();
        List<String> command = List.of(
                ffmpeg,
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-i",
                inputFile.getAbsolutePath(),
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-crf",
                "14",
                "-c:a",
                "aac",
                "-b:a",
                "192k",
                outputFile.getAbsolutePath()
        );
        runCommand(command, "ffmpeg 클립 변환 실패");
    }

    private double parseSeconds(Object value) {
        if (value instanceof Number number) {
            return Math.max(0.0D, number.doubleValue());
        }
        if (!(value instanceof String text) || text.isBlank()) {
            return 0.0D;
        }

        String trimmed = text.trim();
        if (!trimmed.contains(":")) {
            try {
                return Math.max(0.0D, Double.parseDouble(trimmed));
            } catch (NumberFormatException exception) {
                return 0.0D;
            }
        }

        String[] parts = trimmed.split(":");
        double seconds = 0.0D;
        try {
            for (String part : parts) {
                seconds = seconds * 60.0D + Double.parseDouble(part);
            }
            return Math.max(0.0D, seconds);
        } catch (NumberFormatException exception) {
            return 0.0D;
        }
    }

    private String formatSeconds(double seconds) {
        long totalMillis = Math.max(0L, Math.round(seconds * 1000.0D));
        long millis = totalMillis % 1000L;
        long totalSeconds = totalMillis / 1000L;
        long second = totalSeconds % 60L;
        long totalMinutes = totalSeconds / 60L;
        long minute = totalMinutes % 60L;
        long hour = totalMinutes / 60L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", hour, minute, second, millis);
    }

    private File newestRegularFile(Path folder) throws IOException {
        try (var paths = Files.list(folder)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .max(Comparator.comparingLong(File::lastModified))
                    .orElse(null);
        }
    }

    private void runCommand(List<String> command, String failureMessage) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        String output;
        try (var inputStream = process.getInputStream()) {
            output = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException(failureMessage + (output.isBlank() ? "" : ": " + output));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(failureMessage + ": 작업이 중단되었습니다.", exception);
        }
    }

    private synchronized String resolveYtDlp() throws IOException {
        File toolFolder = new File(plugin.getDataFolder(), "tools");
        File ytDlpFile = new File(toolFolder, "yt-dlp.exe");
        if (ytDlpFile.isFile()) {
            return ytDlpFile.getAbsolutePath();
        }
        if (isCommandUsable("yt-dlp")) {
            return "yt-dlp";
        }

        Files.createDirectories(toolFolder.toPath());
        plugin.getLogger().info("yt-dlp.exe가 없어 자동으로 다운로드합니다.");
        try (InputStream inputStream = URI.create(YT_DLP_DOWNLOAD_URL).toURL().openStream()) {
            Files.copy(inputStream, ytDlpFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        if (ytDlpFile.isFile()) {
            plugin.getLogger().info("yt-dlp.exe 다운로드 완료: " + ytDlpFile.getPath());
            return ytDlpFile.getAbsolutePath();
        }
        throw new IOException("yt-dlp.exe를 찾을 수 없습니다. plugins/IdealCup/tools/yt-dlp.exe 파일을 넣거나 yt-dlp를 PATH에 등록하세요.");
    }

    private synchronized String resolveFfmpeg() throws IOException {
        File toolFolder = new File(plugin.getDataFolder(), "tools");
        File ffmpegFile = new File(toolFolder, "ffmpeg.exe");
        if (ffmpegFile.isFile()) {
            return ffmpegFile.getAbsolutePath();
        }
        if (isCommandUsable("ffmpeg")) {
            return "ffmpeg";
        }

        downloadFfmpeg(ffmpegFile);
        if (ffmpegFile.isFile()) {
            return ffmpegFile.getAbsolutePath();
        }
        throw new IOException("ffmpeg.exe를 찾을 수 없습니다. plugins/IdealCup/tools/ffmpeg.exe 파일을 넣거나 ffmpeg를 PATH에 등록하세요.");
    }

    private void downloadFfmpeg(File ffmpegFile) throws IOException {
        Files.createDirectories(ffmpegFile.toPath().getParent());
        plugin.getLogger().info("ffmpeg.exe가 없어 자동으로 다운로드합니다.");
        URI uri = URI.create(FFMPEG_DOWNLOAD_URL);
        try (InputStream inputStream = uri.toURL().openStream();
             ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (!entry.isDirectory() && name.endsWith("/bin/ffmpeg.exe")) {
                    Files.copy(zipInputStream, ffmpegFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    plugin.getLogger().info("ffmpeg.exe 다운로드 완료: " + ffmpegFile.getPath());
                    return;
                }
            }
        }
        throw new IOException("다운로드한 ffmpeg 압축 파일에서 bin/ffmpeg.exe를 찾지 못했습니다.");
    }

    private boolean isCommandUsable(String command) {
        try {
            Process process = new ProcessBuilder(command, "-version").redirectErrorStream(true).start();
            try (var inputStream = process.getInputStream()) {
                inputStream.readAllBytes();
            }
            return process.waitFor() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void deleteFolder(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (var paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    public record FetchResult(boolean success, int candidates, List<String> warnings) {
    }

    private record SourceEntry(String id, String name, String url, double startSeconds, double durationSeconds, File outputFile) {
    }

}

package org.ha2yo.idealCup.resource;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.spi.IIORegistry;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class ResourcePackBuilder {
    private static final String SOURCE_FOLDER = "resourcepack-src";
    private static final String BUILD_FOLDER = "resourcepack-build";
    private static final String PACK_FILE = "resourcepack.zip";
    private static final String SPLIT_PACK_FOLDER = "resourcepack-parts";
    private static final String BASE_SPLIT_PACK_FILE = "idealcup-base.zip";
    private static final String MEDIA_SPLIT_PACK_PREFIX = "idealcup-media-";
    private static final long SPLIT_PACK_MAX_BYTES = 180L * 1024L * 1024L;
    private static final String MANIFEST_PATH = "candidates.yml";
    private static final String ENDING_BGM_MANIFEST_PATH = "assets/idealcup/ending_bgm.yml";
    private static final List<String> VIDEO_EXTENSIONS = List.of("mp4", "mkv", "mov");
    private static final List<String> SOURCE_EXTENSIONS = List.of("png", "jpg", "jpeg", "webp", "gif", "mp4", "mkv", "mov");
    public static final List<Integer> ALLOWED_ANIMATION_FPS = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20);
    private static final int DEFAULT_IMAGE_SIZE = 256;
    private static final int DEFAULT_ANIMATION_FPS = 5;
    private static final int PACK_ICON_SIZE = 128;
    private static final int MAX_GIF_FRAMES = 80;
    private static final String FFMPEG_DOWNLOAD_URL = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";
    private static final Pattern ANIMATION_SIZE_PATTERN = Pattern.compile("\"(width|height)\"\\s*:\\s*(\\d+)");
    private static final Pattern ENDING_THEME_PATTERN = Pattern.compile("ending_theme(\\d*)\\.(ogg|mp3|wav|flac|m4a|aac)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUDIO_DURATION_PATTERN = Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");

    private final JavaPlugin plugin;
    private int maxImageSize = DEFAULT_IMAGE_SIZE;
    private int animationFps = DEFAULT_ANIMATION_FPS;

    public ResourcePackBuilder(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public BuildResult build() {
        return build(DEFAULT_IMAGE_SIZE, message -> {
        }, (progressMessage, warning) -> {
        });
    }

    public BuildResult build(Consumer<String> progress) {
        return build(DEFAULT_IMAGE_SIZE, progress, (progressMessage, warning) -> {
        });
    }

    public BuildResult build(Consumer<String> progress, BiConsumer<String, String> warningProgress) {
        return build(DEFAULT_IMAGE_SIZE, progress, warningProgress);
    }

    public BuildResult build(int maxImageSize, Consumer<String> progress, BiConsumer<String, String> warningProgress) {
        return build(maxImageSize, 0, progress, warningProgress);
    }

    public BuildResult build(int maxImageSize, int workerCount, Consumer<String> progress, BiConsumer<String, String> warningProgress) {
        return build(maxImageSize, workerCount, DEFAULT_ANIMATION_FPS, progress, warningProgress);
    }

    public BuildResult build(int maxImageSize, int workerCount, int animationFps, Consumer<String> progress, BiConsumer<String, String> warningProgress) {
        return build(maxImageSize, workerCount, animationFps, null, progress, warningProgress);
    }

    public BuildResult build(int maxImageSize, int workerCount, int animationFps, String packDescription, Consumer<String> progress, BiConsumer<String, String> warningProgress) {
        this.maxImageSize = Math.max(1, maxImageSize);
        this.animationFps = ALLOWED_ANIMATION_FPS.contains(animationFps) ? animationFps : DEFAULT_ANIMATION_FPS;
        List<String> warnings = new ArrayList<>();
        File sourceFolder = new File(plugin.getDataFolder(), SOURCE_FOLDER);
        if (!sourceFolder.isDirectory()) {
            warnings.add("plugins/IdealCup/resourcepack-src 폴더가 없습니다.");
            return new BuildResult(false, 0, warnings);
        }

        File manifestFile = new File(sourceFolder, MANIFEST_PATH);
        if (!manifestFile.isFile()) {
            warnings.add("resourcepack-src/" + MANIFEST_PATH + " 파일이 없습니다.");
            return new BuildResult(false, 0, warnings);
        }

        YamlConfiguration manifest = YamlConfiguration.loadConfiguration(manifestFile);
        ConfigurationSection section = manifest.getConfigurationSection("candidates");
        if (section == null) {
            warnings.add("candidates.yml에 candidates 섹션이 없습니다.");
            return new BuildResult(false, 0, warnings);
        }
        List<String> candidateIds = new ArrayList<>(section.getKeys(false));
        int totalCandidates = candidateIds.size();
        progress.accept("후보 미디어 " + totalCandidates + "개를 준비합니다.");

        File buildFolder = new File(plugin.getDataFolder(), BUILD_FOLDER);
        try {
            registerImageReaders();
            recreateFolder(buildFolder.toPath());
            writePackMeta(sourceFolder, buildFolder, packDescription);
            copyPackIcon(sourceFolder, buildFolder);
            Files.copy(manifestFile.toPath(), new File(buildFolder, MANIFEST_PATH).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            int builtCandidates = 0;
            List<String> soundModels = new ArrayList<>();
            AtomicInteger processedCandidates = new AtomicInteger();
            ExecutorService executor = Executors.newFixedThreadPool(buildWorkerCount(totalCandidates, workerCount));
            List<Future<CandidateBuildResult>> futures = new ArrayList<>();
            try {
                for (String id : candidateIds) {
                    futures.add(executor.submit(() -> processCandidate(sourceFolder, buildFolder, id, totalCandidates, processedCandidates, progress, warningProgress)));
                }
                for (Future<CandidateBuildResult> future : futures) {
                    CandidateBuildResult result = future.get();
                    if (result.warning() != null) {
                        warnings.add(result.warning());
                    }
                    if (result.built()) {
                        builtCandidates++;
                        manifest.set("candidates." + result.id() + ".manual-playback", result.manualPlayback());
                    }
                    if (result.soundModel() != null) {
                        soundModels.add(result.soundModel());
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                warnings.add("미디어 변환이 중단되었습니다.");
                cleanupBuildFolder(buildFolder.toPath(), warnings);
                return new BuildResult(false, 0, warnings);
            } catch (ExecutionException exception) {
                warnings.add("미디어 변환 중 오류가 발생했습니다: " + exception.getCause().getMessage());
                cleanupBuildFolder(buildFolder.toPath(), warnings);
                return new BuildResult(false, 0, warnings);
            } finally {
                executor.shutdownNow();
            }

            if (builtCandidates == 0) {
                warnings.add("생성된 후보 모델이 없습니다.");
                cleanupBuildFolder(buildFolder.toPath(), warnings);
                return new BuildResult(false, 0, warnings);
            }

            List<EndingSound> endingSounds = copyEndingSounds(sourceFolder, buildFolder);
            soundModels.addAll(endingSounds.stream().map(EndingSound::modelName).toList());
            writeEndingBgmManifest(buildFolder, endingSounds);
            writeSoundsJson(buildFolder, soundModels);
            writePackMeta(sourceFolder, buildFolder, packDescription);
            copyPackIcon(sourceFolder, buildFolder);
            manifest.save(new File(buildFolder, MANIFEST_PATH));
            zipFolder(buildFolder.toPath(), resourcePackFile().toPath());
            progress.accept("resourcepack.zip 저장 완료");
            int splitPackCount = zipSplitResourcePacks(buildFolder.toPath(), candidateIds, warnings);
            progress.accept("resourcepack-parts 저장 완료: " + splitPackCount + "개 (팩당 최대 180MB)");
            cleanupBuildFolder(buildFolder.toPath(), warnings);
            return new BuildResult(true, builtCandidates, warnings);
        } catch (IOException exception) {
            cleanupBuildFolder(buildFolder.toPath(), warnings);
            warnings.add("resourcepack.zip을 쓸 수 없습니다: " + exception.getMessage());
            return new BuildResult(false, 0, warnings);
        }
    }

    private CandidateBuildResult processCandidate(
            File sourceFolder,
            File buildFolder,
            String id,
            int totalCandidates,
            AtomicInteger processedCandidates,
            Consumer<String> progress,
            BiConsumer<String, String> warningProgress
    ) {
        String imagePath = imagePath(id);
        File imageFile = sourceImageFile(sourceFolder, id);
        int processed = processedCandidates.incrementAndGet();
        String progressMessage = "미디어 변환 중: " + processed + "/" + totalCandidates + " (" + id + ")";
        if (!imageFile.isFile()) {
            String warning = "후보 " + id + " 제외: images/" + id + ".png, .jpg, .jpeg, .webp, .gif, .mp4, .mkv, .mov 파일이 없습니다.";
            warningProgress.accept(progressMessage, warning);
            return new CandidateBuildResult(id, false, false, null, warning);
        }

        try {
            if (isVideoFile(imageFile)) {
                writeVideoCandidateTexture(sourceFolder, buildFolder, id, imageFile, imagePath);
                writeCandidateModels(buildFolder, id, true);
                String soundModel = extractCandidateSound(imageFile, new File(buildFolder, "assets/idealcup/sounds/" + modelName(id) + ".ogg")) ? modelName(id) : null;
                progress.accept(progressMessage);
                return new CandidateBuildResult(id, true, true, soundModel, null);
            }

            if (isAnimatedPngSource(imageFile)) {
                BufferedImage image = ImageIO.read(imageFile);
                if (image == null) {
                    String warning = "후보 " + id + " 제외: 올바른 이미지가 아닙니다. " + sourceImagePath(sourceFolder, imageFile);
                    warningProgress.accept(progressMessage, warning);
                    return new CandidateBuildResult(id, false, false, null, warning);
                }
                writeAnimatedPngSource(buildFolder, id, imageFile);
                writeCandidateModels(buildFolder, id, true);
                progress.accept(progressMessage);
                return new CandidateBuildResult(id, true, false, null, null);
            }

            BufferedImage image = ImageIO.read(imageFile);
            if (image == null) {
                String warning = "후보 " + id + " 제외: 올바른 이미지가 아닙니다. " + sourceImagePath(sourceFolder, imageFile);
                warningProgress.accept(progressMessage, warning);
                return new CandidateBuildResult(id, false, false, null, warning);
            }
            BufferedImage outputImage = normalizeImage(image);
            writePngImage(buildFolder, imagePath, outputImage);
            if (isGifFile(imageFile)) {
                writeStaticCandidateTexture(buildFolder, id, outputImage);
                writeAnimatedCandidateTexture(buildFolder, id, imageFile, outputImage);
                writeCandidateModels(buildFolder, id, true);
            } else {
                writeCandidateTexture(buildFolder, id, outputImage);
                writeCandidateModels(buildFolder, id, false);
            }
            progress.accept(progressMessage);
            return new CandidateBuildResult(id, true, false, null, null);
        } catch (IOException exception) {
            String warning = "후보 " + id + " 제외: " + exception.getMessage();
            warningProgress.accept(progressMessage, warning);
            return new CandidateBuildResult(id, false, false, null, warning);
        }
    }

    private int buildWorkerCount(int totalCandidates, int requestedWorkerCount) {
        if (requestedWorkerCount > 0) {
            return Math.max(1, Math.min(Math.min(requestedWorkerCount, 8), totalCandidates));
        }
        return Math.max(1, Math.min(2, totalCandidates));
    }

    public static String modelName(String id) {
        String normalized = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_/-]", "_");
        normalized = normalized.replace('/', '_');
        if (normalized.isBlank()) {
            normalized = "candidate";
        }
        return "candidate_" + normalized;
    }

    public static String staticModelName(String id) {
        return modelName(id) + "_static";
    }

    public static String frameModelName(String id, int frameIndex) {
        return modelName(id) + "_frame_" + String.format(Locale.ROOT, "%03d", frameIndex);
    }

    public static String imagePath(String id) {
        return "images/" + id + ".png";
    }

    private void writeCandidateModels(File buildFolder, String id, boolean hasStaticTexture) throws IOException {
        String modelName = modelName(id);
        writeCandidateModel(buildFolder, modelName, textureReference(modelName));
        writeCandidateModel(buildFolder, staticModelName(id), textureReference(hasStaticTexture ? staticModelName(id) : modelName));
    }

    private void writeCandidateModel(File buildFolder, String modelName, String texture) throws IOException {
        writeItemModel(buildFolder, modelName, texture, "minecraft:cutout");
    }

    private void writeItemModel(File buildFolder, String modelName, String texture, String renderType) throws IOException {
        File modelFile = new File(buildFolder, "assets/minecraft/models/item/idealcup/" + modelName + ".json");
        File itemFile = new File(buildFolder, "assets/minecraft/items/idealcup/" + modelName + ".json");
        Files.createDirectories(modelFile.toPath().getParent());
        Files.createDirectories(itemFile.toPath().getParent());

        String modelJson = """
                {
                  "parent": "minecraft:item/generated",
                  "render_type": "%s",
                  "ambientocclusion": false,
                  "textures": {
                    "layer0": "%s"
                  }
                }
                """.formatted(renderType, texture);
        String itemJson = """
                {
                  "model": {
                    "type": "minecraft:model",
                    "model": "minecraft:item/idealcup/%s"
                  }
                }
                """.formatted(modelName);

        Files.writeString(modelFile.toPath(), modelJson, StandardCharsets.UTF_8);
        Files.writeString(itemFile.toPath(), itemJson, StandardCharsets.UTF_8);
    }

    private File sourceImageFile(File sourceFolder, String id) {
        for (String extension : SOURCE_EXTENSIONS) {
            File imageFile = new File(sourceFolder, ("images/" + id + "." + extension).replace('/', File.separatorChar));
            if (imageFile.isFile()) {
                return imageFile;
            }
        }
        return new File(sourceFolder, imagePath(id).replace('/', File.separatorChar));
    }

    private boolean isGifFile(File imageFile) {
        return imageFile.getName().toLowerCase(Locale.ROOT).endsWith(".gif");
    }

    private boolean isVideoFile(File imageFile) {
        String lowerName = imageFile.getName().toLowerCase(Locale.ROOT);
        return VIDEO_EXTENSIONS.stream().anyMatch(extension -> lowerName.endsWith("." + extension));
    }

    private boolean isAnimatedPngSource(File imageFile) {
        return imageFile.getName().toLowerCase(Locale.ROOT).endsWith(".png") && sourceAnimationMetaFile(imageFile).isFile();
    }

    private File sourceAnimationMetaFile(File imageFile) {
        return new File(imageFile.getPath() + ".mcmeta");
    }

    private void registerImageReaders() {
        ImageIO.scanForPlugins();
        IIORegistry.getDefaultInstance().registerServiceProvider(new WebPImageReaderSpi());
    }

    private String sourceImagePath(File sourceFolder, File imageFile) {
        return sourceFolder.toPath().relativize(imageFile.toPath()).toString().replace('\\', '/');
    }

    private void writePngImage(File buildFolder, String imagePath, BufferedImage image) throws IOException {
        File sourceImageFile = new File(buildFolder, imagePath.replace('/', File.separatorChar));
        Files.createDirectories(sourceImageFile.toPath().getParent());
        ImageIO.write(image, "png", sourceImageFile);
    }

    private void writeCandidateTexture(File buildFolder, String id, BufferedImage image) throws IOException {
        File textureFile = new File(buildFolder, "assets/minecraft/textures/item/idealcup/" + modelName(id) + ".png");
        Files.createDirectories(textureFile.toPath().getParent());
        ImageIO.write(image, "png", textureFile);
    }

    private void writeStaticCandidateTexture(File buildFolder, String id, BufferedImage image) throws IOException {
        File textureFile = new File(buildFolder, "assets/minecraft/textures/item/idealcup/" + staticModelName(id) + ".png");
        Files.createDirectories(textureFile.toPath().getParent());
        ImageIO.write(image, "png", textureFile);
    }

    private void writeFrameCandidateTextureAndModel(File buildFolder, String id, BufferedImage image, int frameIndex) throws IOException {
        String frameModelName = frameModelName(id, frameIndex);
        File textureFile = new File(buildFolder, "assets/minecraft/textures/item/idealcup/" + frameModelName + ".png");
        Files.createDirectories(textureFile.toPath().getParent());
        ImageIO.write(image, "png", textureFile);
        writeCandidateModel(buildFolder, frameModelName, textureReference(frameModelName));
    }

    private void writeAnimatedPngSource(File buildFolder, String id, File imageFile) throws IOException {
        File sourceImageFile = new File(buildFolder, imagePath(id).replace('/', File.separatorChar));
        Files.createDirectories(sourceImageFile.toPath().getParent());
        Files.copy(imageFile.toPath(), sourceImageFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(sourceAnimationMetaFile(imageFile).toPath(), new File(sourceImageFile.getPath() + ".mcmeta").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        File textureFile = new File(buildFolder, "assets/minecraft/textures/item/idealcup/" + modelName(id) + ".png");
        Files.createDirectories(textureFile.toPath().getParent());
        Files.copy(imageFile.toPath(), textureFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(sourceAnimationMetaFile(imageFile).toPath(), new File(textureFile.getPath() + ".mcmeta").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        File staticTextureFile = new File(buildFolder, "assets/minecraft/textures/item/idealcup/" + staticModelName(id) + ".png");
        BufferedImage staticImage = readFirstAnimatedPngFrame(imageFile);
        ImageIO.write(staticImage, "png", staticTextureFile);
        writeAnimatedPngFrameModels(buildFolder, id, imageFile);
    }

    private void writeAnimatedPngFrameModels(File buildFolder, String id, File imageFile) throws IOException {
        BufferedImage image = ImageIO.read(imageFile);
        if (image == null) {
            throw new IOException("올바른 이미지가 아닙니다: " + imageFile.getName());
        }

        AnimationSize animationSize = readAnimationSize(sourceAnimationMetaFile(imageFile).toPath());
        if (animationSize == null) {
            writeFrameCandidateTextureAndModel(buildFolder, id, normalizeImage(image), 0);
            return;
        }

        int frameWidth = Math.min(animationSize.width(), image.getWidth());
        int frameHeight = Math.min(animationSize.height(), image.getHeight());
        int frameCount = Math.max(1, image.getHeight() / frameHeight);
        for (int index = 0; index < frameCount; index++) {
            BufferedImage frame = image.getSubimage(0, index * frameHeight, frameWidth, frameHeight);
            writeFrameCandidateTextureAndModel(buildFolder, id, drawRgbImage(frame, frameWidth, frameHeight), index);
        }
    }

    private boolean extractCandidateSound(File videoFile, File targetFile) throws IOException {
        Files.createDirectories(targetFile.toPath().getParent());
        Files.deleteIfExists(targetFile.toPath());

        String ffmpeg = resolveFfmpeg();
        List<String> command = List.of(
                ffmpeg,
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-i",
                videoFile.getAbsolutePath(),
                "-vn",
                "-af",
                "loudnorm=I=-14:TP=-1.0:LRA=9",
                "-c:a",
                "libvorbis",
                "-q:a",
                "10",
                targetFile.getAbsolutePath()
        );

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        try (var inputStream = process.getInputStream()) {
            inputStream.readAllBytes();
        }
        try {
            int exitCode = process.waitFor();
            boolean success = exitCode == 0 && targetFile.isFile() && targetFile.length() > 0L;
            if (!success) {
                Files.deleteIfExists(targetFile.toPath());
            }
            return success;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("ffmpeg 소리 추출이 중단되었습니다.", exception);
        }
    }

    private void writeSoundsJson(File buildFolder, List<String> soundModels) throws IOException {
        if (soundModels.isEmpty()) {
            return;
        }

        StringBuilder entries = new StringBuilder();
        for (int index = 0; index < soundModels.size(); index++) {
            if (index > 0) {
                entries.append(",\n");
            }
            String modelName = soundModels.get(index);
            entries.append("""
                      "%s": {
                        "sounds": [
                          {
                            "name": "idealcup:%s",
                            "stream": true
                          }
                        ]
                      }""".formatted(modelName, modelName));
        }

        File soundsJson = new File(buildFolder, "assets/idealcup/sounds.json");
        Files.createDirectories(soundsJson.toPath().getParent());
        Files.writeString(soundsJson.toPath(), "{\n" + entries + "\n}\n", StandardCharsets.UTF_8);
    }

    private List<EndingSound> copyEndingSounds(File sourceFolder, File buildFolder) throws IOException {
        List<EndingSoundSource> sounds = new ArrayList<>();
        collectEndingSounds(sourceFolder, sounds);
        collectEndingSounds(new File(sourceFolder, "sounds"), sounds);
        sounds.sort(Comparator.comparingInt(EndingSoundSource::order));

        List<EndingSound> endingSounds = new ArrayList<>();
        Set<String> copiedModels = new LinkedHashSet<>();
        for (EndingSoundSource sound : sounds) {
            if (!copiedModels.add(sound.modelName())) {
                continue;
            }
            File targetFile = new File(buildFolder, "assets/idealcup/sounds/" + sound.modelName() + ".ogg");
            long durationTicks = convertEndingSound(sound.file(), targetFile);
            endingSounds.add(new EndingSound(sound.modelName(), durationTicks, sound.order()));
        }
        return endingSounds;
    }

    private void collectEndingSounds(File folder, List<EndingSoundSource> sounds) {
        File[] files = folder.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            Matcher matcher = ENDING_THEME_PATTERN.matcher(file.getName());
            if (!matcher.matches()) {
                continue;
            }
            String suffix = matcher.group(1);
            int order = suffix == null || suffix.isBlank() ? 1 : Integer.parseInt(suffix);
            String modelName = order == 1 ? "ending_theme" : "ending_theme" + order;
            sounds.add(new EndingSoundSource(file, modelName, order));
        }
    }

    private long convertEndingSound(File sourceFile, File targetFile) throws IOException {
        Files.createDirectories(targetFile.toPath().getParent());
        Files.deleteIfExists(targetFile.toPath());

        String ffmpeg = resolveFfmpeg();
        List<String> command = List.of(
                ffmpeg,
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-i",
                sourceFile.getAbsolutePath(),
                "-vn",
                "-af",
                "loudnorm=I=-14:TP=-1.0:LRA=9",
                "-c:a",
                "libvorbis",
                "-q:a",
                "10",
                targetFile.getAbsolutePath()
        );

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        String output;
        try (var inputStream = process.getInputStream()) {
            output = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0 || !targetFile.isFile() || targetFile.length() <= 0L) {
                Files.deleteIfExists(targetFile.toPath());
                throw new IOException("엔딩 음원 변환에 실패했습니다: " + sourceFile.getName() + (output.isBlank() ? "" : " - " + output.trim()));
            }
            return readAudioDurationTicks(targetFile);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("엔딩 음원 변환이 중단되었습니다.", exception);
        }
    }

    private long readAudioDurationTicks(File audioFile) throws IOException {
        String ffmpeg = resolveFfmpeg();
        ProcessBuilder processBuilder = new ProcessBuilder(
                ffmpeg,
                "-hide_banner",
                "-i",
                audioFile.getAbsolutePath()
        );
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        String output;
        try (var inputStream = process.getInputStream()) {
            output = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            process.waitFor();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("엔딩 음원 길이 확인이 중단되었습니다.", exception);
        }

        Matcher matcher = AUDIO_DURATION_PATTERN.matcher(output);
        if (!matcher.find()) {
            throw new IOException("엔딩 음원 길이를 확인할 수 없습니다: " + audioFile.getName());
        }

        double hours = Double.parseDouble(matcher.group(1));
        double minutes = Double.parseDouble(matcher.group(2));
        double seconds = Double.parseDouble(matcher.group(3));
        return Math.max(1L, Math.round((hours * 3600.0D + minutes * 60.0D + seconds) * 20.0D));
    }

    private void writeEndingBgmManifest(File buildFolder, List<EndingSound> endingSounds) throws IOException {
        if (endingSounds.isEmpty()) {
            return;
        }

        YamlConfiguration manifest = new YamlConfiguration();
        List<String> trackIds = new ArrayList<>();
        for (EndingSound endingSound : endingSounds.stream().sorted(Comparator.comparingInt(EndingSound::order)).toList()) {
            String path = "tracks." + endingSound.modelName() + ".";
            manifest.set(path + "sound", "idealcup:" + endingSound.modelName());
            manifest.set(path + "ticks", endingSound.durationTicks());
            trackIds.add(endingSound.modelName());
        }
        manifest.set("order", trackIds);

        File manifestFile = new File(buildFolder, ENDING_BGM_MANIFEST_PATH);
        Files.createDirectories(manifestFile.toPath().getParent());
        manifest.save(manifestFile);
    }

    private void writeVideoCandidateTexture(File sourceFolder, File buildFolder, String id, File videoFile, String imagePath) throws IOException {
        Path frameFolder = Files.createTempDirectory(buildFolder.toPath().getParent(), "idealcup-video-");
        try {
            extractVideoFrames(videoFile, frameFolder);
            List<BufferedImage> frames = readExtractedVideoFrames(frameFolder);
            if (frames.isEmpty()) {
                throw new IOException("영상에서 프레임을 추출하지 못했습니다: " + sourceImagePath(sourceFolder, videoFile));
            }

            writePngImage(buildFolder, imagePath, frames.get(0));
            writeStaticCandidateTexture(buildFolder, id, frames.get(0));
            writeAnimatedFramesTexture(buildFolder, id, frames);
        } finally {
            deleteFolder(frameFolder);
        }
    }

    private void extractVideoFrames(File videoFile, Path frameFolder) throws IOException {
        String ffmpeg = resolveFfmpeg();
        List<String> command = List.of(
                ffmpeg,
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-i",
                videoFile.getAbsolutePath(),
                "-vf",
                videoFrameFilter(),
                "-compression_level",
                "1",
                frameFolder.resolve("frame_%03d.png").toString()
        );

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
                throw new IOException("ffmpeg 변환 실패" + (output.isBlank() ? "" : ": " + output));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("ffmpeg 변환이 중단되었습니다.", exception);
        }
    }

    private List<BufferedImage> readExtractedVideoFrames(Path frameFolder) throws IOException {
        List<BufferedImage> frames = new ArrayList<>();
        try (var paths = Files.list(frameFolder)) {
            for (Path framePath : paths
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .sorted()
                    .toList()) {
                BufferedImage image = ImageIO.read(framePath.toFile());
                if (image == null) {
                    throw new IOException("ffmpeg가 생성한 프레임을 읽을 수 없습니다: " + framePath.getFileName());
                }
                frames.add(normalizeImage(image));
            }
        }
        return frames;
    }

    private String videoFrameFilter() {
        return "fps=" + animationFps
                + ",scale=w='if(gte(iw,ih),min(iw," + maxImageSize + "),-1)'"
                + ":h='if(gt(ih,iw),min(ih," + maxImageSize + "),-1)'";
    }

    private BufferedImage readFirstAnimatedPngFrame(File imageFile) throws IOException {
        BufferedImage image = ImageIO.read(imageFile);
        if (image == null) {
            throw new IOException("올바른 이미지가 아닙니다: " + imageFile.getName());
        }

        AnimationSize animationSize = readAnimationSize(sourceAnimationMetaFile(imageFile).toPath());
        if (animationSize == null) {
            return normalizeImage(image);
        }

        int width = Math.min(animationSize.width(), image.getWidth());
        int height = Math.min(animationSize.height(), image.getHeight());
        return drawRgbImage(image.getSubimage(0, 0, width, height), width, height);
    }

    private synchronized String resolveFfmpeg() throws IOException {
        File toolFolder = new File(plugin.getDataFolder(), "tools");
        File ffmpegFile = new File(toolFolder, "ffmpeg.exe");
        if (ffmpegFile.isFile()) {
            return ffmpegFile.getAbsolutePath();
        }

        try (var inputStream = plugin.getResource("tools/ffmpeg.exe")) {
            if (inputStream != null) {
                Files.createDirectories(toolFolder.toPath());
                Files.copy(inputStream, ffmpegFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return ffmpegFile.getAbsolutePath();
            }
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

    private void writeAnimatedCandidateTexture(File buildFolder, String id, File gifFile, BufferedImage firstFrame) throws IOException {
        List<BufferedImage> frames = readGifFrames(gifFile, firstFrame.getWidth(), firstFrame.getHeight());
        if (frames.isEmpty()) {
            writeCandidateTexture(buildFolder, id, firstFrame);
            return;
        }

        writeAnimatedFramesTexture(buildFolder, id, frames);
    }

    private void writeAnimatedFramesTexture(File buildFolder, String id, List<BufferedImage> frames) throws IOException {
        int frameWidth = frames.get(0).getWidth();
        int frameHeight = frames.get(0).getHeight();
        BufferedImage strip = new BufferedImage(frameWidth, frameHeight * frames.size(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = strip.createGraphics();
        for (int index = 0; index < frames.size(); index++) {
            BufferedImage frameImage = drawRgbImage(frames.get(index), frameWidth, frameHeight);
            graphics.drawImage(frameImage, 0, frameHeight * index, null);
            writeFrameCandidateTextureAndModel(buildFolder, id, frameImage, index);
        }
        graphics.dispose();

        File textureFile = new File(buildFolder, "assets/minecraft/textures/item/idealcup/" + modelName(id) + ".png");
        Files.createDirectories(textureFile.toPath().getParent());
        ImageIO.write(strip, "png", textureFile);

        File animationFile = new File(textureFile.getPath() + ".mcmeta");
        String json = animationMetaJson(frameWidth, frameHeight, frames.size());
        Files.writeString(animationFile.toPath(), json, StandardCharsets.UTF_8);
    }

    private String animationMetaJson(int frameWidth, int frameHeight, int frameCount) {
        StringBuilder framesJson = new StringBuilder();
        for (int index = 0; index < frameCount; index++) {
            if (index > 0) {
                framesJson.append(",\n");
            }
            framesJson.append("      { \"index\": ").append(index).append(", \"time\": ").append(animationFrameTicks(index)).append(" }");
        }
        return """
                {
                  "animation": {
                    "interpolate": false,
                    "width": %d,
                    "height": %d,
                    "frames": [
                %s
                    ]
                  }
                }
                """.formatted(frameWidth, frameHeight, framesJson);
    }

    private int animationFrameTicks(int frameIndex) {
        int startTick = (int) Math.round(frameIndex * 20.0D / animationFps);
        int endTick = (int) Math.round((frameIndex + 1) * 20.0D / animationFps);
        return Math.max(1, endTick - startTick);
    }

    private List<BufferedImage> readGifFrames(File gifFile, int width, int height) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext()) {
            return List.of();
        }

        ImageReader reader = readers.next();
        List<BufferedImage> frames = new ArrayList<>();
        try (ImageInputStream inputStream = ImageIO.createImageInputStream(gifFile)) {
            reader.setInput(inputStream);
            int frameCount = reader.getNumImages(true);
            int limit = Math.min(frameCount, MAX_GIF_FRAMES);
            for (int index = 0; index < limit; index++) {
                frames.add(drawRgbImage(reader.read(index), width, height));
            }
        } finally {
            reader.dispose();
        }
        return frames;
    }

    private BufferedImage normalizeImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int longestSide = Math.max(width, height);
        int outputWidth = width;
        int outputHeight = height;
        if (longestSide <= maxImageSize) {
            return drawRgbImage(image, outputWidth, outputHeight);
        }

        double scale = (double) maxImageSize / (double) longestSide;
        outputWidth = Math.max(1, (int) Math.round(width * scale));
        outputHeight = Math.max(1, (int) Math.round(height * scale));
        return drawRgbImage(image, outputWidth, outputHeight);
    }

    private BufferedImage drawRgbImage(BufferedImage image, int width, int height) {
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.drawImage(image, 0, 0, width, height, null);
        graphics.dispose();
        return output;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String textureReference(String modelName) {
        return "minecraft:item/idealcup/" + modelName;
    }

    private void writePackMeta(File sourceFolder, File buildFolder, String packDescription) throws IOException {
        File buildPackMeta = new File(buildFolder, "pack.mcmeta");
        String description = packDescription == null || packDescription.isBlank() ? "IdealCup candidate pack" : packDescription;
        String json = """
                {
                  "pack": {
                    "pack_format": 69,
                    "description": "%s"
                  }
                }
                """.formatted(jsonEscape(description));
        Files.writeString(buildPackMeta.toPath(), json, StandardCharsets.UTF_8);
    }

    private void copyPackIcon(File sourceFolder, File buildFolder) throws IOException {
        File sourcePackIcon = new File(sourceFolder, "pack.png");
        if (!sourcePackIcon.isFile()) {
            return;
        }
        BufferedImage image = ImageIO.read(sourcePackIcon);
        if (image == null) {
            throw new IOException("resourcepack-src/pack.png 파일이 올바른 이미지가 아닙니다.");
        }
        ImageIO.write(normalizePackIcon(image), "png", new File(buildFolder, "pack.png"));
    }

    private BufferedImage normalizePackIcon(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        double scale = Math.min((double) PACK_ICON_SIZE / width, (double) PACK_ICON_SIZE / height);
        int outputWidth = Math.max(1, (int) Math.round(width * scale));
        int outputHeight = Math.max(1, (int) Math.round(height * scale));
        int x = (PACK_ICON_SIZE - outputWidth) / 2;
        int y = (PACK_ICON_SIZE - outputHeight) / 2;

        BufferedImage output = new BufferedImage(PACK_ICON_SIZE, PACK_ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.drawImage(image, x, y, outputWidth, outputHeight, null);
        graphics.dispose();
        return output;
    }

    private String jsonEscape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private void zipFolder(Path sourceFolder, Path targetZip) throws IOException {
        Files.createDirectories(targetZip.getParent());
        try (OutputStream outputStream = Files.newOutputStream(targetZip);
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            zipOutputStream.setLevel(Deflater.BEST_SPEED);
            try (var paths = Files.walk(sourceFolder)) {
                for (Path path : paths.sorted(Comparator.naturalOrder()).toList()) {
                    if (Files.isDirectory(path)) {
                        continue;
                    }
                    String entryName = sourceFolder.relativize(path).toString().replace('\\', '/');
                    zipOutputStream.putNextEntry(zipEntry(sourceFolder.relativize(path), path, entryName));
                    Files.copy(path, zipOutputStream);
                    zipOutputStream.closeEntry();
                }
            }
        }
    }

    private int zipSplitResourcePacks(Path buildRoot, List<String> candidateIds, List<String> warnings) throws IOException {
        Path splitFolder = resourcePackPartsFolder().toPath();
        recreateFolder(splitFolder);

        List<Path> allPaths = allRegularRelativePaths(buildRoot).stream()
                .filter(this::isClientPackPath)
                .toList();
        List<CandidatePackGroup> candidateGroups = new ArrayList<>();
        Set<Path> candidatePaths = new LinkedHashSet<>();
        for (String id : candidateIds) {
            CandidatePackGroup group = candidatePackGroup(buildRoot, id, allPaths);
            if (!group.paths().isEmpty()) {
                candidateGroups.add(group);
                candidatePaths.addAll(group.paths());
            }
        }

        List<Path> basePaths = new ArrayList<>();
        for (Path path : allPaths) {
            if (!candidatePaths.contains(path)) {
                basePaths.add(path);
            }
        }

        int writtenPacks = 0;
        zipSelectedPaths(buildRoot, splitFolder.resolve(BASE_SPLIT_PACK_FILE), basePaths);
        writtenPacks++;

        List<Path> requiredPaths = splitPackRequiredPaths(buildRoot);
        List<Path> currentPaths = new ArrayList<>();
        long currentSize = 0L;
        int mediaIndex = 1;

        for (CandidatePackGroup group : candidateGroups) {
            if (group.size() > SPLIT_PACK_MAX_BYTES) {
                if (!currentPaths.isEmpty()) {
                    zipSelectedPaths(buildRoot, splitFolder.resolve(splitPackName(mediaIndex++)), withRequiredPaths(requiredPaths, currentPaths));
                    writtenPacks++;
                    currentPaths.clear();
                    currentSize = 0L;
                }
                warnings.add("후보 " + group.id() + " 리소스가 180MB를 초과해서 단독 분할팩으로 저장됩니다.");
                zipSelectedPaths(buildRoot, splitFolder.resolve(splitPackName(mediaIndex++)), withRequiredPaths(requiredPaths, group.paths()));
                writtenPacks++;
                continue;
            }

            if (!currentPaths.isEmpty() && currentSize + group.size() > SPLIT_PACK_MAX_BYTES) {
                zipSelectedPaths(buildRoot, splitFolder.resolve(splitPackName(mediaIndex++)), withRequiredPaths(requiredPaths, currentPaths));
                writtenPacks++;
                currentPaths.clear();
                currentSize = 0L;
            }

            currentPaths.addAll(group.paths());
            currentSize += group.size();
        }

        if (!currentPaths.isEmpty()) {
            zipSelectedPaths(buildRoot, splitFolder.resolve(splitPackName(mediaIndex)), withRequiredPaths(requiredPaths, currentPaths));
            writtenPacks++;
        }

        return writtenPacks;
    }

    private List<Path> allRegularRelativePaths(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(root::relativize)
                    .sorted(Comparator.comparing(path -> path.toString().replace('\\', '/')))
                    .toList();
        }
    }

    private CandidatePackGroup candidatePackGroup(Path buildRoot, String id, List<Path> allPaths) throws IOException {
        List<Path> paths = new ArrayList<>();
        for (Path path : allPaths) {
            String entryName = path.toString().replace('\\', '/');
            if (isCandidateAsset(entryName, id)) {
                paths.add(path);
            }
        }
        return new CandidatePackGroup(id, paths, pathSize(buildRoot, paths));
    }

    private boolean isCandidateAsset(String entryName, String id) {
        String model = modelName(id);
        if (entryName.equals("assets/idealcup/sounds/" + model + ".ogg")) {
            return true;
        }
        if (!entryName.startsWith("assets/minecraft/models/item/idealcup/")
                && !entryName.startsWith("assets/minecraft/items/idealcup/")
                && !entryName.startsWith("assets/minecraft/textures/item/idealcup/")) {
            return false;
        }
        String fileName = entryName.substring(entryName.lastIndexOf('/') + 1);
        return fileName.equals(model + ".json")
                || fileName.equals(model + ".png")
                || fileName.equals(model + ".png.mcmeta")
                || fileName.startsWith(model + "_");
    }

    private boolean isClientPackPath(Path path) {
        String entryName = path.toString().replace('\\', '/');
        return entryName.equals("pack.mcmeta") || entryName.equals("pack.png") || entryName.startsWith("assets/");
    }

    private List<Path> splitPackRequiredPaths(Path buildRoot) {
        List<Path> paths = new ArrayList<>();
        Path packMeta = Path.of("pack.mcmeta");
        if (Files.isRegularFile(buildRoot.resolve(packMeta))) {
            paths.add(packMeta);
        }
        Path packIcon = Path.of("pack.png");
        if (Files.isRegularFile(buildRoot.resolve(packIcon))) {
            paths.add(packIcon);
        }
        Path sounds = Path.of("assets/idealcup/sounds.json");
        if (Files.isRegularFile(buildRoot.resolve(sounds))) {
            paths.add(sounds);
        }
        Path endingBgm = Path.of(ENDING_BGM_MANIFEST_PATH);
        if (Files.isRegularFile(buildRoot.resolve(endingBgm))) {
            paths.add(endingBgm);
        }
        return paths;
    }

    private List<Path> withRequiredPaths(List<Path> requiredPaths, List<Path> paths) {
        Set<Path> merged = new LinkedHashSet<>(requiredPaths);
        merged.addAll(paths);
        return new ArrayList<>(merged);
    }

    private void zipSelectedPaths(Path sourceFolder, Path targetZip, List<Path> relativePaths) throws IOException {
        Files.createDirectories(targetZip.getParent());
        try (OutputStream outputStream = Files.newOutputStream(targetZip);
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            zipOutputStream.setLevel(Deflater.BEST_SPEED);
            for (Path relativePath : relativePaths) {
                Path sourcePath = sourceFolder.resolve(relativePath);
                if (!Files.isRegularFile(sourcePath)) {
                    continue;
                }
                String entryName = relativePath.toString().replace('\\', '/');
                zipOutputStream.putNextEntry(zipEntry(relativePath, sourcePath, entryName));
                Files.copy(sourcePath, zipOutputStream);
                zipOutputStream.closeEntry();
            }
        }
    }

    private ZipEntry zipEntry(Path relativePath, Path sourcePath, String entryName) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        if (shouldStoreZipEntry(relativePath)) {
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(Files.size(sourcePath));
            entry.setCompressedSize(entry.getSize());
            entry.setCrc(zipCrc32(sourcePath));
        }
        return entry;
    }

    private boolean shouldStoreZipEntry(Path relativePath) {
        String fileName = relativePath.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".png")
                || fileName.endsWith(".ogg")
                || fileName.endsWith(".webp")
                || fileName.endsWith(".gif")
                || fileName.endsWith(".mp4")
                || fileName.endsWith(".mkv")
                || fileName.endsWith(".mov");
    }

    private long zipCrc32(Path path) throws IOException {
        CRC32 crc32 = new CRC32();
        try (InputStream inputStream = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                crc32.update(buffer, 0, read);
            }
        }
        return crc32.getValue();
    }

    private long pathSize(Path buildRoot, List<Path> paths) throws IOException {
        long size = 0L;
        for (Path path : paths) {
            size += Files.size(buildRoot.resolve(path));
        }
        return size;
    }

    private String splitPackName(int index) {
        return MEDIA_SPLIT_PACK_PREFIX + String.format(Locale.ROOT, "%02d", index) + ".zip";
    }

    private File resourcePackPartsFolder() {
        File pluginsFolder = plugin.getDataFolder().getParentFile();
        if (pluginsFolder == null || pluginsFolder.getParentFile() == null) {
            return new File(plugin.getDataFolder(), SPLIT_PACK_FOLDER);
        }
        return new File(pluginsFolder.getParentFile(), SPLIT_PACK_FOLDER);
    }

    private File resourcePackFile() {
        File pluginsFolder = plugin.getDataFolder().getParentFile();
        if (pluginsFolder == null || pluginsFolder.getParentFile() == null) {
            return new File(plugin.getDataFolder(), PACK_FILE);
        }
        return new File(pluginsFolder.getParentFile(), PACK_FILE);
    }

    private AnimationSize readAnimationSize(Path animationPath) throws IOException {
        if (!Files.isRegularFile(animationPath)) {
            return null;
        }
        return readAnimationSize(Files.readString(animationPath, StandardCharsets.UTF_8));
    }

    private AnimationSize readAnimationSize(String json) {
        int width = -1;
        int height = -1;
        Matcher matcher = ANIMATION_SIZE_PATTERN.matcher(json);
        while (matcher.find()) {
            if ("width".equals(matcher.group(1))) {
                width = Integer.parseInt(matcher.group(2));
            } else if ("height".equals(matcher.group(1))) {
                height = Integer.parseInt(matcher.group(2));
            }
        }
        if (width <= 0 || height <= 0) {
            return null;
        }
        return new AnimationSize(width, height);
    }

    private void recreateFolder(Path folder) throws IOException {
        deleteFolder(folder);
        Files.createDirectories(folder);
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

    private void cleanupBuildFolder(Path folder, List<String> warnings) {
        try {
            deleteFolder(folder);
        } catch (IOException exception) {
            warnings.add("임시 리소스팩 빌드 폴더를 지울 수 없습니다: " + exception.getMessage());
        }
    }

    public record BuildResult(boolean success, int candidates, List<String> warnings) {
    }

    private record CandidateBuildResult(String id, boolean built, boolean manualPlayback, String soundModel, String warning) {
    }

    private record CandidatePackGroup(String id, List<Path> paths, long size) {
    }

    private record EndingSoundSource(File file, String modelName, int order) {
    }

    private record EndingSound(String modelName, long durationTicks, int order) {
    }

    private record AnimationSize(int width, int height) {
    }
}

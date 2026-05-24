package org.ha2yo.idealCup.resource;

import org.ha2yo.idealCup.model.Candidate;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class CandidateRepository {
    private static final String RESOURCE_PACK_FILE = "resourcepack.zip";
    private static final String MANIFEST_PATH = "candidates.yml";
    private static final Pattern ANIMATION_SIZE_PATTERN = Pattern.compile("\"(width|height)\"\\s*:\\s*(\\d+)");
    private static final Pattern ANIMATION_TIME_PATTERN = Pattern.compile("\"time\"\\s*:\\s*(\\d+)");
    private static final int DEFAULT_FRAME_TICKS = 2;

    private final JavaPlugin plugin;
    private final List<Candidate> candidates = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public CandidateRepository(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        candidates.clear();
        warnings.clear();

        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            warnings.add("플러그인 데이터 폴더를 만들 수 없습니다.");
            return;
        }

        File packFile = resourcePackFile();
        if (!packFile.isFile()) {
            warnings.add("서버 resourcepack.zip이 없습니다. /idealcup buildpack을 실행하세요.");
            return;
        }

        try (ZipFile zipFile = new ZipFile(packFile, StandardCharsets.UTF_8)) {
            ZipEntry manifestEntry = zipFile.getEntry(MANIFEST_PATH);
            YamlConfiguration manifest;
            if (manifestEntry == null) {
                File sourceManifest = new File(plugin.getDataFolder(), "resourcepack-src/" + MANIFEST_PATH);
                if (!sourceManifest.isFile()) {
                    warnings.add("resourcepack.zip and resourcepack-src/" + MANIFEST_PATH + " are missing.");
                    return;
                }
                manifest = YamlConfiguration.loadConfiguration(sourceManifest);
            } else {
                try (InputStream inputStream = zipFile.getInputStream(manifestEntry);
                     InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                    manifest = YamlConfiguration.loadConfiguration(reader);
                }
            }

            ConfigurationSection section = manifest.getConfigurationSection("candidates");
            if (section == null) {
                warnings.add("candidates.yml에 candidates 섹션이 없습니다.");
                return;
            }

            for (String id : section.getKeys(false)) {
                String basePath = "candidates." + id + ".";
                String name = manifest.getString(basePath + "name", id);
                String imagePath = ResourcePackBuilder.imagePath(id);
                ZipEntry imageEntry = zipFile.getEntry(imagePath);
                if (imageEntry == null) {
                    warnings.add("후보 " + id + " 제외: " + imagePath + " 파일이 없습니다.");
                    continue;
                }

                String modelName = ResourcePackBuilder.modelName(id);
                ZipEntry itemModelEntry = zipFile.getEntry("assets/minecraft/items/idealcup/" + modelName + ".json");
                if (itemModelEntry == null) {
                    warnings.add("후보 " + id + " 제외: 생성된 모델이 없습니다. /idealcup buildpack을 실행하세요.");
                    continue;
                }
                String staticModelName = ResourcePackBuilder.staticModelName(id);
                ZipEntry staticItemModelEntry = zipFile.getEntry("assets/minecraft/items/idealcup/" + staticModelName + ".json");
                if (staticItemModelEntry == null) {
                    warnings.add("후보 " + id + " 제외: 첫 프레임 정지 모델이 없습니다. /idealcup buildpack을 다시 실행하세요.");
                    continue;
                }
                NamespacedKey staticItemModel = new NamespacedKey("minecraft", "idealcup/" + staticModelName);
                BufferedImage image;
                try (InputStream imageInputStream = zipFile.getInputStream(imageEntry)) {
                    image = ImageIO.read(imageInputStream);
                }
                if (image == null) {
                    warnings.add("후보 " + id + " 제외: 올바른 이미지가 아닙니다. " + imagePath);
                    continue;
                }
                AnimationInfo animationInfo = readAnimationInfo(zipFile, zipFile.getEntry(imagePath + ".mcmeta"));
                if (animationInfo == null) {
                    animationInfo = readAnimationInfo(zipFile, zipFile.getEntry("assets/minecraft/textures/item/idealcup/" + modelName + ".png.mcmeta"));
                }
                List<NamespacedKey> frameItemModels = animationInfo == null ? List.of() : readFrameItemModels(zipFile, id);
                if (animationInfo != null && frameItemModels.isEmpty()) {
                    warnings.add("후보 " + id + " 제외: 서버 제어 프레임 모델이 없습니다. /idealcup buildpack을 다시 실행하세요.");
                    continue;
                }
                List<Integer> frameTicks = animationInfo == null ? List.of() : normalizeFrameTicks(animationInfo.frameTicks(), frameItemModels.size());
                int imageWidth = animationInfo == null ? image.getWidth() : animationInfo.width();
                int imageHeight = animationInfo == null ? image.getHeight() : animationInfo.height();
                long playbackTicks = frameTicks.stream().mapToLong(Integer::longValue).sum();
                String soundKey = zipFile.getEntry("assets/idealcup/sounds/" + modelName + ".ogg") == null ? null : "idealcup:" + modelName;
                boolean manualPlayback = manifest.contains(basePath + "manual-playback")
                        ? manifest.getBoolean(basePath + "manual-playback")
                        : !frameItemModels.isEmpty();
                candidates.add(new Candidate(id, name, imagePath, new NamespacedKey("minecraft", "idealcup/" + modelName), staticItemModel, frameItemModels, frameTicks, imageWidth, imageHeight, playbackTicks, soundKey, manualPlayback));
            }
        } catch (Exception exception) {
            warnings.add("resourcepack.zip을 불러올 수 없습니다: " + exception.getMessage());
            plugin.getLogger().warning("후보를 불러올 수 없습니다: " + exception.getMessage());
        }
    }

    private AnimationInfo readAnimationInfo(ZipFile zipFile, ZipEntry animationEntry) throws Exception {
        if (animationEntry == null) {
            return null;
        }

        String json;
        try (InputStream inputStream = zipFile.getInputStream(animationEntry)) {
            json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

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
        List<Integer> frameTicks = new ArrayList<>();
        Matcher timeMatcher = ANIMATION_TIME_PATTERN.matcher(json);
        while (timeMatcher.find()) {
            frameTicks.add(Integer.parseInt(timeMatcher.group(1)));
        }
        if (width <= 0 || height <= 0) {
            return null;
        }
        if (frameTicks.isEmpty()) {
            frameTicks.add(DEFAULT_FRAME_TICKS);
        }
        return new AnimationInfo(width, height, frameTicks);
    }

    private List<Integer> normalizeFrameTicks(List<Integer> frameTicks, int frameCount) {
        if (frameCount <= 0) {
            return List.of();
        }
        List<Integer> normalized = new ArrayList<>(frameCount);
        for (int index = 0; index < frameCount; index++) {
            normalized.add(index < frameTicks.size() ? frameTicks.get(index) : DEFAULT_FRAME_TICKS);
        }
        return List.copyOf(normalized);
    }

    private List<NamespacedKey> readFrameItemModels(ZipFile zipFile, String id) {
        List<NamespacedKey> frameItemModels = new ArrayList<>();
        for (int index = 0; ; index++) {
            String frameModelName = ResourcePackBuilder.frameModelName(id, index);
            if (zipFile.getEntry("assets/minecraft/items/idealcup/" + frameModelName + ".json") == null) {
                break;
            }
            frameItemModels.add(new NamespacedKey("minecraft", "idealcup/" + frameModelName));
        }
        return List.copyOf(frameItemModels);
    }

    public List<Candidate> getCandidates() {
        return Collections.unmodifiableList(candidates);
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public boolean hasEnoughCandidates(int size) {
        return candidates.size() >= size;
    }

    private File resourcePackFile() {
        File pluginsFolder = plugin.getDataFolder().getParentFile();
        if (pluginsFolder == null || pluginsFolder.getParentFile() == null) {
            return new File(plugin.getDataFolder(), RESOURCE_PACK_FILE);
        }
        return new File(pluginsFolder.getParentFile(), RESOURCE_PACK_FILE);
    }

    private record AnimationInfo(int width, int height, List<Integer> frameTicks) {
    }
}

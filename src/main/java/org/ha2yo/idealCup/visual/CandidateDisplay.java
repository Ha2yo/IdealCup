package org.ha2yo.idealCup.visual;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.ha2yo.idealCup.model.Candidate;
import org.ha2yo.idealCup.game.VoteChoice;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CandidateDisplay {
    private static final String DISPLAY_TAG = "idealcup_board_display";
    private static final double CLEAR_HORIZONTAL_MARGIN = 8.0D;
    private static final double CLEAR_VERTICAL_MARGIN = 4.0D;
    private static final double CLEAR_DEPTH_MARGIN = 4.0D;
    private static final double BOARD_BACKGROUND_DEPTH = 0.01D;
    private static final double IMAGE_DEPTH = 0.05D;
    private static final double IMAGE_FRONT_DEPTH = 0.06D;
    private static final double TEXT_DEPTH = 0.085D;
    private static final double TITLE_BAR_DEPTH = BOARD_BACKGROUND_DEPTH + 0.001D;
    private static final double TITLE_TEXT_DEPTH = TEXT_DEPTH;
    private static final double TEXT_OUTLINE_OFFSET = 0.035D;
    private static final double TEXT_OUTLINE_DEPTH_OFFSET = 0.012D;
    private static final double VS_SCALE_MULTIPLIER = 1.4D;
    private static final double VS_Y_DROP_RATIO = 0.04D;
    private static final double VS_OUTLINE_MULTIPLIER = 1.35D;
    private static final double MATCH_NAME_CENTER_X_RATIO = 0.17D;
    private static final double MATCH_NAME_Y_DROP_RATIO = 0.08D;
    private static final double RANKING_NEXT_RANK_GAP_RATIO = 0.24D;

    private final JavaPlugin plugin;
    private final List<Entity> entities = new ArrayList<>();
    private final List<CandidateImage> candidateImages = new ArrayList<>();
    private final Map<TextDisplay, List<TextDisplay>> textOutlines = new HashMap<>();
    private BlockDisplay background;
    private BukkitTask animationTask;

    public CandidateDisplay(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean hasLocations() {
        return LocationConfig.read(plugin, "locations.pos1") != null
                && LocationConfig.read(plugin, "locations.pos2") != null;
    }

    public Optional<VoteChoice> voteChoiceFromLook(Player player) {
        BoardSpec board = readBoardSpec();
        if (board == null) {
            return Optional.empty();
        }

        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        Vector normal = new Vector(board.face().getModX(), 0.0D, board.face().getModZ());
        double denominator = direction.dot(normal);
        if (Math.abs(denominator) < 0.0001D) {
            return Optional.empty();
        }

        Vector center = new Vector(board.centerX(), board.centerY(), board.centerZ());
        double distance = center.clone().subtract(eye.toVector()).dot(normal) / denominator;
        if (distance <= 0.0D) {
            return Optional.empty();
        }

        Vector hit = eye.toVector().add(direction.multiply(distance));
        double verticalOffset = hit.getY() - board.centerY();
        if (Math.abs(verticalOffset) > board.height() / 2.0D) {
            return Optional.empty();
        }

        double horizontalOffset = board.useX() ? hit.getX() - board.centerX() : hit.getZ() - board.centerZ();
        if (Math.abs(horizontalOffset) > board.width() / 2.0D) {
            return Optional.empty();
        }
        return Optional.of(horizontalOffset < 0.0D ? VoteChoice.LEFT : VoteChoice.RIGHT);
    }

    public void showMatch(Candidate left, Candidate right, String cupName, int initialSize, int roundSize, int matchNumber, int totalMatches) {
        BoardSpec board = readBoardSpec();
        if (board == null) {
            return;
        }
        cancelAnimationTask();
        clearEntities();

        TextLayout textLayout = textLayout(board);
        String title = cupName + " " + roundSize + "강   " + matchNumber + "/" + totalMatches;
        double titleScale = fitTitleScale(board, textLayout, title);
        double candidateAreaWidth = board.width() / 2.0D;
        DisplaySize leftSize = fitImageSize(left, candidateAreaWidth, textLayout.contentHeight());
        DisplaySize rightSize = fitImageSize(right, candidateAreaWidth, textLayout.contentHeight());
        double leftX = candidateX(true, leftSize);
        double rightX = candidateX(false, rightSize);
        ensureBackground(board);
        spawnImage(left, board.locationAt(leftX, textLayout.contentY(), IMAGE_DEPTH), board.yaw(), leftSize, 0, false);
        spawnImage(right, board.locationAt(rightX, textLayout.contentY(), IMAGE_DEPTH), board.yaw(), rightSize, 0, false);
        spawnTitle(board, textLayout, title, titleScale);
        spawnText(vsText(), board.locationAt(0.0D, textLayout.contentY() - board.height() * VS_Y_DROP_RATIO, TEXT_DEPTH), board.yaw(), textLayout.vsScale() * VS_SCALE_MULTIPLIER, null, VS_OUTLINE_MULTIPLIER);
        double leftNameScale = fitTextScale(left.name(), textLayout.nameScale(), board.width() * 0.38D);
        double rightNameScale = fitTextScale(right.name(), textLayout.nameScale(), board.width() * 0.38D);
        Location leftNameLocation = fixedNameLocation(board, textLayout, true);
        Location rightNameLocation = fixedNameLocation(board, textLayout, false);
        TextDisplay leftText = spawnText(left.name(), leftNameLocation, board.yaw(), leftNameScale, null);
        TextDisplay rightText = spawnText(right.name(), rightNameLocation, board.yaw(), rightNameScale, null);
        attachCandidateText(left, leftText, leftNameLocation, fixedLoweredNameLocation(board, true), leftNameScale);
        attachCandidateText(right, rightText, rightNameLocation, fixedLoweredNameLocation(board, false), rightNameScale);
    }

    public void showMatchResult(Candidate left, Candidate right, Candidate winner, String cupName, int roundSize, int matchNumber, int totalMatches) {
        BoardSpec board = readBoardSpec();
        if (board == null) {
            return;
        }
        cancelAnimationTask();
        clearEntities();

        Candidate loser = winner.equals(left) ? right : left;
        boolean winnerStartedLeft = winner.equals(left);
        boolean loserStartedLeft = loser.equals(left);
        TextLayout textLayout = textLayout(board);
        String title = cupName + " " + roundSize + "강   " + matchNumber + "/" + totalMatches;
        double titleScale = fitTitleScale(board, textLayout, title);
        double candidateAreaWidth = board.width() / 2.0D;
        DisplaySize loserSize = fitImageSize(loser, candidateAreaWidth, textLayout.contentHeight());
        DisplaySize winnerStartSize = fitImageSize(winner, candidateAreaWidth, textLayout.contentHeight());
        DisplaySize winnerEndSize = fitImageSize(winner, board.width(), textLayout.contentHeight());
        double loserStart = candidateX(loserStartedLeft, loserSize);
        double winnerStart = candidateX(winnerStartedLeft, winnerStartSize);
        ensureBackground(board);

        ItemDisplay loserDisplay = spawnImage(loser, board.locationAt(loserStart, textLayout.contentY(), IMAGE_DEPTH), board.yaw(), loserSize, 2, false);
        ItemDisplay winnerDisplay = spawnImage(winner, board.locationAt(winnerStart, textLayout.contentY(), IMAGE_FRONT_DEPTH), board.yaw(), winnerStartSize, 2, false);
        double loserNameScale = fitTextScale(loser.name(), textLayout.nameScale(), board.width() * 0.38D);
        double winnerNameScale = fitTextScale(winner.name(), textLayout.nameScale(), board.width() * 0.38D);
        TextDisplay loserText = spawnText(loser.name(), board.locationAt(loserStart, textLayout.nameY(), TEXT_DEPTH), board.yaw(), loserNameScale, null);
        TextDisplay winnerText = spawnText(winner.name(), board.locationAt(winnerStart, textLayout.nameY(), TEXT_DEPTH), board.yaw(), winnerNameScale, null);
        spawnTitle(board, textLayout, title, titleScale);

        double loserEnd = resultExitX(board, loserSize, loserStartedLeft);
        double slideDistance = Math.max(Math.abs(loserEnd - loserStart), Math.abs(winnerStart));
        int animationFrames = resultAnimationFrames(board, slideDistance);
        animationTask = new BukkitRunnable() {
            private int frame;

            @Override
            public void run() {
                double progress = easeOutCubic((double) Math.min(frame, animationFrames - 1) / (double) (animationFrames - 1));
                double xLoser = lerp(loserStart, loserEnd, progress);
                double xWinner = lerp(winnerStart, 0.0D, progress);
                DisplaySize winnerSize = lerpSize(winnerStartSize, winnerEndSize, progress);
                moveImage(loserDisplay, board.locationAt(xLoser, textLayout.contentY(), IMAGE_DEPTH), loserSize);
                moveImage(winnerDisplay, board.locationAt(xWinner, textLayout.contentY(), IMAGE_FRONT_DEPTH), winnerSize);
                moveText(loserText, board.locationAt(xLoser, textLayout.nameY(), TEXT_DEPTH), loserNameScale);
                moveText(winnerText, board.locationAt(xWinner, textLayout.nameY(), TEXT_DEPTH), winnerNameScale);
                frame++;
                if (frame >= animationFrames) {
                    animationTask = null;
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    public void showWinner(Candidate winner, String cupName, int initialSize) {
        BoardSpec board = readBoardSpec();
        if (board == null) {
            return;
        }
        cancelAnimationTask();
        clearEntities();
        TextLayout textLayout = textLayout(board);
        String title = cupName + " " + initialSize + "\uac15 \ucd5c\uc885 \uc6b0\uc2b9";
        double titleScale = fitTitleScale(board, textLayout, title);
        ensureBackground(board);
        spawnImage(winner, board.locationAt(0.0D, textLayout.contentY(), IMAGE_DEPTH), board.yaw(), fitImageSize(winner, board.width(), textLayout.contentHeight()), 0, false);
        spawnTitle(board, textLayout, title, titleScale);
        double nameScale = fitTextScale(winner.name(), textLayout.nameScale(), board.width() * 0.72D);
        Location nameLocation = board.locationAt(0.0D, -board.height() / 2.0D + board.height() / 5.0D, TEXT_DEPTH);
        TextDisplay nameText = spawnText(winner.name(), nameLocation, board.yaw(), nameScale, null);
        attachCandidateText(winner, nameText, nameLocation, board.locationAt(0.0D, -board.height() / 2.0D + 0.15D, TEXT_DEPTH), nameScale);
    }

    public void showRanking(String cupName, List<RankingRow> rows) {
        BoardSpec board = readBoardSpec();
        if (board == null) {
            return;
        }
        cancelAnimationTask();
        clearEntities();
        ensureBackground(board);

        TextLayout textLayout = textLayout(board);
        int limit = rows.size();
        if (limit <= 0) {
            spawnText("기록이 없습니다.", board.locationAt(0.0D, 0.0D, TEXT_DEPTH), board.yaw(), textLayout.nameScale(), null);
            return;
        }

        double rowGap = board.height() * 0.65D;
        double startY = -board.height() / 2.0D - rowGap * 0.35D;
        double endY = startY + rowGap * 2.2D * limit + board.height() * 1.2D;
        double imageYOffset = endY - startY;
        List<ScrollingImage> scrollingImages = spawnRankingImages(board, rows, limit, startY, rowGap);
        animateRankingMove(
                scrollingImages,
                imageYOffset,
                rankingScrollFrames(board, imageYOffset),
                board.centerY() + board.height() / 2.0D,
                board.centerY() - board.height() / 2.0D
        );
    }

    public void showPreview(Candidate candidate) {
        BoardSpec board = readBoardSpec();
        if (board == null) {
            return;
        }
        cancelAnimationTask();
        clearEntities();
        TextLayout textLayout = textLayout(board);
        String title = "미리보기 " + candidate.id();
        double titleScale = fitTitleScale(board, textLayout, title);
        ensureBackground(board);
        spawnImage(candidate, board.locationAt(0.0D, textLayout.contentY(), IMAGE_DEPTH), board.yaw(), fitImageSize(candidate, board.width(), textLayout.contentHeight()), 0, false);
        spawnTitle(board, textLayout, title, titleScale);
        double nameScale = fitTextScale(candidate.name(), textLayout.nameScale(), board.width() * 0.72D);
        Location nameLocation = board.locationAt(0.0D, -board.height() / 2.0D + board.height() / 5.0D, TEXT_DEPTH);
        TextDisplay nameText = spawnText(candidate.name(), nameLocation, board.yaw(), nameScale, null);
        attachCandidateText(candidate, nameText, nameLocation, board.locationAt(0.0D, -board.height() / 2.0D + 0.15D, TEXT_DEPTH), nameScale);
    }

    public void resetPlayback() {
        for (CandidateImage candidateImage : candidateImages) {
            if (candidateImage.display().isValid()) {
                candidateImage.display().setItemStack(createImageItem(candidateImage.candidate(), defaultItemModel(candidateImage.candidate())));
                showPlayOverlay(candidateImage);
                moveCandidateText(candidateImage, false);
            }
        }
    }

    public void resetPlayback(Candidate target) {
        for (CandidateImage candidateImage : candidateImages) {
            if (candidateImage.display().isValid() && candidateImage.candidate().equals(target)) {
                candidateImage.display().setItemStack(createImageItem(candidateImage.candidate(), defaultItemModel(candidateImage.candidate())));
                showPlayOverlay(candidateImage);
                moveCandidateText(candidateImage, false);
            }
        }
    }

    public void setPlaybackFrame(int frameIndex) {
        for (CandidateImage candidateImage : candidateImages) {
            if (!candidateImage.display().isValid()) {
                continue;
            }
            hidePlayOverlay(candidateImage);
            moveCandidateText(candidateImage, true);
            Candidate candidate = candidateImage.candidate();
            if (candidate.frameItemModels().isEmpty()) {
                candidateImage.display().setItemStack(createImageItem(candidate, defaultItemModel(candidate)));
                continue;
            }
            int boundedFrameIndex = Math.min(frameIndex, candidate.frameItemModels().size() - 1);
            candidateImage.display().setItemStack(createImageItem(candidate, candidate.frameItemModels().get(boundedFrameIndex)));
        }
    }

    public void setPlaybackFrame(Candidate target, int frameIndex) {
        for (CandidateImage candidateImage : candidateImages) {
            if (!candidateImage.display().isValid() || !candidateImage.candidate().equals(target)) {
                continue;
            }
            hidePlayOverlay(candidateImage);
            moveCandidateText(candidateImage, true);
            Candidate candidate = candidateImage.candidate();
            if (candidate.frameItemModels().isEmpty()) {
                candidateImage.display().setItemStack(createImageItem(candidate, defaultItemModel(candidate)));
                continue;
            }
            int boundedFrameIndex = Math.min(frameIndex, candidate.frameItemModels().size() - 1);
            candidateImage.display().setItemStack(createImageItem(candidate, candidate.frameItemModels().get(boundedFrameIndex)));
        }
    }

    public void clear() {
        cancelAnimationTask();
        clearEntities();
        clearBackground();
    }

    public void clearPersistedBoard() {
        cancelAnimationTask();
        clearEntities();
        clearBackground();
        BoardSpec board = readBoardSpec();
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (shouldClearPersistedEntity(entity, board)) {
                    entity.remove();
                }
            }
        }
    }

    private BoardSpec readBoardSpec() {
        Location pos1 = LocationConfig.read(plugin, "locations.pos1");
        Location pos2 = LocationConfig.read(plugin, "locations.pos2");
        if (pos1 == null || pos2 == null || pos1.getWorld() == null || pos2.getWorld() == null) {
            return null;
        }
        if (!pos1.getWorld().equals(pos2.getWorld())) {
            return null;
        }

        int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
        int minY = Math.min(pos1.getBlockY(), pos2.getBlockY());
        int maxY = Math.max(pos1.getBlockY(), pos2.getBlockY());
        int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());
        boolean useX = (maxX - minX) >= (maxZ - minZ);
        double width = Math.max(1.0D, useX ? maxX - minX + 1.0D : maxZ - minZ + 1.0D);
        double height = Math.max(1.0D, maxY - minY + 1.0D);
        double centerX = (double) minX + ((double) maxX - (double) minX + 1.0D) / 2.0D;
        double centerY = (double) minY + ((double) maxY - (double) minY + 1.0D) / 2.0D;
        double centerZ = (double) minZ + ((double) maxZ - (double) minZ + 1.0D) / 2.0D;
        BlockFace face = yawToFace(pos1.getYaw());
        return new BoardSpec(pos1.getWorld(), centerX, centerY, centerZ, width, height, useX, face, faceToYaw(face));
    }

    private ItemDisplay spawnImage(Candidate candidate, Location location, float yaw, DisplaySize size, int interpolationTicks, boolean playing) {
        ItemStack itemStack = createImageItem(candidate, playing ? candidate.itemModel() : defaultItemModel(candidate));

        ItemDisplay display = location.getWorld().spawn(location, ItemDisplay.class, itemDisplay -> {
            itemDisplay.setItemStack(itemStack);
            itemDisplay.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            itemDisplay.setBillboard(Display.Billboard.FIXED);
            itemDisplay.setRotation(yaw, 0.0F);
            itemDisplay.setInterpolationDuration(interpolationTicks);
            itemDisplay.setBrightness(new Display.Brightness(13, 13));
            itemDisplay.setTransformation(imageTransform(size));
            applyImageDisplayBounds(itemDisplay, size);
        });
        display.addScoreboardTag(DISPLAY_TAG);
        entities.add(display);
        TextDisplay playOverlay = candidate.manualPlayback() && !playing && interpolationTicks == 0
                ? spawnPlayOverlay(location, yaw, size)
                : null;
        candidateImages.add(new CandidateImage(display, candidate, playOverlay, null, null, null, 0.0D));
        return display;
    }

    private void attachCandidateText(Candidate target, TextDisplay textDisplay, Location normalLocation, Location loweredLocation, double scale) {
        for (int index = 0; index < candidateImages.size(); index++) {
            CandidateImage candidateImage = candidateImages.get(index);
            if (candidateImage.candidate().equals(target) && candidateImage.nameText() == null) {
                candidateImages.set(index, new CandidateImage(candidateImage.display(), candidateImage.candidate(), candidateImage.playOverlay(), textDisplay, normalLocation, loweredLocation, scale));
                return;
            }
        }
    }

    private TextDisplay spawnPlayOverlay(Location imageLocation, float yaw, DisplaySize size) {
        BlockFace face = yawToFace(yaw);
        Location location = imageLocation.clone().add(face.getModX() * 0.09D, 0.0D, face.getModZ() * 0.09D);
        double scale = Math.min(size.width(), size.height()) * 0.55D;
        TextDisplay display = location.getWorld().spawn(location, TextDisplay.class, textDisplay -> {
            textDisplay.text(Component.text("▶"));
            textDisplay.setBillboard(Display.Billboard.FIXED);
            textDisplay.setRotation(yaw, 0.0F);
            textDisplay.setAlignment(TextDisplay.TextAlignment.CENTER);
            textDisplay.setShadowed(false);
            textDisplay.setSeeThrough(false);
            textDisplay.setTextOpacity((byte) 255);
            textDisplay.setLineWidth(120);
            textDisplay.setDefaultBackground(false);
            textDisplay.setBackgroundColor(Color.fromARGB(150, 0, 0, 0));
            textDisplay.setBrightness(new Display.Brightness(13, 13));
            textDisplay.setTransformation(textTransform(scale));
        });
        display.addScoreboardTag(DISPLAY_TAG);
        entities.add(display);
        return display;
    }

    private void showPlayOverlay(CandidateImage candidateImage) {
        TextDisplay playOverlay = candidateImage.playOverlay();
        if (playOverlay != null && playOverlay.isValid()) {
            playOverlay.text(Component.text("▶"));
            playOverlay.setTextOpacity((byte) 255);
            playOverlay.setBackgroundColor(Color.fromARGB(150, 0, 0, 0));
        }
    }

    private void hidePlayOverlay(CandidateImage candidateImage) {
        TextDisplay playOverlay = candidateImage.playOverlay();
        if (playOverlay != null && playOverlay.isValid()) {
            playOverlay.text(Component.empty());
            playOverlay.setTextOpacity((byte) 0);
            playOverlay.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        }
    }

    private void moveCandidateText(CandidateImage candidateImage, boolean lowered) {
        TextDisplay nameText = candidateImage.nameText();
        Location location = lowered ? candidateImage.loweredTextLocation() : candidateImage.normalTextLocation();
        if (nameText != null && nameText.isValid() && location != null) {
            nameText.setTextOpacity((byte) 255);
            moveText(nameText, location, candidateImage.textScale());
        }
    }

    private org.bukkit.NamespacedKey defaultItemModel(Candidate candidate) {
        return candidate.manualPlayback() ? candidate.staticItemModel() : candidate.itemModel();
    }

    private ItemStack createImageItem(Candidate candidate, org.bukkit.NamespacedKey itemModel) {
        ItemStack itemStack = new ItemStack(Material.PAPER);
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.setItemModel(itemModel);
        itemMeta.itemName(Component.text(candidate.name()));
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    private void ensureBackground(BoardSpec board) {
        if (background != null && background.isValid()) {
            return;
        }
        Location location = board.locationAt(0.0D, 0.0D, 0.01D);
        background = location.getWorld().spawn(location, BlockDisplay.class, blockDisplay -> {
            blockDisplay.setBlock(Material.BLACK_CONCRETE.createBlockData());
            blockDisplay.setBillboard(Display.Billboard.FIXED);
            blockDisplay.setRotation(board.yaw(), 0.0F);
            blockDisplay.setTransformation(new Transformation(
                    new Vector3f((float) (-board.width() / 2.0D), (float) (-board.height() / 2.0D), 0.0F),
                    new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F),
                    new Vector3f((float) board.width(), (float) board.height(), 0.02F),
                    new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F)
            ));
        });
        background.addScoreboardTag(DISPLAY_TAG);
    }

    private TextDisplay spawnText(String text, Location location, float yaw, double scale, Color backgroundColor) {
        return spawnText(Component.text(text), location, yaw, scale, backgroundColor);
    }

    private TextDisplay spawnText(Component text, Location location, float yaw, double scale, Color backgroundColor) {
        return spawnText(text, location, yaw, scale, backgroundColor, 1.0D);
    }

    private TextDisplay spawnText(Component text, Location location, float yaw, double scale, Color backgroundColor, double outlineMultiplier) {
        List<TextDisplay> outlines = spawnTextOutlines(text, location, yaw, scale, outlineMultiplier);
        TextDisplay display = location.getWorld().spawn(location, TextDisplay.class, textDisplay -> {
            textDisplay.text(text);
            textDisplay.setBillboard(Display.Billboard.FIXED);
            textDisplay.setRotation(yaw, 0.0F);
            textDisplay.setAlignment(TextDisplay.TextAlignment.CENTER);
            textDisplay.setShadowed(false);
            textDisplay.setSeeThrough(false);
            textDisplay.setTextOpacity((byte) 255);
            textDisplay.setLineWidth(400);
            textDisplay.setDefaultBackground(false);
            textDisplay.setBackgroundColor(backgroundColor == null ? Color.fromARGB(0, 0, 0, 0) : backgroundColor);
            textDisplay.setBrightness(new Display.Brightness(13, 13));
            textDisplay.setTransformation(textTransform(scale));
        });
        display.addScoreboardTag(DISPLAY_TAG);
        entities.add(display);
        textOutlines.put(display, outlines);
        return display;
    }

    private Component vsText() {
        return Component.text("VS", TextColor.color(0xFFD37A));
    }

    private List<ScrollingImage> spawnRankingImages(BoardSpec board, List<RankingRow> rows, int limit, double baseY, double rowGap) {
        int imageCount = limit;
        List<ScrollingImage> images = new ArrayList<>();
        if (imageCount <= 0) {
            return images;
        }

        double imageAreaWidth = board.width() * 0.22D;
        double imageAreaHeight = rowGap * 0.68D;
        double x = 0.0D;
        double textBase = Math.max(0.25D, Math.min(rowGap, board.width() / 5.5D));
        double preferredRankScale = Math.max(0.35D, textBase * 0.78D);
        double preferredLabelScale = Math.max(0.24D, textBase * 0.48D);
        double preferredWinsScale = Math.max(0.26D, textBase * 0.50D);
        double rankY = baseY + board.height() * 0.18D;
        for (int index = 0; index < imageCount; index++) {
            RankingRow row = rows.get(index);
            Candidate candidate = row.candidate();
            if (candidate == null) {
                continue;
            }
            DisplaySize size = fitImageSize(candidate, imageAreaWidth, imageAreaHeight);
            String rank = (index + 1) + "위";
            String label = row.name();
            String stats = row.statText();
            double rankScale = fitTextScale(rank, preferredRankScale, imageAreaWidth * 1.4D);
            double labelScale = fitTextScale(label, preferredLabelScale, imageAreaWidth * 2.15D);
            double winsScale = fitTextScale(stats, preferredWinsScale, imageAreaWidth * 2.3D);
            double rankToImageGap = rankingRankToImageGap(board, rankScale);
            double imageToLabelGap = rankingImageToLabelGap(board, labelScale);
            double labelToStatGap = rankingLabelToStatGap(board, winsScale);
            double nextRankGap = dynamicNextRankGap(imageAreaHeight, rankScale, winsScale, rowGap);
            double rankHalfHeight = rankingRankHalfHeight(rankScale);
            double labelHalfHeight = rankingTextHalfHeight(labelScale);
            double winsHalfHeight = rankingTextHalfHeight(winsScale);
            double rankBottomY = rankY - rankHalfHeight;
            double imageTopY = rankBottomY - rankToImageGap;
            double y = imageTopY - size.height() / 2.0D;
            double imageBottomY = imageTopY - size.height();
            double labelTopY = imageBottomY - imageToLabelGap;
            double labelY = labelTopY - labelHalfHeight;
            double labelBottomY = labelTopY - labelHalfHeight * 2.0D;
            double statTopY = labelBottomY - labelToStatGap;
            double statY = statTopY - winsHalfHeight;
            double statBottomY = statTopY - winsHalfHeight * 2.0D;
            double rowStep = rankY - statBottomY + nextRankGap;
            images.add(new ScrollingImage(
                    candidate,
                    rank,
                    label,
                    stats,
                    board.locationAt(x, y, IMAGE_DEPTH),
                    board.locationAt(x, rankY, TEXT_DEPTH),
                    board.locationAt(x, labelY, TEXT_DEPTH),
                    board.locationAt(x, statY, TEXT_DEPTH),
                    rankScale,
                    labelScale,
                    winsScale,
                    size
            ));
            rankY -= rowStep;
        }
        return images;
    }

    private double rankingTextHalfHeight(double scale) {
        return scale * 0.14D;
    }

    private double rankingRankHalfHeight(double scale) {
        return scale * 0.08D;
    }

    private double rankingImageToLabelGap(BoardSpec board, double textScale) {
        double boardScale = Math.max(0.1D, Math.min(board.width(), board.height()));
        double gap = textScale * 0.15D;
        return clamp(gap, boardScale * 0.006D, boardScale * 0.18D);
    }

    private double rankingLabelToStatGap(BoardSpec board, double textScale) {
        double boardScale = Math.max(0.1D, Math.min(board.width(), board.height()));
        double gap = textScale * 0.01D;
        return clamp(gap, boardScale * 0.004D, boardScale * 0.16D);
    }

    private double rankingRankToImageGap(BoardSpec board, double textScale) {
        double boardScale = Math.max(0.1D, Math.min(board.width(), board.height()));
        double gap = textScale * 0.0D;
        return clamp(gap, 0.0D, boardScale * 0.02D);
    }

    private double dynamicNextRankGap(double imageHeight, double rankScale, double winsScale, double rowGap) {
        double sizeBasedGap = imageHeight * RANKING_NEXT_RANK_GAP_RATIO;
        double textBasedGap = Math.max(rankScale, winsScale) * 0.65D;
        double rowBasedGap = rowGap * 0.28D;
        return Math.max(0.35D, Math.min(Math.max(Math.max(sizeBasedGap, textBasedGap), rowBasedGap), rowGap * 0.9D));
    }

    private void animateRankingMove(List<ScrollingImage> images, double imageYOffset, int frames, double boardTopY, double boardBottomY) {
        animationTask = new BukkitRunnable() {
            private int frame;

            @Override
            public void run() {
                if (images.stream().allMatch(ScrollingImage::removed)) {
                    animationTask = null;
                    cancel();
                    return;
                }
                double progress = (double) frame / (double) Math.max(1, frames - 1);
                for (ScrollingImage image : images) {
                    if (image.removed()) {
                        continue;
                    }
                    double offset = imageYOffset * progress;
                    Location imageLocation = image.imageStart().clone().add(0.0D, offset, 0.0D);
                    if (!image.spawned() && imageLocation.getY() + image.size().height() / 2.0D >= boardBottomY) {
                        image.spawn(offset);
                    }
                    if (!image.spawned()) {
                        continue;
                    }
                    moveImage(image.display(), imageLocation, image.size());
                    double imageTopY = imageLocation.getY() + image.size().height() / 2.0D;
                    double imageBottomY = imageLocation.getY() - image.size().height() / 2.0D;
                    if (!image.animationStarted() && imageTopY >= boardBottomY && imageBottomY <= boardTopY) {
                        image.startAnimation(frame);
                    }
                    if (rankingRowBottomY(image, offset) > boardTopY) {
                        removeRankingImage(image);
                        continue;
                    }
                    if (image.animationStarted() && imageBottomY <= boardTopY) {
                        updateRankingFrame(image, frame - image.animationStartFrame());
                    }
                    moveText(image.rankDisplay(), image.rankStart().clone().add(0.0D, offset, 0.0D), image.rankScale());
                    moveText(image.labelDisplay(), image.labelStart().clone().add(0.0D, offset, 0.0D), image.labelScale());
                    moveText(image.winsDisplay(), image.winsStart().clone().add(0.0D, offset, 0.0D), image.winsScale());
                }
                frame++;
                if (frame >= frames) {
                    animationTask = null;
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private int resultAnimationFrames(BoardSpec board, double slideDistance) {
        double boardScale = Math.max(0.1D, Math.min(board.width(), board.height()));
        double normalizedDistance = slideDistance / Math.max(0.1D, board.width());
        double sizeFactor = clamp(boardScale / 6.0D, 0.75D, 1.35D);
        double distanceFactor = clamp(normalizedDistance / 0.58D, 0.85D, 1.2D);
        int preferredFrames = (int) Math.round(30.0D * sizeFactor * distanceFactor);
        int resultTicks = Math.max(12, plugin.getConfig().getInt("timing.result-seconds", 3) * 20);
        return Math.max(12, Math.min(preferredFrames, Math.max(12, resultTicks - 1)));
    }

    private double resultExitX(BoardSpec board, DisplaySize size, boolean leftSide) {
        double exitPadding = resultExitPadding(board);
        double exitDistance = board.width() / 2.0D + size.width() / 2.0D + exitPadding;
        return leftSide ? -exitDistance : exitDistance;
    }

    private double resultExitPadding(BoardSpec board) {
        double boardScale = Math.max(0.1D, Math.min(board.width(), board.height()));
        return clamp(board.width() * 0.025D, boardScale * 0.02D, board.width() * 0.06D);
    }

    private int rankingScrollFrames(BoardSpec board, double imageYOffset) {
        double boardHeights = imageYOffset / Math.max(0.1D, board.height());
        return Math.max(110, (int) Math.round(boardHeights * 130.0D));
    }

    private double rankingRowBottomY(ScrollingImage image, double offset) {
        double imageBottomY = image.imageStart().getY() + offset - image.size().height() / 2.0D;
        double rankBottomY = image.rankStart().getY() + offset - textHalfHeight(image.rankScale());
        double labelBottomY = image.labelStart().getY() + offset - textHalfHeight(image.labelScale());
        double winsBottomY = image.winsStart().getY() + offset - textHalfHeight(image.winsScale());
        return Math.min(Math.min(imageBottomY, rankBottomY), Math.min(labelBottomY, winsBottomY));
    }

    private double textHalfHeight(double scale) {
        return scale * 0.18D;
    }

    private void updateRankingFrame(ScrollingImage image, int elapsedTicks) {
        Candidate candidate = image.candidate();
        if (candidate.frameItemModels().isEmpty()) {
            return;
        }
        int cycleTicks = 0;
        for (int index = 0; index < candidate.frameItemModels().size(); index++) {
            cycleTicks += rankingFrameTicks(candidate, index);
        }
        if (cycleTicks <= 0) {
            return;
        }
        int tick = elapsedTicks % cycleTicks;
        int accumulatedTicks = 0;
        for (int index = 0; index < candidate.frameItemModels().size(); index++) {
            accumulatedTicks += rankingFrameTicks(candidate, index);
            if (tick < accumulatedTicks) {
                image.display().setItemStack(createImageItem(candidate, candidate.frameItemModels().get(index)));
                return;
            }
        }
    }

    private int rankingFrameTicks(Candidate candidate, int frameIndex) {
        if (frameIndex < candidate.frameTicks().size()) {
            return Math.max(1, candidate.frameTicks().get(frameIndex));
        }
        return 1;
    }

    private void removeRankingImage(ScrollingImage image) {
        removeIfValid(image.display());
        removeIfValid(image.rankDisplay());
        removeIfValid(image.labelDisplay());
        removeIfValid(image.winsDisplay());
        image.markRemoved();
    }

    private void removeIfValid(Entity entity) {
        if (entity instanceof TextDisplay textDisplay) {
            List<TextDisplay> outlines = textOutlines.remove(textDisplay);
            if (outlines != null) {
                for (TextDisplay outline : outlines) {
                    if (outline.isValid()) {
                        outline.remove();
                    }
                }
            }
        }
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }

    private void spawnTitle(BoardSpec board, TextLayout textLayout, String title, double titleScale) {
        spawnTitleBackground(board, textLayout);
        spawnTitleText(title, board.locationAt(0.0D, textLayout.titleTextY(), TITLE_TEXT_DEPTH), board.yaw(), titleScale);
    }

    private void spawnTitleBackground(BoardSpec board, TextLayout textLayout) {
        Location location = board.locationAt(0.0D, textLayout.titleY(), TITLE_BAR_DEPTH);
        BlockDisplay display = location.getWorld().spawn(location, BlockDisplay.class, blockDisplay -> {
            blockDisplay.setBlock(Material.BLACK_CONCRETE.createBlockData());
            blockDisplay.setBillboard(Display.Billboard.FIXED);
            blockDisplay.setRotation(board.yaw(), 0.0F);
            blockDisplay.setTransformation(new Transformation(
                    new Vector3f((float) (-board.width() / 2.0D), (float) (-textLayout.titleBarHeight() / 2.0D), 0.0F),
                    new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F),
                    new Vector3f((float) board.width(), (float) textLayout.titleBarHeight(), 0.02F),
                    new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F)
            ));
        });
        display.addScoreboardTag(DISPLAY_TAG);
        entities.add(display);
    }

    private void spawnTitleText(String text, Location location, float yaw, double scale) {
        Component component = Component.text(text);
        List<TextDisplay> outlines = spawnTextOutlines(component, location, yaw, scale);
        TextDisplay display = location.getWorld().spawn(location, TextDisplay.class, textDisplay -> {
            textDisplay.text(component);
            textDisplay.setBillboard(Display.Billboard.FIXED);
            textDisplay.setRotation(yaw, 0.0F);
            textDisplay.setAlignment(TextDisplay.TextAlignment.CENTER);
            textDisplay.setShadowed(false);
            textDisplay.setSeeThrough(false);
            textDisplay.setTextOpacity((byte) 255);
            textDisplay.setLineWidth(400);
            textDisplay.setDefaultBackground(false);
            textDisplay.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            textDisplay.setBrightness(new Display.Brightness(13, 13));
            textDisplay.setTransformation(textTransform(scale));
        });
        display.addScoreboardTag(DISPLAY_TAG);
        entities.add(display);
        textOutlines.put(display, outlines);
    }

    private List<TextDisplay> spawnTextOutlines(Component text, Location location, float yaw, double scale) {
        return spawnTextOutlines(text, location, yaw, scale, 1.0D);
    }

    private List<TextDisplay> spawnTextOutlines(Component text, Location location, float yaw, double scale, double outlineMultiplier) {
        if (outlineMultiplier <= 0.0D) {
            return List.of();
        }
        List<TextDisplay> outlines = new ArrayList<>(4);
        double offset = outlineOffset(scale, outlineMultiplier);
        outlines.add(spawnTextOutline(text, outlineLocation(location, yaw, -offset, 0.0D), yaw, scale));
        outlines.add(spawnTextOutline(text, outlineLocation(location, yaw, offset, 0.0D), yaw, scale));
        outlines.add(spawnTextOutline(text, outlineLocation(location, yaw, 0.0D, -offset), yaw, scale));
        outlines.add(spawnTextOutline(text, outlineLocation(location, yaw, 0.0D, offset), yaw, scale));
        return outlines;
    }

    private TextDisplay spawnTextOutline(Component text, Location location, float yaw, double scale) {
        Component outlineText = Component.text(PlainTextComponentSerializer.plainText().serialize(text), NamedTextColor.BLACK);
        TextDisplay display = location.getWorld().spawn(location, TextDisplay.class, textDisplay -> {
            textDisplay.text(outlineText);
            textDisplay.setBillboard(Display.Billboard.FIXED);
            textDisplay.setRotation(yaw, 0.0F);
            textDisplay.setAlignment(TextDisplay.TextAlignment.CENTER);
            textDisplay.setShadowed(false);
            textDisplay.setSeeThrough(false);
            textDisplay.setTextOpacity((byte) 255);
            textDisplay.setLineWidth(400);
            textDisplay.setDefaultBackground(false);
            textDisplay.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            textDisplay.setBrightness(new Display.Brightness(15, 15));
            textDisplay.setTransformation(textTransform(scale));
        });
        display.addScoreboardTag(DISPLAY_TAG);
        entities.add(display);
        return display;
    }

    private Location outlineLocation(Location location, float yaw, double horizontalOffset, double verticalOffset) {
        BlockFace face = yawToFace(yaw);
        double depthX = face.getModX() * -TEXT_OUTLINE_DEPTH_OFFSET;
        double depthZ = face.getModZ() * -TEXT_OUTLINE_DEPTH_OFFSET;
        if (face == BlockFace.NORTH || face == BlockFace.SOUTH) {
            return location.clone().add(horizontalOffset + depthX, verticalOffset, depthZ);
        }
        return location.clone().add(depthX, verticalOffset, horizontalOffset + depthZ);
    }

    private double outlineOffset(double scale, double multiplier) {
        return TEXT_OUTLINE_OFFSET * multiplier * clamp(scale / 3.0D, 0.22D, 1.6D);
    }

    private void moveImage(ItemDisplay display, Location location, DisplaySize size) {
        if (!display.isValid()) {
            return;
        }
        display.teleport(location);
        display.setTransformation(imageTransform(size));
        applyImageDisplayBounds(display, size);
    }

    private void applyImageDisplayBounds(ItemDisplay display, DisplaySize size) {
        display.setDisplayWidth((float) Math.max(0.1D, size.width()));
        display.setDisplayHeight((float) Math.max(0.1D, size.height()));
        display.setViewRange((float) clamp(Math.max(size.width(), size.height()) * 4.0D, 8.0D, 128.0D));
    }

    private void moveText(TextDisplay display, Location location, double scale) {
        if (!display.isValid()) {
            return;
        }
        display.teleport(location);
        display.setTransformation(textTransform(scale));
        moveTextOutlines(display, location, scale);
    }

    private void moveTextOutlines(TextDisplay display, Location location, double scale) {
        List<TextDisplay> outlines = textOutlines.get(display);
        if (outlines == null) {
            return;
        }
        double offset = outlineOffset(scale, 1.0D);
        Location[] locations = new Location[] {
                outlineLocation(location, display.getLocation().getYaw(), -offset, 0.0D),
                outlineLocation(location, display.getLocation().getYaw(), offset, 0.0D),
                outlineLocation(location, display.getLocation().getYaw(), 0.0D, -offset),
                outlineLocation(location, display.getLocation().getYaw(), 0.0D, offset)
        };
        for (int index = 0; index < outlines.size() && index < locations.length; index++) {
            TextDisplay outline = outlines.get(index);
            if (outline.isValid()) {
                outline.teleport(locations[index]);
                outline.setTransformation(textTransform(scale));
            }
        }
    }

    private Transformation imageTransform(DisplaySize size) {
        return new Transformation(
                new Vector3f(0.0F, 0.0F, 0.0F),
                new AxisAngle4f((float) Math.PI, 0.0F, 1.0F, 0.0F),
                new Vector3f((float) size.width(), (float) size.height(), 0.001F),
                new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F)
        );
    }

    private Transformation textTransform(double scale) {
        return textTransform(scale, scale);
    }

    private Transformation textTransform(double scaleX, double scaleY) {
        return new Transformation(
                new Vector3f(0.0F, 0.0F, 0.0F),
                new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F),
                new Vector3f((float) scaleX, (float) scaleY, 1.0F),
                new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F)
        );
    }

    private TextLayout textLayout(BoardSpec board) {
        double base = Math.max(0.25D, Math.min(board.height(), board.width() / 4.0D));
        double titleScale = Math.max(0.45D, base * 1.05D);
        double nameScale = Math.max(0.35D, base * 0.72D);
        double vsScale = Math.max(0.45D, base * 1.0D);
        double titleBarHeight = Math.max(base * 0.35D, titleScale * 0.34D);
        double contentHeight = Math.max(1.0D, board.height() - titleBarHeight);
        double contentY = -titleBarHeight / 2.0D;
        double titleY = board.height() / 2.0D - titleBarHeight / 2.0D;
        double titleTextY = titleY - titleScale * 0.12D;
        double nameY = -board.height() / 2.0D + contentHeight * 0.38D;
        return new TextLayout(titleY, titleTextY, nameY, titleScale, nameScale, vsScale, titleBarHeight, contentY, contentHeight);
    }

    private double fitTitleScale(BoardSpec board, TextLayout textLayout, String title) {
        return fitTextScale(title, textLayout.titleScale(), board.width() * 0.82D);
    }

    private double fitTextScale(String text, double preferredScale, double maxWorldWidth) {
        int length = Math.max(1, text.length());
        double estimatedWidth = preferredScale * length * 0.16D;
        if (estimatedWidth <= maxWorldWidth) {
            return preferredScale;
        }
        return Math.max(0.25D, preferredScale * maxWorldWidth / estimatedWidth);
    }

    private double clamp(double value, double min, double max) {
        double lower = Math.min(min, max);
        double upper = Math.max(min, max);
        return Math.max(lower, Math.min(upper, value));
    }

    private DisplaySize fitImageSize(Candidate candidate, double maxWidth, double maxHeight) {
        double aspectRatio = candidate.imageHeight() <= 0 ? 1.0D : (double) candidate.imageWidth() / (double) candidate.imageHeight();
        if (aspectRatio <= 0.0D || Double.isNaN(aspectRatio) || Double.isInfinite(aspectRatio)) {
            aspectRatio = 1.0D;
        }
        double height = maxHeight;
        double width = height * aspectRatio;
        if (width > maxWidth) {
            width = maxWidth;
            height = width / aspectRatio;
        }
        return new DisplaySize(width, height);
    }

    private double candidateX(boolean leftSide, DisplaySize size) {
        return leftSide ? -size.width() / 2.0D : size.width() / 2.0D;
    }

    private Location fixedNameLocation(BoardSpec board, TextLayout textLayout, boolean leftSide) {
        return board.locationAt(matchNameX(board, leftSide), matchNameY(board, textLayout), TEXT_DEPTH);
    }

    private Location fixedLoweredNameLocation(BoardSpec board, boolean leftSide) {
        return board.locationAt(fixedNameX(board, leftSide), -board.height() / 2.0D + 0.15D, TEXT_DEPTH);
    }

    private double fixedNameX(BoardSpec board, boolean leftSide) {
        double margin = board.width() * 0.03D;
        double halfCenter = board.width() * 0.25D;
        return leftSide ? -halfCenter + margin : halfCenter - margin;
    }

    private double matchNameX(BoardSpec board, boolean leftSide) {
        double x = board.width() * MATCH_NAME_CENTER_X_RATIO;
        return leftSide ? -x : x;
    }

    private double matchNameY(BoardSpec board, TextLayout textLayout) {
        return textLayout.nameY() - board.height() * MATCH_NAME_Y_DROP_RATIO;
    }

    private DisplaySize lerpSize(DisplaySize start, DisplaySize end, double progress) {
        return new DisplaySize(
                lerp(start.width(), end.width(), progress),
                lerp(start.height(), end.height(), progress)
        );
    }

    private void clearEntities() {
        for (Entity entity : entities) {
            if (entity.isValid()) {
                entity.remove();
            }
        }
        entities.clear();
        candidateImages.clear();
        textOutlines.clear();

        BoardSpec board = readBoardSpec();
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (shouldClearPersistedEntity(entity, board)) {
                    entity.remove();
                }
            }
        }
    }

    private void clearBackground() {
        if (background != null && background.isValid()) {
            background.remove();
        }
        background = null;
    }

    private boolean shouldClearPersistedEntity(Entity entity, BoardSpec board) {
        if (!(entity instanceof Display)) {
            return false;
        }
        if (background != null && background.isValid() && entity.getUniqueId().equals(background.getUniqueId())) {
            return false;
        }
        if (entity.getScoreboardTags().contains(DISPLAY_TAG)) {
            return true;
        }
        return board != null && entity.getWorld().equals(board.world()) && isInBoardClearArea(entity.getLocation(), board);
    }

    private boolean isInBoardClearArea(Location location, BoardSpec board) {
        double minY = board.centerY() - board.height() / 2.0D - CLEAR_VERTICAL_MARGIN;
        double maxY = board.centerY() + board.height() / 2.0D + CLEAR_VERTICAL_MARGIN;
        if (location.getY() < minY || location.getY() > maxY) {
            return false;
        }
        if (board.useX()) {
            return Math.abs(location.getX() - board.centerX()) <= board.width() / 2.0D + CLEAR_HORIZONTAL_MARGIN
                    && Math.abs(location.getZ() - board.centerZ()) <= CLEAR_DEPTH_MARGIN;
        }
        return Math.abs(location.getZ() - board.centerZ()) <= board.width() / 2.0D + CLEAR_HORIZONTAL_MARGIN
                && Math.abs(location.getX() - board.centerX()) <= CLEAR_DEPTH_MARGIN;
    }

    private void cancelAnimationTask() {
        if (animationTask != null) {
            animationTask.cancel();
            animationTask = null;
        }
    }

    private double lerp(double start, double end, double progress) {
        return start + (end - start) * progress;
    }

    private double easeOutCubic(double progress) {
        double inverted = 1.0D - progress;
        return 1.0D - inverted * inverted * inverted;
    }

    private BlockFace yawToFace(float yaw) {
        float normalized = (yaw % 360.0F + 360.0F) % 360.0F;
        if (normalized >= 45.0F && normalized < 135.0F) {
            return BlockFace.WEST;
        }
        if (normalized >= 135.0F && normalized < 225.0F) {
            return BlockFace.NORTH;
        }
        if (normalized >= 225.0F && normalized < 315.0F) {
            return BlockFace.EAST;
        }
        return BlockFace.SOUTH;
    }

    private float faceToYaw(BlockFace face) {
        return switch (face) {
            case NORTH -> 180.0F;
            case EAST -> -90.0F;
            case WEST -> 90.0F;
            default -> 0.0F;
        };
    }

    private record TextLayout(double titleY, double titleTextY, double nameY, double titleScale, double nameScale, double vsScale, double titleBarHeight, double contentY, double contentHeight) {
    }

    private record DisplaySize(double width, double height) {
    }

    private record CandidateImage(ItemDisplay display, Candidate candidate, TextDisplay playOverlay, TextDisplay nameText, Location normalTextLocation, Location loweredTextLocation, double textScale) {
    }

    private final class ScrollingImage {
        private final Candidate candidate;
        private final String rank;
        private final String label;
        private final String stats;
        private final Location imageStart;
        private final Location rankStart;
        private final Location labelStart;
        private final Location winsStart;
        private final double rankScale;
        private final double labelScale;
        private final double winsScale;
        private final DisplaySize size;
        private ItemDisplay display;
        private TextDisplay rankDisplay;
        private TextDisplay labelDisplay;
        private TextDisplay winsDisplay;
        private boolean removed;
        private boolean animationStarted;
        private int animationStartFrame;

        private ScrollingImage(Candidate candidate, String rank, String label, String stats, Location imageStart, Location rankStart, Location labelStart, Location winsStart, double rankScale, double labelScale, double winsScale, DisplaySize size) {
            this.candidate = candidate;
            this.rank = rank;
            this.label = label;
            this.stats = stats;
            this.imageStart = imageStart;
            this.rankStart = rankStart;
            this.labelStart = labelStart;
            this.winsStart = winsStart;
            this.rankScale = rankScale;
            this.labelScale = labelScale;
            this.winsScale = winsScale;
            this.size = size;
        }

        private boolean spawned() {
            return display != null && display.isValid();
        }

        private boolean removed() {
            return removed;
        }

        private void markRemoved() {
            removed = true;
        }

        private void spawn(double offset) {
            display = spawnImage(candidate, imageStart.clone().add(0.0D, offset, 0.0D), imageStart.getYaw(), size, 1, false);
            rankDisplay = spawnText(Component.text(rank), rankStart.clone().add(0.0D, offset, 0.0D), rankStart.getYaw(), rankScale, null, 0.0D);
            labelDisplay = spawnText(Component.text(label), labelStart.clone().add(0.0D, offset, 0.0D), labelStart.getYaw(), labelScale, null, 0.0D);
            winsDisplay = spawnText(Component.text(stats, NamedTextColor.GOLD), winsStart.clone().add(0.0D, offset, 0.0D), winsStart.getYaw(), winsScale, null, 0.0D);
        }

        private boolean animationStarted() {
            return animationStarted;
        }

        private int animationStartFrame() {
            return animationStartFrame;
        }

        private void startAnimation(int frame) {
            animationStarted = true;
            animationStartFrame = frame;
        }

        private ItemDisplay display() {
            return display;
        }

        private Candidate candidate() {
            return candidate;
        }

        private TextDisplay rankDisplay() {
            return rankDisplay;
        }

        private TextDisplay labelDisplay() {
            return labelDisplay;
        }

        private TextDisplay winsDisplay() {
            return winsDisplay;
        }

        private Location imageStart() {
            return imageStart;
        }

        private Location rankStart() {
            return rankStart;
        }

        private Location labelStart() {
            return labelStart;
        }

        private Location winsStart() {
            return winsStart;
        }

        private double rankScale() {
            return rankScale;
        }

        private double labelScale() {
            return labelScale;
        }

        private double winsScale() {
            return winsScale;
        }

        private DisplaySize size() {
            return size;
        }
    }

    public record RankingRow(Candidate candidate, String name, String resultLabel, long votes) {
        private String statText() {
            return resultLabel + " · " + votes + "표 획득";
        }
    }

    private record BoardSpec(World world, double centerX, double centerY, double centerZ, double width, double height, boolean useX, BlockFace face, float yaw) {
        private Location locationAt(double horizontalOffset, double verticalOffset, double depthOffset) {
            double normalX = face.getModX() * depthOffset;
            double normalZ = face.getModZ() * depthOffset;
            if (useX) {
                return new Location(world, centerX + horizontalOffset + normalX, centerY + verticalOffset, centerZ + normalZ, yaw, 0.0F);
            }
            return new Location(world, centerX + normalX, centerY + verticalOffset, centerZ + horizontalOffset + normalZ, yaw, 0.0F);
        }
    }
}

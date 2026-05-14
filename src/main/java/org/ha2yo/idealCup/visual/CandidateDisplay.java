package org.ha2yo.idealCup.visual;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import java.util.List;
import java.util.Optional;

public final class CandidateDisplay {
    private static final String DISPLAY_TAG = "idealcup_board_display";
    private static final double CLEAR_HORIZONTAL_MARGIN = 8.0D;
    private static final double CLEAR_VERTICAL_MARGIN = 4.0D;
    private static final double CLEAR_DEPTH_MARGIN = 4.0D;

    private final JavaPlugin plugin;
    private final List<Entity> entities = new ArrayList<>();
    private final List<CandidateImage> candidateImages = new ArrayList<>();
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

        double candidateAreaWidth = board.width() / 2.0D;
        double candidateAreaHeight = board.height();
        DisplaySize leftSize = fitImageSize(left, candidateAreaWidth, candidateAreaHeight);
        DisplaySize rightSize = fitImageSize(right, candidateAreaWidth, candidateAreaHeight);
        TextLayout textLayout = textLayout(board);
        String title = cupName + " " + roundSize + "강   " + matchNumber + "/" + totalMatches;
        double leftX = candidateX(true, leftSize);
        double rightX = candidateX(false, rightSize);
        ensureBackground(board);
        spawnImage(left, board.locationAt(leftX, 0.0D, 0.05D), board.yaw(), leftSize, 0, false);
        spawnImage(right, board.locationAt(rightX, 0.0D, 0.05D), board.yaw(), rightSize, 0, false);
        spawnTitle(board, textLayout, title);
        spawnText("VS", board.locationAt(0.0D, 0.0D, 0.13D), board.yaw(), textLayout.vsScale(), null);
        double leftNameScale = fitTextScale(left.name(), textLayout.nameScale(), board.width() * 0.38D);
        double rightNameScale = fitTextScale(right.name(), textLayout.nameScale(), board.width() * 0.38D);
        Location leftNameLocation = board.locationAt(leftX, textLayout.nameY(), 0.13D);
        Location rightNameLocation = board.locationAt(rightX, textLayout.nameY(), 0.13D);
        TextDisplay leftText = spawnText(left.name(), leftNameLocation, board.yaw(), leftNameScale, null);
        TextDisplay rightText = spawnText(right.name(), rightNameLocation, board.yaw(), rightNameScale, null);
        attachCandidateText(left, leftText, leftNameLocation, board.locationAt(leftX, -board.height() / 2.0D + 0.15D, 0.13D), leftNameScale);
        attachCandidateText(right, rightText, rightNameLocation, board.locationAt(rightX, -board.height() / 2.0D + 0.15D, 0.13D), rightNameScale);
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
        double candidateAreaWidth = board.width() / 2.0D;
        double candidateAreaHeight = board.height();
        DisplaySize loserSize = fitImageSize(loser, candidateAreaWidth, candidateAreaHeight);
        DisplaySize winnerStartSize = fitImageSize(winner, candidateAreaWidth, candidateAreaHeight);
        DisplaySize winnerEndSize = fitImageSize(winner, board.width(), board.height());
        TextLayout textLayout = textLayout(board);
        String title = cupName + " " + roundSize + "강   " + matchNumber + "/" + totalMatches;
        double loserStart = candidateX(loserStartedLeft, loserSize);
        double winnerStart = candidateX(winnerStartedLeft, winnerStartSize);
        ensureBackground(board);

        ItemDisplay loserDisplay = spawnImage(loser, board.locationAt(loserStart, 0.0D, 0.05D), board.yaw(), loserSize, 2, false);
        ItemDisplay winnerDisplay = spawnImage(winner, board.locationAt(winnerStart, 0.0D, 0.06D), board.yaw(), winnerStartSize, 2, false);
        double loserNameScale = fitTextScale(loser.name(), textLayout.nameScale(), board.width() * 0.38D);
        double winnerNameScale = fitTextScale(winner.name(), textLayout.nameScale(), board.width() * 0.38D);
        TextDisplay loserText = spawnText(loser.name(), board.locationAt(loserStart, textLayout.nameY(), 0.13D), board.yaw(), loserNameScale, null);
        TextDisplay winnerText = spawnText(winner.name(), board.locationAt(winnerStart, textLayout.nameY(), 0.14D), board.yaw(), winnerNameScale, null);
        spawnTitle(board, textLayout, title);

        int animationFrames = 30;
        animationTask = new BukkitRunnable() {
            private int frame;

            @Override
            public void run() {
                double progress = easeOutCubic((double) frame / (double) (animationFrames - 1));
                double loserExitPadding = 0.25D;
                double loserEnd = loserStartedLeft ? -board.width() / 2.0D - loserSize.width() / 2.0D - loserExitPadding : board.width() / 2.0D + loserSize.width() / 2.0D + loserExitPadding;
                double xLoser = lerp(loserStart, loserEnd, progress);
                double xWinner = lerp(winnerStart, 0.0D, progress);
                DisplaySize winnerSize = lerpSize(winnerStartSize, winnerEndSize, progress);
                moveImage(loserDisplay, board.locationAt(xLoser, 0.0D, 0.05D), loserSize);
                moveImage(winnerDisplay, board.locationAt(xWinner, 0.0D, 0.06D), winnerSize);
                moveText(loserText, board.locationAt(xLoser, textLayout.nameY(), 0.13D), loserNameScale);
                moveText(winnerText, board.locationAt(xWinner, textLayout.nameY(), 0.14D), winnerNameScale);
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
        ensureBackground(board);
        spawnImage(winner, board.locationAt(0.0D, 0.0D, 0.05D), board.yaw(), fitImageSize(winner, board.width(), board.height()), 0, false);
        spawnTitle(board, textLayout, title);
        double nameScale = fitTextScale(winner.name(), textLayout.nameScale(), board.width() * 0.72D);
        Location nameLocation = board.locationAt(0.0D, -board.height() / 2.0D + board.height() / 5.0D, 0.13D);
        TextDisplay nameText = spawnText(winner.name(), nameLocation, board.yaw(), nameScale, null);
        attachCandidateText(winner, nameText, nameLocation, board.locationAt(0.0D, -board.height() / 2.0D + 0.15D, 0.13D), nameScale);
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
            spawnText("기록이 없습니다.", board.locationAt(0.0D, 0.0D, 0.14D), board.yaw(), textLayout.nameScale(), null);
            return;
        }

        double rowGap = board.height() * 0.48D;
        double startY = -board.height() / 2.0D - rowGap * 0.35D;
        double endY = startY + rowGap * limit + board.height() * 1.2D;
        List<ScrollingImage> scrollingImages = spawnRankingImages(board, rows, limit, startY, rowGap);
        animateRankingMove(scrollingImages, endY - startY, 300 + limit * 75);
    }

    public void showPreview(Candidate candidate) {
        BoardSpec board = readBoardSpec();
        if (board == null) {
            return;
        }
        cancelAnimationTask();
        clearEntities();
        TextLayout textLayout = textLayout(board);
        ensureBackground(board);
        spawnImage(candidate, board.locationAt(0.0D, 0.0D, 0.05D), board.yaw(), fitImageSize(candidate, board.width(), board.height()), 0, false);
        spawnTitle(board, textLayout, "미리보기 " + candidate.id());
        double nameScale = fitTextScale(candidate.name(), textLayout.nameScale(), board.width() * 0.72D);
        Location nameLocation = board.locationAt(0.0D, -board.height() / 2.0D + board.height() / 5.0D, 0.13D);
        TextDisplay nameText = spawnText(candidate.name(), nameLocation, board.yaw(), nameScale, null);
        attachCandidateText(candidate, nameText, nameLocation, board.locationAt(0.0D, -board.height() / 2.0D + 0.15D, 0.13D), nameScale);
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
        double scale = Math.max(1.8D, Math.min(size.width(), size.height()) * 0.65D);
        TextDisplay display = location.getWorld().spawn(location, TextDisplay.class, textDisplay -> {
            textDisplay.text(Component.text("▶"));
            textDisplay.setBillboard(Display.Billboard.FIXED);
            textDisplay.setRotation(yaw, 0.0F);
            textDisplay.setAlignment(TextDisplay.TextAlignment.CENTER);
            textDisplay.setShadowed(true);
            textDisplay.setSeeThrough(false);
            textDisplay.setTextOpacity((byte) 255);
            textDisplay.setLineWidth(120);
            textDisplay.setDefaultBackground(false);
            textDisplay.setBackgroundColor(Color.fromARGB(150, 0, 0, 0));
            textDisplay.setBrightness(new Display.Brightness(15, 15));
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
        TextDisplay display = location.getWorld().spawn(location, TextDisplay.class, textDisplay -> {
            textDisplay.text(text);
            textDisplay.setBillboard(Display.Billboard.FIXED);
            textDisplay.setRotation(yaw, 0.0F);
            textDisplay.setAlignment(TextDisplay.TextAlignment.CENTER);
            textDisplay.setShadowed(true);
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
        return display;
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
        double rankX = x - imageAreaWidth * 1.05D;
        double winsX = x + imageAreaWidth * 1.05D;
        double rankScale = 3.8D;
        double winsScale = 3.5D;
        double topY = baseY + board.height() * 0.18D;
        for (int index = 0; index < imageCount; index++) {
            RankingRow row = rows.get(index);
            Candidate candidate = row.candidate();
            if (candidate == null) {
                continue;
            }
            DisplaySize size = fitImageSize(candidate, imageAreaWidth, imageAreaHeight);
            double y = topY - index * rowGap;
            ItemDisplay image = spawnImage(candidate, board.locationAt(x, y, 0.08D), board.yaw(), size, 1, true);
            Component rank = Component.text((index + 1) + "위");
            Component label = Component.text(row.name());
            double labelScale = fitTextScale(row.name(), 2.7D, imageAreaWidth * 2.15D);
            double labelYOffset = size.height() / 2.0D + 0.9D;
            double statY = y - 0.12D;
            TextDisplay rankDisplay = spawnText(rank, board.locationAt(rankX, statY, 0.14D), board.yaw(), rankScale, null);
            TextDisplay labelDisplay = spawnText(label, board.locationAt(x, y - labelYOffset, 0.14D), board.yaw(), labelScale, null);
            TextDisplay winsDisplay = spawnText(Component.text(row.statText(), NamedTextColor.GOLD), board.locationAt(winsX, statY, 0.14D), board.yaw(), winsScale, null);
            images.add(new ScrollingImage(
                    image,
                    candidate,
                    rankDisplay,
                    labelDisplay,
                    winsDisplay,
                    board.locationAt(x, y, 0.08D),
                    board.locationAt(rankX, statY, 0.14D),
                    board.locationAt(x, y - labelYOffset, 0.14D),
                    board.locationAt(winsX, statY, 0.14D),
                    rankScale,
                    labelScale,
                    winsScale,
                    size
            ));
        }
        return images;
    }

    private void animateRankingMove(List<ScrollingImage> images, double imageYOffset, int frames) {
        animationTask = new BukkitRunnable() {
            private int frame;

            @Override
            public void run() {
                if (images.stream().noneMatch(image -> image.display().isValid())) {
                    animationTask = null;
                    cancel();
                    return;
                }
                double progress = (double) frame / (double) Math.max(1, frames - 1);
                for (ScrollingImage image : images) {
                    double offset = imageYOffset * progress;
                    moveImage(image.display(), image.imageStart().clone().add(0.0D, offset, 0.0D), image.size());
                    updateRankingFrame(image, frame);
                    moveText(image.rank(), image.rankStart().clone().add(0.0D, offset, 0.0D), image.rankScale());
                    moveText(image.label(), image.labelStart().clone().add(0.0D, offset, 0.0D), image.labelScale());
                    moveText(image.wins(), image.winsStart().clone().add(0.0D, offset, 0.0D), image.winsScale());
                }
                frame++;
                if (frame >= frames) {
                    animationTask = null;
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
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

    private void spawnTitle(BoardSpec board, TextLayout textLayout, String title) {
        int spaceCount = titleBackgroundSpaces(board, textLayout.titleScale());
        spawnTextBackground(" ".repeat(spaceCount), board.locationAt(0.0D, textLayout.titleY(), 0.115D), board.yaw(), textLayout.titleScale(), Color.fromARGB(190, 0, 0, 0));
        spawnText(title, board.locationAt(0.0D, textLayout.titleY(), 0.14D), board.yaw(), fitTextScale(title, textLayout.titleScale(), board.width() * 0.82D), null);
    }

    private void spawnTextBackground(String text, Location location, float yaw, double scale, Color backgroundColor) {
        TextDisplay display = location.getWorld().spawn(location, TextDisplay.class, textDisplay -> {
            textDisplay.text(Component.text(text));
            textDisplay.setBillboard(Display.Billboard.FIXED);
            textDisplay.setRotation(yaw, 0.0F);
            textDisplay.setAlignment(TextDisplay.TextAlignment.CENTER);
            textDisplay.setShadowed(false);
            textDisplay.setSeeThrough(false);
            textDisplay.setTextOpacity((byte) 0);
            textDisplay.setLineWidth(2000);
            textDisplay.setDefaultBackground(false);
            textDisplay.setBackgroundColor(backgroundColor);
            textDisplay.setBrightness(new Display.Brightness(13, 13));
            textDisplay.setTransformation(textTransform(scale));
        });
        display.addScoreboardTag(DISPLAY_TAG);
        entities.add(display);
    }

    private void moveImage(ItemDisplay display, Location location, DisplaySize size) {
        if (!display.isValid()) {
            return;
        }
        display.teleport(location);
        display.setTransformation(imageTransform(size));
    }

    private void moveText(TextDisplay display, Location location, double scale) {
        if (!display.isValid()) {
            return;
        }
        display.teleport(location);
        display.setTransformation(textTransform(scale));
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
        return new Transformation(
                new Vector3f(0.0F, 0.0F, 0.0F),
                new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F),
                new Vector3f((float) scale, (float) scale, (float) scale),
                new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F)
        );
    }

    private TextLayout textLayout(BoardSpec board) {
        double base = Math.max(0.8D, Math.min(board.height(), board.width() / 4.0D));
        double titleScale = clamp(base * 1.05D, 2.6D, 7.2D);
        double nameScale = clamp(base * 0.72D, 2.0D, 5.2D);
        double vsScale = clamp(base * 1.0D, 2.6D, 6.5D);
        double titleY = board.height() / 2.0D - Math.max(0.75D, titleScale * 0.24D);
        double nameY = -board.height() / 2.0D + board.height() * 0.38D;
        return new TextLayout(titleY, nameY, titleScale, nameScale, vsScale);
    }

    private double fitTextScale(String text, double preferredScale, double maxWorldWidth) {
        int length = Math.max(1, text.length());
        double estimatedWidth = preferredScale * length * 0.16D;
        if (estimatedWidth <= maxWorldWidth) {
            return preferredScale;
        }
        return Math.max(0.55D, preferredScale * maxWorldWidth / estimatedWidth);
    }

    private int titleBackgroundSpaces(BoardSpec board, double titleScale) {
        double boardRatio = board.width() / Math.max(1.0D, board.height());
        double scaleRatio = Math.max(1.0D, board.height()) / Math.max(0.01D, titleScale);
        return Math.max(1, (int) Math.ceil(boardRatio * scaleRatio * 12.0D));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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

    private record TextLayout(double titleY, double nameY, double titleScale, double nameScale, double vsScale) {
    }

    private record DisplaySize(double width, double height) {
    }

    private record CandidateImage(ItemDisplay display, Candidate candidate, TextDisplay playOverlay, TextDisplay nameText, Location normalTextLocation, Location loweredTextLocation, double textScale) {
    }

    private record ScrollingImage(ItemDisplay display, Candidate candidate, TextDisplay rank, TextDisplay label, TextDisplay wins, Location imageStart, Location rankStart, Location labelStart, Location winsStart, double rankScale, double labelScale, double winsScale, DisplaySize size) {
    }

    public record RankingRow(Candidate candidate, String name, String resultLabel, long votes) {
        private String statText() {
            return resultLabel + "\n" + votes + "표 획득";
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

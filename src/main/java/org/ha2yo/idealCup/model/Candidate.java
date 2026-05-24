package org.ha2yo.idealCup.model;

import org.bukkit.NamespacedKey;

import java.util.List;

public record Candidate(String id, String name, String imagePath, NamespacedKey itemModel, NamespacedKey staticItemModel, List<NamespacedKey> frameItemModels, List<Integer> frameTicks, int imageWidth, int imageHeight, long playbackTicks, String soundKey, boolean manualPlayback) {
}

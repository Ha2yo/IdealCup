package org.ha2yo.idealCup.game;

import org.ha2yo.idealCup.model.Candidate;

public record Match(Candidate left, Candidate right, int roundSize, int matchNumber, int totalMatches) {
}
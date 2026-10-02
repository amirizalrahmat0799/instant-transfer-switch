package com.its.settlement;

import java.util.List;

/**
 * Multilateral netting: each bank's settlement amount is what it received minus what it sent in the cycle.
 * Every ringgit sent by one bank was received by another, so the nets always add up to zero.
 */
public final class Netting {

    public record Position(String bic, long sentCount, long sentAmount, long receivedCount, long receivedAmount) {
    }

    public record Line(String bic, long sentCount, long sentAmount, long receivedCount, long receivedAmount, long net) {
    }

    private Netting() {
    }

    /** Nets a closed cycle and checks that it balances. */
    public static List<Line> net(List<Position> positions) {
        List<Line> lines = live(positions);
        long total = lines.stream().mapToLong(Line::net).sum();
        long sent = lines.stream().mapToLong(Line::sentAmount).sum();
        long received = lines.stream().mapToLong(Line::receivedAmount).sum();
        if (total != 0 || sent != received) {
            throw new IllegalStateException("Settlement does not balance: sent " + sent + ", received " + received);
        }
        return lines;
    }

    /**
     * Nets an open cycle without the balance check: amounts reserved for transfers still in flight are counted as
     * sent before the receiving side is credited.
     */
    public static List<Line> live(List<Position> positions) {
        return positions.stream()
            .map(p -> new Line(p.bic(), p.sentCount(), p.sentAmount(), p.receivedCount(), p.receivedAmount(),
                p.receivedAmount() - p.sentAmount()))
            .toList();
    }
}

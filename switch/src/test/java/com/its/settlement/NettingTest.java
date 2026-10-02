package com.its.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.its.settlement.Netting.Line;
import com.its.settlement.Netting.Position;

class NettingTest {

    @Test
    void netsAreReceivedMinusSentAndSumToZero() {
        // Alfa paid Bravo RM 100 and Charlie RM 50; Bravo paid Charlie RM 30; Charlie paid Alfa RM 20.
        List<Line> lines = Netting.net(List.of(
            new Position("ALFAMYKL", 2, 15000, 1, 2000),
            new Position("BRVOMYKL", 1, 3000, 1, 10000),
            new Position("CHRLMYKL", 1, 2000, 2, 8000)));

        assertEquals(-13000, lines.get(0).net());
        assertEquals(7000, lines.get(1).net());
        assertEquals(6000, lines.get(2).net());
        assertEquals(0, lines.stream().mapToLong(Line::net).sum());
    }

    @Test
    void anUnbalancedCycleIsRefused() {
        assertThrows(IllegalStateException.class, () -> Netting.net(List.of(
            new Position("ALFAMYKL", 1, 15000, 0, 0),
            new Position("BRVOMYKL", 0, 0, 1, 10000))));
    }

    @Test
    void liveNettingToleratesTransfersInFlight() {
        List<Line> lines = Netting.live(List.of(new Position("ALFAMYKL", 1, 15000, 0, 0)));
        assertEquals(-15000, lines.get(0).net());
    }
}

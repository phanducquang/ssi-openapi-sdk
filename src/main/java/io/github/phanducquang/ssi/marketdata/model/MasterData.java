package io.github.phanducquang.ssi.marketdata.model;

import io.github.phanducquang.ssi.marketdata.enums.Board;

public record MasterData(
        Board board,
        String symbol,
        String tradingDate,
        double ceiling,
        double floor,
        double refPrice) {
}

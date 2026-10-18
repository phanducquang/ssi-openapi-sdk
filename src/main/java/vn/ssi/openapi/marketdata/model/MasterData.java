package vn.ssi.openapi.marketdata.model;

import vn.ssi.openapi.marketdata.enums.Board;

public record MasterData(
        Board board,
        String symbol,
        String tradingDate,
        double ceiling,
        double floor,
        double refPrice) {
}

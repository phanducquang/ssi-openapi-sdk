package vn.ssi.openapi.marketdata.model;

import vn.ssi.openapi.marketdata.enums.Board;

public record SecuritiesInfo(
        String symbol,
        Board board,
        String index,
        String symbolNameVi,
        String symbolNameEn,
        long lotSize,
        String maturityDate,
        String firstTradingDate,
        String lastTradingDate,
        String cwUnderlyingSymbol,
        double cwExercisePrice,
        double cwExecutionRatio,
        long listedShares,
        String icbCode,
        String icbName,
        double iIndex,
        double iNav,
        double openInterest,
        double settlementPrice) {
}

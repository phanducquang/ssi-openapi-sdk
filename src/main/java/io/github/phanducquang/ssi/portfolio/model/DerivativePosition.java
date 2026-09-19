package io.github.phanducquang.ssi.portfolio.model;

public record DerivativePosition(
        String accountNo,
        String symbol,
        long longQuantity,
        long shortQuantity,
        long net,
        double bidAvgPrice,
        double askAvgPrice,
        double tradePrice,
        double floatingPl,
        double tradingPl) {
}

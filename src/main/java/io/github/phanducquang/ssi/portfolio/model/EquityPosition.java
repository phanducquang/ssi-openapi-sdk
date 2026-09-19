package io.github.phanducquang.ssi.portfolio.model;

public record EquityPosition(
        String accountNo,
        String symbol,
        long quantity,
        long blockQuantity,
        long dividendQuantity,
        long buyingQuantity,
        long boughtQuantity,
        long sellingQuantity,
        long soldQuantity,
        long t1SellQuantity,
        long t2SellQuantity,
        double costPrice,
        long mortgageQuantity,
        long sellableQuantity,
        long restrictedQuantity) {
}

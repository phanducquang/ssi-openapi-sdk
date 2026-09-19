package io.github.phanducquang.ssi.streaming.model;

public record PortfolioMessage(
        String accountNo,
        double totalAsset,
        double cashBalance,
        double stockValue) {
}

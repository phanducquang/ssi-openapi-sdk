package io.github.phanducquang.ssi.marketdata.model;

import io.github.phanducquang.ssi.marketdata.enums.Board;

public record MarketIndex(
        String index,
        String indexName,
        Board board) {
}

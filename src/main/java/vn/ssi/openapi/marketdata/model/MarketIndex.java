package vn.ssi.openapi.marketdata.model;

import vn.ssi.openapi.marketdata.enums.Board;

public record MarketIndex(
        String index,
        String indexName,
        Board board) {
}

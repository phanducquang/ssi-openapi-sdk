package io.github.phanducquang.ssi.trading.fco;

import java.util.List;

public record FcoOrderBookResponse(
        int pageIndex,
        int pageSize,
        int itemsCount,
        int pagesCount,
        List<FcoOrder> orderBook) {

    public FcoOrderBookResponse {
        orderBook = orderBook == null ? List.of() : List.copyOf(orderBook);
    }
}

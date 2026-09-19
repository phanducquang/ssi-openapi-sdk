package io.github.phanducquang.ssi.trading.model;

import io.github.phanducquang.ssi.trading.enums.OrderStatus;

public record PlaceOrderResponse(
        String orderId,
        String clientRequestId,
        String rawStatus) {

    public OrderStatus status() {
        return OrderStatus.fromValue(rawStatus);
    }
}

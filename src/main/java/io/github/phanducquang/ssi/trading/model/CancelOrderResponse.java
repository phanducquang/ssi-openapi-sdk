package io.github.phanducquang.ssi.trading.model;

import io.github.phanducquang.ssi.trading.enums.OrderStatus;

public record CancelOrderResponse(
        String clientCancelId,
        String orderId,
        String clientRequestId,
        String rawStatus) {

    public OrderStatus status() {
        return OrderStatus.fromValue(rawStatus);
    }
}

package vn.ssi.openapi.trading.model;

import vn.ssi.openapi.trading.enums.OrderStatus;

public record CancelOrderResponse(
        String clientCancelId,
        String orderId,
        String clientRequestId,
        String rawStatus) {

    public OrderStatus status() {
        return OrderStatus.fromValue(rawStatus);
    }
}

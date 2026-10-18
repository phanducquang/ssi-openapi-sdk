package vn.ssi.openapi.streaming.model;

import vn.ssi.openapi.trading.fco.FcoStatus;
import vn.ssi.openapi.trading.fco.FcoType;

public record FcoOrderUpdateMessage(
        String fcoId,
        FcoStatus processStatus,
        long matchedQuantity,
        boolean placeOrder,
        String symbol,
        long quantity,
        String price,
        String accountNo,
        String updatedTime,
        String status,
        String message,
        String username,
        String eventType,
        FcoType type) {
}

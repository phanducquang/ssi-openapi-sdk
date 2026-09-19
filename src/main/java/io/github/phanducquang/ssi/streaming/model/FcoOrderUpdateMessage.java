package io.github.phanducquang.ssi.streaming.model;

import io.github.phanducquang.ssi.trading.fco.FcoStatus;
import io.github.phanducquang.ssi.trading.fco.FcoType;

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

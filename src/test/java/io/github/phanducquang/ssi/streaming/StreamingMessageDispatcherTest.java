package io.github.phanducquang.ssi.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.phanducquang.ssi.streaming.model.FcoOrderUpdateMessage;
import io.github.phanducquang.ssi.streaming.model.OrderStatusMessage;
import io.github.phanducquang.ssi.streaming.model.PortfolioMessage;
import io.github.phanducquang.ssi.streaming.model.QuoteMessage;
import io.github.phanducquang.ssi.streaming.model.TradeMessage;
import io.github.phanducquang.ssi.trading.enums.OrderSide;
import io.github.phanducquang.ssi.trading.enums.OrderStatus;
import io.github.phanducquang.ssi.trading.enums.OrderType;
import io.github.phanducquang.ssi.trading.fco.FcoStatus;
import io.github.phanducquang.ssi.trading.fco.FcoType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamingMessageDispatcherTest {
    @Test
    void dispatchesTradePayloadToTypedListener() {
        StreamingMessageDispatcher dispatcher = new StreamingMessageDispatcher(new ObjectMapper());
        AtomicReference<TradeMessage> received = new AtomicReference<>();
        dispatcher.onTrade(received::set);
        dispatcher.dispatch("{\"channel\":\"DATA\",\"topic\":\"trade.VNM\",\"data\":{\"t\":\"10:15:01\",\"s\":\"VNM\",\"p\":76500,\"q\":100,\"si\":\"B\",\"v\":120000}}");
        assertEquals("VNM", received.get().symbol());
        assertEquals(new BigDecimal("76500"), received.get().price());
        assertEquals(100L, received.get().quantity());
        assertEquals("B", received.get().side());
    }

    @Test
    void dispatchesTradingOrderStatus() {
        StreamingMessageDispatcher dispatcher = new StreamingMessageDispatcher(new ObjectMapper());
        AtomicReference<OrderStatusMessage> received = new AtomicReference<>();
        dispatcher.onOrderStatus(received::set);

        dispatcher.dispatch("""
                {"channel":"TRADING","topic":"order.1234567","data":{
                  "accountNo":"1234567",
                  "clientRequestId":"REQ-1",
                  "orderId":"OID-1",
                  "symbol":"VNM",
                  "side":"B",
                  "orderType":"LO",
                  "price":"61000",
                  "quantity":100,
                  "osQty":20,
                  "filledQty":80,
                  "cancelQty":0,
                  "orderStatus":"PF",
                  "inputTime":"09:00:00",
                  "modifyTime":"09:01:00",
                  "rejectReason":""
                }}
                """);

        assertEquals("1234567", received.get().accountNo());
        assertEquals(OrderSide.BUY, received.get().side());
        assertEquals(OrderType.LO, received.get().orderType());
        assertEquals(OrderStatus.PARTIAL_FILLED, received.get().status());
        assertEquals(61000L, received.get().price());
        assertEquals(80L, received.get().filledQuantity());
    }

    @Test
    void dispatchesTradingPortfolio() {
        StreamingMessageDispatcher dispatcher = new StreamingMessageDispatcher(new ObjectMapper());
        AtomicReference<PortfolioMessage> received = new AtomicReference<>();
        dispatcher.onPortfolio(received::set);

        dispatcher.dispatch("""
                {"channel":"TRADING","topic":"portfolio.1234567","data":{
                  "accountNo":"1234567",
                  "totalAsset":"2500000.5",
                  "cashBalance":1000000,
                  "stockValue":"1500000.5"
                }}
                """);

        assertEquals("1234567", received.get().accountNo());
        assertEquals(2500000.5d, received.get().totalAsset());
        assertEquals(1000000d, received.get().cashBalance());
        assertEquals(1500000.5d, received.get().stockValue());
    }

    @Test
    void dispatchesFcoOrderUpdateSeparatelyFromNormalOrderStatus() {
        StreamingMessageDispatcher dispatcher = new StreamingMessageDispatcher(new ObjectMapper());
        AtomicReference<OrderStatusMessage> normalOrder = new AtomicReference<>();
        AtomicReference<FcoOrderUpdateMessage> fcoOrder = new AtomicReference<>();
        dispatcher.onOrderStatus(normalOrder::set);
        dispatcher.onFcoOrderUpdate(fcoOrder::set);

        dispatcher.dispatch("""
                {"channel":"TRADING","topic":"order.1234567","data":{
                  "eventType":"fcoEvent",
                  "fcoId":"FCO-1",
                  "processStatus":"WAIT",
                  "matchedQuantity":20,
                  "isPlaceOrder":true,
                  "symbol":"VNM",
                  "quantity":100,
                  "price":"61000",
                  "accountNo":"1234567",
                  "updatedTime":"2026-09-19T10:00:00",
                  "status":"PD",
                  "message":"",
                  "username":"client",
                  "type":"stop"
                }}
                """);

        assertEquals(null, normalOrder.get());
        assertEquals("FCO-1", fcoOrder.get().fcoId());
        assertEquals(FcoStatus.WAIT, fcoOrder.get().processStatus());
        assertEquals(FcoType.STOP, fcoOrder.get().type());
        assertEquals(20L, fcoOrder.get().matchedQuantity());
    }

    @Test
    void dispatchesQuoteLevels() {
        StreamingMessageDispatcher dispatcher = new StreamingMessageDispatcher(new ObjectMapper());
        AtomicReference<QuoteMessage> received = new AtomicReference<>();
        dispatcher.onQuote(received::set);
        dispatcher.dispatch("{\"channel\":\"DATA\",\"topic\":\"quote.VNM\",\"data\":{\"t\":\"10:15:02\",\"s\":\"VNM\",\"bids\":[[76400,1200],[76300,900]],\"asks\":[[76500,700]]}}");
        assertEquals(2, received.get().bids().size());
        assertEquals(new BigDecimal("76400"), received.get().bids().get(0).price());
        assertEquals(1200L, received.get().bids().get(0).volume());
        assertEquals(1, received.get().asks().size());
    }
}

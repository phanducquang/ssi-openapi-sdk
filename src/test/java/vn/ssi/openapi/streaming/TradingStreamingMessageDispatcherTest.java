package vn.ssi.openapi.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import vn.ssi.openapi.streaming.model.FcoOrderUpdateMessage;
import vn.ssi.openapi.streaming.model.OrderStatusMessage;
import vn.ssi.openapi.streaming.model.PortfolioMessage;
import vn.ssi.openapi.trading.enums.OrderSide;
import vn.ssi.openapi.trading.enums.OrderStatus;
import vn.ssi.openapi.trading.enums.OrderType;
import vn.ssi.openapi.trading.fco.FcoStatus;
import vn.ssi.openapi.trading.fco.FcoType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TradingStreamingMessageDispatcherTest {
    @Test
    void dispatchesTradingOrderStatus() {
        StreamingMessageDispatcher dispatcher = new StreamingMessageDispatcher(new ObjectMapper());
        AtomicReference<OrderStatusMessage> received = new AtomicReference<>();
        dispatcher.onOrderStatus(received::set);

        dispatcher.dispatch("""
                {"channel":"TRADING","topic":"order.1234567","data":{
                  "accountNo":"1234567","clientRequestId":"REQ-1","orderId":"OID-1",
                  "symbol":"VNM","side":"B","orderType":"LO","price":"61000",
                  "quantity":100,"osQty":20,"filledQty":80,"cancelQty":0,
                  "orderStatus":"PF","inputTime":"09:00:00","modifyTime":"09:01:00",
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
                  "accountNo":"1234567","totalAsset":"2500000.5",
                  "cashBalance":1000000,"stockValue":"1500000.5"
                }}
                """);

        assertEquals("1234567", received.get().accountNo());
        assertEquals(2500000.5d, received.get().totalAsset());
        assertEquals(1000000d, received.get().cashBalance());
        assertEquals(1500000.5d, received.get().stockValue());
    }

    @Test
    void dispatchesFcoEventSeparatelyFromNormalOrderStatus() {
        StreamingMessageDispatcher dispatcher = new StreamingMessageDispatcher(new ObjectMapper());
        AtomicReference<OrderStatusMessage> normalOrder = new AtomicReference<>();
        AtomicReference<FcoOrderUpdateMessage> fcoOrder = new AtomicReference<>();
        dispatcher.onOrderStatus(normalOrder::set);
        dispatcher.onFcoOrderUpdate(fcoOrder::set);

        dispatcher.dispatch("""
                {"channel":"TRADING","topic":"order.1234567","data":{
                  "eventType":"fcoEvent","fcoId":"FCO-1","processStatus":"WAIT",
                  "matchedQuantity":20,"isPlaceOrder":true,"symbol":"VNM",
                  "quantity":100,"price":"61000","accountNo":"1234567",
                  "updatedTime":"2026-09-19T10:00:00","status":"PD","message":"",
                  "username":"client","type":"stop"
                }}
                """);

        assertNull(normalOrder.get());
        assertEquals("FCO-1", fcoOrder.get().fcoId());
        assertEquals(FcoStatus.WAIT, fcoOrder.get().processStatus());
        assertEquals(FcoType.STOP, fcoOrder.get().type());
        assertEquals(20L, fcoOrder.get().matchedQuantity());
    }
}

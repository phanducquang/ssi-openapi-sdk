package io.github.phanducquang.ssi.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.phanducquang.ssi.marketdata.enums.Board;
import io.github.phanducquang.ssi.marketdata.model.MasterData;
import io.github.phanducquang.ssi.marketdata.model.MarketIndex;
import io.github.phanducquang.ssi.marketdata.model.MarketIndexSummary;
import io.github.phanducquang.ssi.marketdata.model.OhlcData;
import io.github.phanducquang.ssi.marketdata.model.SecuritiesInfo;
import io.github.phanducquang.ssi.marketdata.model.SecuritiesSummary;
import io.github.phanducquang.ssi.streaming.enums.Timeframe;
import io.github.phanducquang.ssi.transport.RestClient;
import io.github.phanducquang.ssi.transport.RestTransport;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MarketDataService {
    public static final String OHLC_PATH = "/api/v3/data/ohlc";
    public static final String MASTER_DATA_PATH = "/api/v3/data/masterdata";
    public static final String INDEX_LIST_PATH = "/api/v3/data/indexList";
    public static final String INDEX_SUMMARY_PATH = "/api/v3/data/indexSummary";
    public static final String SECURITIES_BY_BOARD_PATH = "/api/v3/data/securitiesByBoard";
    public static final String SECURITIES_SUMMARY_PATH = "/api/v3/data/securitiesSummary";

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_SIZE = 1000;

    private static final ZoneId SSI_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final RestTransport restClient;

    public MarketDataService(RestTransport restClient) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
    }

    public List<OhlcData> getOhlc(
            String symbol,
            Timeframe timeframe,
            String fromDate,
            String toDate,
            int page,
            int size) {
        requireText(symbol, "symbol");
        Objects.requireNonNull(timeframe, "timeframe");
        requirePositive(page, "page");
        requirePositive(size, "size");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("from", fromDate == null || fromDate.isBlank() ? beginningOfToday() : fromDate);
        params.put("to", toDate == null || toDate.isBlank() ? endOfToday() : toDate);
        params.put("timeFrame", timeframe.value());
        params.put("pageIndex", page);
        params.put("pageSize", size);

        JsonNode body = restClient.get(OHLC_PATH, params).body();
        return parseOhlc(arrayNode(body, true));
    }

    public List<OhlcData> getOhlc1Minute(String symbol) {
        return getOhlc(symbol, Timeframe.MINUTE_1, null, null, DEFAULT_PAGE, DEFAULT_SIZE);
    }

    public List<OhlcData> getOhlc1MinuteHistorical(String symbol, String fromDate, String toDate) {
        return getOhlcHistorical(symbol, Timeframe.MINUTE_1, fromDate, toDate);
    }

    public List<OhlcData> getOhlc3Minute(String symbol) {
        return getOhlc(symbol, Timeframe.MINUTE_3, null, null, DEFAULT_PAGE, DEFAULT_SIZE);
    }

    public List<OhlcData> getOhlc3MinuteHistorical(String symbol, String fromDate, String toDate) {
        return getOhlcHistorical(symbol, Timeframe.MINUTE_3, fromDate, toDate);
    }

    public List<OhlcData> getOhlc5Minute(String symbol) {
        return getOhlc(symbol, Timeframe.MINUTE_5, null, null, DEFAULT_PAGE, DEFAULT_SIZE);
    }

    public List<OhlcData> getOhlc5MinuteHistorical(String symbol, String fromDate, String toDate) {
        return getOhlcHistorical(symbol, Timeframe.MINUTE_5, fromDate, toDate);
    }

    public List<OhlcData> getOhlc15Minute(String symbol) {
        return getOhlc(symbol, Timeframe.MINUTE_15, null, null, DEFAULT_PAGE, DEFAULT_SIZE);
    }

    public List<OhlcData> getOhlc15MinuteHistorical(String symbol, String fromDate, String toDate) {
        return getOhlcHistorical(symbol, Timeframe.MINUTE_15, fromDate, toDate);
    }

    public List<OhlcData> getOhlc1Hour(String symbol) {
        return getOhlc(symbol, Timeframe.HOUR_1, null, null, DEFAULT_PAGE, DEFAULT_SIZE);
    }

    public List<OhlcData> getOhlc1HourHistorical(String symbol, String fromDate, String toDate) {
        return getOhlcHistorical(symbol, Timeframe.HOUR_1, fromDate, toDate);
    }

    public List<OhlcData> getOhlc1DayHistorical(String symbol, String fromDate, String toDate) {
        return getOhlcHistorical(symbol, Timeframe.DAY_1, fromDate, toDate);
    }

    public List<OhlcData> getOhlc1WeekHistorical(String symbol, String fromDate, String toDate) {
        return getOhlcHistorical(symbol, Timeframe.WEEK_1, fromDate, toDate);
    }

    public List<OhlcData> getOhlc1MonthHistorical(String symbol, String fromDate, String toDate) {
        return getOhlcHistorical(symbol, Timeframe.MONTH_1, fromDate, toDate);
    }

    public List<MarketIndex> getIndexes() {
        return getIndexes(null);
    }

    public List<MarketIndex> getIndexesByBoard(Board board) {
        return getIndexes(Objects.requireNonNull(board, "board"));
    }

    public MarketIndexSummary getIndexSummary(String index) {
        requireText(index, "index");
        return firstOrNull(getIndexSummaries(index, null, null));
    }

    public MarketIndexSummary getIndexSummaryHistorical(String index, String tradingDate) {
        requireText(index, "index");
        requireText(tradingDate, "tradingDate");
        return firstOrNull(getIndexSummaries(index, null, tradingDate));
    }

    public MarketIndexSummary getBoardSummary(Board board) {
        return firstOrNull(getIndexSummaries(null, Objects.requireNonNull(board, "board"), null));
    }

    public MarketIndexSummary getBoardSummaryHistorical(Board board, String tradingDate) {
        Objects.requireNonNull(board, "board");
        requireText(tradingDate, "tradingDate");
        return firstOrNull(getIndexSummaries(null, board, tradingDate));
    }

    public SecuritiesInfo getSecuritiesInfo(String symbol) {
        requireText(symbol, "symbol");
        return firstOrNull(getSecuritiesInfoInternal(null, null, symbol));
    }

    public List<SecuritiesInfo> getSecuritiesInfoByIndex(String index) {
        requireText(index, "index");
        return getSecuritiesInfoInternal(index, null, null);
    }

    public List<SecuritiesInfo> getSecuritiesInfoByBoard(Board board) {
        return getSecuritiesInfoInternal(null, Objects.requireNonNull(board, "board"), null);
    }

    public List<SecuritiesSummary> getSecuritiesSummary(String symbol) {
        requireText(symbol, "symbol");
        String today = todayDate();
        return getSecuritiesSummaryInternal(today, today, symbol, null, DEFAULT_PAGE, DEFAULT_SIZE);
    }

    public List<SecuritiesSummary> getSecuritiesSummaryHistorical(
            String symbol,
            String fromDate,
            String toDate) {
        requireText(symbol, "symbol");
        requireText(fromDate, "fromDate");
        requireText(toDate, "toDate");
        return getSecuritiesSummaryInternal(fromDate, toDate, symbol, null, DEFAULT_PAGE, DEFAULT_SIZE);
    }

    public List<SecuritiesSummary> getSecuritiesSummaryByIndex(String index) {
        requireText(index, "index");
        String today = todayDate();
        return getSecuritiesSummaryInternal(today, today, null, index, DEFAULT_PAGE, DEFAULT_SIZE);
    }

    public List<SecuritiesSummary> getSecuritiesSummaryByIndexHistorical(
            String index,
            String fromDate,
            String toDate) {
        requireText(index, "index");
        requireText(fromDate, "fromDate");
        requireText(toDate, "toDate");
        return getSecuritiesSummaryInternal(fromDate, toDate, null, index, DEFAULT_PAGE, DEFAULT_SIZE);
    }

    public List<MasterData> getMasterData() {
        String today = todayDate();
        return getMasterDataHistorical(today, today);
    }

    public List<MasterData> getMasterDataHistorical(String fromDate, String toDate) {
        requireText(fromDate, "fromDate");
        requireText(toDate, "toDate");

        int page = DEFAULT_PAGE;
        List<MasterData> result = new ArrayList<>();
        while (true) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("From", fromDate);
            params.put("To", toDate);
            params.put("pageIndex", page);
            params.put("pageSize", DEFAULT_SIZE);

            JsonNode body = restClient.get(MASTER_DATA_PATH, params).body();
            result.addAll(parseMasterData(arrayNode(body, true)));

            int pagesCount = Math.max(1, body.path("pagesCount").asInt(1));
            if (page >= pagesCount) {
                return List.copyOf(result);
            }
            page++;
        }
    }

    private List<OhlcData> getOhlcHistorical(
            String symbol,
            Timeframe timeframe,
            String fromDate,
            String toDate) {
        requireText(fromDate, "fromDate");
        requireText(toDate, "toDate");
        return getOhlc(symbol, timeframe, fromDate, toDate, DEFAULT_PAGE, DEFAULT_SIZE);
    }

    private List<MarketIndex> getIndexes(Board board) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (board != null) {
            params.put("board", board.value());
        }
        JsonNode body = restClient.get(INDEX_LIST_PATH, params).body();
        return parseIndexes(arrayNode(body, false));
    }

    private List<MarketIndexSummary> getIndexSummaries(
            String index,
            Board board,
            String tradingDate) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (index != null) {
            params.put("index", index);
        }
        if (board != null) {
            params.put("board", board.value());
        }
        if (tradingDate != null) {
            params.put("tradingDate", tradingDate);
        }

        JsonNode body = restClient.get(INDEX_SUMMARY_PATH, params).body();
        return parseIndexSummaries(arrayNode(body, false));
    }

    private List<SecuritiesInfo> getSecuritiesInfoInternal(
            String index,
            Board board,
            String symbol) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (index != null) {
            params.put("index", index);
        }
        if (board != null) {
            params.put("board", board.value());
        }
        if (symbol != null) {
            params.put("symbol", symbol);
        }

        JsonNode body = restClient.get(SECURITIES_BY_BOARD_PATH, params).body();
        return parseSecuritiesInfo(arrayNode(body, false));
    }

    private List<SecuritiesSummary> getSecuritiesSummaryInternal(
            String fromDate,
            String toDate,
            String symbol,
            String index,
            int page,
            int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", fromDate);
        params.put("to", toDate);
        params.put("pageIndex", page);
        params.put("pageSize", size);
        if (symbol != null) {
            params.put("symbol", symbol);
        }
        if (index != null) {
            params.put("index", index);
        }

        JsonNode body = restClient.get(SECURITIES_SUMMARY_PATH, params).body();
        return parseSecuritiesSummary(arrayNode(body, true));
    }

    private List<OhlcData> parseOhlc(JsonNode items) {
        List<OhlcData> result = new ArrayList<>();
        for (JsonNode item : items) {
            result.add(new OhlcData(
                    text(item, "symbol"),
                    text(item, "tradingDate"),
                    number(item, "open"),
                    number(item, "high"),
                    number(item, "low"),
                    number(item, "close"),
                    integer(item, "volume"),
                    number(item, "value")));
        }
        return List.copyOf(result);
    }

    private List<MarketIndex> parseIndexes(JsonNode items) {
        List<MarketIndex> result = new ArrayList<>();
        for (JsonNode item : items) {
            result.add(new MarketIndex(
                    text(item, "index"),
                    text(item, "indexName"),
                    Board.fromValue(text(item, "board"))));
        }
        return List.copyOf(result);
    }

    private List<MarketIndexSummary> parseIndexSummaries(JsonNode items) {
        List<MarketIndexSummary> result = new ArrayList<>();
        for (JsonNode item : items) {
            result.add(new MarketIndexSummary(
                    text(item, "tradingDate"),
                    integer(item, "totalTrade"),
                    number(item, "totalTradeValue"),
                    integer(item, "totalMatch"),
                    number(item, "totalMatchValue"),
                    integer(item, "totalDeal"),
                    number(item, "totalDealValue"),
                    number(item, "indexChange"),
                    number(item, "indexChangePercentage"),
                    number(item, "indexValue"),
                    integer(item, "totalAdvanceStock"),
                    integer(item, "totalDeclineStock"),
                    integer(item, "totalNoChangeStock"),
                    integer(item, "totalCeilingStock"),
                    integer(item, "totalFloorStock"),
                    integer(item, "totalPropBuy"),
                    number(item, "totalPropBuyValue"),
                    integer(item, "totalPropSell"),
                    number(item, "totalPropSellValue")));
        }
        return List.copyOf(result);
    }

    private List<SecuritiesInfo> parseSecuritiesInfo(JsonNode items) {
        List<SecuritiesInfo> result = new ArrayList<>();
        for (JsonNode item : items) {
            result.add(new SecuritiesInfo(
                    text(item, "symbol"),
                    Board.fromValue(text(item, "board")),
                    nullableText(item, "index"),
                    nullableText(item, "symbolNameVi"),
                    nullableText(item, "symbolNameEn"),
                    integer(item, "lotSize"),
                    nullableText(item, "maturityDate"),
                    nullableText(item, "firstTradingDate"),
                    nullableText(item, "lastTradingDate"),
                    nullableText(item, "cwUnderlyingSymbol"),
                    number(item, "cwExercisePrice"),
                    number(item, "cwExecutionRatio"),
                    integer(item, "listedShare"),
                    nullableText(item, "icbCode"),
                    nullableText(item, "icbName"),
                    number(item, "iIndex"),
                    number(item, "iNav"),
                    number(item, "openInterest"),
                    number(item, "settlementPrice")));
        }
        return List.copyOf(result);
    }

    private List<MasterData> parseMasterData(JsonNode items) {
        List<MasterData> result = new ArrayList<>();
        for (JsonNode item : items) {
            result.add(new MasterData(
                    Board.fromValue(text(item, "board")),
                    text(item, "symbol"),
                    text(item, "tradingDate"),
                    number(item, "ceiling"),
                    number(item, "floor"),
                    number(item, "refPrice")));
        }
        return List.copyOf(result);
    }

    private List<SecuritiesSummary> parseSecuritiesSummary(JsonNode items) {
        List<SecuritiesSummary> result = new ArrayList<>();
        for (JsonNode item : items) {
            result.add(new SecuritiesSummary(
                    text(item, "symbol"),
                    text(item, "tradingDate"),
                    number(item, "priceChange"),
                    number(item, "priceChangePercentage"),
                    number(item, "open"),
                    number(item, "high"),
                    number(item, "low"),
                    number(item, "close"),
                    number(item, "average"),
                    integer(item, "totalMatch"),
                    number(item, "totalMatchValue"),
                    integer(item, "totalBuy"),
                    number(item, "totalTradeBuy"),
                    integer(item, "totalSell"),
                    number(item, "totalTradeSell"),
                    integer(item, "totalForeignBuy"),
                    number(item, "totalForeignBuyValue"),
                    integer(item, "totalForeignSell"),
                    number(item, "totalForeignSellValue"),
                    integer(item, "remainForeignRoom"),
                    integer(item, "totalForeignRoom"),
                    integer(item, "totalDeal"),
                    number(item, "totalDealValue"),
                    number(item, "openInterest"),
                    number(item, "settlementPrice")));
        }
        return List.copyOf(result);
    }

    private static JsonNode arrayNode(JsonNode body, boolean wrapped) {
        if (body == null) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        if (!wrapped && body.isArray()) {
            return body;
        }
        if (body.path("data").isArray()) {
            return body.path("data");
        }
        if (body.isArray()) {
            return body;
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("");
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static long integer(JsonNode node, String field) {
        return node.path(field).asLong(0L);
    }

    private static double number(JsonNode node, String field) {
        return node.path(field).asDouble(0.0d);
    }

    private static <T> T firstOrNull(List<T> values) {
        return values.isEmpty() ? null : values.get(0);
    }

    private static String todayDate() {
        return LocalDate.now(SSI_ZONE).format(DATE_FORMAT);
    }

    private static String beginningOfToday() {
        return todayDate() + " 00:00:00";
    }

    private static String endOfToday() {
        return todayDate() + " 23:59:59";
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be >= 1");
        }
    }
}

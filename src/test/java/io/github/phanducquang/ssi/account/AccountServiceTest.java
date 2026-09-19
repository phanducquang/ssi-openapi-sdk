package io.github.phanducquang.ssi.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.phanducquang.ssi.account.model.AccountType;
import io.github.phanducquang.ssi.transport.RestClient;
import io.github.phanducquang.ssi.transport.RestTransport;
import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccountServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsAccessibleAccounts() throws Exception {
        RestTransport transport = new StubTransport(objectMapper.readTree("""
                [
                  {"accountNo":"1234567","accountType":"Cash"},
                  {"accountNo":"1234568","accountType":"Margin"},
                  {"accountNo":"1234569","accountType":"Derivative"}
                ]
                """));

        var accounts = new AccountService(transport).getAccountInfo();

        assertEquals(3, accounts.size());
        assertEquals("1234567", accounts.get(0).accountNo());
        assertEquals(AccountType.EQUITY, accounts.get(0).accountType());
        assertEquals(AccountType.EQUITY_MARGIN, accounts.get(1).accountType());
        assertEquals(AccountType.DERIVATIVE, accounts.get(2).accountType());
    }

    private static final class StubTransport implements RestTransport {
        private final com.fasterxml.jackson.databind.JsonNode body;

        private StubTransport(com.fasterxml.jackson.databind.JsonNode body) {
            this.body = body;
        }

        @Override
        public RestClient.ApiResponse get(String path, Map<String, ?> queryParams) {
            assertEquals(AccountService.ACCOUNT_INFO_PATH, path);
            return new RestClient.ApiResponse(
                    200,
                    body,
                    HttpHeaders.of(Map.of(), (name, value) -> true));
        }

        @Override
        public RestClient.ApiResponse post(String path, Object body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAccessToken(String token) {
        }

        @Override
        public void close() {
        }
    }
}

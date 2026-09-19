package io.github.phanducquang.ssi.account;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.phanducquang.ssi.account.model.Account;
import io.github.phanducquang.ssi.account.model.AccountType;
import io.github.phanducquang.ssi.transport.RestClient;
import io.github.phanducquang.ssi.transport.RestTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AccountService {
    public static final String ACCOUNT_INFO_PATH = "/api/v3/account/info";

    private final RestTransport restClient;

    public AccountService(RestTransport restClient) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
    }

    public List<Account> getAccountInfo() {
        RestClient.ApiResponse response = restClient.get(ACCOUNT_INFO_PATH);
        JsonNode items = response.body();

        if (!items.isArray() && items.path("data").isArray()) {
            items = items.path("data");
        }

        if (!items.isArray()) {
            return List.of();
        }

        List<Account> accounts = new ArrayList<>();
        for (JsonNode item : items) {
            accounts.add(new Account(
                    item.path("accountNo").asText(""),
                    AccountType.fromValue(item.path("accountType").asText("Cash"))));
        }
        return List.copyOf(accounts);
    }
}

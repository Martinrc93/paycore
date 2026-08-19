package dev.martin.paycore.wallet.infrastructure.web;

import dev.martin.paycore.wallet.application.query.WalletAccess;
import java.security.Principal;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private final WalletAccess wallets;

    public WalletController(WalletAccess wallets) {
        this.wallets = Objects.requireNonNull(wallets, "wallets");
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public WalletResponse ownWallet(Principal principal) {
        UUID customerId = UUID.fromString(Objects.requireNonNull(principal, "principal").getName());
        return WalletResponse.from(wallets.query(customerId));
    }
}

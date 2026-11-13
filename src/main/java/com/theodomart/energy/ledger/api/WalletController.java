package com.theodomart.energy.ledger.api;

import com.theodomart.energy.common.web.PageResponse;
import com.theodomart.energy.ledger.service.LedgerService;
import com.theodomart.energy.ledger.service.WalletTransactionView;
import com.theodomart.energy.ledger.service.WalletView;
import com.theodomart.energy.security.service.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Always the caller's own wallet: the tenant comes from the token, so there is no id to guess. */
@RestController
@RequestMapping("/api/v1/wallet")
@PreAuthorize("hasRole('TENANT')")
@Validated
public class WalletController {

	private final LedgerService ledger;

	public WalletController(LedgerService ledger) {
		this.ledger = ledger;
	}

	@GetMapping
	public WalletView wallet(@AuthenticationPrincipal CurrentUser user) {
		return ledger.wallet(user.id());
	}

	@GetMapping("/transactions")
	public PageResponse<WalletTransactionView> transactions(@AuthenticationPrincipal CurrentUser user,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return PageResponse.from(ledger.walletTransactions(user.id(), page, size), line -> line);
	}

}

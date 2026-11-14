package com.theodomart.energy.ledger.api;

import com.theodomart.energy.ledger.service.LedgerService;
import com.theodomart.energy.ledger.service.TrialBalanceView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/ledger")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminLedgerController {

	private final LedgerService ledger;

	public AdminLedgerController(LedgerService ledger) {
		this.ledger = ledger;
	}

	@GetMapping("/trial-balance")
	public TrialBalanceView trialBalance() {
		return ledger.trialBalance();
	}

}

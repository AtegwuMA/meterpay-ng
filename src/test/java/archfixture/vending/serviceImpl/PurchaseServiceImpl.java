package archfixture.vending.serviceImpl;

import archfixture.ledger.repository.LedgerAccountRepository;
import archfixture.ledger.service.LedgerService;

// Violates rule 2 (reaches into ledger's repository) and rule 5 (floating point money).
// The LedgerService dependency is the allowed way in and must not be flagged.
public class PurchaseServiceImpl {

	private final LedgerService ledger;

	private final LedgerAccountRepository ledgerAccounts;

	private double amountNaira;

	public PurchaseServiceImpl(LedgerService ledger, LedgerAccountRepository ledgerAccounts) {
		this.ledger = ledger;
		this.ledgerAccounts = ledgerAccounts;
	}

}

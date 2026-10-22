package archfixture.ledger.api;

import archfixture.ledger.domain.LedgerAccount;
import archfixture.ledger.serviceImpl.LedgerServiceImpl;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

// Violates rule 1 (injects the implementation) and rule 3 (returns entities, directly and in generics).
@RestController
public class LedgerController {

	private final LedgerServiceImpl service;

	public LedgerController(LedgerServiceImpl service) {
		this.service = service;
	}

	public LedgerAccount account() {
		return null;
	}

	public ResponseEntity<List<LedgerAccount>> accounts() {
		return null;
	}

}

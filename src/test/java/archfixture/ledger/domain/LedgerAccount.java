package archfixture.ledger.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;

@Entity
public class LedgerAccount {

	@Id
	UUID id;

}

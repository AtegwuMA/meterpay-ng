package archfixture.ledger.repository;

import archfixture.ledger.domain.LedgerAccount;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

// Violates rule 4a: inherits deleteById and friends.
public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

}

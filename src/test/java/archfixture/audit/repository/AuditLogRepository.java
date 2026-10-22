package archfixture.audit.repository;

import java.util.UUID;
import org.springframework.data.repository.Repository;

// Violates rule 4b: declares its own delete method on an otherwise insert-only repository.
public interface AuditLogRepository extends Repository<Object, UUID> {

	void removeOlderThan(java.time.Instant cutoff);

}

package archfixture.identity.service;

import java.util.UUID;

public interface UserDirectory {

	boolean isActive(UUID userId);

}

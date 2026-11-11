package archfixture.ledger.serviceImpl;

import archfixture.identity.service.UserDirectory;

/** Breaks rule 6: the ledger asks identity a question, so identity and the ledger now depend on each other. */
public class WalletOwners {

	private final UserDirectory users;

	public WalletOwners(UserDirectory users) {
		this.users = users;
	}

	public UserDirectory users() {
		return users;
	}

}

package com.theodomart.energy.identity.serviceImpl;

import com.theodomart.energy.common.domain.Role;
import com.theodomart.energy.identity.config.BootstrapAdminProperties;
import com.theodomart.energy.identity.domain.PhoneNumbers;
import com.theodomart.energy.identity.domain.User;
import com.theodomart.energy.identity.repository.UserRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates the first admin at startup when none exists (DESIGN 17.3). There is no other way to become an admin in v1,
 * so this never runs through registration. Anything wrong with the configuration stops startup rather than leaving the
 * platform without an admin and nobody noticing.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

	static final String FULL_NAME = "MeterPay Admin";

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

	private final BootstrapAdminProperties properties;

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final TransactionTemplate transactions;

	private final Clock clock;

	public AdminBootstrap(BootstrapAdminProperties properties, UserRepository users, PasswordEncoder passwordEncoder,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.properties = properties;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.transactions = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!properties.isConfigured()) {
			return;
		}
		// A quick look first, so a normal restart does no BCrypt work and writes nothing. It is not what keeps admins
		// unique: two instances starting together can both get past it, and then the email's UNIQUE constraint decides.
		if (Boolean.TRUE.equals(transactions.execute(status -> users.existsByRole(Role.ADMIN)))) {
			log.info("Admin bootstrap skipped: an admin already exists");
			return;
		}
		User admin = newAdmin();
		try {
			transactions.executeWithoutResult(status -> users.saveAndFlush(admin));
			log.info("Bootstrap admin created: userId={}", admin.getId());
		}
		catch (DataIntegrityViolationException ex) {
			// Checked in a transaction of its own: the failed insert ended the first one.
			boolean anotherInstanceWon = Boolean.TRUE.equals(transactions.execute(
					status -> users.findByEmail(admin.getEmail()).map(user -> user.getRole() == Role.ADMIN).orElse(false)));
			if (!anotherInstanceWon) {
				throw new IllegalStateException(
						"BOOTSTRAP_ADMIN_EMAIL or BOOTSTRAP_ADMIN_PHONE belongs to an existing account that is not an admin");
			}
			log.info("Admin bootstrap skipped: another instance created the admin first");
		}
	}

	private User newAdmin() {
		String phone;
		try {
			phone = PhoneNumbers.toE164(properties.phone());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("BOOTSTRAP_ADMIN_PHONE is not a valid phone number");
		}
		return User.create(properties.email(), phone, passwordEncoder.encode(properties.password()), FULL_NAME,
				Role.ADMIN, clock.instant());
	}

}

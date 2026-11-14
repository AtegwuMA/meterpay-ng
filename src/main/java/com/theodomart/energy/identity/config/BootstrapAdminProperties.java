package com.theodomart.energy.identity.config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The first admin, from BOOTSTRAP_ADMIN_EMAIL, BOOTSTRAP_ADMIN_PHONE and BOOTSTRAP_ADMIN_PASSWORD (DESIGN 17.3). All
 * three or none: a half-configured admin fails startup, so a typo in one variable cannot go unnoticed.
 */
@ConfigurationProperties("meterpay.identity.bootstrap-admin")
public record BootstrapAdminProperties(String email, String phone, String password) {

	static final int MIN_PASSWORD_LENGTH = 10;

	static final int MAX_PASSWORD_BYTES = 72;

	private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

	public BootstrapAdminProperties {
		email = blankToNull(email);
		phone = blankToNull(phone);
		password = blankToNull(password);
		List<String> missing = new ArrayList<>();
		if (email == null) {
			missing.add("BOOTSTRAP_ADMIN_EMAIL");
		}
		if (phone == null) {
			missing.add("BOOTSTRAP_ADMIN_PHONE");
		}
		if (password == null) {
			missing.add("BOOTSTRAP_ADMIN_PASSWORD");
		}
		if (!missing.isEmpty() && missing.size() < 3) {
			// Names only, never values: the message ends up in the startup log.
			throw new IllegalStateException("Set all three admin bootstrap variables or none. Missing: " + missing);
		}
		// Registration checks email format at the API; nothing does here, so a plain check stops an obvious typo.
		if (email != null && !EMAIL.matcher(email).matches()) {
			throw new IllegalStateException("BOOTSTRAP_ADMIN_EMAIL is not a valid email address");
		}
		// Same rules as registration, so the admin cannot have a password a user would be refused.
		if (password != null && (password.length() < MIN_PASSWORD_LENGTH
				|| password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES)) {
			throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH
					+ " characters and at most " + MAX_PASSWORD_BYTES + " bytes");
		}
	}

	public boolean isConfigured() {
		return email != null;
	}

	/** Records print every field by default, which would put the password in any log that prints this. */
	@Override
	public String toString() {
		return "BootstrapAdminProperties[email=" + email + ", phone=" + phone + ", password=" + (password == null
				? "unset" : "[redacted]") + "]";
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}

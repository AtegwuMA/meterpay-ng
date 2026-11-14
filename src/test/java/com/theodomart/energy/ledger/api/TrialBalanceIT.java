package com.theodomart.energy.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.theodomart.energy.IntegrationTest;
import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.ledger.service.AccountRef;
import com.theodomart.energy.ledger.service.EntryType;
import com.theodomart.energy.ledger.service.JournalEntryCommand;
import com.theodomart.energy.ledger.service.LedgerService;
import com.theodomart.energy.ledger.service.PostingLine;
import com.theodomart.energy.ledger.service.TrialBalanceView;
import com.theodomart.energy.ledger.serviceImpl.LedgerImbalanceMonitor;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class TrialBalanceIT extends IntegrationTest {

	private static final String PASSWORD = "correct-horse-battery";

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	private final PasswordEncoder passwordEncoder;

	private final LedgerService ledger;

	private final LedgerImbalanceMonitor monitor;

	private final MeterRegistry registry;

	private final TransactionTemplate transaction;

	private UUID tenant;

	@Autowired
	TrialBalanceIT(MockMvc mvc, JdbcTemplate jdbc, PasswordEncoder passwordEncoder, LedgerService ledger,
			LedgerImbalanceMonitor monitor, MeterRegistry registry, TransactionTemplate transaction) {
		this.mvc = mvc;
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
		this.ledger = ledger;
		this.monitor = monitor;
		this.registry = registry;
		this.transaction = transaction;
	}

	@BeforeEach
	void aTenantWithMoney() {
		tenant = user("TENANT", "not-a-real-hash");
		transaction.executeWithoutResult(status -> ledger.openTenantWallet(tenant));
		ledger.post(new JournalEntryCommand(EntryType.WALLET_TOPUP, "test:" + UUID.randomUUID(), null, null, null,
				List.of(PostingLine.debit(AccountRef.gatewayClearing(), Money.ofKobo(250_000)),
						PostingLine.credit(AccountRef.tenantWallet(tenant), Money.ofKobo(250_000)))));
	}

	@Test
	void anAdminSeesTheWholeLedgerBalanced() throws Exception {
		// Shared with every other integration test, so this also checks that none of them left the books unbalanced.
		mvc.perform(get("/api/v1/admin/ledger/trial-balance").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.balanced").value(true))
			.andExpect(jsonPath("$.imbalanceKobo").value(0))
			.andExpect(jsonPath("$.accountsNotMatchingPostings").value(0))
			.andExpect(jsonPath("$.lines.length()").value(5))
			.andExpect(jsonPath("$.lines[?(@.type == 'GATEWAY_CLEARING')].normalBalance").value("DEBIT"))
			.andExpect(jsonPath("$.generatedAt").isNotEmpty());
	}

	@Test
	void tenantsLandlordsAndStrangersAreRefused() throws Exception {
		String landlord = registeredLandlord();

		mvc.perform(get("/api/v1/admin/ledger/trial-balance").header(HttpHeaders.AUTHORIZATION, "Bearer " + landlord))
			.andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/admin/ledger/trial-balance")).andExpect(status().isUnauthorized());
	}

	@Test
	@Transactional
	void aBalanceChangedWithoutAPostingIsCaughtBothWays() {
		// Rolled back with the test. A wallet nudged by one kobo behind the ledger's back, as a bad script might.
		jdbc.update("UPDATE ledger_accounts SET balance_kobo = balance_kobo + 1 WHERE owner_id = ?", tenant);

		TrialBalanceView view = monitor.check();

		assertThat(view.imbalanceKobo()).isEqualTo(-1);
		assertThat(view.accountsNotMatchingPostings()).isOne();
		assertThat(view.balanced()).isFalse();
		assertThat(registry.get("meterpay.ledger.imbalance").gauge().value()).isEqualTo(-1);
		assertThat(registry.get("meterpay.ledger.accounts.mismatched").gauge().value()).isOne();
	}

	@Test
	void aHealthyCheckSetsTheGaugesBackToZero() {
		TrialBalanceView view = monitor.check();

		assertThat(view.balanced()).isTrue();
		assertThat(registry.get("meterpay.ledger.imbalance").gauge().value()).isZero();
		assertThat(registry.get("meterpay.ledger.accounts.mismatched").gauge().value()).isZero();
	}

	private String admin() throws Exception {
		String email = "admin-" + UUID.randomUUID() + "@example.com";
		user("ADMIN", passwordEncoder.encode(PASSWORD), email);
		return login(email);
	}

	private String registeredLandlord() throws Exception {
		String email = "user-" + UUID.randomUUID() + "@example.com";
		mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "phone": "%s", "password": "%s", "fullName": "Ada Obi"}
				""".formatted(email, phone(), PASSWORD))).andExpect(status().isCreated());
		return login(email);
	}

	private String login(String email) throws Exception {
		String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "password": "%s"}
				""".formatted(email, PASSWORD))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private UUID user(String role, String hash) {
		return user(role, hash, "user-" + UUID.randomUUID() + "@example.com");
	}

	private UUID user(String role, String hash, String email) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO users (id, email, phone, password_hash, full_name, role, status, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, 'Ledger User', ?, 'ACTIVE', now(), now(), 0)
				""", id, email, phone(), hash, role);
		return id;
	}

	private static String phone() {
		return "+234809" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000);
	}

}

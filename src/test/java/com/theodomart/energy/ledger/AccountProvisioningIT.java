package com.theodomart.energy.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.theodomart.energy.IntegrationTest;
import com.theodomart.energy.ledger.service.LedgerService;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class AccountProvisioningIT extends IntegrationTest {

	private static final String PASSWORD = "correct-horse-battery";

	private static final String BACKFILL = "db/migration/V11__seed_and_backfill_ledger_accounts.sql";

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	private final LedgerService ledger;

	private final DataSource dataSource;

	private final TransactionTemplate transaction;

	@Autowired
	AccountProvisioningIT(MockMvc mvc, JdbcTemplate jdbc, LedgerService ledger, DataSource dataSource,
			TransactionTemplate transaction) {
		this.mvc = mvc;
		this.jdbc = jdbc;
		this.ledger = ledger;
		this.dataSource = dataSource;
		this.transaction = transaction;
	}

	@Test
	void registeringALandlordOpensOneEmptyRevenueAccount() throws Exception {
		UUID landlord = registerLandlord(uniqueEmail());

		assertThat(accountsOf(landlord)).singleElement().satisfies(account -> {
			assertThat(account).containsEntry("type", "LANDLORD_REVENUE").containsEntry("owner_type", "LANDLORD");
			assertThat(account).containsEntry("currency", "NGN").containsEntry("balance_kobo", 0L);
		});
	}

	@Test
	void registeringATenantOpensOneEmptyWallet() throws Exception {
		String landlordToken = login(uniqueLandlordEmail());
		UUID tenant = registerTenant(uniqueEmail(), inviteFor(landlordToken)).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString()
			.transform(body -> UUID.fromString(JsonPath.read(body, "$.id")));

		assertThat(accountsOf(tenant)).singleElement().satisfies(account -> {
			assertThat(account).containsEntry("type", "TENANT_WALLET").containsEntry("owner_type", "TENANT");
			assertThat(account).containsEntry("balance_kobo", 0L);
		});
	}

	@Test
	void aRejectedInviteCodeLeavesNeitherUserNorWallet() throws Exception {
		String email = uniqueEmail();
		int walletsBefore = walletCount();

		registerTenant(email, "NOT-A-REAL-CODE").andExpect(status().is4xxClientError());

		// The wallet is opened before the code is checked, so only a shared rollback explains no new wallet here.
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isZero();
		assertThat(walletCount()).isEqualTo(walletsBefore);
	}

	@Test
	void aDuplicateRegistrationOpensNoSecondAccount() throws Exception {
		String email = uniqueEmail();
		UUID landlord = registerLandlord(email);

		mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "phone": "%s", "password": "%s", "fullName": "Ada Obi"}
				""".formatted(email, uniquePhone(), PASSWORD))).andExpect(status().isConflict());

		assertThat(accountsOf(landlord)).hasSize(1);
	}

	@Test
	void thePlatformHasExactlyOneOfEachAccount() {
		assertThat(jdbc.queryForList("""
				SELECT type FROM ledger_accounts WHERE owner_type = 'PLATFORM' ORDER BY type
				""", String.class)).containsExactly("GATEWAY_CLEARING", "PLATFORM_FEE_REVENUE", "VAT_PAYABLE");
	}

	@Test
	void openingAnAccountOutsideATransactionIsRefused() {
		assertThatThrownBy(() -> ledger.openTenantWallet(UUID.randomUUID()))
			.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void openingTheSameAccountTwiceKeepsOne() throws Exception {
		UUID landlord = registerLandlord(uniqueEmail());

		transaction.executeWithoutResult(status -> ledger.openLandlordRevenueAccount(landlord));

		assertThat(accountsOf(landlord)).hasSize(1);
	}

	/**
	 * Runs the real V11 script, twice, against users it has never seen: people who registered before M3 and a
	 * registered tenant whose wallet is removed to look the same. Rolled back, so the rows never reach other tests.
	 */
	@Test
	@Transactional
	void backfillGivesEveryEarlierUserExactlyOneAccountAndIsSafeToRepeat() throws Exception {
		UUID earlyTenant = userWithoutAccounts("TENANT", "ACTIVE");
		UUID disabledTenant = userWithoutAccounts("TENANT", "DISABLED");
		UUID earlyLandlord = userWithoutAccounts("LANDLORD", "ACTIVE");
		UUID admin = userWithoutAccounts("ADMIN", "ACTIVE");
		UUID registered = registerLandlord(uniqueEmail());
		UUID walletRemoved = registerTenantDirectly();
		jdbc.update("DELETE FROM ledger_accounts WHERE owner_id = ?", walletRemoved);

		runBackfill();
		runBackfill();

		assertThat(typesOf(earlyTenant)).containsExactly("TENANT_WALLET");
		assertThat(typesOf(disabledTenant)).containsExactly("TENANT_WALLET");
		assertThat(typesOf(walletRemoved)).containsExactly("TENANT_WALLET");
		assertThat(typesOf(earlyLandlord)).containsExactly("LANDLORD_REVENUE");
		assertThat(typesOf(registered)).containsExactly("LANDLORD_REVENUE");
		assertThat(typesOf(admin)).isEmpty();
		thePlatformHasExactlyOneOfEachAccount();
	}

	private void runBackfill() {
		Connection connection = DataSourceUtils.getConnection(dataSource);
		try {
			new ResourceDatabasePopulator(new ClassPathResource(BACKFILL)).populate(connection);
		}
		finally {
			DataSourceUtils.releaseConnection(connection, dataSource);
		}
	}

	private List<Map<String, Object>> accountsOf(UUID owner) {
		return jdbc.queryForList("SELECT * FROM ledger_accounts WHERE owner_id = ?", owner);
	}

	private int walletCount() {
		return jdbc.queryForObject("SELECT count(*) FROM ledger_accounts WHERE type = 'TENANT_WALLET'", Integer.class);
	}

	private List<String> typesOf(UUID owner) {
		return jdbc.queryForList("SELECT type FROM ledger_accounts WHERE owner_id = ?", String.class, owner);
	}

	private UUID userWithoutAccounts(String role, String status) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO users (id, email, phone, password_hash, full_name, role, status, created_at, updated_at, version)
				VALUES (?, ?, ?, 'not-a-real-hash', 'Early User', ?, ?, now(), now(), 0)
				""", id, "user-" + id + "@example.com", uniquePhone(), role, status);
		return id;
	}

	private UUID registerTenantDirectly() throws Exception {
		UUID id = userWithoutAccounts("TENANT", "ACTIVE");
		transaction.executeWithoutResult(status -> ledger.openTenantWallet(id));
		assertThat(typesOf(id)).containsExactly("TENANT_WALLET");
		return id;
	}

	private UUID registerLandlord(String email) throws Exception {
		String body = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "phone": "%s", "password": "%s", "fullName": "Ada Obi"}
				""".formatted(email, uniquePhone(), PASSWORD)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	private String uniqueLandlordEmail() throws Exception {
		String email = uniqueEmail();
		registerLandlord(email);
		return email;
	}

	private ResultActions registerTenant(String email, String code) throws Exception {
		return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "phone": "%s", "password": "%s", "fullName": "Tolu Ade", "inviteCode": "%s"}
				""".formatted(email, uniquePhone(), PASSWORD, code)));
	}

	private String inviteFor(String landlordToken) throws Exception {
		String building = idOf(mvc.perform(post("/api/v1/buildings").header(HttpHeaders.AUTHORIZATION,
				"Bearer " + landlordToken).contentType(MediaType.APPLICATION_JSON).content("""
						{"name": "Sunrise Court", "address": "12 Allen Avenue", "state": "Lagos", "lga": "Ikeja"}
						""")).andExpect(status().isCreated()));
		String unit = idOf(mvc.perform(post("/api/v1/buildings/" + building + "/units")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + landlordToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"label\": \"Flat 1\"}")).andExpect(status().isCreated()));
		String body = mvc.perform(post("/api/v1/units/" + unit + "/invites").header(HttpHeaders.AUTHORIZATION,
				"Bearer " + landlordToken)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.code");
	}

	private String login(String email) throws Exception {
		String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "password": "%s"}
				""".formatted(email, PASSWORD))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private static String idOf(ResultActions created) throws Exception {
		return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id");
	}

	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	private static String uniquePhone() {
		return "+234803" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000);
	}

}

package com.theodomart.energy.property.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.theodomart.energy.IntegrationTest;
import com.theodomart.energy.identity.service.IdentityService;
import com.theodomart.energy.identity.service.RegisterCommand;
import com.theodomart.energy.property.domain.InviteCode;
import com.theodomart.energy.property.service.InvalidInviteCodeException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class TenancyFlowIT extends IntegrationTest {

	private static final String PASSWORD = "correct-horse-battery";

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	private final IdentityService identity;

	private String landlord;

	private String unitId;

	@Autowired
	TenancyFlowIT(MockMvc mvc, JdbcTemplate jdbc, IdentityService identity) {
		this.mvc = mvc;
		this.jdbc = jdbc;
		this.identity = identity;
	}

	@BeforeEach
	void landlordWithOneVacantUnit() throws Exception {
		landlord = landlordToken();
		String building = idOf(mvc.perform(authorised(post("/api/v1/buildings"), landlord).content("""
				{"name": "Sunrise Court", "address": "12 Allen Avenue", "state": "Lagos", "lga": "Ikeja"}
				""")).andExpect(status().isCreated()));
		unitId = idOf(mvc.perform(authorised(post("/api/v1/buildings/" + building + "/units"), landlord)
			.content("{\"label\": \"Flat 1\"}")).andExpect(status().isCreated()));
	}

	@Test
	void landlordInvitesAndTenantRegistersWithTheCode() throws Exception {
		String code = invite(landlord, unitId);
		assertThat(code).matches("[2-9A-Z]{5}-[2-9A-Z]{5}");
		String email = uniqueEmail();

		registerTenant(email, code).andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("TENANT"));

		mvc.perform(authorised(get("/api/v1/me"), login(email)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("TENANT"));
		assertThat(activeTenancies(unitId)).isEqualTo(1);
		String tenantId = jdbc.queryForObject("SELECT id::text FROM users WHERE email = ?", String.class, email);
		assertThat(jdbc.queryForObject("SELECT tenant_id::text FROM tenancies WHERE unit_id = ?::uuid", String.class,
				unitId)).isEqualTo(tenantId);
	}

	@Test
	void registeredTenantHasNoLandlordAccess() throws Exception {
		String email = uniqueEmail();
		registerTenant(email, invite(landlord, unitId)).andExpect(status().isCreated());
		String tenant = login(email);

		mvc.perform(authorised(get("/api/v1/buildings"), tenant)).andExpect(status().isForbidden());
		mvc.perform(authorised(post("/api/v1/units/" + unitId + "/invites"), tenant)).andExpect(status().isForbidden());
	}

	@Test
	void codeWorksHoweverItIsTyped() throws Exception {
		String code = invite(landlord, unitId);

		registerTenant(uniqueEmail(), code.toLowerCase().replace("-", " ")).andExpect(status().isCreated());
	}

	@Test
	void codeWorksOnlyOnce() throws Exception {
		String code = invite(landlord, unitId);
		registerTenant(uniqueEmail(), code).andExpect(status().isCreated());
		String secondEmail = uniqueEmail();

		registerTenant(secondEmail, code).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("INVALID_INVITE_CODE"));

		assertThat(usersWithEmail(secondEmail)).as("the failed registration left no account").isZero();
	}

	@Test
	void unknownCodeIsRejectedAndCreatesNoAccount() throws Exception {
		String email = uniqueEmail();

		registerTenant(email, "AAAAA-BBBBB").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("INVALID_INVITE_CODE"));

		assertThat(usersWithEmail(email)).isZero();
	}

	@Test
	void expiredCodeIsRejected() throws Exception {
		String code = invite(landlord, unitId);
		jdbc.update("UPDATE tenant_invites SET created_at = now() - interval '8 days', "
				+ "expires_at = now() - interval '1 day' WHERE code_hash = ?", InviteCode.hash(code));

		registerTenant(uniqueEmail(), code).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("INVALID_INVITE_CODE"));
	}

	@Test
	void aNewInviteCancelsThePreviousOne() throws Exception {
		String first = invite(landlord, unitId);
		String second = invite(landlord, unitId);

		registerTenant(uniqueEmail(), first).andExpect(status().isUnprocessableContent());
		registerTenant(uniqueEmail(), second).andExpect(status().isCreated());
	}

	@Test
	void duplicateAccountLeavesTheCodeUsable() throws Exception {
		String code = invite(landlord, unitId);
		String takenEmail = uniqueEmail();
		mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "phone": "%s", "password": "%s", "fullName": "Ada Obi"}
				""".formatted(takenEmail, uniquePhone(), PASSWORD))).andExpect(status().isCreated());

		registerTenant(takenEmail, code).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DUPLICATE_ACCOUNT"));

		assertThat(activeTenancies(unitId)).isZero();
		registerTenant(uniqueEmail(), code).andExpect(status().isCreated());
	}

	@Test
	void concurrentRegistrationsWithOneCodeLetExactlyOneThrough() throws Exception {
		String code = invite(landlord, unitId);
		int threads = 6;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		List<Future<?>> results = new ArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				RegisterCommand command = new RegisterCommand(uniqueEmail(), uniquePhone(), PASSWORD, "Racer");
				results.add(pool.submit(() -> {
					start.await();
					return identity.registerTenant(command, code);
				}));
			}
			start.countDown();
			int succeeded = 0;
			for (Future<?> result : results) {
				try {
					result.get(30, TimeUnit.SECONDS);
					succeeded++;
				}
				catch (ExecutionException ex) {
					assertThat(ex.getCause()).isInstanceOf(InvalidInviteCodeException.class);
				}
			}
			assertThat(succeeded).isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(activeTenancies(unitId)).isEqualTo(1);
	}

	@Test
	void occupiedUnitCannotGetANewInvite() throws Exception {
		registerTenant(uniqueEmail(), invite(landlord, unitId)).andExpect(status().isCreated());

		mvc.perform(authorised(post("/api/v1/units/" + unitId + "/invites"), landlord))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("UNIT_OCCUPIED"));
	}

	@Test
	void endingATenancyFreesTheUnitAndKeepsTheHistory() throws Exception {
		registerTenant(uniqueEmail(), invite(landlord, unitId)).andExpect(status().isCreated());

		mvc.perform(authorised(post("/api/v1/units/" + unitId + "/tenancy/end"), landlord))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ENDED"))
			.andExpect(jsonPath("$.endsOn").isNotEmpty());

		registerTenant(uniqueEmail(), invite(landlord, unitId)).andExpect(status().isCreated());
		assertThat(activeTenancies(unitId)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM tenancies WHERE unit_id = ?::uuid", Integer.class, unitId))
			.isEqualTo(2);
	}

	@Test
	void endingWithNoActiveTenancyGives404() throws Exception {
		mvc.perform(authorised(post("/api/v1/units/" + unitId + "/tenancy/end"), landlord))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void anotherLandlordsUnitLooksLikeAMissingOne() throws Exception {
		registerTenant(uniqueEmail(), invite(landlord, unitId)).andExpect(status().isCreated());
		String intruder = landlordToken();

		mvc.perform(authorised(post("/api/v1/units/" + unitId + "/invites"), intruder))
			.andExpect(status().isNotFound());
		mvc.perform(authorised(post("/api/v1/units/" + unitId + "/tenancy/end"), intruder))
			.andExpect(status().isNotFound());
		mvc.perform(authorised(post("/api/v1/units/" + UUID.randomUUID() + "/invites"), intruder))
			.andExpect(status().isNotFound());

		assertThat(activeTenancies(unitId)).isEqualTo(1);
	}

	@Test
	void databaseAllowsOnlyOneActiveTenancyPerUnit() throws Exception {
		String email = uniqueEmail();
		registerTenant(email, invite(landlord, unitId)).andExpect(status().isCreated());
		String tenantId = jdbc.queryForObject("SELECT id::text FROM users WHERE email = ?", String.class, email);

		assertThatThrownBy(() -> insertTenancy(tenantId, "ACTIVE", null))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("uk_tenancies_one_active_per_unit");

		insertTenancy(tenantId, "ENDED", java.time.LocalDate.now());
	}

	@Test
	void invitesAreStoredOnlyAsHashes() throws Exception {
		String code = invite(landlord, unitId);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_invites WHERE code_hash = ?", Integer.class, code))
			.isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_invites WHERE code_hash = ?", Integer.class,
				InviteCode.hash(code)))
			.isEqualTo(1);
	}

	private void insertTenancy(String tenantId, String status, java.time.LocalDate endsOn) {
		jdbc.update("""
				INSERT INTO tenancies (id, unit_id, tenant_id, starts_on, ends_on, status, created_at, updated_at, version)
				VALUES (?, ?::uuid, ?::uuid, ?, ?, ?, now(), now(), 0)
				""", UUID.randomUUID(), unitId, tenantId, java.time.LocalDate.now(), endsOn, status);
	}

	private String invite(String token, String unit) throws Exception {
		String body = mvc.perform(authorised(post("/api/v1/units/" + unit + "/invites"), token))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.unitId").value(unit))
			.andExpect(jsonPath("$.expiresAt").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.code");
	}

	private ResultActions registerTenant(String email, String code) throws Exception {
		return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "phone": "%s", "password": "%s", "fullName": "Tolu Ade", "inviteCode": "%s"}
				""".formatted(email, uniquePhone(), PASSWORD, code)));
	}

	private String landlordToken() throws Exception {
		String email = uniqueEmail();
		mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "phone": "%s", "password": "%s", "fullName": "Ada Obi"}
				""".formatted(email, uniquePhone(), PASSWORD))).andExpect(status().isCreated());
		return login(email);
	}

	private String login(String email) throws Exception {
		String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "password": "%s"}
				""".formatted(email, PASSWORD))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private int usersWithEmail(String email) {
		return jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
	}

	private int activeTenancies(String unit) {
		return jdbc.queryForObject("SELECT count(*) FROM tenancies WHERE unit_id = ?::uuid AND status = 'ACTIVE'",
				Integer.class, unit);
	}

	private static String idOf(ResultActions created) throws Exception {
		return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id");
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorised(
			org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String token) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
	}

	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	private static String uniquePhone() {
		return "+234803" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000);
	}

}

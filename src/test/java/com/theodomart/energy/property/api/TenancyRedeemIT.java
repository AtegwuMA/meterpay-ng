package com.theodomart.energy.property.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.theodomart.energy.IntegrationTest;
import com.theodomart.energy.property.domain.InviteCode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class TenancyRedeemIT extends IntegrationTest {

	private static final String PASSWORD = "correct-horse-battery";

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	private String landlord;

	private String building;

	private String tenant;

	private String firstUnit;

	@Autowired
	TenancyRedeemIT(MockMvc mvc, JdbcTemplate jdbc) {
		this.mvc = mvc;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void tenantAlreadyLivingInOneUnit() throws Exception {
		landlord = landlordToken();
		building = newBuilding();
		firstUnit = newUnit("Flat 1");
		tenant = registerTenant(invite(firstUnit));
	}

	@Test
	void existingTenantJoinsASecondUnitAndKeepsTheSameWallet() throws Exception {
		UUID tenantId = idOfUser(tenant);
		UUID walletBefore = walletOf(tenantId);
		String secondUnit = newUnit("Flat 2");

		redeem(tenant, invite(secondUnit)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.unitId").value(secondUnit))
			.andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
			.andExpect(jsonPath("$.status").value("ACTIVE"));

		mvc.perform(authorised(get("/api/v1/me/tenancies"), tenant))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tenancies.length()").value(2));
		assertThat(jdbc.queryForList("SELECT id FROM ledger_accounts WHERE owner_id = ?", UUID.class, tenantId))
			.containsExactly(walletBefore);
	}

	@Test
	void aCodeIsTypedTheWayTenantsTypeIt() throws Exception {
		String code = invite(newUnit("Flat 2"));
		String typed = " " + code.toLowerCase().replace("-", " - ") + " ";

		redeem(tenant, typed).andExpect(status().isCreated());
	}

	@Test
	void aCodeWorksOnlyOnce() throws Exception {
		String code = invite(newUnit("Flat 2"));
		redeem(tenant, code).andExpect(status().isCreated());

		redeem(anotherTenant(), code).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("INVALID_INVITE_CODE"));
	}

	@Test
	void anUnknownCodeGetsTheSameAnswerAsAUsedOne() throws Exception {
		redeem(tenant, "ABCDE-FGHJK").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("INVALID_INVITE_CODE"))
			.andExpect(jsonPath("$.detail").value("This invite code is invalid or has expired. Ask your landlord for a new one."));
	}

	@Test
	void anExpiredCodeIsRefused() throws Exception {
		String code = invite(newUnit("Flat 2"));
		jdbc.update("UPDATE tenant_invites SET created_at = now() - interval '8 days', "
				+ "expires_at = now() - interval '1 day' WHERE code_hash = ?", InviteCode.hash(code));

		redeem(tenant, code).andExpect(status().isUnprocessableContent());
	}

	@Test
	void aUnitTakenSinceTheInviteIsRefusedAndTheCodeStaysUnused() throws Exception {
		String secondUnit = newUnit("Flat 2");
		String code = invite(secondUnit);
		// Someone moved in another way after the invite was made. Written directly: the API refuses to invite into an
		// occupied unit, so this is the race the unique index exists for.
		jdbc.update("""
				INSERT INTO tenancies (id, unit_id, tenant_id, starts_on, status, created_at, updated_at, version)
				VALUES (?, ?::uuid, ?, current_date, 'ACTIVE', now(), now(), 0)
				""", UUID.randomUUID(), secondUnit, idOfUser(anotherTenant()));

		redeem(tenant, code).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UNIT_OCCUPIED"));

		assertThat(jdbc.queryForObject("SELECT accepted_at IS NULL FROM tenant_invites WHERE code_hash = ?",
				Boolean.class, InviteCode.hash(code)))
			.isTrue();
	}

	@Test
	void twoTenantsRacingForOneCodeGetOneTenancy() throws Exception {
		String code = invite(newUnit("Flat 2"));
		List<String> racers = List.of(tenant, anotherTenant(), anotherTenant(), anotherTenant());
		CountDownLatch start = new CountDownLatch(1);
		List<Callable<Integer>> attempts = new ArrayList<>();
		for (String racer : racers) {
			attempts.add(() -> {
				start.await();
				return redeem(racer, code).andReturn().getResponse().getStatus();
			});
		}

		List<Integer> statuses = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(racers.size())) {
			List<Future<Integer>> futures = new ArrayList<>();
			for (Callable<Integer> attempt : attempts) {
				futures.add(pool.submit(attempt));
			}
			start.countDown();
			for (Future<Integer> future : futures) {
				statuses.add(future.get());
			}
		}

		assertThat(statuses).containsOnlyOnce(201).containsOnly(201, 422);
	}

	@Test
	void aMissingOrMalformedCodeIsABadRequest() throws Exception {
		mvc.perform(authorised(post("/api/v1/tenancies/redeem"), tenant).content("{}")).andExpect(status().isBadRequest());
		redeem(tenant, "SHORT").andExpect(status().isBadRequest());
	}

	@Test
	void onlyTenantsCanRedeemAndOnlyWhenLoggedIn() throws Exception {
		String code = invite(newUnit("Flat 2"));

		redeem(landlord, code).andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/tenancies/redeem").contentType(MediaType.APPLICATION_JSON)
			.content("{\"inviteCode\": \"" + code + "\"}")).andExpect(status().isUnauthorized());
		// Neither refusal used the code up.
		redeem(tenant, code).andExpect(status().isCreated());
	}

	private ResultActions redeem(String token, String code) throws Exception {
		return mvc.perform(authorised(post("/api/v1/tenancies/redeem"), token)
			.content("{\"inviteCode\": \"" + code + "\"}"));
	}

	private UUID walletOf(UUID tenantId) {
		return jdbc.queryForObject("SELECT id FROM ledger_accounts WHERE owner_id = ? AND type = 'TENANT_WALLET'",
				UUID.class, tenantId);
	}

	private UUID idOfUser(String token) throws Exception {
		return UUID.fromString(JsonPath.read(mvc.perform(authorised(get("/api/v1/me"), token))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.id"));
	}

	private String anotherTenant() throws Exception {
		return registerTenant(invite(newUnit("Flat " + UUID.randomUUID().toString().substring(0, 8))));
	}

	private String newBuilding() throws Exception {
		return idOf(mvc.perform(authorised(post("/api/v1/buildings"), landlord).content("""
				{"name": "Sunrise Court", "address": "12 Allen Avenue", "state": "Lagos", "lga": "Ikeja"}
				""")).andExpect(status().isCreated()));
	}

	private String newUnit(String label) throws Exception {
		return idOf(mvc.perform(authorised(post("/api/v1/buildings/" + building + "/units"), landlord)
			.content("{\"label\": \"" + label + "\"}")).andExpect(status().isCreated()));
	}

	private String invite(String unitId) throws Exception {
		String body = mvc.perform(authorised(post("/api/v1/units/" + unitId + "/invites"), landlord))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.code");
	}

	private String registerTenant(String code) throws Exception {
		String email = uniqueEmail();
		mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "phone": "%s", "password": "%s", "fullName": "Tolu Ade", "inviteCode": "%s"}
				""".formatted(email, uniquePhone(), PASSWORD, code))).andExpect(status().isCreated());
		return login(email);
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

	private static String idOf(ResultActions created) throws Exception {
		return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id");
	}

	private static MockHttpServletRequestBuilder authorised(MockHttpServletRequestBuilder request, String token) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
	}

	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	private static String uniquePhone() {
		return "+234803" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000);
	}

}

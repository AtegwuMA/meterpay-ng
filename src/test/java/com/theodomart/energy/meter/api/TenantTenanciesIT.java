package com.theodomart.energy.meter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.theodomart.energy.IntegrationTest;
import com.theodomart.energy.meter.service.MeterService;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
class TenantTenanciesIT extends IntegrationTest {

	private static final String PASSWORD = "correct-horse-battery";

	private static final String TENANCIES = "/api/v1/me/tenancies";

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	private final MeterService meters;

	private final EntityManagerFactory entityManagers;

	private String landlord;

	private String building;

	private String unit;

	@Autowired
	TenantTenanciesIT(MockMvc mvc, JdbcTemplate jdbc, MeterService meters, EntityManagerFactory entityManagers) {
		this.mvc = mvc;
		this.jdbc = jdbc;
		this.meters = meters;
		this.entityManagers = entityManagers;
	}

	@BeforeEach
	void landlordWithOneUnit() throws Exception {
		landlord = landlordToken();
		building = newBuilding("Sunrise Court");
		unit = newUnit(building, "Flat 1");
	}

	@Test
	void tenantFindsTheirMeterWithoutKnowingItsId() throws Exception {
		String tenant = tenantOf(unit);
		String serial = uniqueSerial();
		String meter = installedMeter(unit, serial);

		mvc.perform(authorised(get(TENANCIES), tenant))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].tenancyId").isNotEmpty())
			.andExpect(jsonPath("$[0].buildingId").value(building))
			.andExpect(jsonPath("$[0].buildingName").value("Sunrise Court"))
			.andExpect(jsonPath("$[0].unitId").value(unit))
			.andExpect(jsonPath("$[0].unitLabel").value("Flat 1"))
			.andExpect(jsonPath("$[0].startsOn").isNotEmpty())
			.andExpect(jsonPath("$[0].meter.id").value(meter))
			.andExpect(jsonPath("$[0].meter.serialNumber").value(serial))
			.andExpect(jsonPath("$[0].meter.status").value("PROVISIONED"))
			.andExpect(jsonPath("$[0].meter.creditBalanceWh").value(0));
	}

	@Test
	void unitWithNoMeterYetStillShowsTheTenancy() throws Exception {
		mvc.perform(authorised(get(TENANCIES), tenantOf(unit)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].unitId").value(unit))
			.andExpect(jsonPath("$[0].meter").value(nullValue()));
	}

	@Test
	void creditIsTheMetersRealBalanceEvenBelowZero() throws Exception {
		String tenant = tenantOf(unit);
		String meter = installedMeter(unit, uniqueSerial());
		jdbc.update("UPDATE meters SET credit_balance_wh = -150 WHERE id = ?::uuid", meter);

		mvc.perform(authorised(get(TENANCIES), tenant))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].meter.creditBalanceWh").value(-150));
	}

	@Test
	void statusFollowsTheMeter() throws Exception {
		String tenant = tenantOf(unit);
		String meter = installedMeter(unit, uniqueSerial());
		mvc.perform(authorised(post("/api/v1/meters/" + meter + "/suspend"), landlord)).andExpect(status().isOk());

		mvc.perform(authorised(get(TENANCIES), tenant))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].meter.status").value("SUSPENDED"));
	}

	@Test
	void formerTenantSeesNothing() throws Exception {
		String tenant = tenantOf(unit);
		installedMeter(unit, uniqueSerial());
		mvc.perform(authorised(post("/api/v1/units/" + unit + "/tenancy/end"), landlord)).andExpect(status().isOk());

		mvc.perform(authorised(get(TENANCIES), tenant))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void tenantSeesOnlyTheirOwnTenancy() throws Exception {
		String otherUnit = newUnit(building, "Flat 2");
		String tenant = tenantOf(unit);
		String neighbour = tenantOf(otherUnit);
		String neighboursMeter = installedMeter(otherUnit, uniqueSerial());

		mvc.perform(authorised(get(TENANCIES), tenant))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].unitId").value(unit))
			.andExpect(jsonPath("$[0].meter").value(nullValue()));
		mvc.perform(authorised(get(TENANCIES), neighbour))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].meter.id").value(neighboursMeter));
	}

	@Test
	void severalFlatsComeNewestFirstEachWithItsOwnMeter() throws Exception {
		String tenant = tenantOf(unit);
		UUID tenantId = idOfUser(tenant);
		String second = newUnit(newBuilding("Palm Grove"), "Flat 9");
		String third = newUnit(building, "Flat 3");
		String firstMeter = installedMeter(unit, uniqueSerial());
		String thirdMeter = installedMeter(third, uniqueSerial());
		// A logged-in tenant joining a second unit arrives in M3-02a, so these rows are written directly.
		extraTenancy(second, tenantId, LocalDate.now().plusDays(10));
		extraTenancy(third, tenantId, LocalDate.now().plusDays(20));

		mvc.perform(authorised(get(TENANCIES), tenant))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].unitId").value(third))
			.andExpect(jsonPath("$[0].meter.id").value(thirdMeter))
			.andExpect(jsonPath("$[1].unitId").value(second))
			.andExpect(jsonPath("$[1].buildingName").value("Palm Grove"))
			.andExpect(jsonPath("$[1].meter").value(nullValue()))
			.andExpect(jsonPath("$[2].unitId").value(unit))
			.andExpect(jsonPath("$[2].meter.id").value(firstMeter));
	}

	@Test
	void queryCountDoesNotGrowWithTheNumberOfTenancies() throws Exception {
		String tenant = tenantOf(unit);
		UUID tenantId = idOfUser(tenant);
		installedMeter(unit, uniqueSerial());
		assertThat(statementsToList(tenantId)).isEqualTo(2);

		for (int flat = 2; flat <= 6; flat++) {
			String another = newUnit(building, "Flat " + flat);
			installedMeter(another, uniqueSerial());
			extraTenancy(another, tenantId, LocalDate.now());
		}

		assertThat(meters.listTenancies(tenantId)).hasSize(6);
		assertThat(statementsToList(tenantId)).isEqualTo(2);
	}

	@Test
	void landlordIsRefusedAndAnonymousIsAskedToLogIn() throws Exception {
		mvc.perform(authorised(get(TENANCIES), landlord)).andExpect(status().isForbidden());
		mvc.perform(get(TENANCIES)).andExpect(status().isUnauthorized());
	}

	private long statementsToList(UUID tenantId) {
		Statistics statistics = entityManagers.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
		try {
			statistics.clear();
			meters.listTenancies(tenantId);
			return statistics.getPrepareStatementCount();
		}
		finally {
			statistics.setStatisticsEnabled(false);
		}
	}

	private void extraTenancy(String unitId, UUID tenantId, LocalDate startsOn) {
		jdbc.update("""
				INSERT INTO tenancies (id, unit_id, tenant_id, starts_on, status, created_at, updated_at, version)
				VALUES (?, ?::uuid, ?, ?, 'ACTIVE', now(), now(), 0)
				""", UUID.randomUUID(), unitId, tenantId, Date.valueOf(startsOn));
	}

	private UUID idOfUser(String token) throws Exception {
		return UUID.fromString(JsonPath.read(mvc.perform(authorised(get("/api/v1/me"), token))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.id"));
	}

	private String installedMeter(String unitId, String serial) throws Exception {
		String meter = idOf(mvc.perform(authorised(post("/api/v1/meters"), landlord).content("""
				{"serialNumber": "%s", "gatewayType": "SIMULATED"}
				""".formatted(serial))).andExpect(status().isCreated()));
		mvc.perform(authorised(put("/api/v1/meters/" + meter + "/unit"), landlord)
			.content("{\"unitId\": \"" + unitId + "\"}")).andExpect(status().isOk());
		return meter;
	}

	private String newBuilding(String name) throws Exception {
		return idOf(mvc.perform(authorised(post("/api/v1/buildings"), landlord).content("""
				{"name": "%s", "address": "12 Allen Avenue", "state": "Lagos", "lga": "Ikeja"}
				""".formatted(name))).andExpect(status().isCreated()));
	}

	private String newUnit(String buildingId, String label) throws Exception {
		return idOf(mvc.perform(authorised(post("/api/v1/buildings/" + buildingId + "/units"), landlord)
			.content("{\"label\": \"" + label + "\"}")).andExpect(status().isCreated()));
	}

	private String tenantOf(String unitId) throws Exception {
		String body = mvc.perform(authorised(post("/api/v1/units/" + unitId + "/invites"), landlord))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String code = JsonPath.read(body, "$.code");
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

	private static String uniqueSerial() {
		return "SIM-" + UUID.randomUUID().toString().substring(0, 18).toUpperCase();
	}

	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	private static String uniquePhone() {
		return "+234803" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000);
	}

}

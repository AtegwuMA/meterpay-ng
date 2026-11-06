package com.theodomart.energy.meter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.theodomart.energy.IntegrationTest;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class MeterRegistryIT extends IntegrationTest {

	private static final String PASSWORD = "correct-horse-battery";

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	private String landlord;

	private String unitId;

	@Autowired
	MeterRegistryIT(MockMvc mvc, JdbcTemplate jdbc) {
		this.mvc = mvc;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void landlordWithOneUnit() throws Exception {
		landlord = landlordToken();
		unitId = newUnit(landlord, "Flat 1");
	}

	@Test
	void landlordRegistersAndReadsAMeter() throws Exception {
		String serial = uniqueSerial();
		ResultActions created = registerMeter(landlord, serial.toLowerCase(), "SIMULATED").andExpect(status().isCreated())
			.andExpect(jsonPath("$.serialNumber").value(serial))
			.andExpect(jsonPath("$.gatewayType").value("SIMULATED"))
			.andExpect(jsonPath("$.status").value("PROVISIONED"))
			.andExpect(jsonPath("$.unitId").doesNotExist())
			.andExpect(jsonPath("$.creditBalanceWh").value(0))
			.andExpect(jsonPath("$.lowCreditThresholdWh").value(5000));
		String id = idOf(created);
		created.andExpect(header().string(HttpHeaders.LOCATION, endsWith("/api/v1/meters/" + id)));

		mvc.perform(authorised(get("/api/v1/meters/" + id), landlord))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id));
	}

	@Test
	void landlordCanSetTheLowCreditThreshold() throws Exception {
		mvc.perform(authorised(post("/api/v1/meters"), landlord).content("""
				{"serialNumber": "%s", "gatewayType": "SIMULATED", "lowCreditThresholdWh": 1500}
				""".formatted(uniqueSerial())))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.lowCreditThresholdWh").value(1500));
	}

	@Test
	void duplicateSerialGives409IgnoringCaseEvenAcrossLandlords() throws Exception {
		String serial = uniqueSerial();
		registerMeter(landlord, serial, "SIMULATED").andExpect(status().isCreated());

		registerMeter(landlordToken(), serial.toLowerCase(), "SIMULATED").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
	}

	@Test
	void invalidRegistrationsGive400() throws Exception {
		registerMeter(landlord, uniqueSerial(), "NONSENSE").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		registerMeter(landlord, "no spaces allowed", "SIMULATED").andExpect(status().isBadRequest());
		registerMeter(landlord, uniqueSerial(), "TUYA").andExpect(status().isBadRequest());
		mvc.perform(authorised(post("/api/v1/meters"), landlord).content("""
				{"serialNumber": "%s", "gatewayType": "SIMULATED", "lowCreditThresholdWh": -1}
				""".formatted(uniqueSerial()))).andExpect(status().isBadRequest());
	}

	@Test
	void meterIsAssignedToAUnitAndRepeatingIsHarmless() throws Exception {
		String meter = idOf(registerMeter(landlord, uniqueSerial(), "SIMULATED"));

		assign(landlord, meter, unitId).andExpect(status().isOk())
			.andExpect(jsonPath("$.unitId").value(unitId))
			.andExpect(jsonPath("$.status").value("PROVISIONED"));
		assign(landlord, meter, unitId).andExpect(status().isOk());
	}

	@Test
	void unitCanHaveOnlyOneMeter() throws Exception {
		assign(landlord, idOf(registerMeter(landlord, uniqueSerial(), "SIMULATED")), unitId)
			.andExpect(status().isOk());

		assign(landlord, idOf(registerMeter(landlord, uniqueSerial(), "SIMULATED")), unitId)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
			.andExpect(jsonPath("$.detail").value("This unit already has a meter."));
	}

	@Test
	void assignedMeterCannotMoveToAnotherUnit() throws Exception {
		String meter = idOf(registerMeter(landlord, uniqueSerial(), "SIMULATED"));
		assign(landlord, meter, unitId).andExpect(status().isOk());

		assign(landlord, meter, newUnit(landlord, "Flat 2")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("This meter is already assigned to another unit."));
	}

	@Test
	void anotherLandlordsMeterAndUnitLookMissing() throws Exception {
		String meter = idOf(registerMeter(landlord, uniqueSerial(), "SIMULATED"));
		String intruder = landlordToken();
		String intrudersMeter = idOf(registerMeter(intruder, uniqueSerial(), "SIMULATED"));

		mvc.perform(authorised(get("/api/v1/meters/" + meter), intruder)).andExpect(status().isNotFound());
		assign(intruder, meter, newUnit(intruder, "Flat 9")).andExpect(status().isNotFound());
		assign(intruder, intrudersMeter, unitId).andExpect(status().isNotFound());
	}

	@Test
	void currentTenantCanReadTheirMeterButOthersCannot() throws Exception {
		String meter = idOf(registerMeter(landlord, uniqueSerial(), "SIMULATED"));
		assign(landlord, meter, unitId).andExpect(status().isOk());
		String occupant = tenantOf(landlord, unitId);
		String otherTenant = tenantOf(landlord, newUnit(landlord, "Flat 2"));

		mvc.perform(authorised(get("/api/v1/meters/" + meter), occupant))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(meter));
		mvc.perform(authorised(get("/api/v1/meters/" + meter), otherTenant))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void formerTenantLosesAccess() throws Exception {
		String meter = idOf(registerMeter(landlord, uniqueSerial(), "SIMULATED"));
		assign(landlord, meter, unitId).andExpect(status().isOk());
		String tenant = tenantOf(landlord, unitId);
		mvc.perform(authorised(post("/api/v1/units/" + unitId + "/tenancy/end"), landlord)).andExpect(status().isOk());

		mvc.perform(authorised(get("/api/v1/meters/" + meter), tenant)).andExpect(status().isNotFound());
	}

	@Test
	void tenantCannotRegisterOrAssignAndAnonymousGets401() throws Exception {
		String meter = idOf(registerMeter(landlord, uniqueSerial(), "SIMULATED"));
		String tenant = tenantOf(landlord, unitId);

		registerMeter(tenant, uniqueSerial(), "SIMULATED").andExpect(status().isForbidden());
		assign(tenant, meter, unitId).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/meters/" + meter)).andExpect(status().isUnauthorized());
	}

	@Test
	void databaseRejectsALowerCaseSerialWrittenAroundTheEntity() throws Exception {
		String landlordId = jdbc.queryForObject("SELECT id::text FROM users LIMIT 1", String.class);

		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO meters (id, landlord_id, serial_number, gateway_type, status, last_register_wh,
					credit_balance_wh, low_credit_threshold_wh, created_at, updated_at, version)
				VALUES (?, ?::uuid, 'lower-case', 'SIMULATED', 'PROVISIONED', 0, 0, 0, now(), now(), 0)
				""", UUID.randomUUID(), landlordId))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("ck_meters_serial_upper");
	}

	@ParameterizedTest
	@ValueSource(strings = { "PROVISIONED", "ACTIVE", "LOW_CREDIT", "CUTOFF", "TAMPER_SUSPECTED" })
	void resumeRestoresThePreviousStateAcrossRequests(String previous) throws Exception {
		String meter = idOf(registerMeter(landlord, uniqueSerial(), "SIMULATED"));
		setStatus(meter, previous);

		action(landlord, meter, "suspend").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUSPENDED"));
		assertThat(jdbc.queryForObject("SELECT status_before_suspension FROM meters WHERE id = ?::uuid", String.class,
				meter))
			.isEqualTo(previous);

		action(landlord, meter, "resume").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(previous));
		assertThat(jdbc.queryForObject("SELECT status_before_suspension FROM meters WHERE id = ?::uuid", String.class,
				meter))
			.isNull();
	}

	@Test
	void suspendingASuspendedMeterAndResumingARunningOneGive409() throws Exception {
		String meter = idOf(registerMeter(landlord, uniqueSerial(), "SIMULATED"));

		action(landlord, meter, "resume").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"))
			.andExpect(jsonPath("$.detail").value("Only a suspended meter can be resumed. This one is PROVISIONED."));

		action(landlord, meter, "suspend").andExpect(status().isOk());
		action(landlord, meter, "suspend").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"));

		action(landlord, meter, "resume").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PROVISIONED"));
	}

	@Test
	void decommissionedMeterCannotBeSuspendedOrResumed() throws Exception {
		String meter = idOf(registerMeter(landlord, uniqueSerial(), "SIMULATED"));
		setStatus(meter, "DECOMMISSIONED");

		action(landlord, meter, "suspend").andExpect(status().isConflict());
		action(landlord, meter, "resume").andExpect(status().isConflict());
	}

	@Test
	void onlyTheOwningLandlordCanSuspendOrResume() throws Exception {
		String meter = idOf(registerMeter(landlord, uniqueSerial(), "SIMULATED"));
		assign(landlord, meter, unitId).andExpect(status().isOk());
		String occupant = tenantOf(landlord, unitId);
		String intruder = landlordToken();

		action(intruder, meter, "suspend").andExpect(status().isNotFound());
		action(occupant, meter, "suspend").andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/meters/" + meter + "/suspend")).andExpect(status().isUnauthorized());

		action(landlord, meter, "suspend").andExpect(status().isOk());

		action(intruder, meter, "resume").andExpect(status().isNotFound());
		action(occupant, meter, "resume").andExpect(status().isForbidden());
		mvc.perform(authorised(get("/api/v1/meters/" + meter), occupant))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUSPENDED"));
	}

	@Test
	void parallelSuspendsLetExactlyOneWinAndKeepThePreviousState() throws Exception {
		String meter = idOf(registerMeter(landlord, uniqueSerial(), "SIMULATED"));
		setStatus(meter, "CUTOFF");
		int requests = 4;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();

		try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
			Callable<Integer> suspend = () -> {
				start.await();
				return action(landlord, meter, "suspend").andReturn().getResponse().getStatus();
			};
			for (int i = 0; i < requests; i++) {
				results.add(pool.submit(suspend));
			}
			start.countDown();
		}

		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> result : results) {
			statuses.add(result.get());
		}
		assertThat(statuses).containsOnlyOnce(200).containsOnly(200, 409);
		action(landlord, meter, "resume").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CUTOFF"));
	}

	@Test
	void databaseRejectsASuspensionMemoryThatDisagreesWithTheStatus() throws Exception {
		String meter = idOf(registerMeter(landlord, uniqueSerial(), "SIMULATED"));

		assertThatThrownBy(() -> setStatus(meter, "SUSPENDED", null))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("ck_meters_suspension_memory");
		assertThatThrownBy(() -> setStatus(meter, "ACTIVE", "CUTOFF"))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("ck_meters_suspension_memory");
		assertThatThrownBy(() -> setStatus(meter, "SUSPENDED", "SUSPENDED"))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("ck_meters_status_before_suspension");
	}

	// Nothing else moves a meter out of PROVISIONED until M6, so tests set the starting state directly.
	private void setStatus(String meter, String status) {
		setStatus(meter, status, null);
	}

	private void setStatus(String meter, String status, String before) {
		jdbc.update("UPDATE meters SET status = ?, status_before_suspension = ? WHERE id = ?::uuid", status, before,
				meter);
	}

	private ResultActions action(String token, String meter, String name) throws Exception {
		return mvc.perform(authorised(post("/api/v1/meters/" + meter + "/" + name), token));
	}

	private ResultActions registerMeter(String token, String serial, String gateway) throws Exception {
		return mvc.perform(authorised(post("/api/v1/meters"), token).content("""
				{"serialNumber": "%s", "gatewayType": "%s"}
				""".formatted(serial, gateway)));
	}

	private ResultActions assign(String token, String meter, String unit) throws Exception {
		return mvc.perform(authorised(put("/api/v1/meters/" + meter + "/unit"), token)
			.content("{\"unitId\": \"" + unit + "\"}"));
	}

	private String newUnit(String token, String label) throws Exception {
		String building = idOf(mvc.perform(authorised(post("/api/v1/buildings"), token).content("""
				{"name": "Sunrise Court", "address": "12 Allen Avenue", "state": "Lagos", "lga": "Ikeja"}
				""")).andExpect(status().isCreated()));
		return idOf(mvc.perform(authorised(post("/api/v1/buildings/" + building + "/units"), token)
			.content("{\"label\": \"" + label + "\"}")).andExpect(status().isCreated()));
	}

	private String tenantOf(String landlordToken, String unit) throws Exception {
		String body = mvc.perform(authorised(post("/api/v1/units/" + unit + "/invites"), landlordToken))
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

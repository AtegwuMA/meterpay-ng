package com.theodomart.energy.tariff.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.theodomart.energy.IntegrationTest;
import com.theodomart.energy.common.domain.Energy;
import com.theodomart.energy.common.domain.Money;
import com.theodomart.energy.tariff.repository.TariffPlanRepository;
import com.theodomart.energy.tariff.repository.TariffVersionRepository;
import com.theodomart.energy.tariff.service.PriceBreakdown;
import com.theodomart.energy.tariff.service.PricingCalculator;
import com.theodomart.energy.tariff.service.TariffService;
import com.theodomart.energy.tariff.service.TariffVersionView;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class TariffFlowIT extends IntegrationTest {

	private static final String PASSWORD = "correct-horse-battery";

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	private final TariffService tariffs;

	private final PricingCalculator pricing;

	private final TariffPlanRepository plans;

	private final TariffVersionRepository versions;

	private String landlord;

	private String building;

	@Autowired
	TariffFlowIT(MockMvc mvc, JdbcTemplate jdbc, TariffService tariffs, PricingCalculator pricing,
			TariffPlanRepository plans, TariffVersionRepository versions) {
		this.mvc = mvc;
		this.jdbc = jdbc;
		this.tariffs = tariffs;
		this.pricing = pricing;
		this.plans = plans;
		this.versions = versions;
	}

	@BeforeEach
	void landlordWithABuilding() throws Exception {
		landlord = landlordToken();
		building = newBuilding(landlord);
	}

	@Test
	void firstVersionCreatesThePlanAndStartsNow() throws Exception {
		ResultActions created = addVersion(landlord, building, 22_500).andExpect(status().isCreated())
			.andExpect(jsonPath("$.buildingId").value(building))
			.andExpect(jsonPath("$.planName").value("Standard"))
			.andExpect(jsonPath("$.rateKoboPerKwh").value(22_500))
			.andExpect(jsonPath("$.active").value(true));
		String id = idOf(created);
		created.andExpect(header().string(HttpHeaders.LOCATION,
				endsWith("/api/v1/buildings/" + building + "/tariffs/" + id)));

		assertThat(activeVersion().map(TariffVersionView::id)).contains(UUID.fromString(id));
	}

	@Test
	void vatAndFeeComeFromThePlatformNotTheLandlord() throws Exception {
		mvc.perform(authorised(post(tariffsOf(building)), landlord).content("""
				{"rateKoboPerKwh": 22500, "vatBasisPoints": 0, "platformFeeBasisPoints": 0}
				""")).andExpect(status().isCreated())
			.andExpect(jsonPath("$.vatBasisPoints").value(750))
			.andExpect(jsonPath("$.platformFeeBasisPoints").value(200));
	}

	@Test
	void theActiveVersionPricesThePurchaseInTheDesignExample() throws Exception {
		addVersion(landlord, building, 22_500).andExpect(status().isCreated());

		PriceBreakdown price = pricing.price(Money.ofKobo(200_000), activeVersion().orElseThrow());

		assertThat(price.vat()).isEqualTo(Money.ofKobo(13_953));
		assertThat(price.fee()).isEqualTo(Money.ofKobo(3_720));
		assertThat(price.net()).isEqualTo(Money.ofKobo(182_327));
		assertThat(price.energy()).isEqualTo(Energy.ofWh(8_103));
	}

	@Test
	void futureDatedVersionIsNotActiveYet() throws Exception {
		String current = idOf(addVersion(landlord, building, 22_500).andExpect(status().isCreated()));
		Instant nextMonth = future(30);

		String scheduled = idOf(addVersion(landlord, building, 30_000, nextMonth).andExpect(status().isCreated())
			.andExpect(jsonPath("$.effectiveFrom").value(nextMonth.toString()))
			.andExpect(jsonPath("$.active").value(false)));

		mvc.perform(authorised(get(tariffsOf(building)), landlord))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.content[0].id").value(scheduled))
			.andExpect(jsonPath("$.content[0].active").value(false))
			.andExpect(jsonPath("$.content[1].id").value(current))
			.andExpect(jsonPath("$.content[1].active").value(true));
		assertThat(activeVersion().map(TariffVersionView::rateKoboPerKwh)).contains(22_500L);
	}

	@Test
	void buildingWithOnlyAFutureVersionHasNoActiveTariff() throws Exception {
		addVersion(landlord, building, 22_500, future(1)).andExpect(status().isCreated());

		assertThat(activeVersion()).isEmpty();
	}

	@Test
	void buildingWithNoTariffListsNothing() throws Exception {
		mvc.perform(authorised(get(tariffsOf(building)), landlord))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(0))
			.andExpect(jsonPath("$.content.length()").value(0));
		assertThat(activeVersion()).isEmpty();
	}

	@Test
	void theActiveVersionChangesExactlyAtTheEffectiveDate() throws Exception {
		addVersion(landlord, building, 22_500).andExpect(status().isCreated());
		Instant start = future(7);
		addVersion(landlord, building, 30_000, start).andExpect(status().isCreated());
		UUID plan = plans.findByBuildingId(UUID.fromString(building)).orElseThrow().getId();

		assertThat(rateAt(plan, start.minus(1, ChronoUnit.MICROS))).isEqualTo(22_500);
		assertThat(rateAt(plan, start)).isEqualTo(30_000);
		assertThat(rateAt(plan, start.plusSeconds(1))).isEqualTo(30_000);
		assertThat(versions.findFirstByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(plan,
				Instant.parse("2020-01-01T00:00:00Z")))
			.isEmpty();
	}

	@Test
	void planIsNamedByTheFirstVersionAndNeverChanges() throws Exception {
		mvc.perform(authorised(post(tariffsOf(building)), landlord).content("""
				{"rateKoboPerKwh": 22500, "planName": "  Residential "}
				""")).andExpect(status().isCreated()).andExpect(jsonPath("$.planName").value("Residential"));

		mvc.perform(authorised(post(tariffsOf(building)), landlord).content("""
				{"rateKoboPerKwh": 30000, "planName": "Other", "effectiveFrom": "%s"}
				""".formatted(future(1)))).andExpect(status().isCreated()).andExpect(jsonPath("$.planName").value("Residential"));

		assertThat(planCount()).isEqualTo(1);
	}

	@Test
	void invalidVersionsGive400AndLeaveNothingBehind() throws Exception {
		addVersion(landlord, building, 0).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("rateKoboPerKwh"));
		mvc.perform(authorised(post(tariffsOf(building)), landlord).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("rateKoboPerKwh"));
		mvc.perform(authorised(post(tariffsOf(building)), landlord).content("""
				{"rateKoboPerKwh": 22500, "effectiveFrom": "next tuesday"}
				""")).andExpect(status().isBadRequest());
		addVersion(landlord, building, 22_500, Instant.now().minus(1, ChronoUnit.DAYS))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("Effective date cannot be in the past."));

		assertThat(planCount()).as("the rolled back request must not leave an empty plan").isZero();
	}

	@Test
	void twoVersionsCannotStartAtTheSameMoment() throws Exception {
		Instant start = future(7);
		addVersion(landlord, building, 22_500, start).andExpect(status().isCreated());

		addVersion(landlord, building, 30_000, start).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
			.andExpect(jsonPath("$.detail").value("A tariff version already starts at that time."));
	}

	@Test
	void parallelFirstVersionsAllSucceedAndShareOnePlan() throws Exception {
		int requests = 4;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();

		try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
			for (int i = 0; i < requests; i++) {
				Instant effectiveFrom = future(i + 1);
				Callable<Integer> add = () -> {
					start.await();
					return addVersion(landlord, building, 22_500, effectiveFrom).andReturn().getResponse().getStatus();
				};
				results.add(pool.submit(add));
			}
			start.countDown();
		}

		for (Future<Integer> result : results) {
			assertThat(result.get()).isEqualTo(201);
		}
		assertThat(planCount()).isEqualTo(1);
	}

	@Test
	void versionsArePagedNewestFirst() throws Exception {
		addVersion(landlord, building, 10_000, future(1)).andExpect(status().isCreated());
		addVersion(landlord, building, 20_000, future(2)).andExpect(status().isCreated());

		mvc.perform(authorised(get(tariffsOf(building)).param("size", "1"), landlord))
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].rateKoboPerKwh").value(20_000));
		mvc.perform(authorised(get(tariffsOf(building)).param("size", "101"), landlord))
			.andExpect(status().isBadRequest());
	}

	@Test
	void onlyTheOwningLandlordCanSeeOrChangeTariffs() throws Exception {
		String intruder = landlordToken();
		String unit = idOf(mvc.perform(authorised(post("/api/v1/buildings/" + building + "/units"), landlord)
			.content("{\"label\": \"Flat 1\"}")).andExpect(status().isCreated()));
		String tenant = tenantOf(landlord, unit);

		addVersion(intruder, building, 1).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		mvc.perform(authorised(get(tariffsOf(building)), intruder)).andExpect(status().isNotFound());
		addVersion(tenant, building, 1).andExpect(status().isForbidden());
		mvc.perform(authorised(get(tariffsOf(building)), tenant)).andExpect(status().isForbidden());
		mvc.perform(get(tariffsOf(building))).andExpect(status().isUnauthorized());
		addVersion(landlord, UUID.randomUUID().toString(), 22_500).andExpect(status().isNotFound());
		assertThat(planCount()).isZero();
	}

	@Test
	void versionsCannotBeEditedOrDeletedOverHttp() throws Exception {
		addVersion(landlord, building, 22_500).andExpect(status().isCreated());

		mvc.perform(authorised(put(tariffsOf(building)), landlord).content("{\"rateKoboPerKwh\": 1}"))
			.andExpect(status().isMethodNotAllowed());
		mvc.perform(authorised(delete(tariffsOf(building)), landlord)).andExpect(status().isMethodNotAllowed());
	}

	@Test
	void databaseRejectsAnUnusableVersionWrittenAroundTheEntity() throws Exception {
		addVersion(landlord, building, 22_500).andExpect(status().isCreated());
		UUID plan = plans.findByBuildingId(UUID.fromString(building)).orElseThrow().getId();

		assertRejected(plan, 0, 750, 200, "ck_tariff_versions_rate");
		assertRejected(plan, 22_500, 10_001, 200, "ck_tariff_versions_vat");
		assertRejected(plan, 22_500, 750, 10_000, "ck_tariff_versions_fee");
	}

	private void assertRejected(UUID plan, long rate, int vat, int fee, String constraint) {
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO tariff_versions (id, plan_id, rate_kobo_per_kwh, vat_basis_points,
					platform_fee_basis_points, effective_from, created_at)
				VALUES (?, ?, ?, ?, ?, now(), now())
				""", UUID.randomUUID(), plan, rate, vat, fee))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining(constraint);
	}

	private long rateAt(UUID plan, Instant at) {
		return versions.findFirstByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(plan, at)
			.orElseThrow()
			.getRateKoboPerKwh();
	}

	private Optional<TariffVersionView> activeVersion() {
		return tariffs.findActiveVersion(UUID.fromString(building));
	}

	private Integer planCount() {
		return jdbc.queryForObject("SELECT count(*) FROM tariff_plans WHERE building_id = ?::uuid", Integer.class,
				building);
	}

	private static Instant future(int days) {
		return Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
	}

	private static String tariffsOf(String building) {
		return "/api/v1/buildings/" + building + "/tariffs";
	}

	private ResultActions addVersion(String token, String building, long rate) throws Exception {
		return mvc.perform(authorised(post(tariffsOf(building)), token).content("""
				{"rateKoboPerKwh": %d}
				""".formatted(rate)));
	}

	private ResultActions addVersion(String token, String building, long rate, Instant effectiveFrom)
			throws Exception {
		return mvc.perform(authorised(post(tariffsOf(building)), token).content("""
				{"rateKoboPerKwh": %d, "effectiveFrom": "%s"}
				""".formatted(rate, effectiveFrom)));
	}

	private String newBuilding(String token) throws Exception {
		return idOf(mvc.perform(authorised(post("/api/v1/buildings"), token).content("""
				{"name": "Sunrise Court", "address": "12 Allen Avenue", "state": "Lagos", "lga": "Ikeja"}
				""")).andExpect(status().isCreated()));
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

	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	private static String uniquePhone() {
		return "+234803" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000);
	}

}

package com.theodomart.energy.property.api;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.theodomart.energy.IntegrationTest;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// Every test registers its own landlord, so lists only ever contain that test's buildings.
@AutoConfigureMockMvc
class BuildingsIT extends IntegrationTest {

	private static final String PASSWORD = "correct-horse-battery";

	private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	private final PasswordEncoder passwordEncoder;

	@Autowired
	BuildingsIT(MockMvc mvc, JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
		this.mvc = mvc;
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
	}

	@Test
	void landlordCreatesAndReadsABuilding() throws Exception {
		String token = landlordToken();

		ResultActions created = createBuilding(token, "Sunrise Court").andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("Sunrise Court"))
			.andExpect(jsonPath("$.state").value("Lagos"))
			.andExpect(jsonPath("$.landlordId").doesNotExist());
		String id = idOf(created);
		created.andExpect(header().string(HttpHeaders.LOCATION, endsWith("/api/v1/buildings/" + id)));

		mvc.perform(get("/api/v1/buildings/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.address").value("12 Allen Avenue"))
			.andExpect(jsonPath("$.lga").value("Ikeja"));
	}

	@Test
	void listIsPagedSortedByNameAndShowsOnlyTheCallersBuildings() throws Exception {
		String token = landlordToken();
		createBuilding(token, "Charlie Court").andExpect(status().isCreated());
		createBuilding(token, "Alpha Court").andExpect(status().isCreated());
		createBuilding(token, "Bravo Court").andExpect(status().isCreated());
		createBuilding(landlordToken(), "Someone Elses Court").andExpect(status().isCreated());

		listBuildings(token, "page=0&size=2").andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(2))
			.andExpect(jsonPath("$.content[0].name").value("Alpha Court"))
			.andExpect(jsonPath("$.content[1].name").value("Bravo Court"))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(2))
			.andExpect(jsonPath("$.totalElements").value(3));
		listBuildings(token, "page=1&size=2").andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].name").value("Charlie Court"));
	}

	@Test
	void pageSizeAbove100IsRejected() throws Exception {
		listBuildings(landlordToken(), "size=101").andExpect(status().isBadRequest())
			.andExpect(content().contentType(PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void patchChangesOnlyTheFieldsSent() throws Exception {
		String token = landlordToken();
		String id = idOf(createBuilding(token, "Sunrise Court"));

		mvc.perform(patch("/api/v1/buildings/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Sunset Court\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Sunset Court"))
			.andExpect(jsonPath("$.address").value("12 Allen Avenue"))
			.andExpect(jsonPath("$.lga").value("Ikeja"));
	}

	@Test
	void patchWithABlankValueIsRejected() throws Exception {
		String token = landlordToken();
		String id = idOf(createBuilding(token, "Sunrise Court"));

		mvc.perform(patch("/api/v1/buildings/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"   \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void createWithMissingFieldsGives400WithFieldErrors() throws Exception {
		mvc.perform(post("/api/v1/buildings").header(HttpHeaders.AUTHORIZATION, bearer(landlordToken()))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Only a name\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.length()").value(3));
	}

	@Test
	void unitsAreAddedAndListedByLabel() throws Exception {
		String token = landlordToken();
		String id = idOf(createBuilding(token, "Sunrise Court"));

		addUnit(token, id, "Flat 2").andExpect(status().isCreated())
			.andExpect(jsonPath("$.buildingId").value(id))
			.andExpect(jsonPath("$.label").value("Flat 2"));
		addUnit(token, id, " Flat   1 ").andExpect(status().isCreated()).andExpect(jsonPath("$.label").value("Flat 1"));

		mvc.perform(get("/api/v1/buildings/" + id + "/units").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.content[0].label").value("Flat 1"))
			.andExpect(jsonPath("$.content[1].label").value("Flat 2"));
	}

	@Test
	void duplicateUnitLabelInTheSameBuildingGives409IgnoringCase() throws Exception {
		String token = landlordToken();
		String id = idOf(createBuilding(token, "Sunrise Court"));
		addUnit(token, id, "Flat 3B").andExpect(status().isCreated());

		addUnit(token, id, "flat 3b").andExpect(status().isConflict())
			.andExpect(content().contentType(PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
	}

	@Test
	void sameUnitLabelIsFineInADifferentBuilding() throws Exception {
		String token = landlordToken();
		addUnit(token, idOf(createBuilding(token, "First Court")), "Flat 1").andExpect(status().isCreated());

		addUnit(token, idOf(createBuilding(token, "Second Court")), "Flat 1").andExpect(status().isCreated());
	}

	@Test
	void unknownBuildingGives404() throws Exception {
		mvc.perform(get("/api/v1/buildings/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION,
				bearer(landlordToken())))
			.andExpect(status().isNotFound())
			.andExpect(content().contentType(PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void anotherLandlordsBuildingLooksExactlyLikeAMissingOne() throws Exception {
		String owner = landlordToken();
		String id = idOf(createBuilding(owner, "Private Court"));
		String intruder = landlordToken();

		mvc.perform(get("/api/v1/buildings/" + id).header(HttpHeaders.AUTHORIZATION, bearer(intruder)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		addUnit(intruder, id, "Flat 1").andExpect(status().isNotFound());
	}

	@Test
	void tenantIsForbiddenFromBuildingEndpoints() throws Exception {
		listBuildings(tenantToken(), "").andExpect(status().isForbidden())
			.andExpect(content().contentType(PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	void anonymousCallerGets401() throws Exception {
		mvc.perform(get("/api/v1/buildings")).andExpect(status().isUnauthorized());
	}

	private ResultActions createBuilding(String token, String name) throws Exception {
		return mvc.perform(post("/api/v1/buildings").header(HttpHeaders.AUTHORIZATION, bearer(token))
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"name": "%s", "address": "12 Allen Avenue", "state": "Lagos", "lga": "Ikeja"}
					""".formatted(name)));
	}

	private ResultActions listBuildings(String token, String query) throws Exception {
		return mvc.perform(get("/api/v1/buildings?" + query).header(HttpHeaders.AUTHORIZATION, bearer(token)));
	}

	private ResultActions addUnit(String token, String buildingId, String label) throws Exception {
		return mvc.perform(post("/api/v1/buildings/" + buildingId + "/units")
			.header(HttpHeaders.AUTHORIZATION, bearer(token))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"label\": \"" + label + "\"}"));
	}

	private String landlordToken() throws Exception {
		String email = "landlord-" + UUID.randomUUID() + "@example.com";
		mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "phone": "%s", "password": "%s", "fullName": "Ada Obi"}
				""".formatted(email, uniquePhone(), PASSWORD))).andExpect(status().isCreated());
		return login(email);
	}

	/** Tenants cannot self-register until invites exist (M1-06), so the row is written directly. */
	private String tenantToken() throws Exception {
		String email = "tenant-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (id, email, phone, password_hash, full_name, role, status, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, 'Tolu Ade', 'TENANT', 'ACTIVE', now(), now(), 0)
				""", UUID.randomUUID(), email, uniquePhone(), passwordEncoder.encode(PASSWORD));
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

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private static String uniquePhone() {
		return "+234803" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000);
	}

}

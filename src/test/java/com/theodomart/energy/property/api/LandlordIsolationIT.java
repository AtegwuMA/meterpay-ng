package com.theodomart.energy.property.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.theodomart.energy.IntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Two real landlords: the second one tries every building and unit endpoint against the first one's data. */
@AutoConfigureMockMvc
class LandlordIsolationIT extends IntegrationTest {

	private static final String PASSWORD = "correct-horse-battery";

	private final MockMvc mvc;

	private String owner;

	private String intruder;

	private String buildingId;

	@Autowired
	LandlordIsolationIT(MockMvc mvc) {
		this.mvc = mvc;
	}

	@BeforeEach
	void twoLandlordsAndOneBuilding() throws Exception {
		owner = landlordToken();
		intruder = landlordToken();
		String body = mvc
			.perform(authorised(post("/api/v1/buildings"), owner).content("""
					{"name": "Private Court", "address": "12 Allen Avenue", "state": "Lagos", "lga": "Ikeja"}
					"""))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		buildingId = JsonPath.read(body, "$.id");
		mvc.perform(authorised(post("/api/v1/buildings/" + buildingId + "/units"), owner)
			.content("{\"label\": \"Flat 1\"}")).andExpect(status().isCreated());
	}

	@Test
	void everyEndpointReturns404ForAnotherLandlordsBuilding() throws Exception {
		for (MockHttpServletRequestBuilder attempt : attemptsOn(buildingId)) {
			mvc.perform(authorised(attempt, intruder))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		}
	}

	@Test
	void anotherLandlordsBuildingIsIndistinguishableFromOneThatDoesNotExist() throws Exception {
		String unknownId = UUID.randomUUID().toString();

		List<MockHttpServletRequestBuilder> onReal = attemptsOn(buildingId);
		List<MockHttpServletRequestBuilder> onUnknown = attemptsOn(unknownId);
		for (int i = 0; i < onReal.size(); i++) {
			String real = mvc.perform(authorised(onReal.get(i), intruder)).andReturn().getResponse().getContentAsString();
			String missing = mvc.perform(authorised(onUnknown.get(i), intruder))
				.andReturn()
				.getResponse()
				.getContentAsString();

			assertThat(comparable(real, buildingId)).isEqualTo(comparable(missing, unknownId));
		}
	}

	@Test
	void failedAttemptsChangeNothing() throws Exception {
		for (MockHttpServletRequestBuilder attempt : attemptsOn(buildingId)) {
			mvc.perform(authorised(attempt, intruder)).andExpect(status().isNotFound());
		}

		mvc.perform(authorised(get("/api/v1/buildings/" + buildingId), owner))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Private Court"));
		mvc.perform(authorised(get("/api/v1/buildings/" + buildingId + "/units"), owner))
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.content[0].label").value("Flat 1"));
	}

	@Test
	void listsNeverIncludeAnotherLandlordsBuildings() throws Exception {
		mvc.perform(authorised(get("/api/v1/buildings"), intruder))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(0))
			.andExpect(jsonPath("$.content.length()").value(0));
		mvc.perform(authorised(get("/api/v1/buildings"), owner)).andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void ownerStillHasFullAccess() throws Exception {
		mvc.perform(authorised(patch("/api/v1/buildings/" + buildingId), owner).content("{\"name\": \"Renamed\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Renamed"));
		mvc.perform(authorised(post("/api/v1/buildings/" + buildingId + "/units"), owner)
			.content("{\"label\": \"Flat 2\"}")).andExpect(status().isCreated());
	}

	private static List<MockHttpServletRequestBuilder> attemptsOn(String id) {
		return List.of(get("/api/v1/buildings/" + id),
				patch("/api/v1/buildings/" + id).content("{\"name\": \"Stolen\"}"),
				get("/api/v1/buildings/" + id + "/units"),
				post("/api/v1/buildings/" + id + "/units").content("{\"label\": \"Flat 99\"}"));
	}

	/** The id and traceId differ between any two requests, so they are removed before comparing. */
	private static String comparable(String problemJson, String id) {
		return problemJson.replace(id, "<id>").replaceAll("\"traceId\":\"[^\"]*\"", "\"traceId\":\"<trace>\"");
	}

	private static MockHttpServletRequestBuilder authorised(MockHttpServletRequestBuilder request, String token) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
	}

	private String landlordToken() throws Exception {
		String email = "landlord-" + UUID.randomUUID() + "@example.com";
		String phone = "+234803" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000);
		mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "phone": "%s", "password": "%s", "fullName": "Ada Obi"}
				""".formatted(email, phone, PASSWORD))).andExpect(status().isCreated());
		String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "password": "%s"}
				""".formatted(email, PASSWORD))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

}

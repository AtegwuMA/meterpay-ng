package com.theodomart.energy.ledger.api;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class WalletIT extends IntegrationTest {

	private static final String PASSWORD = "correct-horse-battery";

	private final MockMvc mvc;

	private final LedgerService ledger;

	private String landlordToken;

	private UUID landlordId;

	private String building;

	private String tenantToken;

	private UUID tenantId;

	@Autowired
	WalletIT(MockMvc mvc, LedgerService ledger) {
		this.mvc = mvc;
		this.ledger = ledger;
	}

	@BeforeEach
	void tenantWithAWallet() throws Exception {
		landlordToken = register(uniqueEmail(), null);
		landlordId = idOf(landlordToken);
		building = idFrom(mvc.perform(authorised(post("/api/v1/buildings"), landlordToken).content("""
				{"name": "Sunrise Court", "address": "12 Allen Avenue", "state": "Lagos", "lga": "Ikeja"}
				""")).andExpect(status().isCreated()));
		tenantToken = newTenant();
		tenantId = idOf(tenantToken);
	}

	@Test
	void aNewTenantHasAnEmptyWalletAndNoHistory() throws Exception {
		mvc.perform(authorised(get("/api/v1/wallet"), tenantToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.balanceKobo").value(0))
			.andExpect(jsonPath("$.currency").value("NGN"))
			.andExpect(jsonPath("$.updatedAt").isNotEmpty());
		mvc.perform(authorised(get("/api/v1/wallet/transactions"), tenantToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(0))
			.andExpect(jsonPath("$.totalElements").value(0))
			.andExpect(jsonPath("$.size").value(20));
	}

	@Test
	void theWalletShowsItsBalanceAndEveryMovementNewestFirst() throws Exception {
		UUID intent = UUID.randomUUID();
		ledger.post(topUp(tenantId, 500_000, intent));
		ledger.post(purchase(tenantId, 200_000, "key-" + UUID.randomUUID()));

		mvc.perform(authorised(get("/api/v1/wallet"), tenantToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.balanceKobo").value(300_000));
		mvc.perform(authorised(get("/api/v1/wallet/transactions"), tenantToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.content[0].type").value("ENERGY_PURCHASE"))
			.andExpect(jsonPath("$.content[0].direction").value("DEBIT"))
			.andExpect(jsonPath("$.content[0].amountKobo").value(200_000))
			.andExpect(jsonPath("$.content[1].type").value("WALLET_TOPUP"))
			.andExpect(jsonPath("$.content[1].direction").value("CREDIT"))
			.andExpect(jsonPath("$.content[1].amountKobo").value(500_000))
			.andExpect(jsonPath("$.content[1].referenceType").value("PAYMENT_INTENT"))
			.andExpect(jsonPath("$.content[1].referenceId").value(intent.toString()))
			.andExpect(jsonPath("$.content[1].description").value("Wallet top-up"))
			.andExpect(jsonPath("$.content[1].entryId").isNotEmpty())
			.andExpect(jsonPath("$.content[1].postedAt").isNotEmpty());
	}

	@Test
	void historyPagesWithoutLosingOrRepeatingAnEntry() throws Exception {
		ledger.post(topUp(tenantId, 900_000, UUID.randomUUID()));
		ledger.post(purchase(tenantId, 100_000, "key-a-" + UUID.randomUUID()));
		ledger.post(purchase(tenantId, 100_000, "key-b-" + UUID.randomUUID()));

		String first = mvc.perform(authorised(get("/api/v1/wallet/transactions?page=0&size=2"), tenantToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(2))
			.andExpect(jsonPath("$.totalElements").value(3))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String second = mvc.perform(authorised(get("/api/v1/wallet/transactions?page=1&size=2"), tenantToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].type").value("WALLET_TOPUP"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		List<String> ids = new java.util.ArrayList<>(JsonPath.read(first, "$.content[*].entryId"));
		ids.addAll(JsonPath.read(second, "$.content[*].entryId"));
		org.assertj.core.api.Assertions.assertThat(ids).doesNotHaveDuplicates().hasSize(3);
	}

	@Test
	void anEntryWithSeveralWalletLinesShowsOnceWithItsNet() throws Exception {
		ledger.post(topUp(tenantId, 1_000, UUID.randomUUID()));
		ledger.post(new JournalEntryCommand(EntryType.ADJUSTMENT, "key-" + UUID.randomUUID(), null, null, "Correction",
				List.of(PostingLine.debit(AccountRef.tenantWallet(tenantId), Money.ofKobo(100)),
						PostingLine.credit(AccountRef.tenantWallet(tenantId), Money.ofKobo(30)),
						PostingLine.credit(AccountRef.vatPayable(), Money.ofKobo(70)))));

		mvc.perform(authorised(get("/api/v1/wallet/transactions"), tenantToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.content[0].type").value("ADJUSTMENT"))
			.andExpect(jsonPath("$.content[0].direction").value("DEBIT"))
			.andExpect(jsonPath("$.content[0].amountKobo").value(70));
		mvc.perform(authorised(get("/api/v1/wallet"), tenantToken)).andExpect(jsonPath("$.balanceKobo").value(930));
	}

	@Test
	void aTenantSeesOnlyTheirOwnWallet() throws Exception {
		ledger.post(topUp(tenantId, 500_000, UUID.randomUUID()));
		String neighbour = newTenant();

		mvc.perform(authorised(get("/api/v1/wallet"), neighbour)).andExpect(jsonPath("$.balanceKobo").value(0));
		mvc.perform(authorised(get("/api/v1/wallet/transactions"), neighbour))
			.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void onlyTenantsHaveAWalletAndOnlyWhenLoggedIn() throws Exception {
		mvc.perform(authorised(get("/api/v1/wallet"), landlordToken)).andExpect(status().isForbidden());
		mvc.perform(authorised(get("/api/v1/wallet/transactions"), landlordToken)).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/wallet")).andExpect(status().isUnauthorized());
	}

	@Test
	void pageSizeIsBounded() throws Exception {
		mvc.perform(authorised(get("/api/v1/wallet/transactions?size=101"), tenantToken))
			.andExpect(status().isBadRequest());
		mvc.perform(authorised(get("/api/v1/wallet/transactions?size=0"), tenantToken))
			.andExpect(status().isBadRequest());
		mvc.perform(authorised(get("/api/v1/wallet/transactions?page=-1"), tenantToken))
			.andExpect(status().isBadRequest());
	}

	private JournalEntryCommand topUp(UUID tenant, long kobo, UUID intent) {
		return new JournalEntryCommand(EntryType.WALLET_TOPUP, "topup:" + intent, "PAYMENT_INTENT", intent,
				"Wallet top-up", List.of(PostingLine.debit(AccountRef.gatewayClearing(), Money.ofKobo(kobo)),
						PostingLine.credit(AccountRef.tenantWallet(tenant), Money.ofKobo(kobo))));
	}

	private JournalEntryCommand purchase(UUID tenant, long kobo, String key) {
		return new JournalEntryCommand(EntryType.ENERGY_PURCHASE, key, null, null, "Energy purchase",
				List.of(PostingLine.debit(AccountRef.tenantWallet(tenant), Money.ofKobo(kobo)),
						PostingLine.credit(AccountRef.landlordRevenue(landlordId), Money.ofKobo(kobo))));
	}

	private String newTenant() throws Exception {
		String unit = idFrom(mvc.perform(authorised(post("/api/v1/buildings/" + building + "/units"), landlordToken)
			.content("{\"label\": \"Flat " + UUID.randomUUID().toString().substring(0, 8) + "\"}"))
			.andExpect(status().isCreated()));
		String code = JsonPath.read(mvc.perform(authorised(post("/api/v1/units/" + unit + "/invites"), landlordToken))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.code");
		return register(uniqueEmail(), code);
	}

	private String register(String email, String inviteCode) throws Exception {
		String code = inviteCode == null ? "" : ", \"inviteCode\": \"" + inviteCode + "\"";
		mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "phone": "%s", "password": "%s", "fullName": "Tolu Ade"%s}
				""".formatted(email, uniquePhone(), PASSWORD, code))).andExpect(status().isCreated());
		String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "password": "%s"}
				""".formatted(email, PASSWORD))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private UUID idOf(String token) throws Exception {
		return UUID.fromString(JsonPath.read(mvc.perform(authorised(get("/api/v1/me"), token))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.id"));
	}

	private static String idFrom(ResultActions created) throws Exception {
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

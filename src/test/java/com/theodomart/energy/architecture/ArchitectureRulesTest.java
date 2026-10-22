package com.theodomart.energy.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

/**
 * Most modules are still empty, so ArchitectureTest passes trivially today. These tests run the same
 * rules against the deliberately broken classes in {@code archfixture} to prove every rule really fails
 * on a violation, and that it blames the right class.
 */
class ArchitectureRulesTest {

	private static final String ROOT = "archfixture";

	private static final JavaClasses FIXTURES = new ClassFileImporter().importPackages(ROOT);

	@Test
	void rule1FlagsInjectingAServiceImplementation() {
		EvaluationResult result = ArchitectureRules.nothingDependsOnServiceImplementations(ROOT).evaluate(FIXTURES);

		assertThat(result.hasViolation()).isTrue();
		assertThat(report(result)).contains("archfixture.ledger.api.LedgerController")
			.contains("archfixture.ledger.serviceImpl.LedgerServiceImpl");
	}

	@Test
	void rule2FlagsReachingIntoAnotherModulesRepository() {
		EvaluationResult result = ArchitectureRules.moduleInternalsAreOnlyUsedInsideTheModule(ROOT, "ledger")
			.evaluate(FIXTURES);

		assertThat(result.hasViolation()).isTrue();
		assertThat(report(result)).contains("archfixture.vending.serviceImpl.PurchaseServiceImpl")
			.contains("archfixture.ledger.repository.LedgerAccountRepository");
	}

	@Test
	void rule2AllowsAccessThroughServiceInterfaces() {
		EvaluationResult result = ArchitectureRules.moduleInternalsAreOnlyUsedInsideTheModule(ROOT, "ledger")
			.evaluate(FIXTURES);

		assertThat(report(result)).contains("PurchaseServiceImpl")
			.doesNotContain("<archfixture.ledger.service.LedgerService>");
	}

	@Test
	void rule3FlagsControllersReturningEntitiesEvenInsideGenerics() {
		EvaluationResult result = ArchitectureRules.controllersNeverReturnEntities(ROOT).evaluate(FIXTURES);

		assertThat(result.hasViolation()).isTrue();
		assertThat(report(result)).contains("LedgerController.account()").contains("LedgerController.accounts()");
	}

	@Test
	void rule4FlagsAppendOnlyRepositoryExtendingJpaRepository() {
		EvaluationResult result = ArchitectureRules.appendOnlyRepositoriesDoNotExtendCrudRepository(ROOT)
			.evaluate(FIXTURES);

		assertThat(result.hasViolation()).isTrue();
		assertThat(report(result)).contains("archfixture.ledger.repository.LedgerAccountRepository");
	}

	@Test
	void rule4FlagsAppendOnlyRepositoryDeclaringARemoveMethod() {
		EvaluationResult result = ArchitectureRules.appendOnlyRepositoriesDeclareNoDeleteMethods(ROOT)
			.evaluate(FIXTURES);

		assertThat(result.hasViolation()).isTrue();
		assertThat(report(result)).contains("AuditLogRepository.removeOlderThan");
	}

	@Test
	void rule5FlagsFloatingPointMoneyOutsideCommon() {
		EvaluationResult result = ArchitectureRules.noFloatingPointFieldsOutsideCommon(ROOT).evaluate(FIXTURES);

		assertThat(result.hasViolation()).isTrue();
		assertThat(report(result)).contains("PurchaseServiceImpl.amountNaira");
	}

	private static String report(EvaluationResult result) {
		return String.join("\n", result.getFailureReport().getDetails());
	}

}

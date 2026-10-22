package com.theodomart.energy.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Controller;

/**
 * The rules from DESIGN §5.2. They take the root package as a parameter so the same rules can be run
 * against deliberately broken fixtures, proving each one actually fails when it should.
 *
 * <p>allowEmptyShould(true): most modules do not exist yet. A rule with nothing to check passes now and
 * starts biting the moment the first matching class is added.
 */
final class ArchitectureRules {

	/** Feature modules from DESIGN §5.1. {@code common} is shared by everyone and is not a module. */
	static final List<String> MODULES = List.of("security", "identity", "property", "meter", "tariff", "ledger",
			"payment", "vending", "telemetry", "alerting", "audit", "reporting", "outbox", "gateway");

	private ArchitectureRules() {
	}

	/** Rule 1: consumers depend on service interfaces, never on serviceImpl. */
	static ArchRule nothingDependsOnServiceImplementations(String root) {
		return noClasses().that()
			.resideOutsideOfPackage(root + "..serviceImpl..")
			.should()
			.dependOnClassesThat()
			.resideInAPackage(root + "..serviceImpl..")
			.because("callers must depend on service interfaces so implementations can change (DESIGN 5.2 rule 1)")
			.allowEmptyShould(true);
	}

	/**
	 * Rule 2: only a module's {@code service} interfaces (and {@code event} records, the contract its
	 * Kafka consumers read) may be used from outside that module.
	 */
	static ArchRule moduleInternalsAreOnlyUsedInsideTheModule(String root, String module) {
		String modulePackage = root + "." + module;
		return classes().that()
			.resideInAPackage(modulePackage + "..")
			.and()
			.resideOutsideOfPackages(modulePackage + ".service..", modulePackage + ".event..")
			.should()
			.onlyHaveDependentClassesThat()
			.resideInAPackage(modulePackage + "..")
			.because("other modules may only call " + module + " through its service interfaces (DESIGN 5.2 rule 2)")
			.allowEmptyShould(true);
	}

	/** Rule 3: controllers return DTO records, never JPA entities, not even inside generics. */
	static ArchRule controllersNeverReturnEntities(String root) {
		// Meta-annotated covers @Controller and @RestController, which carries @Controller itself.
		return methods().that()
			.areDeclaredInClassesThat()
			.resideInAPackage(root + "..")
			.and()
			.areDeclaredInClassesThat()
			.areMetaAnnotatedWith(Controller.class)
			.should(notReturnJpaEntities())
			.because("entities leak persistence details and lazy loading into the API (DESIGN 5.2 rule 3)")
			.allowEmptyShould(true);
	}

	/** Rule 4a: ledger and audit repositories cannot inherit delete methods from Spring Data. */
	static ArchRule appendOnlyRepositoriesDoNotExtendCrudRepository(String root) {
		return noClasses().that()
			.resideInAnyPackage(root + ".ledger.repository..", root + ".audit.repository..")
			.should()
			.beAssignableTo(CrudRepository.class)
			.because("CrudRepository and JpaRepository expose deleteById and friends; ledger and audit are"
					+ " insert only (DESIGN 5.2 rule 4)")
			.allowEmptyShould(true);
	}

	/** Rule 4b: ledger and audit repositories do not declare delete methods of their own either. */
	static ArchRule appendOnlyRepositoriesDeclareNoDeleteMethods(String root) {
		return noMethods().that()
			.areDeclaredInClassesThat()
			.resideInAnyPackage(root + ".ledger.repository..", root + ".audit.repository..")
			.should()
			.haveNameStartingWith("delete")
			.orShould()
			.haveNameStartingWith("remove")
			.because("ledger and audit are insert only; corrections are new entries (DESIGN 5.2 rule 4)")
			.allowEmptyShould(true);
	}

	/**
	 * Rule 5: no floating point or BigDecimal fields outside common. DESIGN scopes the rule to money and
	 * energy fields, but this codebase has no legitimate fractional quantity (voltage is in decivolts), so
	 * banning the types outright is simpler and cannot be dodged by naming a field differently.
	 */
	static ArchRule noFloatingPointFieldsOutsideCommon(String root) {
		return noFields().that()
			.areDeclaredInClassesThat()
			.resideInAPackage(root + "..")
			.and()
			.areDeclaredInClassesThat()
			.resideOutsideOfPackage(root + ".common..")
			.should()
			.haveRawType(double.class)
			.orShould()
			.haveRawType(Double.class)
			.orShould()
			.haveRawType(float.class)
			.orShould()
			.haveRawType(Float.class)
			.orShould()
			.haveRawType(BigDecimal.class)
			.because("money is long kobo and energy is long Wh (DESIGN 5.2 rule 5, DESIGN 6.1)")
			.allowEmptyShould(true);
	}

	private static ArchCondition<JavaMethod> notReturnJpaEntities() {
		return new ArchCondition<>("not return JPA entities") {
			@Override
			public void check(JavaMethod method, ConditionEvents events) {
				for (JavaClass type : method.getReturnType().getAllInvolvedRawTypes()) {
					if (type.isAnnotatedWith(Entity.class)) {
						events.add(SimpleConditionEvent.violated(method,
								method.getFullName() + " returns entity " + type.getName()));
					}
				}
			}
		};
	}

}

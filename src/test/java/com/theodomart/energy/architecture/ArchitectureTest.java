package com.theodomart.energy.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/** Enforces DESIGN §5.2 on the production code. */
class ArchitectureTest {

	private static final String ROOT = "com.theodomart.energy";

	private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages(ROOT);

	static final List<String> MODULES = ArchitectureRules.MODULES;

	@Test
	void nothingDependsOnServiceImplementations() {
		ArchitectureRules.nothingDependsOnServiceImplementations(ROOT).check(PRODUCTION_CLASSES);
	}

	@ParameterizedTest(name = "{0}")
	@FieldSource("MODULES")
	void moduleInternalsAreOnlyUsedInsideTheModule(String module) {
		ArchitectureRules.moduleInternalsAreOnlyUsedInsideTheModule(ROOT, module).check(PRODUCTION_CLASSES);
	}

	@Test
	void controllersNeverReturnEntities() {
		ArchitectureRules.controllersNeverReturnEntities(ROOT).check(PRODUCTION_CLASSES);
	}

	@Test
	void ledgerAndAuditRepositoriesCannotDelete() {
		ArchitectureRules.appendOnlyRepositoriesDoNotExtendCrudRepository(ROOT).check(PRODUCTION_CLASSES);
		ArchitectureRules.appendOnlyRepositoriesDeclareNoDeleteMethods(ROOT).check(PRODUCTION_CLASSES);
	}

	@Test
	void noFloatingPointFieldsOutsideCommon() {
		ArchitectureRules.noFloatingPointFieldsOutsideCommon(ROOT).check(PRODUCTION_CLASSES);
	}

}

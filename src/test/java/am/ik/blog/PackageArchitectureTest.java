package am.ik.blog;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "am.ik.blog", importOptions = ImportOption.DoNotIncludeTests.class)
class PackageArchitectureTest {

	@ArchTest
	static final ArchRule featurePackagesShouldBeFreeOfCycles = slices().matching("am.ik.blog.(*)..")
		.should()
		.beFreeOfCycles();

	/**
	 * Feature sub-packages (e.g. {@code am.ik.blog.entry}) must not depend on the
	 * {@code am.ik.blog.config} package. Config classes may define beans that live under
	 * feature packages, and allowing the reverse direction would invite import cycles.
	 * Cross-cutting helpers that features need should live outside {@code config} — see
	 * {@code am.ik.blog.asset} and {@code am.ik.blog.htmx} for existing examples.
	 */
	@ArchTest
	static final ArchRule featurePackagesShouldNotDependOnConfig = noClasses().that()
		.resideInAPackage("am.ik.blog.(*)..")
		.and()
		.resideOutsideOfPackage("am.ik.blog.config..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("am.ik.blog.config..");

}

// The fork's buildSrc tests: the ones the fork's own rules depend on (publishing coordinates,
// version stability, dependency stability). `:buildSrc-tests` is not part of the fork's build
// (settings.gradle includes the COMPOSE subset only), so this small build compiles those tests
// against the buildSrc jars the fork's build leaves in out/buildSrc and runs them. Not part of
// the fork's build. Run it with extended/compose/tests/buildsrc-tests.sh.
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "fork-buildsrc-tests"

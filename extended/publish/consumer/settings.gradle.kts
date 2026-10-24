// A consumer that resolves window modules and the fork's Compose under them from a staged
// bundle, the way an application would from the repository the bundle is uploaded to. Nothing
// is compiled: the check is what Gradle resolves. Run by extended-publish.yml:
//   gradle -p extended/publish/consumer checkResolution -Pbundle=<dir> -Pjvm=<g:a:v> -PlinuxX64=<g:a:v> -Psuffix=<s>
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
rootProject.name = "fork-consumer"

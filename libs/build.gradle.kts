plugins {
  alias(libs.plugins.kotlin.jvm) apply false
}

group = "org.jep21s.meetupflowagent.libs"
version = "1.0-SNAPSHOT"

allprojects {
  repositories {
    mavenCentral()
  }
}

subprojects {
  group = rootProject.group
  version = rootProject.version
}

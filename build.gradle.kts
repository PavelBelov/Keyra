plugins {
    id("com.android.application") version "8.10.1" apply false
    kotlin("android") version "2.1.20" apply false
    kotlin("jvm") version "2.1.20" apply false
}

apply(from = "scripts/fdroid-validate.gradle")
apply(from = "scripts/dependency-audit.gradle")
apply(from = "scripts/release.gradle")

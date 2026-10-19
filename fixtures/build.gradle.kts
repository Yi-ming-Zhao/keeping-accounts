plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace="dev.localledger.fixtures"; compileSdk=36
 defaultConfig { applicationId="dev.localledger.fixtures";minSdk=30;targetSdk=35;versionCode=1;versionName="1" }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

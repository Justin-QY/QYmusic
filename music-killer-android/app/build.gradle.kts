import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.serialization) // kotlin 搴忓垪鍖?
  id("kotlin-kapt") // brv 蹇呴』寮曞叆姝ゆ彃浠?
  alias(libs.plugins.google.ksp) // ksp
  alias(libs.plugins.navigation.safeargs)
}

android {
  // 绛惧悕淇℃伅浠庢湰鍦?keystore.properties 璇诲彇锛堜笉鍏ュ簱锛?
  val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) {
      f.inputStream().use { load(it) }
    }
  }
  fun prop(key: String, fallback: String): String =
    (keystoreProps.getProperty(key) ?: System.getenv(key) ?: fallback)

  signingConfigs {
    getByName("debug") {
      storeFile = file(prop("storeFile", "C:/OldData/mk/android/mk.jks"))
      storePassword = prop("storePassword", "")
      keyAlias = prop("keyAlias", "qymusic")
      keyPassword = prop("keyPassword", "")
      enableV1Signing = true
      enableV2Signing = true
    }
    create("release") {
      storeFile = file(prop("storeFile", "C:/OldData/mk/android/mk.jks"))
      storePassword = prop("storePassword", "")
      keyAlias = prop("keyAlias", "qymusic")
      keyPassword = prop("keyPassword", "")
      enableV1Signing = true
      enableV2Signing = true
    }
  }
  namespace = "com.qymusic.app"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.qymusic.app"
    minSdk = 24
    targetSdk = 36
    versionCode = 49
    versionName = "1.0.4"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    signingConfig = signingConfigs.getByName("release")
  }

  buildTypes {
    release {
      // 绠€鍗曟贩娣嗭紝闃叉鐩存帴鍙嶇紪璇戝悗澶嶇敤鎺ュ彛涓庡瘑閽?
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
  }

  buildFeatures {
    buildConfig = true // 蹇呴』 true锛屽惁鍒欐病鏈?BuildConfig 鏂囦欢
    viewBinding = true
    dataBinding = true
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "4.1.2"
    }
  }
  ndkVersion = "27.0.12077973"

  splits {
    abi {
      isUniversalApk = true
      reset()
      include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    }
  }


}

dependencies {

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.material)

  implementation(libs.androidx.media3.exoplayer)
  // implementation(libs.androidx.media3.exoplayer.midi)
  implementation(libs.androidx.media3.session)
  implementation(libs.androidx.media3.ui)
  implementation(libs.androidx.media3.common)

  implementation(libs.engine)
  implementation(libs.brv)
  implementation(libs.okhttp)
  implementation(libs.net)
  implementation(libs.kotlin.serialization.json)
  implementation(libs.glide)
  implementation(libs.litepal)
  implementation(libs.tooltip)
  // 鏉冮檺璇锋眰
  implementation(libs.xxpermissions)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.androidx.localbroadcastmanager)
  implementation(libs.androidx.navigation.fragment.ktx)
  implementation(libs.androidx.navigation.ui.ktx)
  implementation("androidx.palette:palette-ktx:1.0.0")
  implementation("com.github.Moriafly:LyricViewX:1.4.0-alpha02")
  // https://mvnrepository.com/artifact/com.intuit.sdp/sdp-android
  runtimeOnly("com.intuit.sdp:sdp-android:1.1.1")

  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
}

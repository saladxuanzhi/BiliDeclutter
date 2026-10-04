plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bilideclutter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bilideclutter"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // 可选：通过 gradle property 指定正式 keystore（本地或 CI 传参都行）
        val storeFileProp = providers.gradleProperty("signingStoreFile").orNull
        if (!storeFileProp.isNullOrBlank()) {
            create("release") {
                storeFile = file(storeFileProp)
                storePassword = providers.gradleProperty("signingStorePassword").get()
                keyAlias = providers.gradleProperty("signingKeyAlias").get()
                keyPassword = providers.gradleProperty("signingKeyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            // 模块代码不需要混淆，避免入口类被重命名导致 xposed_init 找不到
            isMinifyEnabled = false
            // 保证 release 一定有签名：有正式 keystore 用正式的，否则用 debug 兜底（临时签名）
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}

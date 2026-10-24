group = "dev.roflsunriz.vpass"

patches {
    about {
        name = "Vpass ReVanced Patches"
        description = "三井住友カード Vpass アプリのRoot・改ざん・USBデバッグ検知を無効化する ReVanced パッチ"
        source = "https://github.com/roflsunriz/vpass-revanced"
        author = "roflsunriz"
        contact = "https://github.com/roflsunriz/vpass-revanced/issues"
        website = "https://github.com/roflsunriz/vpass-revanced"
        license = "MIT"
    }
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

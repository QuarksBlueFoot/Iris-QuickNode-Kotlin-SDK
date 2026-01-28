// iris-nlp: Natural Language Transaction Builder for Iris SDK
// Build Solana transactions by typing in plain English

dependencies {
    api(project(":iris-core"))
    api(project(":iris-rpc"))
    api(project(":iris-das"))
    api(project(":iris-metis"))
    api(project(":iris-jito"))
    api(project(":iris-priority"))
    api(project(":iris-sns"))
    api(project(":iris-privacy"))
    
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
}

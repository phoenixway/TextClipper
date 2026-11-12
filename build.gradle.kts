buildscript { configurations.classpath { resolutionStrategy.force("com.squareup:javapoet:1.13.0") } }
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
}

/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.tools

import org.junit.*
import org.junit.runner.*
import org.junit.runners.*
import java.io.*
import java.util.*
import java.util.jar.*
import kotlin.collections.ArrayList

@RunWith(Parameterized::class)
class PublicApiTest(
    private val rootDir: String,
    private val moduleName: String
) {
    companion object {
        private val apiProps = ClassLoader.getSystemClassLoader()
            .getResource("api.properties").openStream().use { Properties().apply { load(it) } }
        private val nonPublicPackages = apiProps.getProperty("packages.internal")!!.split(" ")

        @Parameterized.Parameters(name = "{1}")
        @JvmStatic
        fun modules(): List<Array<Any>> {
            val moduleRoots = apiProps.getProperty("module.roots").split(" ")
            val moduleMarker = apiProps.getProperty("module.marker")!!
            val moduleIgnore = apiProps.getProperty("module.ignore")!!.split(" ").toSet()
            val modules = ArrayList<Array<Any>>()
            for (rootDir in moduleRoots) {
                File("../$rootDir").listFiles( FileFilter { it.isDirectory })?.forEach { dir ->
                    if (dir.name !in moduleIgnore && File(dir, moduleMarker).exists()) {
                        modules += arrayOf<Any>(rootDir, dir.name)
                    }
                }
            }
            return modules
        }
    }

    @Test
    fun testApi() {
        val libsDir = File("../$rootDir/$moduleName/build/libs").absoluteFile.normalize()
        val jarPath = getJarPath(libsDir)
        val kotlinJvmMappingsFiles = listOf(libsDir.resolve("../visibilities.json"))
        val visibilities =
                kotlinJvmMappingsFiles
                        .map { readKotlinVisibilities(it) }
                        .reduce { m1, m2 -> m1 + m2 }
        JarFile(jarPath).use { jarFile ->
            val api = getBinaryAPI(jarFile, visibilities).filterOutNonPublic(nonPublicPackages)
            api.dumpAndCompareWith(File("reference-public-api").resolve("$moduleName.txt"))
            // check for atomicfu leaks
            jarFile.checkForAtomicFu()
        }
    }

    private fun getJarPath(libsDir: File): File {
        val regex = Regex("$moduleName-.+\\.jar")
        var files = (libsDir.listFiles() ?: throw Exception("Cannot list files in $libsDir"))
            .filter { it.name.let {
                    it matches regex
                    && !it.endsWith("-sources.jar")
                    && !it.endsWith("-javadoc.jar")
                    && !it.endsWith("-tests.jar")}
                    && !it.name.contains("-metadata-")}
        if (files.size > 1) // maybe multiplatform?
            files = files.filter { it.name.startsWith("$moduleName-jvm-") }
        return files.singleOrNull() ?:
            error("No single file matching $regex in $libsDir:\n${files.joinToString("\n")}")
    }
}

private val ATOMIC_FU_REF = "Lkotlinx/atomicfu/".toByteArray()

private fun JarFile.checkForAtomicFu() {
    var found = false
    for (e in entries()) {
        if (e.name.endsWith(".class")) {
            getInputStream(e).use {
                val bytes = it.readBytes()
                loop@for (i in 0 until bytes.size - ATOMIC_FU_REF.size) {
                    for (j in 0 until ATOMIC_FU_REF.size) {
                        if (bytes[i + j] != ATOMIC_FU_REF[j]) continue@loop
                    }
                    // found!
                    println("Found atomicfu reference in ${e.name}")
                    found = true
                    break@loop
                }
            }
        }
    }
    if (found) error("Found references to atomicfu in $name")
}
